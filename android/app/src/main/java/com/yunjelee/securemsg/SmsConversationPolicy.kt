package com.yunjelee.securemsg

/**
 * A relay conversation may control the carrier gateway only when it belongs to
 * exactly one account: the gateway owner's account. Without this check, an
 * arbitrary SecureMsg user could add the gateway owner to a group, name that
 * group like a phone number, and turn an encrypted chat into an SMS command.
 */
object SmsConversationPolicy {
    fun ownedPhone(name: String, members: List<String>, username: String): String? {
        if (members.size != 1 || members.single() != username) return null
        val phone = PhoneNumberNormalizer.normalize(name)
        return phone.takeIf { Regex("^\\+?[0-9*#]{3,24}$").matches(it) }
    }
}
