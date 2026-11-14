package com.example.easybooks.reflow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Typische Maße des Fließtexts eines Buchs (in Analyse-Pixeln), aus einer Stichprobe von Seiten.
 */
data class BookStyle(
    val xHeight: Float,
    /** Abstand von Grundlinie zu Grundlinie. */
    val pitch: Float,
    /** Oberlänge (Grundlinie bis Oberkante) und Unterlänge einer Zeile. */
    val ascent: Float,
    val descent: Float,
    /** Wo der Text auf einer normalen Seite beginnt und endet. */
    val textTop: Float,
    val textBottom: Float,
    val columnLeft: Float,
    val columnRight: Float,
) {
    val columnWidth: Float get() = columnRight - columnLeft

    companion object {
        fun from(pages: List<PageText>): BookStyle {
            val body = pages.flatMap { p -> p.textLines.filter { it.words.size >= 3 } }
            val all = pages.flatMap { it.textLines }
            val lines = body.ifEmpty { all }
            val xh = medianF(lines.map { it.xHeight.toFloat() }) ?: 12f
            val lineHeight = medianF(lines.map { it.height.toFloat() }) ?: (xh * 2f)
            val pitches = ArrayList<Float>()
            for (p in pages) {
                val tl = p.textLines
                for (i in 1 until tl.size) {
                    val d = (tl[i].baseline - tl[i - 1].baseline).toFloat()
                    if (d > lineHeight * 0.7f && d < lineHeight * 2.2f) pitches += d
                }
            }
            val full = pages.filter { it.textLines.size >= 5 }
            val lefts = full.mapNotNull { columnOf(it)?.first }
            val rights = full.mapNotNull { columnOf(it)?.second }
            val width = pages.firstOrNull()?.width?.toFloat() ?: 1000f
            val height = pages.firstOrNull()?.height?.toFloat() ?: 1400f
            return BookStyle(
                xHeight = xh,
                pitch = medianF(pitches) ?: (lineHeight * 1.25f),
                ascent = medianF(lines.map { (it.baseline - it.top).toFloat() }) ?: (xh * 1.4f),
                descent = medianF(lines.map { (it.bottom - it.baseline).toFloat() }) ?: (xh * 0.45f),
                textTop = medianF(full.map { it.textLines.first().top.toFloat() }) ?: 0f,
                textBottom = medianF(full.map { it.textLines.last().bottom.toFloat() }) ?: height,
                columnLeft = medianF(lefts) ?: 0f,
                columnRight = medianF(rights) ?: width,
            )
        }

        /**
         * Linke und rechte Kante des Fließtexts auf einer Seite. Eingerückte Zeilen und kurze
         * Absatzenden zählen kaum, weil nur die Ränder der meisten Zeilen gelten.
         */
        fun columnOf(page: PageText): Pair<Float, Float>? {
            val lines = page.textLines.filter { it.words.size >= 3 }
            if (lines.size < 3) return null
            val lefts = lines.map { it.left }.sorted()
            val rights = lines.map { it.right }.sorted()
            return lefts[lefts.size / 10].toFloat() to rights[min(rights.size - 1, rights.size * 9 / 10)].toFloat()
        }

        internal fun medianF(values: List<Float>): Float? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}

/**
 * Setzt die analysierten Seiten eines Buchs neu, Seite für Seite in Lesereihenfolge.
 *
 * Der Text fließt über die Grenzen der PDF-Seiten hinweg. Neue Bildschirmseiten beginnen,
 * wenn die aktuelle voll ist, vor Kapitelanfängen und für ganzseitige Bilder.
 *
 * @param scale Bildschirm-Pixel pro Analyse-Pixel (bestimmt die Schriftgröße)
 */
class ReflowLayouter(
    private val style: BookStyle,
    private val frame: ReflowFrame,
    private val scale: Float,
) {
    private val k = scale
    private val xh = style.xHeight
    private val wordGap = xh * WORD_GAP * k
    private val pitchOut = style.pitch * k
    private val nominalAscent = style.ascent * k
    private val nominalDescent = style.descent * k
    private val leading = max(pitchOut - nominalAscent - nominalDescent, xh * k * 0.15f)
    private val limitY = (frame.height - frame.marginBottom).toFloat()

    private val pages = ArrayList<ScreenPage>()
    private var current = ArrayList<Placement>()
    private var currentStartPage = -1
    private var currentStartLine = 0
    private var currentImageOnly = false
    private var cursorY = frame.marginTop.toFloat()

    private val tokens = ArrayList<Token>()
    private var paragraph = ParagraphStyle()
    private var previous: LineRef? = null
    private var breakBeforeNext = false
    private var finished = false

    /** Fertige Bildschirmseiten (die gerade gefüllte Seite fehlt noch). */
    val completedPages: List<ScreenPage> get() = pages

    fun add(index: Int, page: PageText) {
        check(!finished) { "Layout already finished" }
        if (page.image) {
            flushParagraph()
            page.lines.firstOrNull()?.let { placeImagePage(index, it) }
            previous = null
            breakBeforeNext = true
            return
        }
        val column = BookStyle.columnOf(page) ?: (style.columnLeft to style.columnRight)
        val lines = bodyLines(page, column)
        if (lines.isEmpty()) {
            // Leere Seite, oft zwischen Kapiteln.
            breakBeforeNext = true
            return
        }
        val first = page.lines[lines.first()]
        if (breakBeforeNext || first.top > style.textTop + 3 * style.pitch) {
            flushParagraph()
            closePage()
            previous = null
        }
        breakBeforeNext = false

        for (lineIndex in lines) {
            val line = page.lines[lineIndex]
            if (line.figure) {
                flushParagraph()
                placeFigure(index, lineIndex, line)
                previous = LineRef(line, index, column, figure = true, centered = false)
                continue
            }
            if (line.words.isEmpty()) continue
            val centered = isCentered(line, column)
            val start = paragraphStart(line, index, column, centered)
            if (start != null) {
                flushParagraph()
                paragraph = start
            }
            line.words.forEachIndexed { i, word ->
                tokens += Token(index, lineIndex, line, word, lastInLine = i == line.words.lastIndex)
            }
            previous = LineRef(line, index, column, figure = false, centered = centered)
        }
        val last = page.lines[lines.last()]
        if (last.bottom < style.textBottom - 3 * style.pitch) breakBeforeNext = true
    }

    /** Schließt das Layout ab und liefert alle Bildschirmseiten. */
    fun finish(): List<ScreenPage> {
        if (!finished) {
            flushParagraph()
            closePage()
            finished = true
        }
        return pages
    }

    // ------------------------------------------------------------------ Struktur

    /** Zeilen ohne Kopf- und Fußzeilen (Seitenzahlen, lebende Kolumnentitel). */
    private fun bodyLines(page: PageText, column: Pair<Float, Float>): List<Int> {
        val indices = page.lines.indices.toMutableList()
        val text = indices.filter { !page.lines[it].figure }
        if (text.size >= 3) {
            val colW = column.second - column.first
            val first = page.lines[text[0]]
            val second = page.lines[text[1]]
            if (second.baseline - first.baseline > 2.2f * style.pitch &&
                first.width < 0.5f * colW && first.xHeight < 0.85f * xh
            ) indices.remove(text[0])
            val last = page.lines[text[text.size - 1]]
            val beforeLast = page.lines[text[text.size - 2]]
            if (last.baseline - beforeLast.baseline > 2.0f * style.pitch &&
                last.width < 0.15f * colW && last.top > page.height * 0.8f
            ) indices.remove(text[text.size - 1])
        }
        return indices
    }

    private fun isCentered(line: TextLine, column: Pair<Float, Float>): Boolean {
        val colW = column.second - column.first
        val indent = line.left - column.first
        val rightSpace = column.second - line.right
        return indent > 1.5f * xh && abs(indent - rightSpace) < max(1.5f * xh, 0.05f * colW) &&
            line.width < 0.8f * colW
    }

    /** Beginnt mit dieser Zeile ein neuer Absatz? `null` = Fortsetzung. */
    private fun paragraphStart(
        line: TextLine,
        page: Int,
        column: Pair<Float, Float>,
        centered: Boolean,
    ): ParagraphStyle? {
        val prev = previous
        val colW = column.second - column.first
        val indent = line.left - column.first
        // Leerzeile(n) davor, z. B. unter einer Überschrift: Abstand übernehmen.
        var space = 0f
        if (prev != null && !prev.figure && prev.page == page) {
            val gap = line.baseline - prev.line.baseline
            if (gap > 1.6f * style.pitch) space = min((gap - style.pitch) * k, 2.5f * pitchOut)
        }
        val start = when {
            prev == null || prev.figure -> true
            centered || prev.centered -> true
            space > 0f -> true
            prev.line.words.last().hyphenated -> false
            indent > max(0.9f * xh, 0.025f * colW) -> true
            else -> {
                // Hätte das erste Wort noch in die vorige Zeile gepasst, endete dort ein Absatz.
                val firstWord = line.words.first().width
                prev.line.right + WORD_GAP * xh + firstWord < prev.column.second - 0.3f * xh
            }
        }
        if (!start) return null
        val indentOut = if (!centered && indent > 0.9f * xh) min(indent * k, 2.5f * xh * k) else 0f
        return ParagraphStyle(indentOut, centered, space)
    }

    // ------------------------------------------------------------------ Absätze

    private fun flushParagraph() {
        if (tokens.isEmpty()) {
            paragraph = ParagraphStyle()
            return
        }
        val lines = breakLines(units(tokens), paragraph)
        lines.forEachIndexed { i, line -> placeLine(line, paragraph, first = i == 0, last = i == lines.lastIndex) }
        tokens.clear()
        paragraph = ParagraphStyle()
    }

    /** Wörter, die über einen Trennstrich am Zeilenende zusammengehören, bilden eine Einheit. */
    private fun units(tokens: List<Token>): List<List<Token>> {
        val units = ArrayList<List<Token>>()
        var current = ArrayList<Token>()
        for ((i, t) in tokens.withIndex()) {
            current += t
            val joinsNext = t.lastInLine && t.word.hyphenated && i + 1 < tokens.size
            if (!joinsNext) {
                units += current
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) units += current
        return units
    }

    /**
     * Teil [from]..[to] einer Einheit als starres Stück. Vor einem Umbruch innerhalb der
     * Einheit bleibt der Trennstrich stehen, sonst werden die Teile ohne Strich verbunden.
     */
    private fun segment(unit: List<Token>, from: Int, to: Int): Segment {
        val pieces = ArrayList<Piece>()
        var x = 0f
        for (i in from..to) {
            val t = unit[i]
            val right = if (i < to) t.word.stemRight else t.word.right
            pieces += Piece(t, right, x)
            x += (right - t.word.left) * k
            if (i < to) x += (t.word.hyphenLeft - t.word.stemRight) * k
        }
        return Segment(pieces, x)
    }

    private fun breakLines(units: List<List<Token>>, style: ParagraphStyle): List<OutLine> {
        val lines = ArrayList<OutLine>()
        var segments = ArrayList<Segment>()
        var width = 0f
        var available = frame.textWidth - style.indent
        fun emit() {
            lines += OutLine(segments, width)
            segments = ArrayList()
            width = 0f
            available = frame.textWidth.toFloat()
        }
        for (unit in units) {
            var start = 0
            while (start < unit.size) {
                val gap = if (segments.isEmpty()) 0f else wordGap
                val whole = segment(unit, start, unit.lastIndex)
                if (width + gap + whole.width <= available) {
                    segments += whole
                    width += gap + whole.width
                    start = unit.size
                    continue
                }
                var placed = false
                for (m in unit.lastIndex - 1 downTo start) {
                    val part = segment(unit, start, m)
                    if (width + gap + part.width <= available) {
                        segments += part
                        width += gap + part.width
                        emit()
                        start = m + 1
                        placed = true
                        break
                    }
                }
                if (placed) continue
                if (segments.isNotEmpty()) {
                    emit()
                    continue
                }
                // Selbst allein zu breit: wird beim Platzieren verkleinert.
                val single = segment(unit, start, start)
                segments += single
                width = single.width
                start++
                if (start < unit.size) emit()
            }
        }
        if (segments.isNotEmpty()) emit()
        return lines
    }

    private fun placeLine(line: OutLine, style: ParagraphStyle, first: Boolean, last: Boolean) {
        val indent = if (first) style.indent else 0f
        val available = frame.textWidth - indent
        val shrink = if (line.width > available) available / line.width else 1f
        var ascent = 0f
        var descent = 0f
        for (segment in line.segments) for (piece in segment.pieces) {
            val l = piece.token.line
            ascent = max(ascent, (l.baseline - l.top) * k * shrink)
            descent = max(descent, (l.bottom - l.baseline) * k * shrink)
        }
        // Gleichmäßiger Zeilenabstand: Ein Umlaut über einem Großbuchstaben oder eine Klammer
        // soll keine Lücke reißen. Nur deutlich größere Schrift (Überschriften) bekommt mehr Platz.
        ascent = if (ascent > nominalAscent * 1.35f) ascent else nominalAscent
        descent = if (descent > nominalDescent * 1.6f) descent else nominalDescent
        var y = cursorY + if (first && current.isNotEmpty()) style.spaceBefore else 0f
        if (y + ascent + descent > limitY && current.isNotEmpty()) {
            closePage()
            y = cursorY
        }
        val baselineY = y + ascent

        var gap = wordGap * shrink
        var x = frame.marginX + indent
        if (style.centered) {
            x = frame.marginX + (frame.textWidth - line.width * shrink) / 2f
        } else if (!last && line.segments.size > 1) {
            val extra = (available - line.width * shrink) / (line.segments.size - 1)
            if (extra > 0f && extra < MAX_JUSTIFY * wordGap) gap += extra
        }
        val first0 = line.segments.first().pieces.first().token
        markStart(first0.page, first0.lineIndex)
        line.segments.forEachIndexed { i, segment ->
            if (i > 0) x += gap
            for (piece in segment.pieces) {
                val t = piece.token
                current += Placement(
                    page = t.page,
                    srcLeft = t.word.left,
                    srcTop = t.line.top,
                    srcRight = piece.right,
                    srcBottom = t.line.bottom,
                    dstX = x + piece.dx * shrink,
                    dstY = baselineY - (t.line.baseline - t.line.top) * k * shrink,
                    scale = k * shrink,
                )
            }
            x += segment.width * shrink
        }
        cursorY = baselineY + descent + leading
    }

    // ------------------------------------------------------------------ Bilder

    private fun placeFigure(page: Int, lineIndex: Int, line: TextLine) {
        val w = max(1, line.width)
        val h = max(1, line.height)
        val s = minOf(k, frame.textWidth / w.toFloat(), frame.textHeight / h.toFloat())
        var y = cursorY
        if (y + h * s > limitY && current.isNotEmpty()) {
            closePage()
            y = cursorY
        }
        markStart(page, lineIndex)
        current += Placement(
            page, line.left, line.top, line.right, line.bottom,
            dstX = frame.marginX + (frame.textWidth - w * s) / 2f,
            dstY = y,
            scale = s,
            word = false,
        )
        cursorY = y + h * s + leading
    }

    /** Ganzseitiges Bild (z. B. Cover): eigene Seite, so groß wie möglich. */
    private fun placeImagePage(page: Int, figure: TextLine) {
        closePage()
        val w = max(1, figure.width)
        val h = max(1, figure.height)
        val s = min(frame.width / w.toFloat(), frame.height / h.toFloat())
        markStart(page, 0)
        current += Placement(
            page, figure.left, figure.top, figure.right, figure.bottom,
            dstX = (frame.width - w * s) / 2f,
            dstY = (frame.height - h * s) / 2f,
            scale = s,
            word = false,
        )
        currentImageOnly = true
        closePage()
    }

    // ------------------------------------------------------------------ Seiten

    private fun markStart(page: Int, line: Int) {
        if (current.isEmpty()) {
            currentStartPage = page
            currentStartLine = line
        }
    }

    private fun closePage() {
        if (current.isNotEmpty()) {
            pages += ScreenPage(current, currentStartPage, currentStartLine, currentImageOnly)
        }
        current = ArrayList()
        currentImageOnly = false
        cursorY = frame.marginTop.toFloat()
    }

    private class Token(val page: Int, val lineIndex: Int, val line: TextLine, val word: Word, val lastInLine: Boolean)
    private class Piece(val token: Token, val right: Int, val dx: Float)
    private class Segment(val pieces: List<Piece>, val width: Float)
    private class OutLine(val segments: List<Segment>, val width: Float)
    private class LineRef(
        val line: TextLine,
        val page: Int,
        val column: Pair<Float, Float>,
        val figure: Boolean,
        val centered: Boolean,
    )

    private data class ParagraphStyle(
        val indent: Float = 0f,
        val centered: Boolean = false,
        val spaceBefore: Float = 0f,
    )

    companion object {
        /** Wortabstand in x-Höhen (etwa die Breite eines Leerzeichens). */
        const val WORD_GAP = 0.62f

        /**
         * Blocksatz nur, solange die Lücken nicht mehr als so viele Wortabstände wachsen.
         * Flattersatz mitten im Absatz sähe sonst wie ein Absatzende aus.
         */
        private const val MAX_JUSTIFY = 5f
    }
}

/**
 * Wählt die Vergrößerung: Die x-Höhe des Fließtexts soll [targetXHeight] Bildschirm-Pixel haben.
 * Ist der Text schon bei voller Breite größer, bleibt es bei voller Breite (nie kleiner als bisher).
 */
fun reflowScale(style: BookStyle, frame: ReflowFrame, targetXHeight: Float): Float {
    val byTarget = targetXHeight / style.xHeight
    val fitWidth = if (style.columnWidth > 0f) 0.98f * frame.textWidth / style.columnWidth else byTarget
    return max(byTarget, fitWidth)
}

/** Lesestelle am Anfang einer Bildschirmseite. */
fun ScreenPage.position(lineCount: Int): ReadingPosition =
    ReadingPosition(startPage, if (lineCount > 0) startLine.toFloat() / lineCount else 0f)

/**
 * Index der Bildschirmseite, auf der [position] liegt, oder `null`, wenn diese Stelle
 * noch nicht gesetzt ist. [lineCount] liefert die Zeilenzahl einer PDF-Seite.
 */
fun List<ScreenPage>.indexOf(position: ReadingPosition, lineCount: (Int) -> Int, complete: Boolean): Int? {
    if (isEmpty()) return null
    val line = (position.fraction * lineCount(position.page)).toInt()
    var result = 0
    for ((i, p) in withIndex()) {
        if (p.startPage < position.page || (p.startPage == position.page && p.startLine <= line)) result = i
        else return result
    }
    // Alle gesetzten Seiten liegen davor: Erst wenn alles gesetzt ist, ist die letzte Seite sicher richtig.
    return if (complete) result else null
}
