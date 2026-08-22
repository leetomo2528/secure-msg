package com.yunjelee.securemsg.ui

import com.yunjelee.securemsg.PhoneNumberNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactIndexTest {
    @Test
    fun hangulNamesIndexByChoseong() {
        assertEquals("ㄱ", ContactIndex.label("김하은"))
        assertEquals("ㅁ", ContactIndex.label("민수"))
        assertEquals("ㅇ", ContactIndex.label("엄마"))
        assertEquals("ㅎ", ContactIndex.label("  힣"))
    }

    @Test
    fun doubledConsonantsFoldIntoTheirBase() {
        assertEquals("ㄱ", ContactIndex.label("까치"))
        assertEquals("ㄷ", ContactIndex.label("땅콩"))
        assertEquals("ㅂ", ContactIndex.label("빵집"))
        assertEquals("ㅅ", ContactIndex.label("쌍둥이"))
        assertEquals("ㅈ", ContactIndex.label("짜장"))
        assertEquals("ㄱ", ContactIndex.label("ㄲ"))
    }

    @Test
    fun otherScriptsUseUppercasedFirstLetterAndSymbolsFallBack() {
        assertEquals("A", ContactIndex.label("alice"))
        assertEquals("B", ContactIndex.label("Bob"))
        assertEquals("#", ContactIndex.label("010-0000-0000"))
        assertEquals("#", ContactIndex.label("(주) 회사"))
        assertEquals("#", ContactIndex.label(""))
        assertEquals("#", ContactIndex.label("   "))
    }

    @Test
    fun groupsOrderHangulThenLatinThenSymbolsAndSortMembers() {
        val groups = ContactIndex.group(
            listOf(
                entry("bob"),
                entry("이지윤"),
                entry("#1 택배"),
                entry("Alice"),
                entry("김하은"),
                entry("엄마"),
                entry("민수"),
                entry("alan"),
            ),
        )

        assertEquals(listOf("ㄱ", "ㅁ", "ㅇ", "A", "B", "#"), groups.map { it.label })
        assertEquals(listOf("김하은"), groups[0].contacts.map { it.name })
        assertEquals(listOf("엄마", "이지윤"), groups[2].contacts.map { it.name })
        assertEquals(listOf("alan", "Alice"), groups[3].contacts.map { it.name })
        assertEquals(listOf("#1 택배"), groups[5].contacts.map { it.name })
    }

    @Test
    fun emptyInputProducesNoGroups() {
        assertEquals(emptyList<ContactGroup>(), ContactIndex.group(emptyList()))
    }

    @Test
    fun filterMatchesNameCaseInsensitivelyAndNumberWithoutSeparators() {
        val mom = entry("엄마", "010-1234-5678")
        val alice = entry("Alice", "+82 10-9999-0000")
        val contacts = listOf(mom, alice)

        assertEquals(contacts, ContactIndex.filter(contacts, "  "))
        assertEquals(listOf(mom), ContactIndex.filter(contacts, "엄"))
        assertEquals(listOf(alice), ContactIndex.filter(contacts, "aLiCe"))
        assertEquals(listOf(mom), ContactIndex.filter(contacts, "01012345678"))
        assertEquals(listOf(mom), ContactIndex.filter(contacts, "1234-5678"))
        assertEquals(listOf(alice), ContactIndex.filter(contacts, "+821099990000"))
        assertEquals(emptyList<ContactEntry>(), ContactIndex.filter(contacts, "없는 사람"))
    }

    private fun entry(name: String, phone: String = "010-0000-0000") = ContactEntry(
        name = name,
        phone = phone,
        normalizedPhone = PhoneNumberNormalizer.normalize(phone),
    )
}
