package com.yunjelee.securemsg

object PhoneNumberNormalizer {
    /**
     * The one shape a normalized value must have to reach the carrier.
     *
     * This decides four things that must never disagree: whether a relay
     * conversation may drive the gateway, whether an inbound sender can ever be
     * relayed, whether an outgoing SMS may be dispatched, and what 설정 accepts
     * as a sender rule. Each gate used to spell the pattern out for itself, so
     * tightening one (rejecting `#`, allowing a longer international number)
     * would have left the security gate on the old rule.
     *
     * Written with explicit ASCII classes, never `\d`/`\w`: Android's regex
     * engine is ICU and those classes are Unicode there but ASCII in the unit
     * tests, so a shorthand would accept Eastern Arabic digits on a device
     * while every test still passed.
     */
    val SMS_ADDRESS = Regex("^\\+?[0-9*#]{3,24}$")

    fun isSmsAddress(value: String): Boolean = SMS_ADDRESS.matches(value)

    /**
     * Returns the stable address used for SMS conversation identity.
     *
     * Korean numbers are stored in E.164-like form so a carrier sender such as
     * `01012345678` resolves to the same thread as `+821012345678`. We accept
     * the common local shapes 02 + 7/8 digits and 0xx + 7/8 digits. A bare 82
     * is treated as a country code only when the remainder has one of those
     * Korean lengths; this avoids rewriting arbitrary short codes. `00` remains
     * the generic international dial prefix, and non-Korean country codes,
     * star/hash service codes, and other identifiers are otherwise preserved.
     */
    fun normalize(value: String): String {
        val compact = value
            .trim()
            .replace(Regex("[\\s().-]"), "")
            .replace(Regex("^00"), "+")

        koreanNationalPart(compact)?.let { return "+82$it" }
        return compact
    }

    private fun koreanNationalPart(value: String): String? {
        val local = when {
            value.startsWith("+82") -> value.removePrefix("+82").removePrefix("0")
            value.startsWith("82") -> value.removePrefix("82").removePrefix("0")
            value.startsWith("0") -> value.removePrefix("0")
            else -> return null
        }
        return local.takeIf {
            Regex("^(?:2[0-9]{7,8}|50[0-9]{9}|[1-9][0-9]{8,9})$").matches(it)
        }
    }

    fun redact(value: String): String {
        val normalized = normalize(value)
        if (normalized.length <= 4) return "***"
        return "***${normalized.takeLast(4)}"
    }
}
