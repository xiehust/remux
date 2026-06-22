package dev.remux.core.terminal

/**
 * A self-contained xterm-256color terminal emulator: UTF-8 decoding, a
 * ground/escape/CSI/OSC state machine, an SGR-styled screen buffer with
 * scrollback and scroll regions, and East-Asian-width-aware cursor advance.
 *
 * Pure Kotlin/JVM — no Android dependencies — so it is fully unit-testable
 * off-device. The Compose renderer (phase 9) reads [cellAt] / [cursorRow] /
 * [cursorCol] / [scrollbackLine]; it does not live here.
 */
class Terminal(
    cols: Int,
    rows: Int,
    val scrollbackLimit: Int = 1000,
) {
    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    private var grid: Array<Array<Cell>> = Array(rows) { blankRow(cols) }
    private val scrollbackDeque = ArrayDeque<Array<Cell>>()

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var pen = Pen()

    private var savedRow = 0
    private var savedCol = 0
    private var savedPen = Pen()

    // Auto-wrap pending flag (deferred wrap, as xterm does).
    private var wrapPending = false

    // --- parser state ---
    private enum class State { GROUND, ESCAPE, CSI, OSC }
    private var state = State.GROUND
    private val params = ArrayList<Int>()
    private val paramBuf = StringBuilder()
    private var csiPrivate = false

    // --- UTF-8 decode state ---
    private var u8Remaining = 0
    private var u8CodePoint = 0
    private var u8Min = 0

    private fun blankRow(n: Int): Array<Cell> = Array(n) { Cell.EMPTY }

    // ------------------------------------------------------------------ input

    fun feed(bytes: ByteArray) {
        for (b in bytes) feedByte(b.toInt() and 0xFF)
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun feedByte(b: Int) {
        when (state) {
            State.GROUND -> ground(b)
            State.ESCAPE -> escape(b)
            State.CSI -> csi(b)
            State.OSC -> osc(b)
        }
    }

    private fun ground(b: Int) {
        if (u8Remaining > 0) {
            decodeContinuation(b)
            return
        }
        when {
            b == 0x1B -> {
                state = State.ESCAPE
            }
            b < 0x20 || b == 0x7F -> control(b)
            b < 0x80 -> printCodePoint(b)
            b in 0xC0..0xDF -> startUtf8(b, 1, b and 0x1F, 0x80)
            b in 0xE0..0xEF -> startUtf8(b, 2, b and 0x0F, 0x800)
            b in 0xF0..0xF7 -> startUtf8(b, 3, b and 0x07, 0x10000)
            else -> printCodePoint(0xFFFD)
        }
    }

    private fun startUtf8(b: Int, remaining: Int, initial: Int, min: Int) {
        u8Remaining = remaining
        u8CodePoint = initial
        u8Min = min
    }

    private fun decodeContinuation(b: Int) {
        if (b and 0xC0 != 0x80) {
            // Invalid sequence; emit replacement and reprocess this byte.
            u8Remaining = 0
            printCodePoint(0xFFFD)
            feedByte(b)
            return
        }
        u8CodePoint = (u8CodePoint shl 6) or (b and 0x3F)
        u8Remaining--
        if (u8Remaining == 0) {
            val cp = u8CodePoint
            if (cp < u8Min || cp in 0xD800..0xDFFF || cp > 0x10FFFF) {
                printCodePoint(0xFFFD)
            } else {
                printCodePoint(cp)
            }
        }
    }

    private fun control(b: Int) {
        when (b) {
            0x08 -> { // BS
                if (cursorCol > 0) cursorCol--
                wrapPending = false
            }
            0x09 -> { // HT
                val next = ((cursorCol / 8) + 1) * 8
                cursorCol = minOf(next, cols - 1)
                wrapPending = false
            }
            0x0A, 0x0B, 0x0C -> lineFeed() // LF, VT, FF
            0x0D -> { cursorCol = 0; wrapPending = false } // CR
            else -> { /* BEL and others: ignored */ }
        }
    }

    private fun printCodePoint(cp: Int) {
        val w = CharWidth.width(cp)
        if (w == 0) {
            // Combining mark / zero-width: do not advance, do not allocate a cell.
            return
        }
        if (wrapPending || cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
            wrapPending = false
        }
        if (w == 2 && cursorCol == cols - 1) {
            // No room for a wide char on this line; wrap first.
            cursorCol = 0
            lineFeed()
        }
        grid[cursorRow][cursorCol] = pen.toCell(cp, wide = w == 2)
        if (w == 2) {
            if (cursorCol + 1 < cols) {
                grid[cursorRow][cursorCol + 1] = pen.toCell(cp, widePlaceholder = true)
            }
            cursorCol += 2
        } else {
            cursorCol += 1
        }
        if (cursorCol >= cols) {
            cursorCol = cols - 1
            wrapPending = true
        }
    }

    private fun Pen.toCell(cp: Int, wide: Boolean = false, widePlaceholder: Boolean = false) = Cell(
        codePoint = if (widePlaceholder) 0 else cp,
        fg = fg, bg = bg, bold = bold, dim = dim, italic = italic, underline = underline, reverse = reverse,
        wide = wide, widePlaceholder = widePlaceholder,
    )

    // ------------------------------------------------------------- escape/CSI

    private fun escape(b: Int) {
        when (b.toChar()) {
            '[' -> { state = State.CSI; params.clear(); paramBuf.setLength(0); csiPrivate = false }
            ']' -> { state = State.OSC }
            'D' -> { lineFeed(); state = State.GROUND }       // IND
            'M' -> { reverseIndex(); state = State.GROUND }   // RI
            'E' -> { cursorCol = 0; lineFeed(); state = State.GROUND } // NEL
            '7' -> { saveCursor(); state = State.GROUND }     // DECSC
            '8' -> { restoreCursor(); state = State.GROUND }  // DECRC
            'c' -> { reset(); state = State.GROUND }          // RIS
            else -> state = State.GROUND
        }
    }

    private fun csi(b: Int) {
        val c = b.toChar()
        when {
            c == '?' -> csiPrivate = true
            c in '0'..'9' -> paramBuf.append(c)
            c == ';' -> { params.add(paramBuf.toString().toIntOrNull() ?: 0); paramBuf.setLength(0) }
            b in 0x40..0x7E -> {
                if (paramBuf.isNotEmpty()) params.add(paramBuf.toString().toIntOrNull() ?: 0)
                dispatchCsi(c)
                state = State.GROUND
            }
            // intermediate bytes (space..'/') are ignored for our subset
        }
    }

    private fun osc(b: Int) {
        // Terminate on BEL or on ESC \ (ST). We don't act on OSC (titles, etc.).
        if (b == 0x07) {
            state = State.GROUND
        } else if (b == 0x1B) {
            state = State.GROUND // approximate ST handling; next byte (\) is dropped harmlessly
        }
    }

    private fun param(i: Int, default: Int): Int {
        val v = params.getOrNull(i) ?: return default
        return if (v == 0) default else v
    }

    private fun dispatchCsi(c: Char) {
        wrapPending = false
        when (c) {
            'A' -> cursorRow = (cursorRow - param(0, 1)).coerceAtLeast(scrollTop)
            'B' -> cursorRow = (cursorRow + param(0, 1)).coerceAtMost(scrollBottom)
            'C' -> cursorCol = (cursorCol + param(0, 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - param(0, 1)).coerceAtLeast(0)
            'E' -> { cursorCol = 0; cursorRow = (cursorRow + param(0, 1)).coerceAtMost(scrollBottom) }
            'F' -> { cursorCol = 0; cursorRow = (cursorRow - param(0, 1)).coerceAtLeast(scrollTop) }
            'G', '`' -> cursorCol = (param(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (param(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (param(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (param(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(params.getOrElse(0) { 0 })
            'K' -> eraseLine(params.getOrElse(0) { 0 })
            'm' -> applySgr()
            'r' -> {
                val top = (param(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (param(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) { scrollTop = top; scrollBottom = bottom; cursorRow = top; cursorCol = 0 }
            }
            'S' -> scrollUp(param(0, 1))
            'T' -> scrollDown(param(0, 1))
            'L' -> insertLines(param(0, 1))
            'M' -> deleteLines(param(0, 1))
            'P' -> deleteChars(param(0, 1))
            'X' -> eraseChars(param(0, 1))
            '@' -> insertChars(param(0, 1))
            's' -> saveCursor()
            'u' -> restoreCursor()
            // 'h'/'l' (mode set/reset) intentionally ignored for the MVP subset.
        }
    }

    // --------------------------------------------------------------- movement

    private fun lineFeed() {
        if (cursorRow == scrollBottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
    }

    private fun saveCursor() { savedRow = cursorRow; savedCol = cursorCol; savedPen = pen }
    private fun restoreCursor() {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, cols - 1)
        pen = savedPen
    }

    // ----------------------------------------------------------- scroll/erase

    private fun scrollUp(n: Int) {
        repeat(n.coerceAtLeast(1)) {
            val removed = grid[scrollTop]
            if (scrollTop == 0) {
                scrollbackDeque.addLast(removed)
                while (scrollbackDeque.size > scrollbackLimit) scrollbackDeque.removeFirst()
            }
            for (r in scrollTop until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = blankRow(cols)
        }
    }

    private fun scrollDown(n: Int) {
        repeat(n.coerceAtLeast(1)) {
            for (r in scrollBottom downTo scrollTop + 1) grid[r] = grid[r - 1]
            grid[scrollTop] = blankRow(cols)
        }
    }

    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(n.coerceAtLeast(1)) {
            for (r in scrollBottom downTo cursorRow + 1) grid[r] = grid[r - 1]
            grid[cursorRow] = blankRow(cols)
        }
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(n.coerceAtLeast(1)) {
            for (r in cursorRow until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = blankRow(cols)
        }
    }

    private fun insertChars(n: Int) {
        val row = grid[cursorRow]
        val count = n.coerceIn(1, cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + count) row[c] = row[c - count]
        for (c in cursorCol until cursorCol + count) row[c] = Cell.EMPTY
    }

    private fun deleteChars(n: Int) {
        val row = grid[cursorRow]
        val count = n.coerceIn(1, cols - cursorCol)
        for (c in cursorCol until cols - count) row[c] = row[c + count]
        for (c in cols - count until cols) row[c] = Cell.EMPTY
    }

    private fun eraseChars(n: Int) {
        val row = grid[cursorRow]
        val end = (cursorCol + n.coerceAtLeast(1)).coerceAtMost(cols)
        for (c in cursorCol until end) row[c] = Cell.EMPTY
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { // cursor to end
                eraseLine(0)
                for (r in cursorRow + 1 until rows) grid[r] = blankRow(cols)
            }
            1 -> { // start to cursor
                for (r in 0 until cursorRow) grid[r] = blankRow(cols)
                eraseLine(1)
            }
            2, 3 -> for (r in 0 until rows) grid[r] = blankRow(cols)
        }
    }

    private fun eraseLine(mode: Int) {
        val row = grid[cursorRow]
        when (mode) {
            0 -> for (c in cursorCol until cols) row[c] = Cell.EMPTY
            1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) row[c] = Cell.EMPTY
            2 -> for (c in 0 until cols) row[c] = Cell.EMPTY
        }
    }

    // ----------------------------------------------------------------- SGR

    private fun applySgr() {
        if (params.isEmpty()) { pen = Pen(); return }
        var i = 0
        while (i < params.size) {
            when (val code = params[i]) {
                0 -> pen = Pen()
                1 -> pen = pen.copy(bold = true)
                2 -> pen = pen.copy(dim = true)
                3 -> pen = pen.copy(italic = true)
                4 -> pen = pen.copy(underline = true)
                7 -> pen = pen.copy(reverse = true)
                22 -> pen = pen.copy(bold = false, dim = false)
                23 -> pen = pen.copy(italic = false)
                24 -> pen = pen.copy(underline = false)
                27 -> pen = pen.copy(reverse = false)
                in 30..37 -> pen = pen.copy(fg = TerminalColor.Indexed(code - 30))
                in 40..47 -> pen = pen.copy(bg = TerminalColor.Indexed(code - 40))
                39 -> pen = pen.copy(fg = TerminalColor.Default)
                49 -> pen = pen.copy(bg = TerminalColor.Default)
                in 90..97 -> pen = pen.copy(fg = TerminalColor.Indexed(code - 90 + 8))
                in 100..107 -> pen = pen.copy(bg = TerminalColor.Indexed(code - 100 + 8))
                38 -> i = parseExtendedColor(i) { pen = pen.copy(fg = it) }
                48 -> i = parseExtendedColor(i) { pen = pen.copy(bg = it) }
            }
            i++
        }
    }

    /** Parses 38/48 extended color params starting at [start]; returns the index
     *  of the last consumed param. */
    private inline fun parseExtendedColor(start: Int, set: (TerminalColor) -> Unit): Int {
        return when (params.getOrNull(start + 1)) {
            5 -> { // 38;5;n
                val n = params.getOrNull(start + 2) ?: 0
                set(TerminalColor.Indexed(n.coerceIn(0, 255)))
                start + 2
            }
            2 -> { // 38;2;r;g;b
                val r = params.getOrNull(start + 2) ?: 0
                val g = params.getOrNull(start + 3) ?: 0
                val b = params.getOrNull(start + 4) ?: 0
                set(TerminalColor.Rgb(r and 0xFF, g and 0xFF, b and 0xFF))
                start + 4
            }
            else -> start
        }
    }

    private fun reset() {
        for (r in 0 until rows) grid[r] = blankRow(cols)
        cursorRow = 0; cursorCol = 0
        scrollTop = 0; scrollBottom = rows - 1
        pen = Pen(); wrapPending = false
    }

    // --------------------------------------------------------------- resize

    /** Resizes the active screen, clamping content (no reflow). Scrollback is
     *  preserved. The cursor is clamped into the new bounds. */
    fun resize(newCols: Int, newRows: Int) {
        require(newCols > 0 && newRows > 0) { "dimensions must be positive" }
        val newGrid = Array(newRows) { blankRow(newCols) }
        val copyRows = minOf(rows, newRows)
        val copyCols = minOf(cols, newCols)
        for (r in 0 until copyRows) {
            for (c in 0 until copyCols) newGrid[r][c] = grid[r][c]
        }
        grid = newGrid
        cols = newCols
        rows = newRows
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
        wrapPending = false
    }

    // ------------------------------------------------------------- accessors

    fun cellAt(row: Int, col: Int): Cell = grid[row][col]

    fun rowText(row: Int): String {
        val sb = StringBuilder()
        for (c in 0 until cols) {
            val cell = grid[row][c]
            if (cell.widePlaceholder) continue
            sb.appendCodePoint(if (cell.codePoint == 0) ' '.code else cell.codePoint)
        }
        return sb.toString()
    }

    val scrollbackSize: Int get() = scrollbackDeque.size

    fun scrollbackLine(index: Int): Array<Cell> = scrollbackDeque[index]

    fun scrollbackText(index: Int): String {
        val line = scrollbackDeque[index]
        val sb = StringBuilder()
        for (cell in line) {
            if (cell.widePlaceholder) continue
            sb.appendCodePoint(if (cell.codePoint == 0) ' '.code else cell.codePoint)
        }
        return sb.toString()
    }

    /** Current pen, exposed for tests. */
    internal fun currentPen(): Pen = pen
}
