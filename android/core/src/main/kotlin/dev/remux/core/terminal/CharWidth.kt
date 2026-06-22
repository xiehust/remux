package dev.remux.core.terminal

/**
 * Terminal column width of a Unicode code point: 0 for combining marks, 2 for
 * East-Asian wide / fullwidth characters and most emoji, 1 otherwise. Uses a
 * compact range table (no external dependency) sufficient for terminal display.
 */
object CharWidth {

    private val combiningRanges = arrayOf(
        0x0300 to 0x036F, // combining diacritical marks
        0x0483 to 0x0489,
        0x0591 to 0x05BD,
        0x0610 to 0x061A,
        0x064B to 0x065F,
        0x0670 to 0x0670,
        0x06D6 to 0x06DC,
        0x0E31 to 0x0E31,
        0x0E34 to 0x0E3A,
        0x1AB0 to 0x1AFF,
        0x1DC0 to 0x1DFF,
        0x20D0 to 0x20FF, // combining marks for symbols
        0xFE20 to 0xFE2F, // combining half marks
    )

    private val wideRanges = arrayOf(
        0x1100 to 0x115F, // Hangul Jamo
        0x2329 to 0x232A, // angle brackets
        0x2E80 to 0x303E, // CJK radicals, Kangxi, CJK symbols/punctuation
        0x3041 to 0x33FF, // Hiragana, Katakana, CJK symbols, enclosed
        0x3400 to 0x4DBF, // CJK Ext A
        0x4E00 to 0x9FFF, // CJK Unified Ideographs
        0xA000 to 0xA4CF, // Yi
        0xAC00 to 0xD7A3, // Hangul Syllables
        0xF900 to 0xFAFF, // CJK compatibility ideographs
        0xFE30 to 0xFE4F, // CJK compatibility forms
        0xFF00 to 0xFF60, // Fullwidth forms
        0xFFE0 to 0xFFE6, // Fullwidth signs
        0x1F300 to 0x1F64F, // emoji / symbols & pictographs / emoticons
        0x1F900 to 0x1F9FF, // supplemental symbols & pictographs
        0x20000 to 0x3FFFD, // CJK Ext B..
    )

    private fun inRanges(cp: Int, ranges: Array<Pair<Int, Int>>): Boolean {
        for ((lo, hi) in ranges) {
            if (cp in lo..hi) return true
            if (cp < lo) break
        }
        return false
    }

    fun isCombining(cp: Int): Boolean = inRanges(cp, combiningRanges)

    fun isWide(cp: Int): Boolean = inRanges(cp, wideRanges)

    /** Returns 0, 1, or 2. */
    fun width(cp: Int): Int {
        if (cp == 0) return 0
        if (cp < 0x20 || (cp in 0x7F..0x9F)) return 0 // control chars have no width
        if (isCombining(cp)) return 0
        if (isWide(cp)) return 2
        return 1
    }
}
