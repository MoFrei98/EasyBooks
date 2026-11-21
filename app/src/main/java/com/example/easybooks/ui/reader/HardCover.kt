package com.example.easybooks.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.example.easybooks.ui.theme.AgedDarkReaderColors
import com.example.easybooks.ui.theme.AgedLightReaderColors
import com.example.easybooks.ui.theme.Textures
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Ansicht "Altes Buch": Ist die erste Seite ein Bild (das Cover), wird sie zum festen
 * Buchdeckel. Er bekommt eine Lederstruktur, abgegriffene Kanten und die Rille am Falz,
 * und beim Umblättern klappt er starr um den Buchrücken auf, statt sich wie Papier zu biegen.
 */
internal object HardCover {

    /** Vorderseite des Deckels: das Cover auf einem festen, eingebundenen Deckel. */
    fun front(page: ImageBitmap, dark: Boolean, unit: Float): ImageBitmap {
        val src = page.asAndroidBitmap()
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (dark) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.8f, 0.8f, 0.8f, 1f) })
        })
        // Lederstruktur: feine Poren und ein paar abgegriffene Stellen.
        canvas.drawRect(0f, 0f, w, h, Paint().apply {
            shader = BitmapShader(Textures.grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        })
        canvas.drawRect(0f, 0f, w, h, Paint().apply {
            shader = BitmapShader(Textures.blotches, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                val s = max(w, h) / Textures.blotches.width
                setLocalMatrix(Matrix().apply { setScale(s, s) })
            }
            alpha = 45
        })
        // Abgegriffene, dunklere Kanten.
        canvas.drawRect(0f, 0f, w, h, Paint().apply {
            shader = RadialGradient(
                w / 2f, h / 2f, hypot(w, h) / 2f,
                intArrayOf(0x00281808, 0x00281808, 0x70281808), floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
        // Rundung zum Buchrücken und die Rille am Falz.
        val hinge = w * 0.05f
        canvas.drawRect(0f, 0f, hinge * 0.6f, h, Paint().apply {
            shader = LinearGradient(0f, 0f, hinge * 0.6f, 0f, 0x70000000, 0x00000000, Shader.TileMode.CLAMP)
        })
        canvas.drawRect(hinge - 1.4f * unit, 0f, hinge, h, Paint().apply { color = 0x5A000000 })
        canvas.drawRect(hinge, 0f, hinge + 1.2f * unit, h, Paint().apply { color = 0x30FFFFFF })
        // Kante des Deckels.
        canvas.drawRect(
            0.75f * unit, 0.75f * unit, w - 0.75f * unit, h - 0.75f * unit,
            Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * unit
                color = 0x50000000
            },
        )
        return out.asImageBitmap()
    }

    /**
     * Innenseite des Deckels: am Rand umgeschlagenes Leder, in der Mitte Vorsatzpapier.
     * Der Rücken liegt rechts (so liegt der Deckel aufgeklappt links neben dem Buch).
     */
    fun inside(width: Int, height: Int, dark: Boolean, unit: Float): ImageBitmap {
        val w = width.toFloat()
        val h = height.toFloat()
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (dark) 0xFF122216.toInt() else 0xFF2A4A30.toInt())
        canvas.drawRect(0f, 0f, w, h, Paint().apply {
            shader = BitmapShader(Textures.grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        })
        // Vorsatzpapier, links, oben und unten vom Leder eingefasst.
        val turnIn = w * 0.06f
        val paper = if (dark) AgedDarkReaderColors.paper else AgedLightReaderColors.paper
        canvas.drawRect(turnIn, turnIn, w, h - turnIn, Paint().apply { color = paper.toArgb() })
        canvas.drawRect(turnIn, turnIn, w, h - turnIn, Paint().apply {
            shader = BitmapShader(Textures.blotches, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                val s = max(w, h) / Textures.blotches.width * 0.7f
                setLocalMatrix(Matrix().apply { setScale(s, s) })
            }
            alpha = 70
        })
        canvas.drawRect(turnIn, turnIn, w, h - turnIn, Paint().apply {
            shader = BitmapShader(Textures.grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        })
        // Schatten am Falz (rechts).
        canvas.drawRect(w - w * 0.08f, 0f, w, h, Paint().apply {
            shader = LinearGradient(w, 0f, w - w * 0.08f, 0f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        })
        return out.asImageBitmap()
    }
}

private val coverMeshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

/**
 * Zeichnet den Buchdeckel beim Aufklappen: Er dreht sich starr um den Buchrücken (linker
 * Rand) und wird dabei perspektivisch kleiner. Ab der Hälfte zeigt er seine Innenseite.
 * Seine Dicke ist an den Kanten zu sehen, und er wirft einen Schatten auf die Seite darunter.
 *
 * @param progress 0 = Deckel liegt, 1 = ganz aufgeklappt
 */
internal fun DrawScope.drawHardCover(
    progress: Float,
    front: ImageBitmap?,
    inside: ImageBitmap?,
    shade: Color,
    dark: Boolean,
) {
    val p = progress.coerceIn(0f, 1f)
    val theta = PI * p
    val cosT = cos(theta).toFloat()
    val sinT = sin(theta).toFloat()
    val w = size.width
    val h = size.height
    val thick = 3.dp.toPx()
    val distance = 2.4f * max(w, h)
    val cx = w / 2f
    val cy = h / 2f

    /** Punkt (u, v) auf dem Deckel, [off] Richtung Unterseite, perspektivisch auf den Bildschirm. */
    fun point(u: Float, v: Float, off: Float = 0f): Offset {
        val x = u * cosT + off * sinT
        val z = u * sinT - off * cosT
        val s = distance / (distance - z)
        return Offset(cx + (x - cx) * s, cy + (v - cy) * s)
    }

    // Schatten des angehobenen Deckels auf der Seite darunter.
    if (sinT > 0.01f) {
        val reach = (w * max(cosT, 0f) + 40.dp.toPx() * sinT).coerceAtMost(w)
        drawRect(
            Brush.horizontalGradient(
                0f to shade.copy(alpha = 0.4f * sinT),
                1f to Color.Transparent,
                startX = 0f,
                endX = reach,
            ),
            size = androidx.compose.ui.geometry.Size(reach, h),
        )
    }

    // Kanten des Deckels (seine Dicke): außen, oben und unten.
    val edge = if (dark) Color(0xFF0E1A10) else Color(0xFF1B3020)
    fun quad(a: Offset, b: Offset, c: Offset, d: Offset, color: Color) {
        val path = Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
            lineTo(d.x, d.y)
            close()
        }
        drawPath(path, color)
    }
    quad(point(w, 0f), point(w, h), point(w, h, thick), point(w, 0f, thick), edge)
    quad(point(0f, 0f), point(w, 0f), point(w, 0f, thick), point(0f, 0f, thick), edge.copy(alpha = 0.9f))
    quad(point(0f, h), point(w, h), point(w, h, thick), point(0f, h, thick), edge.copy(alpha = 0.9f))

    // Fläche: vorne das Cover, nach der Hälfte die Innenseite (an der Unterseite des Deckels).
    val showFront = cosT >= 0f
    val image = (if (showFront) front else inside) ?: return
    val bitmap = image.asAndroidBitmap()
    val cols = 12
    val rows = 12
    val verts = FloatArray((cols + 1) * (rows + 1) * 2)
    val colors = IntArray((cols + 1) * (rows + 1))
    val light = if (showFront) 0.55f + 0.45f * cosT else 0.6f - 0.4f * cosT
    val lit = Color(light, light, light).toArgb()
    var k = 0
    for (row in 0..rows) {
        for (col in 0..cols) {
            // Die Innenseite hat ihren Rücken rechts: von hinten gesehen ist sie gespiegelt.
            val u = if (showFront) col / cols.toFloat() * w else (1f - col / cols.toFloat()) * w
            val v = row / rows.toFloat() * h
            val pt = point(u, v, if (showFront) 0f else thick)
            verts[k * 2] = pt.x
            verts[k * 2 + 1] = pt.y
            colors[k] = lit
            k++
        }
    }
    drawIntoCanvas { it.nativeCanvas.drawBitmapMesh(bitmap, cols, rows, verts, 0, colors, 0, coverMeshPaint) }
}
