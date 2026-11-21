package com.example.easybooks.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.example.easybooks.ui.theme.AgedDarkReaderColors
import com.example.easybooks.ui.theme.AgedLightReaderColors
import com.example.easybooks.ui.theme.Textures
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.max
import kotlin.random.Random

/**
 * Druckt eine gerenderte Seite auf altes Papier.
 *
 * Das Papier entsteht im Code: Grundton, weiche Flecken, feine Fasern, hellere Mitte,
 * dunklere Ränder und ein paar Stockflecken. Die Seite wird darauf "gedruckt": Weiß wird
 * zu Papier, Schwarz zu brauner Tinte. Bildseiten (z. B. Cover) werden nur leicht getönt,
 * damit ihre Farben stimmen.
 *
 * Die Kanten sind leicht ausgefranst wie bei altem, gerissenem Papier: An den Rändern wird
 * das Blatt durchsichtig, entlang einer unregelmäßigen Linie, und der Rand ist etwas
 * stärker vergilbt.
 *
 * Dieselbe Seite sieht immer gleich aus ([seed]), verschiedene Seiten etwas anders.
 */
internal object AgedPaper {

    /**
     * @param fray größte Tiefe der Ausfransung in Pixeln ([FrayDepth])
     * @param unit Pixel pro dp, damit die Fasern auf jedem Bildschirm gleich groß wirken
     * @param bound Kante am Bundsteg, die gebunden und daher nicht ausgefranst ist
     *   (0 oben, 1 rechts, 2 unten, 3 links, -1 keine)
     */
    fun print(
        page: ImageBitmap,
        seed: Int,
        dark: Boolean,
        picture: Boolean,
        fray: Float,
        unit: Float,
        bound: Int = -1,
    ): ImageBitmap {
        val src = page.asAndroidBitmap()
        val width = src.width
        val height = src.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawPaper(canvas, width, height, seed, dark)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        when {
            picture && dark -> {
                paint.colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(0.8f, 0.8f, 0.8f, 1f) })
                canvas.drawBitmap(src, 0f, 0f, paint)
            }
            picture -> {
                canvas.drawBitmap(src, 0f, 0f, paint)
                // Helle Stellen leicht zum Papierton ziehen (Abdunkeln mit wenig Deckkraft).
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
                    color = AgedLightReaderColors.paper.toArgb()
                    alpha = 90
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                })
            }
            dark -> {
                // Weiß wird schwarz, Schwarz wird helle Tinte; das Hellere gewinnt.
                paint.colorFilter = PageColors.night(Color.Black, AgedDarkReaderColors.ink).asAndroidColorFilter()
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                canvas.drawBitmap(src, 0f, 0f, paint)
            }
            else -> {
                // Schwarz wird braune Tinte, Weiß bleibt; das Dunklere gewinnt.
                paint.colorFilter = inkFilter(AgedLightReaderColors.ink)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                canvas.drawBitmap(src, 0f, 0f, paint)
            }
        }
        drawEdges(canvas, width, height, dark)
        if (fray > 0f) frayEdges(out, seed, fray, unit, bound)
        return out.asImageBitmap()
    }

    /**
     * Rückseite eines Blatts für das Umblättern: dasselbe Papier mit denselben ausgefransten
     * Kanten, die Schrift der Vorderseite scheint nur schwach durch.
     */
    fun back(front: ImageBitmap, dark: Boolean): ImageBitmap {
        val src = front.asAndroidBitmap()
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val paper = if (dark) AgedDarkReaderColors.paper else AgedLightReaderColors.paper
        // "Oben drauf" behält die Deckkraft (und damit die Fransen) der Vorderseite.
        Canvas(out).drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Paint().apply {
            color = paper.toArgb()
            alpha = 240
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        })
        return out.asImageBitmap()
    }

    /** Macht die Kanten unregelmäßig durchsichtig und färbt den Riss etwas dunkler. */
    private fun frayEdges(bitmap: Bitmap, seed: Int, fray: Float, unit: Float, bound: Int) {
        val w = bitmap.width
        val h = bitmap.height
        // Streifen, in dem gerechnet wird: Ausfransung plus vergilbter Saum dahinter.
        val band = ceil(fray + 3f * unit).toInt().coerceAtMost(minOf(w, h) / 2)
        if (band <= 0) return
        val top = FloatArray(w) { Deckle.depth(seed, 0, it.toFloat(), unit, fray) }
        val bottom = FloatArray(w) { Deckle.depth(seed, 2, it.toFloat(), unit, fray) }
        val left = FloatArray(h) { Deckle.depth(seed, 3, it.toFloat(), unit, fray) }
        val right = FloatArray(h) { Deckle.depth(seed, 1, it.toFloat(), unit, fray) }
        val seam = 3f * unit

        fun region(x0: Int, y0: Int, rw: Int, rh: Int) {
            if (rw <= 0 || rh <= 0) return
            val px = IntArray(rw * rh)
            bitmap.getPixels(px, 0, rw, x0, y0, rw, rh)
            for (yy in 0 until rh) for (xx in 0 until rw) {
                val x = x0 + xx
                val y = y0 + yy
                var alpha = 1f
                var tint = 1f
                fun edge(distance: Float, depth: Float) {
                    if (distance >= band) return
                    alpha *= smoothstep(depth - 0.6f, depth + 0.6f, distance)
                    tint = minOf(tint, 1f - 0.22f * (1f - smoothstep(depth, depth + seam, distance)))
                }
                if (bound != 0) edge(y + 0.5f, top[x])
                if (bound != 2) edge(h - y - 0.5f, bottom[x])
                if (bound != 3) edge(x + 0.5f, left[y])
                if (bound != 1) edge(w - x - 0.5f, right[y])
                if (alpha >= 1f && tint >= 1f) continue
                val c = px[yy * rw + xx]
                val a = (alpha * 255f).toInt().coerceIn(0, 255)
                val r = (((c shr 16) and 0xFF) * tint).toInt()
                val g = (((c shr 8) and 0xFF) * tint).toInt()
                val b = ((c and 0xFF) * tint).toInt()
                px[yy * rw + xx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(px, 0, rw, x0, y0, rw, rh)
        }
        region(0, 0, w, band)
        region(0, h - band, w, band)
        region(0, band, band, h - 2 * band)
        region(w - band, band, band, h - 2 * band)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Grundton, Flecken, Fasern und Stockflecken. */
    private fun drawPaper(canvas: Canvas, width: Int, height: Int, seed: Int, dark: Boolean) {
        val random = Random(seed * 7919 + 17)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(if (dark) AgedDarkReaderColors.paper.toArgb() else BASE_LIGHT)

        // Weiche Flecken, je Seite verschoben.
        val scale = max(w, h) / Textures.blotches.width * 0.9f
        canvas.drawRect(0f, 0f, w, h, Paint().apply {
            shader = BitmapShader(Textures.blotches, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(random.nextFloat() * w, random.nextFloat() * h)
                })
            }
            alpha = if (dark) 40 else 80
        })

        if (!dark) {
            // Die Mitte ist heller, wie bei Papier, das lange im Buch lag.
            canvas.drawRect(0f, 0f, w, h, Paint().apply {
                shader = RadialGradient(
                    w / 2f, h * 0.46f, max(w, h) * 0.62f,
                    intArrayOf(0x70FFF8E2, 0x00FFF8E2), null, Shader.TileMode.CLAMP,
                )
            })
            // Feine Fasern.
            canvas.drawRect(0f, 0f, w, h, Paint().apply {
                shader = BitmapShader(Textures.grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                    val s = max(1f, w / 900f)
                    setLocalMatrix(Matrix().apply { setScale(s, s) })
                }
            })
            // Stockflecken.
            val unit = w / 1000f
            repeat(3 + random.nextInt(4)) {
                val cx = random.nextFloat() * w
                val cy = random.nextFloat() * h
                val r = (4f + random.nextFloat() * 16f) * unit * 1.6f
                canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(cx, cy, r, intArrayOf(0x4D875523, 0x00875523), null, Shader.TileMode.CLAMP)
                })
            }
        }
    }

    /** Dunklere Ränder (vergilbt von außen nach innen). */
    private fun drawEdges(canvas: Canvas, width: Int, height: Int, dark: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = hypot(w, h) / 2f
        val edge = if (dark) 0x73000000 else 0x5C784819
        canvas.drawRect(0f, 0f, w, h, Paint().apply {
            shader = RadialGradient(
                w / 2f, h / 2f, radius,
                intArrayOf(edge and 0x00FFFFFF, edge and 0x00FFFFFF, edge),
                floatArrayOf(0f, 0.66f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
    }

    /** Schwarz → [ink], Weiß bleibt Weiß, Zwischentöne dazwischen. */
    private fun inkFilter(ink: Color): ColorMatrixColorFilter {
        val r = ink.red
        val g = ink.green
        val b = ink.blue
        return ColorMatrixColorFilter(
            floatArrayOf(
                1 - r, 0f, 0f, 0f, r * 255f,
                0f, 1 - g, 0f, 0f, g * 255f,
                0f, 0f, 1 - b, 0f, b * 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }

    /** Grundton des hellen alten Papiers (etwas heller als die Ränder). */
    private const val BASE_LIGHT = 0xFFEBDAB2.toInt()
}

/** Größte Tiefe der ausgefransten Kanten. */
internal val FrayDepth = 3.dp

/** Verlauf einer ausgefransten Papierkante: grobe Wellen, kleinere Zacken und feine Fasern. */
internal object Deckle {
    /**
     * Wie tief die Kante [edge] (0 oben, 1 rechts, 2 unten, 3 links) an Stelle [pos] (Pixel
     * entlang der Kante) ausgefranst ist: 0 bis [max]. Meist nur wenig, stellenweise tiefer.
     */
    fun depth(seed: Int, edge: Int, pos: Float, unit: Float, max: Float): Float {
        val s = seed * 31 + edge * 7919
        val coarse = noise(s, pos / (18f * unit))
        val mid = noise(s + 101, pos / (4.5f * unit))
        val fine = noise(s + 211, pos / (1.4f * unit))
        val v = 0.5f * coarse + 0.32f * mid + 0.18f * fine
        return max * v.pow(1.5f)
    }

    private fun noise(seed: Int, x: Float): Float {
        val i = floor(x).toInt()
        val f = x - i
        val t = f * f * (3f - 2f * f)
        val a = hash(seed, i)
        return a + (hash(seed, i + 1) - a) * t
    }

    private fun hash(seed: Int, i: Int): Float {
        var h = seed * 374761393 + i * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }
}
