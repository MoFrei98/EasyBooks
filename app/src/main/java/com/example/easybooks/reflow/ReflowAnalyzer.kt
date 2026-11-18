package com.example.easybooks.reflow

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Findet Zeilen, Wörter, Trennstriche und Bilder auf einer gerenderten Seite.
 *
 * Arbeitet nur mit Pixeln (ARGB), damit es auch bei gescannten PDFs ohne Textebene
 * funktioniert und auf jeder Plattform gleich rechnet (die iOS-App nutzt dieselbe Logik).
 */
object ReflowAnalyzer {
    /** Mittlere Helligkeit, unter der die ganze Seite als Bild gilt (wie im Dark Mode). */
    private const val IMAGE_LUMINANCE = 0.55

    /** Pixel dunkler als dieser Anteil der Papierhelligkeit gelten als Tinte. */
    private const val INK_FACTOR = 0.63

    /**
     * Für die Trennung in Wörter zählen auch blasse Pixel zum Buchstaben: feine Haarstriche
     * (der Querstrich im H) und geglättete Kanten. Sonst zerfällt ein H in zwei Teile und
     * etwas weitere Buchstabenabstände (nach dem J) sehen aus wie Wortabstände.
     */
    private const val FAINT_FACTOR = 0.85

    /** Pixel heller als dieser Wert gehören bei Bildseiten zum Rand. */
    private const val WHITE = 232

    fun analyze(argb: IntArray, width: Int, height: Int): PageText {
        val n = width * height
        val lum = ByteArray(n)
        val histogram = IntArray(256)
        var sum = 0L
        for (i in 0 until n) {
            val c = argb[i]
            val l = (((c shr 16) and 0xFF) * 54 + ((c shr 8) and 0xFF) * 183 + (c and 0xFF) * 19) shr 8
            lum[i] = l.toByte()
            histogram[l]++
            sum += l
        }
        if (n == 0) return PageText(width, height, emptyList())
        if (sum / (255.0 * n) < IMAGE_LUMINANCE) return imagePage(lum, width, height)

        val paper = percentile(histogram, n, 0.9)
        val threshold = (paper * INK_FACTOR).roundToInt()
        val ink = BooleanArray(n) { (lum[it].toInt() and 0xFF) < threshold }
        val faintThreshold = (paper * FAINT_FACTOR).roundToInt()
        val faint = BooleanArray(n) { (lum[it].toInt() and 0xFF) < faintThreshold }
        return Page(ink, faint, width, height).analyze()
    }

    /** Ganze Seite als ein Bild, begrenzt auf den nicht weißen Bereich. */
    private fun imagePage(lum: ByteArray, width: Int, height: Int): PageText {
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((lum[row + x].toInt() and 0xFF) < WHITE) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return PageText(width, height, emptyList())
        val figure = TextLine(
            top = minY, bottom = maxY + 1, baseline = maxY + 1, xHeight = 0,
            left = minX, right = maxX + 1, words = emptyList(), figure = true,
        )
        return PageText(width, height, listOf(figure), image = true)
    }

    private fun percentile(histogram: IntArray, total: Int, p: Double): Int {
        val target = (total * p).toLong()
        var seen = 0L
        for (v in histogram.indices) {
            seen += histogram[v]
            if (seen > target) return v
        }
        return 255
    }

    /** Ein waagerechter Streifen mit Tinte, Kandidat für eine Zeile. */
    private class Run(var top: Int, var bottom: Int) {
        val height: Int get() = bottom - top
    }

    /**
     * @param ink Pixel, die sicher Tinte sind (Zeilen, x-Höhe, Bilder, Trennstriche)
     * @param faint auch blasse Pixel; nur für die Grenzen der Buchstaben und Wörter
     */
    private class Page(val ink: BooleanArray, val faint: BooleanArray, val width: Int, val height: Int) {

        fun analyze(): PageText {
            val colInk = IntArray(width)
            var inkRows = 0
            for (y in 0 until height) {
                val row = y * width
                var any = false
                for (x in 0 until width) {
                    if (ink[row + x]) {
                        colInk[x]++
                        any = true
                    }
                }
                if (any) inkRows++
            }
            if (inkRows == 0) return PageText(width, height, emptyList())
            val left = colInk.indexOfFirst { it > 0 }
            val right = colInk.indexOfLast { it > 0 } + 1

            val lines = ArrayList<TextLine>()
            for (region in columns(colInk, left, right, inkRows)) {
                lines += regionLines(region.first, region.last + 1)
            }
            return PageText(width, height, lines)
        }

        /**
         * Erkennt zweispaltigen Satz an einer leeren senkrechten Gasse in der Mitte.
         * Die Spalten werden nacheinander gelesen: erst links, dann rechts.
         *
         * @param inkRows Anzahl der Pixelzeilen mit Tinte; die Gasse darf nur in wenigen davon
         *   etwas enthalten (z. B. eine Überschrift über beide Spalten).
         */
        private fun columns(colInk: IntArray, left: Int, right: Int, inkRows: Int): List<IntRange> {
            val whole = listOf(left until right)
            val w = right - left
            if (w < 40) return whole
            val tolerance = max(1, (inkRows * 0.02).toInt())
            // Deutlich breiter als ein Wortabstand, sonst wären zufällig übereinander
            // liegende Wortlücken schon eine "Spalte".
            val minGap = max(12, (w * 0.035).roundToInt())
            val from = left + (w * 0.3).toInt()
            val to = left + (w * 0.7).toInt()
            var bestStart = -1
            var bestEnd = -1
            var x = from
            while (x < to) {
                if (colInk[x] <= tolerance) {
                    var s = x
                    while (s > left && colInk[s - 1] <= tolerance) s--
                    var e = x
                    while (e < right && colInk[e] <= tolerance) e++
                    if (e - s > bestEnd - bestStart) {
                        bestStart = s
                        bestEnd = e
                    }
                    x = e
                } else {
                    x++
                }
            }
            if (bestEnd - bestStart < minGap) return whole
            // Beide Spalten müssen über die meisten Zeilen Text haben. Eine zentrierte
            // Titelseite mit wenigen Zeilen ist kein zweispaltiger Satz.
            var leftRows = 0
            var rightRows = 0
            for (y in 0 until height) {
                val row = y * width
                if ((left until bestStart).any { ink[row + it] }) leftRows++
                if ((bestEnd until right).any { ink[row + it] }) rightRows++
            }
            if (leftRows < inkRows * 0.4 || rightRows < inkRows * 0.4) return whole
            return listOf(left until bestStart, bestEnd until right)
        }

        private fun regionLines(x0: Int, x1: Int): List<TextLine> {
            val rowInk = IntArray(height)
            for (y in 0 until height) {
                val row = y * width
                var count = 0
                for (x in x0 until x1) if (ink[row + x]) count++
                rowInk[y] = count
            }
            var runs = ArrayList<Run>()
            var y = 0
            while (y < height) {
                if (rowInk[y] > 0) {
                    val start = y
                    while (y < height && rowInk[y] > 0) y++
                    runs += Run(start, y)
                } else {
                    y++
                }
            }
            if (runs.isEmpty()) return emptyList()

            // Punkte und Akzente über Buchstaben bilden eigene flache Streifen: mit der Zeile verbinden.
            val typical = median(runs.filter { it.height >= 3 }.map { it.height }) ?: return emptyList()
            runs = mergeSmall(runs, typical)
            val lineHeight = median(runs.map { it.height }) ?: return emptyList()

            // Zu hohe Streifen sind oft zwei sich berührende Zeilen.
            val split = ArrayList<Run>()
            for (run in runs) splitTall(run, rowInk, lineHeight, split)

            val raw = split.map { run -> measure(run, rowInk, x0, x1, lineHeight) }
            val pageXHeight = median(raw.filter { !it.figure }.map { it.xHeight }) ?: (lineHeight / 2)
            val glyphs = raw.map { if (it.figure) null else glyphs(it, x0, x1) }
            val wordGap = wordGapThreshold(raw, glyphs, pageXHeight)
            return raw.mapIndexed { i, line ->
                val g = glyphs[i]
                if (g == null) line else withWords(line, g, wordGap * lineScale(line, pageXHeight))
            }
        }

        /** Senkrechte Streifen mit Tinte innerhalb einer Zeile: [Anfang, Ende) je Buchstabe. */
        private fun glyphs(line: TextLine, x0: Int, x1: Int): IntArray {
            val cols = IntArray(x1 - x0)
            for (y in line.top until line.bottom) {
                val row = y * width
                for (x in x0 until x1) if (faint[row + x]) cols[x - x0]++
            }
            val bounds = ArrayList<Int>()
            var x = 0
            while (x < cols.size) {
                if (cols[x] > 0) {
                    val s = x
                    while (x < cols.size && cols[x] > 0) x++
                    bounds += x0 + s
                    bounds += x0 + x
                } else {
                    x++
                }
            }
            return bounds.toIntArray()
        }

        /** Größere Überschriften haben größere Abstände: Schwelle mit der x-Höhe der Zeile skalieren. */
        private fun lineScale(line: TextLine, pageXHeight: Int): Double =
            if (pageXHeight <= 0) 1.0 else (line.xHeight.toDouble() / pageXHeight).coerceIn(0.6, 2.5)

        /**
         * Abstand, ab dem eine Lücke zwischen zwei Wörtern liegt. Die Lücken einer Seite
         * häufen sich bei Buchstabenabständen (klein) und Wortabständen (groß); die Schwelle
         * liegt in der dünnsten Stelle dazwischen.
         */
        private fun wordGapThreshold(lines: List<TextLine>, glyphs: List<IntArray?>, pageXHeight: Int): Double {
            val xh = pageXHeight.coerceAtLeast(4)
            val counts = IntArray(xh * 2)
            for ((i, g) in glyphs.withIndex()) {
                if (g == null) continue
                val s = lineScale(lines[i], pageXHeight)
                var j = 2
                while (j + 1 < g.size) {
                    val gap = ((g[j] - g[j - 1]) / s).roundToInt()
                    if (gap < counts.size) counts[gap]++
                    j += 2
                }
            }
            val lo = max(2, (xh * 0.35).roundToInt())
            val hi = max(lo, (xh * 0.75).roundToInt())
            // Etwas geglättet, damit einzelne Ausreißer nicht entscheiden.
            fun smoothed(t: Int) = counts[t - 1] + 2 * counts[t] + counts.getOrElse(t + 1) { 0 }
            val bestCount = (lo..hi).minOf { smoothed(it) }
            // Oft ist eine ganze Strecke leer. Dann die Mitte der längsten leeren Strecke
            // nehmen, nicht ihren Anfang: Ein etwas weiterer Buchstabenabstand (z. B. nach
            // einem J) darf noch kein Wortabstand sein.
            var bestStart = lo
            var bestLength = 0
            var t = lo
            while (t <= hi) {
                if (smoothed(t) == bestCount) {
                    val start = t
                    while (t <= hi && smoothed(t) == bestCount) t++
                    if (t - start > bestLength) {
                        bestStart = start
                        bestLength = t - start
                    }
                } else {
                    t++
                }
            }
            return bestStart + (bestLength - 1) / 2.0
        }

        private fun mergeSmall(runs: List<Run>, typical: Int): ArrayList<Run> {
            val pending = runs.map { Run(it.top, it.bottom) }.toMutableList()
            val result = ArrayList<Run>()
            var i = 0
            while (i < pending.size) {
                val run = pending[i]
                if (run.height < typical * 0.5) {
                    val gapPrev = if (result.isEmpty()) Int.MAX_VALUE else run.top - result.last().bottom
                    val gapNext = if (i + 1 < pending.size) pending[i + 1].top - run.bottom else Int.MAX_VALUE
                    val limit = typical * 0.5
                    if (gapNext <= gapPrev && gapNext <= limit) {
                        pending[i + 1].top = run.top
                        i++
                        continue
                    }
                    if (gapPrev <= limit) {
                        result.last().bottom = run.bottom
                        i++
                        continue
                    }
                }
                result += run
                i++
            }
            return result
        }

        private fun splitTall(run: Run, rowInk: IntArray, lineHeight: Int, out: MutableList<Run>) {
            if (run.height <= lineHeight * 1.75) {
                out += run
                return
            }
            val margin = (lineHeight * 0.35).toInt()
            var peak = 0
            for (y in run.top until run.bottom) peak = max(peak, rowInk[y])
            var minY = -1
            var minValue = Int.MAX_VALUE
            for (y in run.top + margin until run.bottom - margin) {
                if (rowInk[y] < minValue) {
                    minValue = rowInk[y]
                    minY = y
                }
            }
            if (minY < 0 || minValue > max(2, (peak * 0.08).toInt())) {
                out += run
                return
            }
            splitTall(Run(run.top, minY), rowInk, lineHeight, out)
            splitTall(Run(minY + 1, run.bottom), rowInk, lineHeight, out)
        }

        /** Grundlinie, x-Höhe und Breite einer Zeile; sehr hohe oder dichte Streifen sind Bilder. */
        private fun measure(run: Run, rowInk: IntArray, x0: Int, x1: Int, lineHeight: Int): TextLine {
            var left = x1
            var right = x0
            var dark = 0L
            for (y in run.top until run.bottom) {
                val row = y * width
                for (x in x0 until x1) {
                    if (ink[row + x]) {
                        if (x < left) left = x
                        if (x >= right) right = x + 1
                        dark++
                    }
                }
            }
            val area = max(1L, run.height.toLong() * max(1, right - left))
            val density = dark.toDouble() / area
            val figure = run.height > lineHeight * 2.6 || (run.height > lineHeight * 1.5 && density > 0.42)
            if (figure) {
                return TextLine(run.top, run.bottom, run.bottom, 0, left, right, emptyList(), figure = true)
            }
            var peak = 0
            for (y in run.top until run.bottom) peak = max(peak, rowInk[y])
            val core = peak * 0.4
            var first = run.top
            while (first < run.bottom - 1 && rowInk[first] < core) first++
            var last = run.bottom - 1
            while (last > first && rowInk[last] < core) last--
            var baseline = last + 1
            var xHeight = baseline - first
            if (xHeight < run.height * 0.25) {
                xHeight = (run.height * 0.5).roundToInt()
                baseline = min(run.bottom, run.top + (run.height * 0.78).roundToInt())
            }
            return TextLine(run.top, run.bottom, baseline, xHeight, left, right, emptyList())
        }

        private fun withWords(line: TextLine, glyphs: IntArray, threshold: Double): TextLine {
            val count = glyphs.size / 2
            if (count == 0) return line.copy(words = emptyList())
            fun start(i: Int) = glyphs[2 * i]
            fun end(i: Int) = glyphs[2 * i + 1]
            val gap = max(2, threshold.roundToInt())
            val words = ArrayList<Word>()
            var wordFirst = 0
            for (g in 1..count) {
                if (g == count || start(g) - end(g - 1) >= gap) {
                    words += Word(start(wordFirst), end(g - 1))
                    wordFirst = g
                }
            }
            // Trennstrich am Zeilenende: kurzer, flacher Strich auf halber x-Höhe.
            val lastWord = words.last()
            val last = count - 1
            if (last > 0 && start(last) > lastWord.left && isHyphen(line, start(last), end(last))) {
                words[words.lastIndex] = lastWord.copy(stemRight = end(last - 1), hyphenLeft = start(last))
            }
            return line.copy(left = words.first().left, right = words.last().right, words = words)
        }

        private fun isHyphen(line: TextLine, x0: Int, x1: Int): Boolean {
            val xh = line.xHeight
            if (xh <= 0) return false
            val w = x1 - x0
            if (w < xh * 0.2 || w > xh * 0.8) return false
            var top = -1
            var bottom = -1
            for (y in line.top until line.bottom) {
                val row = y * width
                for (x in x0 until x1) {
                    if (ink[row + x]) {
                        if (top < 0) top = y
                        bottom = y
                        break
                    }
                }
            }
            if (top < 0) return false
            val h = bottom - top + 1
            if (h > max(2.0, xh * 0.3)) return false
            val center = (top + bottom) / 2.0
            val aboveBaseline = line.baseline - center
            return aboveBaseline >= xh * 0.2 && aboveBaseline <= xh * 0.8
        }
    }

    internal fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
