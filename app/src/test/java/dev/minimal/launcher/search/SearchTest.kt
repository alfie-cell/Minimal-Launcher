package dev.minimal.launcher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private val labels = listOf(
        "Calculator", "Calendar", "Camera", "Chrome", "Clock", "Contacts",
        "Files by Google", "Gmail", "Google", "Google Maps", "Google Play Store",
        "Messages", "Phone", "Photos", "Settings", "WhatsApp", "Wi-Fi Analyzer", "YouTube",
        "YouTube Music", "Économie", "PayPal",
    )

    private fun search(q: String, boost: (String) -> Int = { 0 }): List<String> =
        Search.rank(labels, q, { SearchKey(it) }, boost)

    @Test fun emptyQueryReturnsEverythingInOrder() {
        assertEquals(labels, search(""))
        assertEquals(labels, search("   "))
    }

    @Test fun exactBeatsPrefix() {
        assertEquals("Google", search("google").first())
    }

    @Test fun prefixBeatsWordPrefix() {
        val r = search("ma")
        assertEquals("Google Maps", r.first()) // only word-prefix match
        assertTrue(search("c").take(6).all { it.startsWith("C") })
    }

    @Test fun wordPrefix() {
        assertEquals("Google Maps", search("maps").first())
        assertEquals("Files by Google", search("files").first())
    }

    @Test fun camelCaseWords() {
        assertEquals(listOf("YouTube", "YouTube Music"), search("tube").take(2))
        assertEquals("WhatsApp", search("app").first())
        assertEquals("PayPal", search("pal").first())
    }

    @Test fun multiWordPrefixes() {
        assertEquals("Google Maps", search("goo ma").first())
        assertEquals("Google Play Store", search("g p s").first())
    }

    @Test fun initials() {
        assertEquals(listOf("Gmail", "Google Maps"), search("gm").take(2))
        assertTrue(search("gps").contains("Google Play Store"))
        assertEquals("YouTube Music", search("ytm").first())
    }

    @Test fun caseAndAccentInsensitive() {
        assertEquals("Économie", search("econ").first())
        assertEquals("Économie", search("ÉCON").first())
    }

    @Test fun punctuationIgnored() {
        assertEquals("Wi-Fi Analyzer", search("wifi").first())
        assertEquals("Wi-Fi Analyzer", search("wi-fi").first())
    }

    @Test fun substringAndFuzzy() {
        assertTrue(search("amer").contains("Camera"))
        assertTrue(search("clcltr").contains("Calculator"))
        assertEquals(emptyList<String>(), search("zzzz"))
    }

    @Test fun launchCountBreaksTiesWithinTier() {
        val r = search("c") { if (it == "Chrome") 10 else 0 }
        assertEquals("Chrome", r.first())
        // but never lifts a weaker tier over a stronger one
        val r2 = search("go") { if (it == "Files by Google") 100 else 0 }
        assertEquals("Google", r2.first())
    }

    @Test fun tightestSubsequence() {
        assertEquals(3, Search.tightestSubsequence("abcabc", "abc"))
        assertEquals(0, Search.tightestSubsequence("abc", "cab"))
        assertEquals(2, Search.tightestSubsequence("axxab", "ab"))
    }
}
