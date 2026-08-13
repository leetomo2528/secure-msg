package com.yunjelee.securemsg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Default-SMS receiver. SMS_DELIVER is delivered only to the user-selected default
 * SMS app. We classify before writing to the system SMS Provider; a blocked message
 * is kept in the local quarantine table and is never notified or relayed.
 *
 * The receiver performs only classification plus the mandatory durable local
 * transaction. Network work remains in the foreground service so a relay outage
 * cannot delay notification or local presentation.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return@launch
                if (msgs.isEmpty()) return@launch

                val sender = msgs[0].displayOriginatingAddress
                    ?: msgs[0].originatingAddress
                    ?: return@launch
                val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                if (body.isBlank()) return@launch
                val receivedAt = msgs.maxOfOrNull { it.timestampMillis }
                    ?.takeIf { it > 0 } ?: System.currentTimeMillis()
                val db = try {
                    AppDatabase.get(context)
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "database unavailable; accepting SMS", e)
                    null
                }
                val decision = try {
                    if (db == null) BlocklistManager.Decision(false, "")
                    else BlocklistManager.evaluate(sender, body, db)
                } catch (e: Exception) {
                    // A database/classifier failure must not make the default
                    // SMS app silently destroy the only delivered copy.
                    Log.e("SmsReceiver", "classification failed; accepting SMS", e)
                    BlocklistManager.Decision(false, "")
                }

                if (decision.blocked && db != null) {
                    val quarantined = try {
                        db.blockedSmsDao().insert(
                            BlockedSms(
                                phoneNumber = sender,
                                body = body,
                                reason = decision.reason,
                                receivedAt = receivedAt,
                            ),
                        )
                        true
                    } catch (e: Exception) {
                        Log.e("SmsReceiver", "quarantine write failed; accepting SMS", e)
                        false
                    }
                    if (quarantined) {
                        Log.i(
                            "SmsReceiver",
                            "SMS quarantined from ${PhoneNumberNormalizer.redact(sender)}: " +
                                decision.reason,
                        )
                        pending.setResultCode(android.app.Activity.RESULT_OK)
                        return@launch
                    }
                }

                // As the default SMS app, SecureMsg owns provider persistence. Room must
                // become authoritative before the notification can open the conversation.
                val providerId = SmsProvider.insertIncoming(context, sender, body, receivedAt)
                val activeDb = db ?: AppDatabase.get(context)
                val content = RelayContentCodec.text(body)
                val persisted = IncomingMessageRepository(activeDb).persistCarrier(
                    kind = ProviderIdentity.SMS,
                    direction = "incoming_sms",
                    phoneNumber = sender,
                    content = content,
                    providerId = providerId,
                    receivedAt = receivedAt,
                )
                if (persisted?.newlyCreated == true) {
                    SmsNotifier.notifyIncoming(
                        context = context,
                        phoneNumber = persisted.conversation.normalizedPhone,
                        body = body,
                        date = receivedAt,
                        cid = persisted.conversation.cid,
                        messageIdentity = persisted.outbox.mid,
                    )
                }
                Log.i(
                    "SmsReceiver",
                    "SMS accepted from ${PhoneNumberNormalizer.redact(sender)} (${body.length} chars)",
                )

                if (persisted != null) {
                    val serviceIntent = Intent(context, SmsBridgeService::class.java).apply {
                        action = SmsBridgeService.ACTION_INCOMING_SMS
                        putExtra(SmsBridgeService.EXTRA_PHONE, sender)
                        putExtra(SmsBridgeService.EXTRA_BODY, body)
                        putExtra(SmsBridgeService.EXTRA_PROVIDER_ID, providerId ?: -1L)
                        putExtra(SmsBridgeService.EXTRA_PROVIDER_EPOCH, persisted.outbox.providerEpoch)
                        putExtra(SmsBridgeService.EXTRA_RECEIVED_AT, receivedAt)
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
                pending.setResultCode(android.app.Activity.RESULT_OK)
            } catch (e: Exception) {
                Log.e("SmsReceiver", "failed to process SMS_DELIVER", e)
                pending.setResultCode(android.app.Activity.RESULT_OK)
            } finally {
                pending.finish()
            }
        }
    }
}
