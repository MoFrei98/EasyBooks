import Foundation

/// Findet Zeilen, Wörter, Trennstriche und Bilder auf einer gerenderten Seite.
/// Arbeitet nur mit Pixeln (RGBA, zeilenweise ohne Lücken) und rechnet wie die Android-App.
public enum ReflowAnalyzer {
    /// Mittlere Helligkeit, unter der die ganze Seite als Bild gilt (wie im Dark Mode).
    private static let imageLuminance = 0.55
    /// Pixel dunkler als dieser Anteil der Papierhelligkeit gelten als Tinte.
    private static let inkFactor = 0.63
    /// Pixel heller als dieser Wert gehören bei Bildseiten zum Rand.
    private static let white = 232

    public static func analyze(rgba: [UInt8], width: Int, height: Int) -> PageText {
        let n = width * height
        if n == 0 { return PageText(width: width, height: height, lines: []) }
        var lum = [UInt8](repeating: 0, count: n)
        var histogram = [Int](repeating: 0, count: 256)
        var sum = 0
        rgba.withUnsafeBufferPointer { px in
            for i in 0..<n {
                let r = Int(px[i * 4]), g = Int(px[i * 4 + 1]), b = Int(px[i * 4 + 2])
                let l = (r * 54 + g * 183 + b * 19) >> 8
                lum[i] = UInt8(l)
                histogram[l] += 1
                sum += l
            }
        }
        if Double(sum) / (255.0 * Double(n)) < imageLuminance {
            return imagePage(lum, width, height)
        }
        let paper = percentile(histogram, n, 0.9)
        let threshold = Int((Double(paper) * inkFactor).rounded())
        let ink = lum.map { Int($0) < threshold }
        return Page(ink: ink, width: width, height: height).analyze()
    }

    /// Ganze Seite als ein Bild, begrenzt auf den nicht weißen Bereich.
    private static func imagePage(_ lum: [UInt8], _ width: Int, _ height: Int) -> PageText {
        var minX = width, maxX = -1, minY = height, maxY = -1
        for y in 0..<height {
            let row = y * width
            for x in 0..<width where Int(lum[row + x]) < white {
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                if y < minY { minY = y }
                if y > maxY { maxY = y }
            }
        }
        if maxX < 0 { return PageText(width: width, height: height, lines: []) }
        let figure = TextLine(top: minY, bottom: maxY + 1, baseline: maxY + 1, xHeight: 0,
                              left: minX, right: maxX + 1, words: [], figure: true)
        return PageText(width: width, height: height, lines: [figure], image: true)
    }

    private static func percentile(_ histogram: [Int], _ total: Int, _ p: Double) -> Int {
        let target = Int(Double(total) * p)
        var seen = 0
        for v in 0..<256 {
            seen += histogram[v]
            if seen > target { return v }
        }
        return 255
    }

    static func median(_ values: [Int]) -> Int? {
        if values.isEmpty { return nil }
        let sorted = values.sorted()
        return sorted[sorted.count / 2]
    }

    /// Ein waagerechter Streifen mit Tinte, Kandidat für eine Zeile.
    private struct Run {
        var top: Int
        var bottom: Int
        var height: Int { bottom - top }
    }

    private struct Page {
        let ink: [Bool]
        let width: Int
        let height: Int

        func analyze() -> PageText {
            var colInk = [Int](repeating: 0, count: width)
            var inkRows = 0
            for y in 0..<height {
                let row = y * width
                var any = false
                for x in 0..<width where ink[row + x] {
                    colInk[x] += 1
                    any = true
                }
                if any { inkRows += 1 }
            }
            if inkRows == 0 { return PageText(width: width, height: height, lines: []) }
            let left = colInk.firstIndex { $0 > 0 }!
            let right = colInk.lastIndex { $0 > 0 }! + 1
            var lines: [TextLine] = []
            for region in columns(colInk, left, right, inkRows) {
                lines += regionLines(region.lowerBound, region.upperBound)
            }
            return PageText(width: width, height: height, lines: lines)
        }

        /// Zweispaltiger Satz: leere senkrechte Gasse in der Mitte, beide Spalten über die meisten
        /// Zeilen mit Text. Die Spalten werden nacheinander gelesen: erst links, dann rechts.
        private func columns(_ colInk: [Int], _ left: Int, _ right: Int, _ inkRows: Int) -> [Range<Int>] {
            let whole = [left..<right]
            let w = right - left
            if w < 40 { return whole }
            let tolerance = max(1, Int(Double(inkRows) * 0.02))
            // Deutlich breiter als ein Wortabstand.
            let minGap = max(12, Int((Double(w) * 0.035).rounded()))
            let from = left + Int(Double(w) * 0.3)
            let to = left + Int(Double(w) * 0.7)
            var bestStart = -1, bestEnd = -1
            var x = from
            while x < to {
                if colInk[x] <= tolerance {
                    var s = x
                    while s > left && colInk[s - 1] <= tolerance { s -= 1 }
                    var e = x
                    while e < right && colInk[e] <= tolerance { e += 1 }
                    if e - s > bestEnd - bestStart {
                        bestStart = s
                        bestEnd = e
                    }
                    x = e
                } else {
                    x += 1
                }
            }
            if bestEnd - bestStart < minGap { return whole }
            var leftRows = 0, rightRows = 0
            for y in 0..<height {
                let row = y * width
                if (left..<bestStart).contains(where: { ink[row + $0] }) { leftRows += 1 }
                if (bestEnd..<right).contains(where: { ink[row + $0] }) { rightRows += 1 }
            }
            if Double(leftRows) < Double(inkRows) * 0.4 || Double(rightRows) < Double(inkRows) * 0.4 { return whole }
            return [left..<bestStart, bestEnd..<right]
        }

        private func regionLines(_ x0: Int, _ x1: Int) -> [TextLine] {
            var rowInk = [Int](repeating: 0, count: height)
            for y in 0..<height {
                let row = y * width
                var count = 0
                for x in x0..<x1 where ink[row + x] { count += 1 }
                rowInk[y] = count
            }
            var runs: [Run] = []
            var y = 0
            while y < height {
                if rowInk[y] > 0 {
                    let start = y
                    while y < height && rowInk[y] > 0 { y += 1 }
                    runs.append(Run(top: start, bottom: y))
                } else {
                    y += 1
                }
            }
            if runs.isEmpty { return [] }
            // Punkte und Akzente über Buchstaben bilden eigene flache Streifen: mit der Zeile verbinden.
            guard let typical = ReflowAnalyzer.median(runs.filter { $0.height >= 3 }.map(\.height)) else { return [] }
            runs = mergeSmall(runs, typical)
            guard let lineHeight = ReflowAnalyzer.median(runs.map(\.height)) else { return [] }

            // Zu hohe Streifen sind oft zwei sich berührende Zeilen.
            var split: [Run] = []
            for run in runs { splitTall(run, rowInk, lineHeight, &split) }

            let raw = split.map { measure($0, rowInk, x0, x1, lineHeight) }
            let pageXHeight = ReflowAnalyzer.median(raw.filter { !$0.figure }.map(\.xHeight)) ?? (lineHeight / 2)
            let glyphRuns = raw.map { $0.figure ? nil : glyphs(of: $0, x0, x1) }
            let wordGap = wordGapThreshold(raw, glyphRuns, pageXHeight)
            return raw.enumerated().map { i, line in
                guard let g = glyphRuns[i] else { return line }
                return withWords(line, g, wordGap * lineScale(line, pageXHeight))
            }
        }

        private func mergeSmall(_ runs: [Run], _ typical: Int) -> [Run] {
            var pending = runs
            var result: [Run] = []
            var i = 0
            let limit = Double(typical) * 0.5
            while i < pending.count {
                let run = pending[i]
                if Double(run.height) < Double(typical) * 0.5 {
                    let gapPrev = result.isEmpty ? Int.max : run.top - result[result.count - 1].bottom
                    let gapNext = i + 1 < pending.count ? pending[i + 1].top - run.bottom : Int.max
                    if gapNext <= gapPrev && Double(gapNext) <= limit {
                        pending[i + 1].top = run.top
                        i += 1
                        continue
                    }
                    if Double(gapPrev) <= limit {
                        result[result.count - 1].bottom = run.bottom
                        i += 1
                        continue
                    }
                }
                result.append(run)
                i += 1
            }
            return result
        }

        private func splitTall(_ run: Run, _ rowInk: [Int], _ lineHeight: Int, _ out: inout [Run]) {
            if Double(run.height) <= Double(lineHeight) * 1.75 {
                out.append(run)
                return
            }
            let margin = Int(Double(lineHeight) * 0.35)
            var peak = 0
            for y in run.top..<run.bottom { peak = max(peak, rowInk[y]) }
            var minY = -1
            var minValue = Int.max
            if run.top + margin < run.bottom - margin {
                for y in (run.top + margin)..<(run.bottom - margin) where rowInk[y] < minValue {
                    minValue = rowInk[y]
                    minY = y
                }
            }
            if minY < 0 || minValue > max(2, Int(Double(peak) * 0.08)) {
                out.append(run)
                return
            }
            splitTall(Run(top: run.top, bottom: minY), rowInk, lineHeight, &out)
            splitTall(Run(top: minY + 1, bottom: run.bottom), rowInk, lineHeight, &out)
        }

        /// Grundlinie, x-Höhe und Breite einer Zeile; sehr hohe oder dichte Streifen sind Bilder.
        private func measure(_ run: Run, _ rowInk: [Int], _ x0: Int, _ x1: Int, _ lineHeight: Int) -> TextLine {
            var left = x1, right = x0
            var dark = 0
            for y in run.top..<run.bottom {
                let row = y * width
                for x in x0..<x1 where ink[row + x] {
                    if x < left { left = x }
                    if x >= right { right = x + 1 }
                    dark += 1
                }
            }
            let area = max(1, run.height * max(1, right - left))
            let density = Double(dark) / Double(area)
            let figure = Double(run.height) > Double(lineHeight) * 2.6 ||
                (Double(run.height) > Double(lineHeight) * 1.5 && density > 0.42)
            if figure {
                return TextLine(top: run.top, bottom: run.bottom, baseline: run.bottom, xHeight: 0,
                                left: left, right: right, words: [], figure: true)
            }
            var peak = 0
            for y in run.top..<run.bottom { peak = max(peak, rowInk[y]) }
            let core = Double(peak) * 0.4
            var first = run.top
            while first < run.bottom - 1 && Double(rowInk[first]) < core { first += 1 }
            var last = run.bottom - 1
            while last > first && Double(rowInk[last]) < core { last -= 1 }
            var baseline = last + 1
            var xHeight = baseline - first
            if Double(xHeight) < Double(run.height) * 0.25 {
                xHeight = Int((Double(run.height) * 0.5).rounded())
                baseline = min(run.bottom, run.top + Int((Double(run.height) * 0.78).rounded()))
            }
            return TextLine(top: run.top, bottom: run.bottom, baseline: baseline, xHeight: xHeight,
                            left: left, right: right, words: [])
        }

        /// Senkrechte Streifen mit Tinte innerhalb einer Zeile: [Anfang, Ende) je Buchstabe.
        private func glyphs(of line: TextLine, _ x0: Int, _ x1: Int) -> [Int] {
            var cols = [Int](repeating: 0, count: x1 - x0)
            for y in line.top..<line.bottom {
                let row = y * width
                for x in x0..<x1 where ink[row + x] { cols[x - x0] += 1 }
            }
            var bounds: [Int] = []
            var x = 0
            while x < cols.count {
                if cols[x] > 0 {
                    let s = x
                    while x < cols.count && cols[x] > 0 { x += 1 }
                    bounds.append(x0 + s)
                    bounds.append(x0 + x)
                } else {
                    x += 1
                }
            }
            return bounds
        }

        /// Größere Überschriften haben größere Abstände: Schwelle mit der x-Höhe der Zeile skalieren.
        private func lineScale(_ line: TextLine, _ pageXHeight: Int) -> Double {
            if pageXHeight <= 0 { return 1 }
            return min(2.5, max(0.6, Double(line.xHeight) / Double(pageXHeight)))
        }

        /// Abstand, ab dem eine Lücke zwischen zwei Wörtern liegt: die dünnste Stelle zwischen den
        /// gehäuften Buchstabenabständen (klein) und Wortabständen (groß) der Seite.
        private func wordGapThreshold(_ lines: [TextLine], _ glyphs: [[Int]?], _ pageXHeight: Int) -> Double {
            let xh = max(4, pageXHeight)
            var counts = [Int](repeating: 0, count: xh * 2)
            for (i, g) in glyphs.enumerated() {
                guard let g else { continue }
                let s = lineScale(lines[i], pageXHeight)
                var j = 2
                while j + 1 < g.count {
                    let gap = Int((Double(g[j] - g[j - 1]) / s).rounded())
                    if gap >= 0 && gap < counts.count { counts[gap] += 1 }
                    j += 2
                }
            }
            let lo = max(2, Int((Double(xh) * 0.35).rounded()))
            let hi = max(lo, Int((Double(xh) * 0.75).rounded()))
            var best = Int((Double(xh) * 0.55).rounded())
            var bestCount = Int.max
            for t in lo...hi {
                let next = t + 1 < counts.count ? counts[t + 1] : 0
                let c = counts[t - 1] + 2 * counts[t] + next
                if c < bestCount {
                    bestCount = c
                    best = t
                }
            }
            return Double(best)
        }

        private func withWords(_ line: TextLine, _ glyphs: [Int], _ threshold: Double) -> TextLine {
            let count = glyphs.count / 2
            var line = line
            if count == 0 {
                line.words = []
                return line
            }
            func start(_ i: Int) -> Int { glyphs[2 * i] }
            func end(_ i: Int) -> Int { glyphs[2 * i + 1] }
            let gap = max(2, Int(threshold.rounded()))
            var words: [Word] = []
            var wordFirst = 0
            for g in 1...count where g == count || start(g) - end(g - 1) >= gap {
                words.append(Word(left: start(wordFirst), right: end(g - 1)))
                wordFirst = g
            }
            // Trennstrich am Zeilenende: kurzer, flacher Strich auf halber x-Höhe.
            let lastWord = words[words.count - 1]
            let last = count - 1
            if last > 0 && start(last) > lastWord.left && isHyphen(line, start(last), end(last)) {
                words[words.count - 1] = Word(left: lastWord.left, right: lastWord.right,
                                              stemRight: end(last - 1), hyphenLeft: start(last))
            }
            line.left = words[0].left
            line.right = words[words.count - 1].right
            line.words = words
            return line
        }

        private func isHyphen(_ line: TextLine, _ x0: Int, _ x1: Int) -> Bool {
            let xh = Double(line.xHeight)
            if xh <= 0 { return false }
            let w = Double(x1 - x0)
            if w < xh * 0.2 || w > xh * 0.8 { return false }
            var top = -1, bottom = -1
            for y in line.top..<line.bottom {
                let row = y * width
                if (x0..<x1).contains(where: { ink[row + $0] }) {
                    if top < 0 { top = y }
                    bottom = y
                }
            }
            if top < 0 { return false }
            let h = Double(bottom - top + 1)
            if h > max(2.0, xh * 0.3) { return false }
            let center = Double(top + bottom) / 2
            let aboveBaseline = Double(line.baseline) - center
            return aboveBaseline >= xh * 0.2 && aboveBaseline <= xh * 0.8
        }
    }
}
