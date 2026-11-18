package com.example.easybooks.reflow

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Gespeicherte Analyse eines Buchs, damit "Groß" beim nächsten Öffnen sofort bereit ist.
 *
 * @param fileSize Größe der PDF-Datei; ändert sie sich, wird neu analysiert
 * @param scale Analyse-Pixel pro PDF-Punkt
 */
class ReflowAnalysis(
    val pageCount: Int,
    val fileSize: Long,
    val scale: Float,
    val style: BookStyle,
    val pages: Array<PageText?>,
)

object ReflowCache {
    private const val MAGIC = 0x45424652 // "EBFR" (alter App-Name; bleibt, damit gespeicherte Analysen gültig bleiben)
    /** 2: Wortgrenzen mit blassen Pixeln (Issue #34); ältere Analysen werden neu erstellt. */
    private const val VERSION = 2

    fun write(analysis: ReflowAnalysis, file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { encode(analysis, it) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Cannot write $file")
        }
    }

    /** Liest die Analyse oder `null`, wenn die Datei fehlt, kaputt ist oder nicht mehr passt. */
    fun read(file: File, pageCount: Int, fileSize: Long): ReflowAnalysis? {
        if (!file.isFile) return null
        return try {
            file.inputStream().use { decode(it) }
                ?.takeIf { it.pageCount == pageCount && it.fileSize == fileSize }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    fun encode(analysis: ReflowAnalysis, output: OutputStream) {
        DataOutputStream(GZIPOutputStream(output).buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(analysis.pageCount)
            out.writeLong(analysis.fileSize)
            out.writeFloat(analysis.scale)
            with(analysis.style) {
                for (v in floatArrayOf(xHeight, pitch, ascent, descent, textTop, textBottom, columnLeft, columnRight)) {
                    out.writeFloat(v)
                }
            }
            for (page in analysis.pages) {
                if (page == null) {
                    out.writeBoolean(false)
                    continue
                }
                out.writeBoolean(true)
                out.writeInt(page.width)
                out.writeInt(page.height)
                out.writeBoolean(page.image)
                out.writeInt(page.lines.size)
                for (l in page.lines) {
                    out.writeShort(l.top)
                    out.writeShort(l.bottom)
                    out.writeShort(l.baseline)
                    out.writeShort(l.xHeight)
                    out.writeShort(l.left)
                    out.writeShort(l.right)
                    out.writeBoolean(l.figure)
                    out.writeShort(l.words.size)
                    for (w in l.words) {
                        out.writeShort(w.left)
                        out.writeShort(w.right)
                        out.writeShort(w.stemRight)
                        out.writeShort(w.hyphenLeft)
                    }
                }
            }
        }
    }

    fun decode(input: InputStream): ReflowAnalysis? {
        DataInputStream(GZIPInputStream(input).buffered()).use { inp ->
            if (inp.readInt() != MAGIC || inp.readInt() != VERSION) return null
            val pageCount = inp.readInt()
            val fileSize = inp.readLong()
            val scale = inp.readFloat()
            val s = FloatArray(8) { inp.readFloat() }
            val style = BookStyle(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7])
            val pages = arrayOfNulls<PageText>(pageCount)
            for (p in 0 until pageCount) {
                if (!inp.readBoolean()) continue
                val width = inp.readInt()
                val height = inp.readInt()
                val image = inp.readBoolean()
                val lines = List(inp.readInt()) {
                    val top = inp.readShort().toInt()
                    val bottom = inp.readShort().toInt()
                    val baseline = inp.readShort().toInt()
                    val xHeight = inp.readShort().toInt()
                    val left = inp.readShort().toInt()
                    val right = inp.readShort().toInt()
                    val figure = inp.readBoolean()
                    val words = List(inp.readShort().toInt()) {
                        Word(
                            left = inp.readShort().toInt(),
                            right = inp.readShort().toInt(),
                            stemRight = inp.readShort().toInt(),
                            hyphenLeft = inp.readShort().toInt(),
                        )
                    }
                    TextLine(top, bottom, baseline, xHeight, left, right, words, figure)
                }
                pages[p] = PageText(width, height, lines, image)
            }
            return ReflowAnalysis(pageCount, fileSize, scale, style, pages)
        }
    }
}
