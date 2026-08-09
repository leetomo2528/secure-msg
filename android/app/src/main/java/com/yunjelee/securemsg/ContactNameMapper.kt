package com.yunjelee.securemsg

/** A minimal contact projection. Keeping mapping independent of Android makes it unit-testable. */
data class ContactPhoneRow(
    val displayName: String,
    val phoneNumber: String,
)

data class ContactNameUpdate(
    val cid: String,
    val localContactName: String?,
)

data class ContactMapping(
    val updates: List<ContactNameUpdate>,
    /** Complete desired state, including null clears, used for relay publication. */
    val desiredNames: List<ContactNameUpdate>,
    val matchedThreadCount: Int,
    val contactPhoneCount: Int,
)

/** Pure phone-number-to-thread mapping used by the Android-only contact sync. */
object ContactNameMapper {
    fun map(
        contacts: List<ContactPhoneRow>,
        threads: List<SmsThread>,
    ): ContactMapping {
        val namesByPhone = buildMap {
            contacts.forEach { contact ->
                val name = contact.displayName.trim()
                val phone = PhoneNumberNormalizer.normalize(contact.phoneNumber)
                if (name.isNotEmpty() && phone.isNotEmpty() && phone !in this) {
                    put(phone, name)
                }
            }
        }
        var matched = 0
        val desiredNames = threads.map { thread ->
            val name = namesByPhone[PhoneNumberNormalizer.normalize(thread.phoneNumber)]
            if (name != null) matched++
            ContactNameUpdate(thread.cid, name)
        }
        val localNames = threads.associate { it.cid to it.localContactName }
        val updates = desiredNames.filter { it.localContactName != localNames[it.cid] }
        return ContactMapping(
            updates = updates,
            desiredNames = desiredNames,
            matchedThreadCount = matched,
            contactPhoneCount = namesByPhone.size,
        )
    }
}
