package com.example.easybooks.ui.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 3D-Umblättern: Die Seite biegt sich wie ein Blatt Papier um den Buchrücken (linker Rand).
 *
 * Die Seite wird als Gitter aus Spalten und Zeilen berechnet. Entlang jeder Zeile dreht
 * sich das Papier zunehmend, der freie Rand eilt voraus – so wölbt sich die Seite.
 * Zeilen weit weg von der Stelle, an der der Finger greift, hinken hinterher. Dadurch
 * hebt sich zuerst die gegriffene Ecke und die Faltkante läuft schräg. Gegen Ende des
 * Umblätterns richtet sich die Seite wieder gerade aus.
 *
 * Das Gitter wird perspektivisch projiziert, mit [android.graphics.Canvas.drawBitmapMesh]
 * gezeichnet und je nach Winkel schattiert. Die Rückseite erscheint als Papier.
 */
internal object PageCurl {
    const val COLS = 32
    const val ROWS = 16

    /** Wie weit der freie Rand dem Buchrücken vorauseilt (0 = starre Drehung). */
    private const val CURL_SPREAD = 0.7f

    /** Wie stark Zeilen fern der gegriffenen Ecke zurückbleiben (0 = gerade Faltkante). */
    private const val TILT = 1.1f

    /** Abstand der gedachten Kamera in Seitenbreiten (kleiner = stärkere Perspektive). */
    private const val CAMERA_DISTANCE = 3.2f

    /** Projizierte Gitterpunkte, zeilenweise, plus Drehwinkel des Papiers an jedem Punkt. */
    class Mesh(val cols: Int, val rows: Int) {
        val x = FloatArray((cols + 1) * (rows + 1))
        val y = FloatArray((cols + 1) * (rows + 1))
        val angle = FloatArray((cols + 1) * (rows + 1))
        fun index(col: Int, row: Int) = row * (cols + 1) + col
    }

    /**
     * Berechnet die gebogene Seite.
     *
     * @param progress 0 = Seite liegt flach, 1 = Seite ist ganz nach links umgeblättert
     * @param grab Höhe, an der der sichtbare Teil gegriffen wird (0 = oben, 1 = unten)
     * @param visibleTop Oberkante des sichtbaren Teils auf der Seite (in Pixeln der Seite)
     * @param visibleBottom Unterkante des sichtbaren Teils auf der Seite
     */
    fun project(
        width: Float,
        height: Float,
        progress: Float,
        grab: Float,
        visibleTop: Float = 0f,
        visibleBottom: Float = height,
        cols: Int = COLS,
        rows: Int = ROWS,
    ): Mesh {
        val mesh = Mesh(cols, rows)
        val step = width / cols
        val camera = width * CAMERA_DISTANCE
        val cx = width / 2f
        // Perspektive auf die Mitte dessen, was man sieht – sonst wölbt sich eine hohe
        // Seite immer von unten, weil ihre Mitte unter dem Bildschirm liegt.
        val cy = (visibleTop + visibleBottom) / 2f
        val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(1f)

        // Gegriffene Ecke: oben (0) oder unten (1). Nahe der Mitte hebt sich der Rand gerade.
        val g = grab.coerceIn(0f, 1f)
        val corner = if (g < 0.5f) 0f else 1f
        val cornerWeight = smoothstep(0.08f, 0.38f, abs(g - 0.5f))
        // Zum Ende des Umblätterns richtet sich die Seite gerade aus.
        val tilt = TILT * cornerWeight * (1f - progress).let { it * it * (3f - 2f * it) }

        val thetas = FloatArray(cols)
        for (row in 0..rows) {
            val v = row.toFloat() / rows
            // Abstand zur gegriffenen Ecke, gemessen am sichtbaren Teil.
            val yOnPage = v * height
            val distance = (abs((yOnPage - visibleTop) / visibleHeight - corner)).coerceIn(0f, 1.5f)
            val lag = tilt * distance
            for (col in 0 until cols) {
                // Position entlang der Seite: 0 = Buchrücken, 1 = freier Rand.
                val s = (col + 0.5f) / cols - lag
                val t = (progress * (1f + CURL_SPREAD) - CURL_SPREAD * (1f - s)).coerceIn(0f, 1f)
                thetas[col] = (PI * (t * t * (3f - 2f * t))).toFloat()
            }
            var x = 0f
            var z = 0f
            val y = v * height
            for (col in 0..cols) {
                if (col > 0) {
                    x += step * cos(thetas[col - 1])
                    z += step * sin(thetas[col - 1])
                }
                // Perspektive: Was näher an der Kamera ist (größeres z), wird größer.
                val f = camera / (camera - z)
                val i = mesh.index(col, row)
                mesh.x[i] = cx + (x - cx) * f
                mesh.y[i] = cy + (y - cy) * f
                mesh.angle[i] = thetas[(col - 1).coerceIn(0, cols - 1)]
            }
        }
        return mesh
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Helligkeit der Vorderseite: frontal = hell, schräg = dunkler. */
    fun frontLight(angle: Float): Float = 0.5f + 0.5f * max(0f, cos(angle))

    /** Helligkeit der Rückseite. */
    fun backLight(angle: Float): Float = 0.74f + 0.26f * abs(cos(angle))
}

private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
private val plainPaint = Paint(Paint.ANTI_ALIAS_FLAG)

/**
 * Zeichnet die umblätternde Seite in die Fläche dieser DrawScope (Größe = Seite).
 * Die Seite darf beim Umblättern über die Fläche hinausragen.
 *
 * @param grab Griffhöhe im sichtbaren Teil der Seite (0 = oben, 1 = unten)
 * @param visibleTop Oberkante des sichtbaren Teils auf der Seite
 * @param visibleBottom Unterkante des sichtbaren Teils auf der Seite
 */
internal fun DrawScope.drawCurledPage(
    progress: Float,
    grab: Float,
    visibleTop: Float,
    visibleBottom: Float,
    bitmap: ImageBitmap?,
    colorFilter: ColorFilter?,
    paperColor: Color,
    shadowColor: Color,
    backBitmap: ImageBitmap? = null,
) {
    val p = progress.coerceIn(0f, 1f)
    val mesh = PageCurl.project(size.width, size.height, p, grab, visibleTop, visibleBottom)
    val cols = mesh.cols
    val rows = mesh.rows
    val lift = sin(PI * p).toFloat().coerceAtLeast(0f)

    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas

        // 1. Weicher Schatten auf der Seite darunter, rechts neben der angehobenen Seite.
        if (lift > 0.01f) {
            val shadowWidth = 56.dp.toPx() * lift
            val triangles = Triangles()
            val inner = shadowColor.copy(alpha = 0.38f * lift).toArgb()
            val outer = shadowColor.copy(alpha = 0f).toArgb()
            for (row in 0 until rows) {
                val x0 = rightmost(mesh, row)
                val x1 = rightmost(mesh, row + 1)
                // Schatten fällt nur auf die Seite darunter, nicht auf den Hintergrund.
                val y0 = mesh.y[mesh.index(cols, row)].coerceIn(0f, size.height)
                val y1 = mesh.y[mesh.index(cols, row + 1)].coerceIn(0f, size.height)
                if (x0 >= size.width && x1 >= size.width) continue
                triangles.quad(
                    x0, y0, inner, x0 + shadowWidth, y0, outer,
                    x1 + shadowWidth, y1, outer, x1, y1, inner,
                )
            }
            triangles.draw(canvas)
        }

        // 2. Vorderseite: Bild auf das gebogene Gitter legen, je Punkt schattiert.
        if (bitmap != null) {
            val verts = FloatArray(mesh.x.size * 2)
            val colors = IntArray(mesh.x.size)
            for (i in mesh.x.indices) {
                verts[i * 2] = mesh.x[i]
                verts[i * 2 + 1] = mesh.y[i]
                val light = PageCurl.frontLight(mesh.angle[i])
                colors[i] = Color(light, light, light).toArgb()
            }
            meshPaint.colorFilter = colorFilter?.asAndroidColorFilter()
            canvas.drawBitmapMesh(bitmap.asAndroidBitmap(), cols, rows, verts, 0, colors, 0, meshPaint)
        }

        // 3. Rückseite (und Vorderseite, solange noch kein Bild da ist) als Papier. Bei altem
        //    Papier liegt eine eigene Rückseite als Textur darauf, mit denselben Fransen.
        val textured = backBitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val back = if (textured) TexturedTriangles(backBitmap!!.asAndroidBitmap()) else null
        val paper = Triangles()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val a = mesh.index(col, row)
                val b = mesh.index(col + 1, row)
                val c = mesh.index(col + 1, row + 1)
                val d = mesh.index(col, row + 1)
                val backSide = signedArea(mesh, a, b, c, d) < 0f
                if (!backSide && bitmap != null) continue
                fun shade(i: Int): Int {
                    val light = if (backSide) PageCurl.backLight(mesh.angle[i]) else PageCurl.frontLight(mesh.angle[i])
                    return paperColor.shaded(light).toArgb()
                }
                if (backSide && back != null) {
                    fun light(i: Int): Int = Color.White.shaded(PageCurl.backLight(mesh.angle[i])).toArgb()
                    back.quad(
                        mesh, cols, rows,
                        intArrayOf(col, col + 1, col + 1, col), intArrayOf(row, row, row + 1, row + 1),
                        intArrayOf(a, b, c, d).map { light(it) }.toIntArray(),
                    )
                    continue
                }
                paper.quad(
                    mesh.x[a], mesh.y[a], shade(a), mesh.x[b], mesh.y[b], shade(b),
                    mesh.x[c], mesh.y[c], shade(c), mesh.x[d], mesh.y[d], shade(d),
                )
            }
        }
        paper.draw(canvas)
        back?.draw(canvas)
    }

    // 4. Feine Kante am freien Rand, damit sich die Seite vom Hintergrund abhebt
    //    (bei ausgefranstem Papier nicht: Die Fransen sind die Kante).
    if (backBitmap != null) return
    val edge = Path()
    for (row in 0..rows) {
        val i = mesh.index(cols, row)
        if (row == 0) edge.moveTo(mesh.x[i], mesh.y[i]) else edge.lineTo(mesh.x[i], mesh.y[i])
    }
    drawPath(edge, shadowColor.copy(alpha = 0.18f * lift), style = Stroke(width = 1.dp.toPx()))
}

/** Am weitesten rechts liegender Punkt einer Gitterzeile. */
private fun rightmost(mesh: PageCurl.Mesh, row: Int): Float {
    var result = Float.NEGATIVE_INFINITY
    for (col in 0..mesh.cols) result = max(result, mesh.x[mesh.index(col, row)])
    return result
}

/** Vorzeichen > 0: Viereck zeigt die Vorderseite, < 0: es ist gespiegelt (Rückseite). */
private fun signedArea(mesh: PageCurl.Mesh, vararg corners: Int): Float {
    var sum = 0f
    for (k in corners.indices) {
        val i = corners[k]
        val j = corners[(k + 1) % corners.size]
        sum += mesh.x[i] * mesh.y[j] - mesh.x[j] * mesh.y[i]
    }
    return sum
}

private fun Color.shaded(light: Float) = Color(red * light, green * light, blue * light, alpha)

/**
 * Dreiecke mit einer Textur (Rückseite des Blatts), je Eckpunkt schattiert. Nur ab Android 10.
 */
private class TexturedTriangles(private val texture: android.graphics.Bitmap) {
    private val points = ArrayList<Float>()
    private val texs = ArrayList<Float>()
    private val colors = ArrayList<Int>()

    /** Zelle (Spalten [cs], Zeilen [rs] je Ecke) mit den Gitterpunkten in [mesh]. */
    fun quad(mesh: PageCurl.Mesh, cols: Int, rows: Int, cs: IntArray, rs: IntArray, light: IntArray) {
        fun add(k: Int) {
            val i = mesh.index(cs[k], rs[k])
            points += mesh.x[i]
            points += mesh.y[i]
            texs += cs[k] / cols.toFloat() * texture.width
            texs += rs[k] / rows.toFloat() * texture.height
            colors += light[k]
        }
        add(0); add(1); add(2)
        add(0); add(2); add(3)
    }

    fun draw(canvas: Canvas) {
        if (colors.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(
                texture, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, points.size, points.toFloatArray(), 0,
            texs.toFloatArray(), 0, colors.toIntArray(), 0, null, 0, 0, paint,
        )
    }
}

/**
 * Sammelt Dreiecke mit Farben je Eckpunkt und zeichnet sie in einem Rutsch.
 * Ab Android 10 mit drawVertices (weiche Farbverläufe), davor als einzelne Flächen.
 */
private class Triangles {
    private val points = ArrayList<Float>()
    private val colors = ArrayList<Int>()

    fun quad(
        x0: Float, y0: Float, c0: Int,
        x1: Float, y1: Float, c1: Int,
        x2: Float, y2: Float, c2: Int,
        x3: Float, y3: Float, c3: Int,
    ) {
        add(x0, y0, c0); add(x1, y1, c1); add(x2, y2, c2)
        add(x0, y0, c0); add(x2, y2, c2); add(x3, y3, c3)
    }

    private fun add(x: Float, y: Float, color: Int) {
        points += x
        points += y
        colors += color
    }

    fun draw(canvas: Canvas) {
        if (colors.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES, points.size, points.toFloatArray(), 0,
                null, 0, colors.toIntArray(), 0, null, 0, 0, plainPaint,
            )
        } else {
            val path = android.graphics.Path()
            var i = 0
            while (i < colors.size) {
                path.reset()
                path.moveTo(points[i * 2], points[i * 2 + 1])
                path.lineTo(points[i * 2 + 2], points[i * 2 + 3])
                path.lineTo(points[i * 2 + 4], points[i * 2 + 5])
                path.close()
                plainPaint.color = colors[i]
                canvas.drawPath(path, plainPaint)
                i += 3
            }
        }
    }
}
