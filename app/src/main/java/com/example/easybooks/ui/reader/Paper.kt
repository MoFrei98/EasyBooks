package com.example.easybooks.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.example.easybooks.ui.theme.LocalReaderColors
import com.example.easybooks.ui.theme.ReaderColors
import kotlin.math.ceil

private const val MAX_SHEETS = 3

/** Lage einer Seite im aufgeschlagenen Buch. */
internal enum class PageSide {
    /** Einzelne Seite: Blattkanten links und rechts, Bundsteg links. */
    SINGLE,

    /** Linke Seite: gelesene Blätter links, Bundsteg rechts. */
    LEFT,

    /** Rechte Seite: noch zu lesende Blätter rechts, Bundsteg links. */
    RIGHT,
}

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
    val spineAlpha = if (darkPaper) 0.35f else 0.13f
    val leftSheets = if (side == PageSide.RIGHT) 0 else ceil(MAX_SHEETS * readFraction).toInt().coerceIn(0, MAX_SHEETS)
    val rightSheets = if (side == PageSide.LEFT) 0 else ceil(MAX_SHEETS * (1f - readFraction)).toInt().coerceIn(0, MAX_SHEETS)
    val ground: Shape = if (aged) InsetRectangle(FrayDepth) else RectangleShape
    // Schatten am Bundsteg und Abdunkeln nur auf dem Papier, nicht in den ausgefransten Lücken.
    val overlay = if (aged) BlendMode.SrcAtop else BlendMode.SrcOver

    Box(
        modifier
            .drawBehind {
                // Bei ausgefransten Kanten oben und unten nicht hervorschauen lassen.
                drawSheetEdges(colors, leftSheets, rightSheets, if (aged) FrayDepth.toPx() else 0f)
            }
            .shadow(3.dp, ground, clip = !aged)
            .graphicsLayer { if (aged) compositingStrategy = CompositingStrategy.Offscreen }
            .background(paperColor, ground)
            .drawWithContent {
                drawContent()
                drawSpineShade(colors.shade, spineAlpha, onRight = side == PageSide.LEFT, blendMode = overlay)
                val d = dim()
                if (d > 0f) drawRect(colors.shade, alpha = 0.22f * d, blendMode = overlay)
            },
        content = content,
    )
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

private fun DrawScope.drawSpineShade(shade: Color, alpha: Float, onRight: Boolean, blendMode: BlendMode) {
    val width = size.width * 0.045f
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
