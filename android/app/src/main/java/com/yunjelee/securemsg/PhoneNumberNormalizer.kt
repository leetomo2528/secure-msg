package com.yunjelee.securemsg

object PhoneNumberNormalizer {
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
