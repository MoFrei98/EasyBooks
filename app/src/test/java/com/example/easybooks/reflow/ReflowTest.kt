package com.example.easybooks.reflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Künstliche Seiten: "Buchstaben" sind schwarze Rechtecke (x-Höhe 14 px, manche mit Oberlänge),
 * Buchstabenabstand 2 px, Wortabstand 10 px, Zeilenabstand 30 px.
 */
private class SyntheticPage(val width: Int = 600, val height: Int = 900) {
    val pixels = IntArray(width * height) { WHITE }

    fun rect(x: Int, y: Int, w: Int, h: Int, color: Int = BLACK) {
        for (yy in y until y + h) for (xx in x until x + w) pixels[yy * width + xx] = color
    }

    /** Zeichnet ein Wort aus [letters] Buchstaben, gibt den rechten Rand zurück. */
    fun word(x: Int, baseline: Int, letters: Int): Int {
        var cx = x
        repeat(letters) { i ->
            // Etwa jeder vierte Buchstabe hat eine Oberlänge, wie in echtem Text.
            val tall = i % 4 == 1
            val h = if (tall) 20 else 14
            rect(cx, baseline - h, 6, h)
            cx += 6 + 2
        }
        return cx - 2
    }

    /** Trennstrich direkt hinter einem Wort (Buchstabenabstand davor). */
    fun hyphen(x: Int, baseline: Int): Int {
        rect(x + 2, baseline - 8, 6, 2)
        return x + 2 + 6
    }

    /** Eine Zeile aus Wörtern mit den angegebenen Buchstabenzahlen. */
    fun line(x: Int, baseline: Int, words: List<Int>, hyphenAtEnd: Boolean = false): Int {
        var cx = x
        for ((i, n) in words.withIndex()) {
            if (i > 0) cx += 10
            cx = word(cx, baseline, n)
        }
        if (hyphenAtEnd) cx = hyphen(cx, baseline)
        return cx
    }

    fun analyze(): PageText = ReflowAnalyzer.analyze(pixels, width, height)

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}

/** Seite mit Fließtext: Absätze mit eingerückter erster Zeile und kurzer letzter Zeile. */
private fun prosePage(paragraphs: Int = 3, linesPerParagraph: Int = 5, hyphenLine: Int = -1): SyntheticPage {
    val page = SyntheticPage()
    var baseline = 60
    var lineNo = 0
    repeat(paragraphs) {
        repeat(linesPerParagraph) { l ->
            val last = l == linesPerParagraph - 1
            val indent = if (l == 0) 30 else 0
            val words = if (last) listOf(4, 3, 5) else listOf(5, 3, 6, 4, 7, 3, 5, 4, 6).let { w ->
                if (indent > 0) w.dropLast(1) else w
            }
            page.line(40 + indent, baseline, words, hyphenAtEnd = lineNo == hyphenLine)
            baseline += 30
            lineNo++
        }
    }
    return page
}

class ReflowAnalyzerTest {

    @Test
    fun `finds lines and words`() {
        val text = prosePage(paragraphs = 2, linesPerParagraph = 4).analyze()
        assertFalse(text.image)
        assertEquals(8, text.lines.size)
        val first = text.lines[0]
        assertEquals(8, first.words.size)
        assertEquals(60, first.baseline)
        assertEquals(14, first.xHeight)
        assertEquals(70, first.left)
        assertEquals(3, text.lines[3].words.size)
        assertTrue(text.lines.none { it.figure })
    }

    @Test
    fun `detects hyphen only at the hyphenated line end`() {
        val text = prosePage(hyphenLine = 2).analyze()
        val hyphenated = text.lines.mapIndexedNotNull { i, l -> if (l.words.last().hyphenated) i else null }
        assertEquals(listOf(2), hyphenated)
        val word = text.lines[2].words.last()
        assertTrue(word.stemRight in (word.left + 1) until word.hyphenLeft)
        assertEquals(word.right, word.hyphenLeft + 6)
    }

    @Test
    fun `small gaps like dots after a word stay in the word`() {
        val page = SyntheticPage()
        // "Wort..." : Punkte mit 4 px Abstand, dann ein normaler Wortabstand.
        for (b in listOf(60, 90, 120, 150)) {
            var x = page.word(40, b, 5)
            repeat(3) {
                page.rect(x + 4, b - 3, 3, 3)
                x += 7
            }
            page.line(x + 10, b, listOf(4, 6, 3))
        }
        val text = page.analyze()
        assertEquals(4, text.lines.size)
        assertEquals(4, text.lines[0].words.size)
    }

    @Test
    fun `H with a faint hairline crossbar stays one letter`() {
        // Issue #34: Bei feinen Schriften ist der Querstrich im H nur blassgrau gerendert.
        // Die Lücke zwischen den Stämmen ist breiter als ein Wortabstand. Das H darf trotzdem
        // nicht in zwei Wörter zerfallen.
        val page = SyntheticPage()
        val hairline = 0xFFB4B4B4.toInt() // heller als die Tinten-Schwelle, aber kein Papier
        val starts = ArrayList<Int>()
        for (b in listOf(60, 90, 120, 150, 180, 210)) {
            var x = page.word(40, b, 4) + 10
            starts += x
            page.rect(x, b - 20, 3, 20)
            page.rect(x + 14, b - 20, 3, 20)
            page.rect(x + 3, b - 11, 11, 1, hairline)
            x = page.word(x + 17 + 2, b, 5) + 10
            page.line(x, b, listOf(3, 6))
        }
        val text = page.analyze()
        assertEquals(6, text.lines.size)
        for ((i, line) in text.lines.withIndex()) {
            assertEquals(4, line.words.size)
            assertEquals(starts[i], line.words[1].left)
        }
    }

    @Test
    fun `two columns are read left then right`() {
        val page = SyntheticPage(width = 700)
        for (i in 0 until 8) {
            page.line(30, 60 + 30 * i, listOf(5, 4, 6))
            page.line(400, 60 + 30 * i, listOf(4, 6, 5))
        }
        val text = page.analyze()
        assertEquals(16, text.lines.size)
        assertTrue(text.lines.take(8).all { it.right < 350 })
        assertTrue(text.lines.drop(8).all { it.left >= 400 })
    }

    @Test
    fun `centered title page is not split into columns`() {
        val page = SyntheticPage()
        // Wenige zentrierte Zeilen mit viel Luft dazwischen, wie auf einer Titelseite.
        for ((i, words) in listOf(listOf(9, 12), listOf(5, 7, 4, 6, 3), listOf(8), listOf(3, 10, 4, 9)).withIndex()) {
            val width = words.sum() * 8 - 2 + (words.size - 1) * 10
            page.line((600 - width) / 2, 150 + 140 * i, words)
        }
        val text = page.analyze()
        assertEquals(4, text.lines.size)
        assertEquals(listOf(2, 5, 1, 4), text.lines.map { it.words.size })
    }

    @Test
    fun `dark page is an image page`() {
        val page = SyntheticPage()
        page.rect(50, 50, 500, 800, 0xFF203040.toInt())
        val text = page.analyze()
        assertTrue(text.image)
        assertEquals(1, text.lines.size)
        assertTrue(text.lines[0].figure)
    }

    @Test
    fun `tall dense block is a figure`() {
        val page = prosePage(paragraphs = 1, linesPerParagraph = 4)
        page.rect(100, 250, 300, 200, 0xFF808080.toInt())
        val text = page.analyze()
        assertEquals(1, text.lines.count { it.figure })
        assertEquals(4, text.lines.count { !it.figure })
    }
}

class ReflowLayouterTest {
    private val pages = List(3) { prosePage(hyphenLine = if (it == 0) 2 else -1).analyze() }
    private val style = BookStyle.from(pages)
    private val frame = ReflowFrame(width = 400, height = 700, marginX = 20, marginTop = 20, marginBottom = 20)

    private fun layout(scale: Float): List<ScreenPage> {
        val layouter = ReflowLayouter(style, frame, scale)
        pages.forEachIndexed { i, p -> layouter.add(i, p) }
        return layouter.finish()
    }

    @Test
    fun `style matches the synthetic text`() {
        assertEquals(14f, style.xHeight)
        assertEquals(30f, style.pitch)
        assertEquals(40f, style.columnLeft)
    }

    @Test
    fun `every word is placed once in reading order`() {
        val result = layout(2f)
        val placed = result.flatMap { it.placements }.map { Triple(it.page, it.srcTop, it.srcLeft) }
        val expected = pages.flatMapIndexed { p, text ->
            text.lines.flatMap { l -> l.words.map { Triple(p, l.top, it.left) } }
        }
        // Der Trennstrich verbindet zwei Wörter, die Teile bleiben einzelne Stücke.
        assertEquals(expected, placed)
    }

    @Test
    fun `placements stay inside the text area`() {
        for (scale in listOf(1f, 2f, 3f)) {
            for (page in layout(scale)) for (p in page.placements) {
                assertTrue("left $p", p.dstX >= frame.marginX - 0.5f)
                assertTrue("right $p", p.dstX + p.dstWidth <= frame.width - frame.marginX + 0.5f)
                assertTrue("top $p", p.dstY >= 0f)
                assertTrue("bottom $p", p.dstY + p.dstHeight <= frame.height - frame.marginBottom + 0.5f)
            }
        }
    }

    @Test
    fun `bigger text needs more pages`() {
        val small = layout(1f).size
        val large = layout(2.5f).size
        assertTrue("$small < $large", small < large)
    }

    @Test
    fun `hyphenated word is joined without hyphen inside a line`() {
        val result = layout(1f)
        val word = pages[0].lines[2].words.last()
        val piece = result.flatMap { it.placements }.first { it.page == 0 && it.srcTop == pages[0].lines[2].top && it.srcLeft == word.left }
        // Entweder ohne Strich verbunden oder am Zeilenende mit Strich.
        assertTrue(piece.srcRight == word.stemRight || piece.srcRight == word.right)
        val next = result.flatMap { it.placements }.first { it.page == 0 && it.srcTop == pages[0].lines[3].top }
        if (piece.srcRight == word.stemRight) {
            val gap = next.dstX - (piece.dstX + piece.dstWidth)
            assertEquals((word.hyphenLeft - word.stemRight).toFloat(), gap, 0.5f)
        }
    }

    @Test
    fun `paragraphs start on a new indented line`() {
        val result = layout(1f)
        val all = result.flatMap { it.placements }
        val firstOfSecondParagraph = pages[0].lines[5]
        val p = all.first { it.page == 0 && it.srcTop == firstOfSecondParagraph.top && it.srcLeft == firstOfSecondParagraph.left }
        assertTrue(p.dstX > frame.marginX + 5f)
        val before = all[all.indexOf(p) - 1]
        assertTrue(before.dstY < p.dstY)
    }

    @Test
    fun `reading position maps to screen pages and back`() {
        val result = layout(2f)
        assertTrue(result.size > 3)
        val lineCount = { page: Int -> pages[page].lines.size }
        for ((i, page) in result.withIndex()) {
            val pos = page.position(lineCount(page.startPage))
            assertEquals(i, result.indexOf(pos, lineCount, complete = true))
        }
        assertEquals(0, result.indexOf(ReadingPosition(0), lineCount, complete = true))
        // Noch nicht gesetzte Stellen: abwarten.
        val partial = result.take(2)
        assertEquals(null, partial.indexOf(ReadingPosition(2, 0.5f), lineCount, complete = false))
    }

    @Test
    fun `image page gets its own screen page`() {
        val cover = SyntheticPage().apply { rect(0, 0, 600, 900, 0xFF203040.toInt()) }.analyze()
        val layouter = ReflowLayouter(style, frame, 2f)
        layouter.add(0, cover)
        layouter.add(1, pages[0])
        val result = layouter.finish()
        assertTrue(result[0].imageOnly)
        assertEquals(1, result[0].placements.size)
        assertEquals(1, result[1].startPage)
    }

    @Test
    fun `scale keeps text at least as big as full width`() {
        val tiny = reflowScale(style, frame, targetXHeight = 1f)
        assertEquals(0.98f * frame.textWidth / style.columnWidth, tiny, 0.001f)
        assertEquals(30f / 14f, reflowScale(style, frame, targetXHeight = 30f), 0.001f)
    }
}

class ReflowCacheTest {
    @Test
    fun `analysis survives a round trip`() {
        val texts = arrayOf<PageText?>(prosePage(hyphenLine = 1).analyze(), null)
        val style = BookStyle.from(texts.filterNotNull())
        val out = ByteArrayOutputStream()
        ReflowCache.encode(ReflowAnalysis(2, 1234L, 2.5f, style, texts), out)
        val back = ReflowCache.decode(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(back)
        back!!
        assertEquals(2, back.pageCount)
        assertEquals(1234L, back.fileSize)
        assertEquals(2.5f, back.scale)
        assertEquals(style, back.style)
        assertEquals(texts[0], back.pages[0])
        assertEquals(null, back.pages[1])
    }
}
