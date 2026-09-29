package dev.minimal.launcher.search

import java.text.Normalizer
import java.util.Locale

/** Pre-computed, normalised forms of a label so each keystroke only does string comparisons. */
class SearchKey(label: String) {
    val normalized: String = Search.normalize(label)
    val words: List<String> = Search.splitWords(label)
    val initials: String = words.joinToString("") { it.take(1) }
    val compact: String = words.joinToString("")
}

/**
 * App-name search. Pure Kotlin (no Android types) so it is covered by JVM unit tests.
 *
 * Tiers, best first: exact, label prefix, word prefix, multi-word prefixes, initials,
 * substring, fuzzy subsequence. Within a tier, higher [rank] boost wins, then input order.
 */
object Search {
    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val CAMEL = Regex("(?<=\\p{Ll})(?=\\p{Lu})")

    const val EXACT = 1000
    const val PREFIX = 900
    const val WORD_PREFIX = 800
    const val MULTI_WORD = 750
    const val INITIALS = 700
    const val SUBSTRING = 600
    const val FUZZY = 300

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .trim()
            .replace(SPACES, " ")

    /** Splits on punctuation/whitespace and camelCase ("YouTube" -> you, tube). */
    fun splitWords(label: String): List<String> =
        label.split(SEPARATORS)
            .flatMap { it.split(CAMEL) }
            .map { normalize(it) }
            .filter { it.isNotEmpty() }

    /** Score of [key] for an already-[normalize]d [query]; 0 means no match. */
    fun score(key: SearchKey, query: String): Int {
        if (query.isEmpty()) return 0
        val n = key.normalized
        if (n == query) return EXACT
        if (n.startsWith(query)) return PREFIX

        val wordIndex = key.words.indexOfFirst { it.startsWith(query) }
        if (wordIndex >= 0) return WORD_PREFIX - wordIndex.coerceAtMost(49)

        val queryWords = query.split(' ').filter { it.isNotEmpty() }
        if (queryWords.size > 1 && prefixesInOrder(key.words, queryWords)) return MULTI_WORD

        val compactQuery = query.replace(SEPARATORS, "")
        if (compactQuery.isEmpty()) return 0
        if (compactQuery.length >= 2 && key.initials.startsWith(compactQuery)) return INITIALS

        val at = key.compact.indexOf(compactQuery)
        if (at >= 0) return SUBSTRING - at.coerceAtMost(49)

        val span = tightestSubsequence(key.compact, compactQuery)
        if (span > 0) return FUZZY + 99 * compactQuery.length / span
        return 0
    }

    fun <T> rank(items: List<T>, rawQuery: String, keyOf: (T) -> SearchKey, boost: (T) -> Int): List<T> {
        val query = normalize(rawQuery)
        if (query.isEmpty()) return items
        val hits = ArrayList<Hit<T>>()
        for (item in items) {
            val s = score(keyOf(item), query)
            if (s > 0) hits += Hit(item, s, boost(item))
        }
        // sortWith is stable, so equal hits keep the caller's (alphabetical) order.
        hits.sortWith(compareByDescending<Hit<T>> { it.score }.thenByDescending { it.boost })
        return hits.map { it.item }
    }

    private class Hit<T>(val item: T, val score: Int, val boost: Int)

    private fun prefixesInOrder(words: List<String>, queryWords: List<String>): Boolean {
        var q = 0
        for (w in words) {
            if (q < queryWords.size && w.startsWith(queryWords[q])) q++
        }
        return q == queryWords.size
    }

    /** Length of the shortest window of [text] containing [query] as a subsequence, or 0. */
    internal fun tightestSubsequence(text: String, query: String): Int {
        var best = 0
        for (start in text.indices) {
            if (text[start] != query[0]) continue
            var qi = 1
            var i = start + 1
            while (qi < query.length && i < text.length) {
                if (text[i] == query[qi]) qi++
                i++
            }
            if (qi < query.length) break // no later start can match either
            val span = i - start
            if (best == 0 || span < best) best = span
        }
        return best
    }
}
