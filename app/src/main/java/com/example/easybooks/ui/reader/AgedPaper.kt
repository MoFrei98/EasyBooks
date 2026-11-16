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
import kotlin.math.hypot
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
 * Dieselbe Seite sieht immer gleich aus ([seed]), verschiedene Seiten etwas anders.
 */
internal object AgedPaper {

    fun print(page: ImageBitmap, seed: Int, dark: Boolean, picture: Boolean): ImageBitmap {
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
        return out.asImageBitmap()
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
