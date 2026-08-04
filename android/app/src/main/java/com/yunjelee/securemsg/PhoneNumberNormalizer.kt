package com.yunjelee.securemsg

object PhoneNumberNormalizer {
    fun normalize(value: String): String = value
        .trim()
        .replace(Regex("[\\s().-]"), "")
        .replace(Regex("^00"), "+")

    fun redact(value: String): String {
        val normalized = normalize(value)
        if (normalized.length <= 4) return "***"
        return "***${normalized.takeLast(4)}"
    }
}
