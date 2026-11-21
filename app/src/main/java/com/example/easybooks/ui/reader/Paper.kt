package com.example.easybooks.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.easybooks.ui.theme.LocalReaderColors
import com.example.easybooks.ui.theme.ReaderColors
import com.example.easybooks.ui.theme.Textures
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val MAX_SHEETS = 3

/** Lage einer Seite im aufgeschlagenen Buch. */
internal enum class PageSide {
    /** Einzelne Seite: Blattkanten links und rechts, Bundsteg links. */
    SINGLE,

    /** Linke Seite: gelesene Blätter links, Bundsteg rechts. */
    LEFT,

    /** Rechte Seite: noch zu lesende Blätter rechts, Bundsteg links. */
    RIGHT,

    /** Fester Buchdeckel des geschlossenen Buchs (erste Seite, wenn sie ein Bild ist). */
    COVER,
}

/** Platz außen (und oben/unten) für Einband und Buchblock, wenn ein aufgeschlagenes Buch daliegt. */
internal val BookOuterMargin = 18.dp
internal val BookVerticalMargin = 18.dp

/** Rechteck, das um [inset] kleiner ist: Schatten und Grund unter ausgefransten Kanten. */
private class InsetRectangle(private val inset: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val i = with(density) { inset.toPx() }
        return Outline.Rectangle(Rect(i, i, size.width - i, size.height - i))
    }
}

/**
 * Zeichnet eine Seite als Blatt Papier.
 *
 * - Schatten unter dem Blatt
 * - Blattkanten links (schon gelesen) und rechts (noch zu lesen), je nach Fortschritt
 * - Schattierung am Bundsteg (linker Rand), wie bei einem aufgeschlagenen Buch
 * - [dim]: Abdunkeln, solange die Seite noch von der umgeblätterten Seite bedeckt ist (0..1)
 * - [aged]: Die Seite ist auf altes Papier mit ausgefransten Kanten gedruckt. Schatten und Grund
 *   liegen dann etwas innen, damit die Ausfransung sichtbar bleibt.
 * - [side] links oder rechts: Die Seite liegt in einem aufgeschlagenen Buch. Darunter sind der
 *   Buchblock und der Ledereinband zu sehen, am Bundsteg ist die Seite bündig, damit sich linke
 *   und rechte Seite beim Weitergleiten zu einem Buch zusammenfügen.
 */
@Composable
internal fun Paper(
    paperColor: Color,
    darkPaper: Boolean,
    readFraction: Float,
    dim: () -> Float,
    modifier: Modifier = Modifier,
    aged: Boolean = false,
    side: PageSide = PageSide.SINGLE,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalReaderColors.current
    if (side == PageSide.COVER) {
        CoverBoard(darkPaper, dim, modifier, content)
        return
    }
    val book = side != PageSide.SINGLE
    val spineAlpha = when {
        book && darkPaper -> 0.5f
        book -> 0.3f
        darkPaper -> 0.35f
        else -> 0.13f
    }
    val leftSheets = if (side == PageSide.RIGHT) 0 else ceil(MAX_SHEETS * readFraction).toInt().coerceIn(0, MAX_SHEETS)
    val rightSheets = if (side == PageSide.LEFT) 0 else ceil(MAX_SHEETS * (1f - readFraction)).toInt().coerceIn(0, MAX_SHEETS)
    val ground: Shape = if (aged) InsetRectangle(FrayDepth) else RectangleShape
    // Schatten am Bundsteg und Abdunkeln nur auf dem Papier, nicht in den ausgefransten Lücken.
    val overlay = if (aged) BlendMode.SrcAtop else BlendMode.SrcOver

    Box(
        modifier
            .drawBehind {
                if (book) {
                    // Linke Seiten: gelesene Blätter darunter; rechte Seiten: die noch zu lesenden.
                    val fraction = if (side == PageSide.LEFT) readFraction else 1f - readFraction
                    drawOpenBook(colors, spineRight = side == PageSide.LEFT, fraction = fraction, dark = darkPaper)
                } else {
                    // Bei ausgefransten Kanten oben und unten nicht hervorschauen lassen.
                    drawSheetEdges(colors, leftSheets, rightSheets, if (aged) FrayDepth.toPx() else 0f)
                }
            }
            .shadow(3.dp, ground, clip = !aged)
            .graphicsLayer { if (aged) compositingStrategy = CompositingStrategy.Offscreen }
            .background(paperColor, ground)
            .drawWithContent {
                drawContent()
                drawSpineShade(
                    colors.shade,
                    spineAlpha,
                    onRight = side == PageSide.LEFT,
                    widthFraction = if (book) 0.075f else 0.045f,
                    blendMode = overlay,
                )
                val d = dim()
                if (d > 0f) drawRect(colors.shade, alpha = 0.22f * d, blendMode = overlay)
            },
        content = content,
    )
}

/** Der Buchdeckel liegt auf dem geschlossenen Buch: abgerundet, ohne Papiergrund und Bundsteg. */
@Composable
private fun CoverBoard(
    dark: Boolean,
    dim: () -> Float,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(topStart = 1.5.dp, bottomStart = 1.5.dp, topEnd = 5.dp, bottomEnd = 5.dp)
    Box(
        modifier
            .drawBehind { drawClosedBook(colors, dark) }
            .shadow(2.dp, shape)
            .drawWithContent {
                drawContent()
                val d = dim()
                if (d > 0f) drawRect(colors.shade, alpha = 0.22f * d)
            },
        content = content,
    )
}

/**
 * Das geschlossene Buch unter dem Deckel: rechts und unten sieht man den Buchblock und den
 * hinteren Deckel, dazu den Schatten auf dem Tisch.
 */
private fun DrawScope.drawClosedBook(colors: ReaderColors, dark: Boolean) {
    val w = size.width
    val h = size.height
    val depth = 7.dp.toPx()
    val radius = CornerRadius(5.dp.toPx())
    // Schatten auf dem Tisch.
    val step = 1.6.dp.toPx()
    for (i in 7 downTo 1) {
        drawRoundRect(
            Color.Black.copy(alpha = 0.07f),
            topLeft = Offset(-i * step * 0.3f, -i * step * 0.5f + 2.dp.toPx()),
            size = Size(w + depth + i * step * 1.3f, h + depth + i * step * 1.5f),
            cornerRadius = CornerRadius(radius.x + i * step),
        )
    }
    // Hinterer Deckel, um die Buchdicke nach rechts unten versetzt.
    val leather = if (dark) listOf(Color(0xFF1C3320), Color(0xFF0E1A10)) else listOf(Color(0xFF2C4D31), Color(0xFF17301C))
    drawRoundRect(
        Brush.linearGradient(leather, start = Offset(0f, 0f), end = Offset(w, h)),
        topLeft = Offset(depth * 0.55f, depth),
        size = Size(w, h),
        cornerRadius = radius,
    )
    // Buchblock dazwischen: Papierkanten mit feinen Linien.
    val block = Rect(depth * 0.25f, depth * 0.45f, w + depth * 0.4f, h + depth * 0.8f)
    drawRect(colors.paperEdge, topLeft = block.topLeft, size = block.size)
    val line = lerp(colors.paperEdge, Color.Black, 0.3f).copy(alpha = 0.55f)
    var y = h
    while (y < block.bottom) {
        drawLine(line, Offset(block.left, y), Offset(block.right, y), strokeWidth = 0.5.dp.toPx())
        y += 1.3.dp.toPx()
    }
    var x = w
    while (x < block.right) {
        drawLine(line, Offset(x, block.top), Offset(x, block.bottom), strokeWidth = 0.5.dp.toPx())
        x += 1.3.dp.toPx()
    }
}

private fun DrawScope.drawSheetEdges(colors: ReaderColors, left: Int, right: Int, vInset: Float) {
    val step = 2.dp.toPx()
    val inset = 1.dp.toPx()
    val line = 0.6.dp.toPx()
    fun sheet(i: Int, direction: Float) {
        val dx = i * step * direction
        val dy = i * inset + vInset
        drawRect(colors.paperEdge, topLeft = Offset(dx, dy), size = Size(size.width, size.height - 2 * dy))
        val x = if (direction > 0) size.width + dx - line / 2 else dx + line / 2
        drawLine(
            color = colors.shade.copy(alpha = 0.18f),
            start = Offset(x, dy),
            end = Offset(x, size.height - dy),
            strokeWidth = line,
        )
    }
    // Von außen nach innen zeichnen, damit nähere Blätter die äußeren überdecken.
    for (i in right downTo 1) sheet(i, direction = 1f)
    for (i in left downTo 1) sheet(i, direction = -1f)
}

/**
 * Das aufgeschlagene Buch unter einer Seite: Schatten auf dem Tisch, Ledereinband und der
 * Buchblock aus übereinanderliegenden Blättern. Außen, oben und unten steht beides über die
 * Seite hinaus, am Bundsteg schließt es bündig ab (dort liegt die andere Buchhälfte).
 *
 * @param fraction Anteil der Blätter auf dieser Seite des Buchs (0..1)
 */
private fun DrawScope.drawOpenBook(colors: ReaderColors, spineRight: Boolean, fraction: Float, dark: Boolean) {
    val w = size.width
    val h = size.height
    val step = 1.4.dp.toPx()
    val layers = (2 + (5 * fraction).roundToInt()).coerceIn(2, 7)
    val overhang = 5.dp.toPx()
    val cover = layers * step + overhang
    val far = 10_000f

    /** Fläche, die außen, oben und unten um [grow] über die Seite hinausgeht. */
    fun outer(grow: Float): Rect =
        if (spineRight) Rect(-grow, -grow, w, h + grow) else Rect(0f, -grow, w + grow, h + grow)

    clipRect(left = if (spineRight) -far else 0f, top = -far, right = if (spineRight) w else far, bottom = far) {
        // Weicher Schatten des Buchs auf dem Tisch.
        val shadowStep = 1.6.dp.toPx()
        for (i in 7 downTo 1) {
            val r = outer(cover + i * shadowStep)
            drawRoundRect(
                Color.Black.copy(alpha = 0.07f),
                topLeft = r.topLeft + Offset(0f, 1.5.dp.toPx()),
                size = r.size,
                cornerRadius = CornerRadius(6.dp.toPx() + i * shadowStep),
            )
        }
        // Einband aus grünem Leder, außen abgerundet; am Bundsteg über den Rand hinaus, damit
        // dort keine Rundung zu sehen ist.
        val c = outer(cover)
        val extra = 24.dp.toPx()
        val binding = if (spineRight) Rect(c.left, c.top, c.right + extra, c.bottom) else Rect(c.left - extra, c.top, c.right, c.bottom)
        val leather = if (dark) {
            listOf(Color(0xFF1C3320), Color(0xFF122216))
        } else {
            listOf(Color(0xFF34593A), Color(0xFF1E3823))
        }
        drawRoundRect(
            Brush.linearGradient(leather, start = binding.topLeft, end = binding.bottomRight),
            topLeft = binding.topLeft,
            size = binding.size,
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
        drawRect(Textures.grainBrush, topLeft = binding.topLeft, size = binding.size, alpha = 0.9f)
        // Feine Goldlinie entlang der Einbandkante.
        val gold = if (dark) Color(0xFF8C7445) else Color(0xFFC9A55A)
        val g = outer(cover - 2.dp.toPx())
        drawRoundRect(
            gold.copy(alpha = 0.55f),
            topLeft = if (spineRight) g.topLeft else Offset(g.left - extra, g.top),
            size = Size(g.width + extra, g.height),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.8.dp.toPx()),
        )
        // Buchblock: Blätter von außen nach innen, jedes Blatt ein Stück kleiner.
        val light = colors.paperEdge
        val shade = lerp(colors.paperEdge, Color.Black, 0.25f)
        for (i in layers downTo 1) {
            val r = outer(i * step)
            drawRect(if (i % 2 == 0) light else lerp(light, shade, 0.5f), topLeft = r.topLeft, size = r.size)
            // Dunkle Kante jedes Blatts außen, oben und unten.
            val line = 0.6.dp.toPx()
            val edge = shade.copy(alpha = 0.6f)
            drawRect(edge, topLeft = r.topLeft, size = Size(r.width, line))
            drawRect(edge, topLeft = Offset(r.left, r.bottom - line), size = Size(r.width, line))
            val x = if (spineRight) r.left else r.right - line
            drawRect(edge, topLeft = Offset(x, r.top), size = Size(line, r.height))
        }
    }
}

private fun DrawScope.drawSpineShade(
    shade: Color,
    alpha: Float,
    onRight: Boolean,
    widthFraction: Float,
    blendMode: BlendMode,
) {
    val width = size.width * widthFraction
    val start = if (onRight) size.width else 0f
    val end = if (onRight) size.width - width else width
    drawRect(
        brush = Brush.horizontalGradient(
            0f to shade.copy(alpha = alpha),
            0.35f to shade.copy(alpha = alpha * 0.35f),
            1f to Color.Transparent,
            startX = start,
            endX = end,
        ),
        topLeft = Offset(if (onRight) size.width - width else 0f, 0f),
        size = Size(width, size.height),
        blendMode = blendMode,
    )
}
