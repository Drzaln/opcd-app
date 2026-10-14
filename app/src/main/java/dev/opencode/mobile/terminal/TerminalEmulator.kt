package dev.opencode.mobile.terminal

/** Cell attribute bits. */
object Attr {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val INVERSE = 16
    const val STRIKE = 32
}

const val COLOR_DEFAULT = -1
/** Truecolor marker: a positive value >= 1_000_000 packs 0xRRGGBB. */
const val TRUE_RGB_BASE = 1_000_000
fun packRgb(r: Int, g: Int, b: Int) = TRUE_RGB_BASE + (r shl 16) + (g shl 8) + b

class Cell {
    var ch: Char = ' '
    var fg: Int = COLOR_DEFAULT
    var bg: Int = COLOR_DEFAULT
    var attr: Int = 0

    fun set(o: Cell) {
        ch = o.ch; fg = o.fg; bg = o.bg; attr = o.attr
    }

    fun reset() {
        ch = ' '; fg = COLOR_DEFAULT; bg = COLOR_DEFAULT; attr = 0
    }

    fun clear(attrs: Cell) {
        ch = ' '; fg = attrs.fg; bg = attrs.bg; attr = attrs.attr
    }
}

/**
 * A compact ANSI/xterm screen emulator: grid + cursor + scrollback + alternate screen.
 * Supports the SGR/CSI subset used by shells, editors and pagers. Mutate on one thread.
 */
class TerminalEmulator(cols: Int = 80, rows: Int = 24, private val scrollbackLimit: Int = 2000) {

    var cols = cols.coerceAtLeast(2)
        private set
    var rows = rows.coerceAtLeast(2)
        private set

    var version: Long = 0
        private set

    // Writes run on a background thread while the UI reads a frame, so all mutation and reads are
    // serialized on this lock (the UI reads rows under readLocked).
    private val lock = Any()

    fun <T> readLocked(block: () -> T): T = synchronized(lock) { block() }

    private val scrollback = ArrayDeque<MutableList<Cell>>()
    private var screen = Array(this.rows) { MutableList(this.cols) { Cell() } }

    private var cursorX = 0
    private var cursorY = 0
    private var savedX = 0
    private var savedY = 0
    private var cursorVisible = true
    private var pendingWrap = false

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private val pen = Cell()
    private val savedPen = Cell()

    // Alternate screen buffer (DECSET 1049/47/1047).
    private var altActive = false
    private var primaryScreen: Array<MutableList<Cell>>? = null
    private var primaryX = 0
    private var primaryY = 0
    private var primaryTop = 0
    private var primaryBottom = 0

    var title: String? = null
        private set

    private var state = 0 // 0 ground, 1 esc, 2 csi, 3 osc, 4 charset
    private val params = StringBuilder()
    private val osc = StringBuilder()

    val scrollbackSize: Int get() = scrollback.size
    val cursorCol: Int get() = cursorX
    val cursorRow: Int get() = scrollback.size + cursorY
    val isCursorVisible: Boolean get() = cursorVisible
    val onAlternateScreen: Boolean get() = altActive

    fun screenLine(index: Int): List<Cell>? {
        if (index < scrollback.size) return scrollback[index]
        val row = index - scrollback.size
        return screen.getOrNull(row)
    }

    fun totalLines(): Int = scrollback.size + rows

    fun snapshotText(): String = linesText(0, totalLines())

    fun linesText(from: Int, count: Int): String = buildString {
        val end = (from + count).coerceAtMost(totalLines())
        for (i in from.coerceAtLeast(0) until end) {
            val line = screenLine(i) ?: continue
            append(line.joinToString("") { it.ch.toString() }.trimEnd())
            append('\n')
        }
    }

    private fun bump() {
        version++
    }

    // ---------- input ----------

    fun write(text: String) = synchronized(lock) { writeLocked(text) }

    private fun writeLocked(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            i++
            when (state) {
                0 -> when (c) {
                    '\u001b' -> state = 1
                    '\n', '\u000b', '\u000c' -> lineFeed()
                    '\r' -> { cursorX = 0; pendingWrap = false }
                    '\t' -> tab()
                    '\b' -> if (cursorX > 0) cursorX--
                    '\u0007', '\u000e', '\u000f' -> Unit
                    else -> if (c.code >= 32 || c == ' ') putChar(c)
                }
                1 -> when (c) {
                    '[' -> { state = 2; params.setLength(0) }
                    ']' -> { state = 3; osc.setLength(0) }
                    '(', ')', '*', '+' -> state = 4
                    '7' -> { savedX = cursorX; savedY = cursorY; savedPen.set(pen); state = 0 }
                    '8' -> { cursorX = savedX; cursorY = savedY; pen.set(savedPen); state = 0 }
                    '=', '>', 'M' -> state = 0
                    'c' -> { resetLocked(); state = 0 }
                    'D' -> { lineFeed(); state = 0 }
                    'E' -> { cursorX = 0; lineFeed(); state = 0 }
                    else -> state = 0
                }
                2 -> when {
                    c == '\u001b' -> state = 1
                    c.code in 0x40..0x7e -> { csi(c, params.toString()); state = 0 }
                    else -> params.append(c)
                }
                3 -> when (c) {
                    '\u0007' -> { applyOsc(); state = 0 }
                    '\u001b' -> { applyOsc(); state = 1 }
                    else -> osc.append(c)
                }
                4 -> state = 0
            }
        }
        bump()
    }

    private fun applyOsc() {
        val value = osc.toString()
        val idx = value.indexOf(';')
        if (idx > 0 && (value.startsWith("0") || value.startsWith("2"))) {
            title = value.substring(idx + 1).ifBlank { null }
        }
    }

    private fun tab() {
        val next = ((cursorX / 8) + 1) * 8
        cursorX = if (next >= cols) cols - 1 else next
        pendingWrap = false
    }

    // ---------- editing ----------

    private fun putChar(c: Char) {
        if (pendingWrap) {
            cursorX = 0
            lineFeed()
            pendingWrap = false
        }
        if (cursorY in scrollTop..scrollBottom) {
            val line = screen[cursorY]
            val cell = line[cursorX]
            cell.ch = c
            cell.fg = pen.fg
            cell.bg = pen.bg
            cell.attr = pen.attr
        }
        if (cursorX == cols - 1) pendingWrap = true else cursorX++
    }

    private fun lineFeed() {
        if (cursorY == scrollBottom) scrollUp(1) else if (cursorY < rows - 1) cursorY++
        pendingWrap = false
    }

    private fun pushScrollback(line: MutableList<Cell>, force: Boolean = false) {
        if (altActive && !force) return
        if (scrollbackLimit <= 0) return
        scrollback.addLast(line)
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
    }

    private fun scrollUp(n: Int) {
        repeat(n.coerceAtMost(scrollBottom - scrollTop + 1)) {
            if (scrollTop == 0) pushScrollback(screen[scrollTop])
            for (r in scrollTop until scrollBottom) screen[r] = screen[r + 1]
            screen[scrollBottom] = MutableList(cols) { Cell() }
        }
    }

    private fun scrollDown(n: Int) {
        repeat(n.coerceAtMost(scrollBottom - scrollTop + 1)) {
            for (r in scrollBottom downTo scrollTop + 1) screen[r] = screen[r - 1]
            screen[scrollTop] = MutableList(cols) { Cell() }
        }
    }

    // ---------- CSI ----------

    private fun intParams(): List<Int> {
        if (params.isEmpty()) return emptyList()
        return params.toString().split(';').map { it.substringBefore(':').toIntOrNull() ?: 0 }
    }

    private fun p(list: List<Int>, i: Int, default: Int): Int {
        val v = list.getOrNull(i) ?: return default
        return if (v == 0) default else v
    }

    private fun csi(final: Char, raw: String) {
        val priv = raw.startsWith("?") || raw.startsWith(">") || raw.startsWith("<") || raw.startsWith("=")
        val body = if (priv) raw.substring(1) else raw
        params.setLength(0)
        params.append(body)
        val list = intParams()
        when (final) {
            'A' -> { cursorY = (cursorY - p(list, 0, 1)).coerceAtLeast(scrollTop); pendingWrap = false }
            'B' -> { cursorY = (cursorY + p(list, 0, 1)).coerceAtMost(scrollBottom); pendingWrap = false }
            'C' -> { cursorX = (cursorX + p(list, 0, 1)).coerceAtMost(cols - 1); pendingWrap = false }
            'D' -> { cursorX = (cursorX - p(list, 0, 1)).coerceAtLeast(0); pendingWrap = false }
            'E' -> { cursorY = (cursorY + p(list, 0, 1)).coerceAtMost(scrollBottom); cursorX = 0 }
            'F' -> { cursorY = (cursorY - p(list, 0, 1)).coerceAtLeast(scrollTop); cursorX = 0 }
            'G', '`' -> cursorX = (p(list, 0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> cursorY = (p(list, 0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorY = (p(list, 0, 1) - 1).coerceIn(0, rows - 1)
                cursorX = (p(list, 1, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(list.getOrElse(0) { 0 })
            'K' -> eraseLine(list.getOrElse(0) { 0 })
            'L' -> insertLines(p(list, 0, 1))
            'M' -> deleteLines(p(list, 0, 1))
            'P' -> deleteChars(p(list, 0, 1))
            '@' -> insertChars(p(list, 0, 1))
            'X' -> eraseChars(p(list, 0, 1))
            'S' -> scrollUp(p(list, 0, 1))
            'T' -> scrollDown(p(list, 0, 1))
            'r' -> {
                val top = p(list, 0, 1) - 1
                val bottom = p(list, 1, rows) - 1
                if (top in 0 until bottom && bottom < rows) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorX = 0
                    cursorY = scrollTop
                }
            }
            'm' -> sgr(list)
            's' -> { savedX = cursorX; savedY = cursorY; savedPen.set(pen) }
            'u' -> { cursorX = savedX; cursorY = savedY; pen.set(savedPen) }
            'h' -> if (priv) decset(list)
            'l' -> if (priv) decrst(list)
            'n', 'c', 't', 'g' -> Unit
            else -> Unit
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorY + 1 until rows) clearRow(r)
            }
            1 -> {
                for (r in 0 until cursorY) clearRow(r)
                eraseLine(1)
            }
            2, 3 -> for (r in 0 until rows) clearRow(r)
        }
    }

    private fun eraseLine(mode: Int) {
        val line = screen[cursorY]
        when (mode) {
            0 -> for (c in cursorX until cols) line[c].clear(pen)
            1 -> for (c in 0..cursorX.coerceAtMost(cols - 1)) line[c].clear(pen)
            2 -> for (c in 0 until cols) line[c].clear(pen)
        }
    }

    private fun eraseChars(n: Int) {
        val line = screen[cursorY]
        for (c in cursorX until (cursorX + n).coerceAtMost(cols)) line[c].clear(pen)
    }

    private fun clearRow(r: Int) {
        val line = screen[r]
        for (c in 0 until cols) line[c].clear(pen)
    }

    private fun insertLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        repeat(n.coerceAtMost(scrollBottom - cursorY + 1)) {
            for (r in scrollBottom downTo cursorY + 1) screen[r] = screen[r - 1]
            screen[cursorY] = MutableList(cols) { Cell() }
        }
    }

    private fun deleteLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        repeat(n.coerceAtMost(scrollBottom - cursorY + 1)) {
            for (r in cursorY until scrollBottom) screen[r] = screen[r + 1]
            screen[scrollBottom] = MutableList(cols) { Cell() }
        }
    }

    private fun insertChars(n: Int) {
        val line = screen[cursorY]
        for (i in cols - 1 downTo cursorX + n) line[i].set(line[i - n])
        for (i in cursorX until (cursorX + n).coerceAtMost(cols)) line[i].clear(pen)
    }

    private fun deleteChars(n: Int) {
        val line = screen[cursorY]
        for (i in cursorX until cols - n) line[i].set(line[i + n])
        for (i in (cols - n).coerceAtLeast(cursorX) until cols) line[i].clear(pen)
    }

    private fun sgr(list: List<Int>) {
        if (list.isEmpty()) { pen.attr = 0; pen.fg = COLOR_DEFAULT; pen.bg = COLOR_DEFAULT; return }
        var i = 0
        while (i < list.size) {
            when (val v = list[i]) {
                0 -> { pen.attr = 0; pen.fg = COLOR_DEFAULT; pen.bg = COLOR_DEFAULT }
                1 -> pen.attr = pen.attr or Attr.BOLD
                2 -> pen.attr = pen.attr or Attr.DIM
                3 -> pen.attr = pen.attr or Attr.ITALIC
                4 -> pen.attr = pen.attr or Attr.UNDERLINE
                7 -> pen.attr = pen.attr or Attr.INVERSE
                9 -> pen.attr = pen.attr or Attr.STRIKE
                22 -> pen.attr = pen.attr and Attr.BOLD.inv() and Attr.DIM.inv()
                23 -> pen.attr = pen.attr and Attr.ITALIC.inv()
                24 -> pen.attr = pen.attr and Attr.UNDERLINE.inv()
                27 -> pen.attr = pen.attr and Attr.INVERSE.inv()
                29 -> pen.attr = pen.attr and Attr.STRIKE.inv()
                in 30..37 -> pen.fg = v - 30
                39 -> pen.fg = COLOR_DEFAULT
                in 40..47 -> pen.bg = v - 40
                49 -> pen.bg = COLOR_DEFAULT
                in 90..97 -> pen.fg = v - 90 + 8
                in 100..107 -> pen.bg = v - 100 + 8
                38, 48 -> {
                    val target = v == 38
                    when (list.getOrNull(i + 1)) {
                        5 -> {
                            val idx = list.getOrNull(i + 2) ?: 0
                            if (target) pen.fg = idx.coerceIn(0, 255) else pen.bg = idx.coerceIn(0, 255)
                            i += 2
                        }
                        2 -> {
                            val r = list.getOrNull(i + 2) ?: 0
                            val g = list.getOrNull(i + 3) ?: 0
                            val b = list.getOrNull(i + 4) ?: 0
                            if (target) pen.fg = packRgb(r, g, b) else pen.bg = packRgb(r, g, b)
                            i += 4
                        }
                    }
                }
            }
            i++
        }
    }

    private fun decset(list: List<Int>) {
        for (v in list) when (v) {
            25 -> cursorVisible = true
            47, 1047, 1049 -> enterAlt()
            2004 -> Unit
        }
    }

    private fun decrst(list: List<Int>) {
        for (v in list) when (v) {
            25 -> cursorVisible = false
            47, 1047, 1049 -> leaveAlt()
        }
    }

    private fun enterAlt() {
        if (altActive) return
        primaryScreen = screen
        primaryX = cursorX
        primaryY = cursorY
        primaryTop = scrollTop
        primaryBottom = scrollBottom
        screen = Array(rows) { MutableList(cols) { Cell() } }
        cursorX = 0
        cursorY = 0
        scrollTop = 0
        scrollBottom = rows - 1
        altActive = true
    }

    private fun leaveAlt() {
        if (!altActive) return
        screen = primaryScreen ?: Array(rows) { MutableList(cols) { Cell() } }
        primaryScreen = null
        cursorX = primaryX.coerceIn(0, cols - 1)
        cursorY = primaryY.coerceIn(0, rows - 1)
        // Clamp the margins — a resize may have happened while the alt screen was active.
        scrollTop = primaryTop.coerceIn(0, rows - 1)
        scrollBottom = primaryBottom.coerceIn(scrollTop, rows - 1)
        altActive = false
    }

    // ---------- lifecycle ----------

    fun reset() = synchronized(lock) { resetLocked() }

    private fun resetLocked() {
        scrollback.clear()
        screen = Array(rows) { MutableList(cols) { Cell() } }
        cursorX = 0; cursorY = 0; savedX = 0; savedY = 0
        pen.attr = 0; pen.fg = COLOR_DEFAULT; pen.bg = COLOR_DEFAULT
        scrollTop = 0; scrollBottom = rows - 1
        pendingWrap = false
        cursorVisible = true
        altActive = false
        primaryScreen = null
        bump()
    }

    fun resize(newCols: Int, newRows: Int) = synchronized(lock) { resizeLocked(newCols, newRows) }

    private fun resizeLocked(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(2)
        val r = newRows.coerceAtLeast(2)
        if (c == cols && r == rows) return

        // Vertical: like a real terminal's SIGWINCH handling, keep the cursor on screen by moving
        // lines to/from scrollback instead of dropping the bottom rows (which lost the prompt when
        // the keyboard opened). totalLines stays constant, so the viewport doesn't jump either.
        val grow = r - rows
        if (grow < 0) {
            val drop = -grow
            val kept = Array(r) { screen[it + drop] }
            repeat(drop) { pushScrollback(screen[it]) }
            screen = kept
            cursorY -= drop
        } else if (grow > 0) {
            val pull = if (altActive) 0 else minOf(grow, scrollback.size)
            val added = Array(r) { MutableList(cols) { Cell() } }
            // scrollback.removeLast() yields newest first, so fill the view back-to-front.
            for (i in pull - 1 downTo 0) added[i] = scrollback.removeLast()
            for (row in 0 until rows) added[pull + row] = screen[row]
            screen = added
            cursorY += pull
        }

        // The primary buffer is parked while the alt screen is active — resize it too, so leaving
        // the alt screen after a resize (e.g. the keyboard opening) lines up instead of clipping.
        primaryScreen?.let { primary ->
            val drop = primary.size - r
            // Lines scrolled out of the parked primary buffer still belong to its scrollback.
            if (drop > 0) repeat(drop) { pushScrollback(primary[it], force = true) }
            val shifted = when {
                drop > 0 -> Array(r) { primary[it + drop] }
                drop < 0 -> Array(r) { i -> primary.getOrNull(i) ?: MutableList(cols) { Cell() } }
                else -> primary
            }
            for (row in shifted.indices) {
                val old = shifted[row]
                shifted[row] = MutableList(c) { col -> old.getOrNull(col) ?: Cell() }
            }
            primaryScreen = shifted
            primaryY = (primaryY - drop.coerceAtLeast(0)).coerceIn(0, r - 1)
            primaryX = primaryX.coerceIn(0, c - 1)
            primaryTop = 0
            primaryBottom = r - 1
        }

        // Horizontal: keep the left-most columns.
        if (c != cols) {
            for (row in screen.indices) {
                val old = screen[row]
                screen[row] = MutableList(c) { col -> old.getOrNull(col) ?: Cell() }
            }
        }

        cols = c
        rows = r
        scrollTop = 0
        scrollBottom = r - 1
        cursorX = cursorX.coerceIn(0, c - 1)
        cursorY = cursorY.coerceIn(0, r - 1)
        bump()
    }
}
