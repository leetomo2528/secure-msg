package com.yunjelee.securemsg.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.yunjelee.securemsg.ContactSync
import com.yunjelee.securemsg.Favorites
import com.yunjelee.securemsg.PhoneNumberNormalizer
import com.yunjelee.securemsg.SmsThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/** One address-book phone entry as the 연락처 tab shows it. */
internal data class ContactEntry(
    val name: String,
    /** As typed into the address book — shown verbatim under the name. */
    val phone: String,
    /** [PhoneNumberNormalizer] output: the key for favourites and thread matching. */
    val normalizedPhone: String,
)

internal data class ContactGroup(val label: String, val contacts: List<ContactEntry>)

/**
 * Pure grouping/search for the contact list, kept free of Android so it is
 * unit-testable. Hangul names group by 초성 (doubled consonants fold into
 * their base, the way the stock dialer does), other scripts by their first
 * letter uppercased, and anything else under [OTHER].
 */
internal object ContactIndex {
    const val OTHER = "#"

    private const val CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val doubled = mapOf('ㄲ' to 'ㄱ', 'ㄸ' to 'ㄷ', 'ㅃ' to 'ㅂ', 'ㅆ' to 'ㅅ', 'ㅉ' to 'ㅈ')
    private val separators = Regex("[\\s().-]")

    fun label(name: String): String {
        val first = name.trim().firstOrNull() ?: return OTHER
        val code = first.code
        if (code in 0xAC00..0xD7A3) {
            // Syllable block = 초성 × 21 중성 × 28 종성; each 초성 spans 588 code points.
            val cho = CHOSEONG[(code - 0xAC00) / 588]
            return (doubled[cho] ?: cho).toString()
        }
        // A bare consonant as a name ("ㅋㅋ 친구") still indexes under itself.
        if (first in CHOSEONG) return (doubled[first] ?: first).toString()
        if (first.isLetter()) return first.uppercaseChar().toString()
        return OTHER
    }

    /** Hangul groups in 가나다 order, then other letters, then [OTHER]. */
    fun group(contacts: List<ContactEntry>): List<ContactGroup> =
        contacts
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .groupBy { label(it.name) }
            .map { (label, members) -> ContactGroup(label, members) }
            .sortedWith(
                compareBy<ContactGroup> { rank(it.label) }
                    .thenBy { CHOSEONG.indexOf(it.label) }
                    .thenBy { it.label },
            )

    /** Name or number contains the term; separators are ignored on both sides. */
    fun filter(contacts: List<ContactEntry>, query: String): List<ContactEntry> {
        val term = query.trim()
        if (term.isEmpty()) return contacts
        val compactTerm = term.replace(separators, "")
        return contacts.filter { contact ->
            contact.name.contains(term, ignoreCase = true) ||
                contact.phone.contains(term) ||
                (
                    compactTerm.isNotEmpty() && (
                        contact.phone.replace(separators, "").contains(compactTerm) ||
                            contact.normalizedPhone.contains(compactTerm)
                        )
                    )
        }
    }

    private fun rank(label: String): Int = when {
        label == OTHER -> 2
        label.length == 1 && label[0] in CHOSEONG -> 0
        else -> 1
    }
}

/**
 * 연락처 tab state that has to outlive the pane: the address-book read and
 * the search text. MainScreen owns one, so leaving the tab and coming back
 * neither re-queries the provider nor drops what was typed.
 *
 * [resumeTick] advances on every ON_RESUME of the screen (a grant made in
 * system settings, edits in the Contacts app) and the pane reads the book once
 * per value — only while it is on screen, so the read stays lazy.
 */
@Stable
internal class ContactsPaneState(initialFavorites: Set<String>) {
    var hasPermission by mutableStateOf(false)
    var deniedOnce by mutableStateOf(false)
    // null = not read yet (no permission, or the IO read is still running).
    var contacts by mutableStateOf<List<ContactEntry>?>(null)
    var favorites by mutableStateOf(initialFavorites)
    var query by mutableStateOf("")
    var resumeTick by mutableIntStateOf(0)
    /** [resumeTick] the current [contacts] were read for; -1 until the first read lands. */
    var loadedTick = -1
}

@Composable
internal fun rememberContactsPaneState(): ContactsPaneState {
    val context = LocalContext.current
    val state = remember { ContactsPaneState(Favorites.load(context)) }
    // ON_RESUME is also delivered on registration, so tick 1 is the initial
    // load. The permission dialog pauses the activity, so a grant from the
    // pane's button is followed by a resume too — one load, not two.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.hasPermission = ContactSync.hasPermission(context)
        state.resumeTick++
    }
    return state
}

/**
 * "연락처" tab: address book grouped by 즐겨찾기 and 초성, with a message
 * affordance per row. Tapping a row (or its bubble) opens the existing thread
 * for that number, or hands the number to the composer — no thread is
 * created here; that happens on the first send via OutgoingSmsDispatcher.
 * Long-press toggles the on-device favourite.
 */
@Composable
internal fun ColumnScope.ContactsPane(
    state: ContactsPaneState,
    threads: List<SmsThread>,
    onOpenThread: (SmsThread) -> Unit,
    onNewNumber: () -> Unit,
    onStartConversation: (normalizedPhone: String, displayName: String) -> Unit,
) {
    val context = LocalContext.current
    val hasPermission = state.hasPermission
    val contacts = state.contacts
    val favorites = state.favorites
    val query = state.query
    val syncStatus = remember(state.resumeTick) { ContactSync.loadStatus(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // The result lands before the resume that follows the dialog; that
        // resume bumps resumeTick and does the read.
        state.hasPermission = granted
        state.deniedOnce = !granted
    }

    // Never touch the resolver before the permission check: the query throws
    // SecurityException instead of returning an empty cursor. Keyed on the
    // tick alone so a grant cannot start one read and the resume another.
    LaunchedEffect(state.resumeTick) {
        val tick = state.resumeTick
        if (!state.hasPermission || state.loadedTick == tick) return@LaunchedEffect
        state.contacts = withContext(Dispatchers.IO) {
            runCatching { readContacts(context) }
                .onFailure { Log.w("ContactsPane", "contact read failed", it) }
                .getOrDefault(emptyList())
        }
        state.loadedTick = tick
    }

    // Same matching as ContactNameMapper.map: normalized phone on both sides.
    // Prefer the relay cid over a provisional local_ one, like ThreadDao.getByPhone.
    val threadsByPhone = remember(threads) {
        buildMap<String, SmsThread> {
            threads.forEach { thread ->
                val key = PhoneNumberNormalizer.normalize(thread.phoneNumber)
                val existing = this[key]
                if (existing == null ||
                    (existing.cid.startsWith("local_") && !thread.cid.startsWith("local_"))
                ) {
                    put(key, thread)
                }
            }
        }
    }
    val visibleContacts = remember(contacts, query) {
        ContactIndex.filter(contacts.orEmpty(), query)
    }
    val groups = remember(visibleContacts) { ContactIndex.group(visibleContacts) }
    val favoriteRows = remember(visibleContacts, favorites) {
        visibleContacts.filter { it.normalizedPhone in favorites }
    }

    fun open(contact: ContactEntry) {
        val thread = threadsByPhone[contact.normalizedPhone]
        if (thread != null) {
            onOpenThread(thread)
        } else {
            onStartConversation(contact.normalizedPhone, contact.name)
        }
    }

    Column(Modifier.fillMaxWidth().weight(1f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "연락처",
                color = Sm.text1,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
            )
            Text(
                syncStatus?.let {
                    "${syncTimeLabel(it.lastSyncedAt)} 동기화 · 대화 ${it.matchedThreadCount}개 이름 적용"
                } ?: "이름 동기화 전",
                color = Sm.text4,
                fontSize = 11.sp,
            )
        }
        SmSearchPill(
            query = query,
            onQueryChange = { state.query = it.take(100) },
            placeholder = "이름·번호 검색",
            modifier = Modifier.padding(top = 14.dp, start = 20.dp, end = 20.dp),
        )
        Box(Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp)) {
            SmEntryCard(
                title = "번호로 새 문자",
                subtitle = "연락처에 없는 번호로 바로 보내기",
                onClick = onNewNumber,
            )
        }

        if (!hasPermission) {
            ContactsPermissionCard(
                deniedOnce = state.deniedOnce,
                onRequest = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
                onOpenAppSettings = { openAppSettings(context) },
            )
            return@Column
        }

        val loaded = contacts
        if (loaded == null) {
            Text(
                "연락처를 불러오는 중…",
                color = Sm.text4,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 16.dp, start = 20.dp, end = 20.dp),
            )
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp),
        ) {
            if (visibleContacts.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (loaded.isEmpty()) "저장된 연락처가 없습니다." else "일치하는 이름이나 번호가 없습니다.",
                        color = Sm.text4,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                }
            }
            // Favourites stay in their 초성 group as well, so keys carry the section.
            if (favoriteRows.isNotEmpty()) {
                item(key = "h:fav") { SmSectionHeader("즐겨찾기", leadingStar = true) }
                items(favoriteRows, key = { "fav:${it.normalizedPhone}|${it.name}" }) { contact ->
                    SmContactRow(
                        name = contact.name,
                        phone = contact.phone,
                        favorite = true,
                        hasThread = contact.normalizedPhone in threadsByPhone,
                        onClick = { open(contact) },
                        onLongClick = { state.favorites = Favorites.toggle(context, contact.normalizedPhone) },
                        onMessageClick = { open(contact) },
                    )
                }
            }
            groups.forEach { group ->
                item(key = "h:${group.label}") { SmSectionHeader(group.label) }
                items(
                    group.contacts,
                    key = { "c:${group.label}:${it.normalizedPhone}|${it.name}" },
                ) { contact ->
                    SmContactRow(
                        name = contact.name,
                        phone = contact.phone,
                        favorite = contact.normalizedPhone in favorites,
                        hasThread = contact.normalizedPhone in threadsByPhone,
                        onClick = { open(contact) },
                        onLongClick = { state.favorites = Favorites.toggle(context, contact.normalizedPhone) },
                        onMessageClick = { open(contact) },
                    )
                }
            }
        }
    }
}

/** Shown in place of the list until READ_CONTACTS is granted. */
@Composable
private fun ContactsPermissionCard(
    deniedOnce: Boolean,
    onRequest: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    SmCard(Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp)) {
        SectionTitle("연락처 권한")
        Caption(
            "주소록을 읽어 이름·번호 목록을 보여줍니다. 연락처는 이 기기에서만 읽으며 " +
                "전체 목록을 서버로 보내지 않습니다.",
        )
        SmGradientButton(
            text = "연락처 권한 허용",
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth(),
        )
        if (deniedOnce) {
            // After "don't ask again" the system dialog no longer appears;
            // the only remaining path is the app's own settings page.
            Caption("권한이 거부되었습니다. 다시 묻지 않도록 설정했다면 앱 설정에서 직접 허용하세요.")
            SmGhostButton(
                text = "앱 설정 열기",
                onClick = onOpenAppSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Log.w("ContactsPane", "app settings unavailable", it) }
}

/**
 * Address-book read for the list. `ContactSync.readContactPhones` is private
 * to that object, so the query is mirrored here with the same projection and
 * selection — keep the two in step if either changes. The provider returns
 * one row per phone number; a contact linked across accounts can repeat a
 * number, so rows are deduplicated on (name, normalized phone).
 */
private fun readContacts(context: Context): List<ContactEntry> {
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    val rows = buildList {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.NUMBER} IS NOT NULL",
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC",
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            )
            val numberIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty().trim()
                val number = cursor.getString(numberIndex).orEmpty().trim()
                val normalized = PhoneNumberNormalizer.normalize(number)
                if (name.isNotEmpty() && normalized.isNotEmpty()) {
                    add(ContactEntry(name, number, normalized))
                }
            }
        }
    }
    return rows.distinctBy { it.name to it.normalizedPhone }
}

/** "오늘 08:12" / "어제 23:40" / "8/3 09:15" for the header meta. */
private fun syncTimeLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val time = String.format(
        Locale.KOREA,
        "%02d:%02d",
        then[Calendar.HOUR_OF_DAY],
        then[Calendar.MINUTE],
    )
    fun sameDay(other: Calendar) =
        then[Calendar.YEAR] == other[Calendar.YEAR] &&
            then[Calendar.DAY_OF_YEAR] == other[Calendar.DAY_OF_YEAR]
    if (sameDay(today)) return "오늘 $time"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(today)) return "어제 $time"
    return "${then[Calendar.MONTH] + 1}/${then[Calendar.DAY_OF_MONTH]} $time"
}
