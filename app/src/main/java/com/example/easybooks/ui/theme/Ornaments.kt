package com.example.easybooks.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Zierelemente der alten Bücherei als Vektorgrafik: Lilie, Messingecke und Zierlinie.
 * Dieselben Pfade stecken im App-Icon (res/drawable/ic_launcher_*.xml) und in der iPhone-App.
 */
internal object Ornaments {
    /** Lilie (Fleur-de-lis), Zeichenfläche 40 × 48. */
    const val FLEUR_W = 40f
    const val FLEUR_H = 48f
    val fleur: Path by lazy {
        path(
            "M20 1C15 9 14.5 18 20 27C25.5 18 25 9 20 1Z" +
                "M18.5 28C11 29 3.5 25 4 17C4.4 11 10.5 9.5 13 13.5C14.8 16.5 12.5 20 10 19C12 23 15 25.5 18.5 25Z" +
                "M21.5 28C29 29 36.5 25 36 17C35.6 11 29.5 9.5 27 13.5C25.2 16.5 27.5 20 30 19C28 23 25 25.5 21.5 25Z" +
                "M11.2 26.5H28.8Q30 26.5 30 27.7V29.3Q30 30.5 28.8 30.5H11.2Q10 30.5 10 29.3V27.7Q10 26.5 11.2 26.5Z" +
                "M17 31L20 46L23 31Z" +
                "M16 31C13 35 10 36 7 40C11 39.5 14 37 16.5 33Z" +
                "M24 31C27 35 30 36 33 40C29 39.5 26 37 23.5 33Z"
        )
    }

    /** Messingecke, Zeichenfläche 40 × 40, Ecke oben links. */
    const val CORNER = 40f
    val cornerPlate: Path by lazy { path("M0 0H37Q34 7 27 8.5Q20 9.5 17.5 14Q13 16 10.5 21Q8.5 28 8 37Q4 36 0 39Z") }
    val cornerShine: Path by lazy { path("M4 4H24Q19 6 15 10Q10 13 8 18Q6 21 4 23") }

    /** Zierlinie, Zeichenfläche 240 × 24. */
    const val DIVIDER_W = 240f
    const val DIVIDER_H = 24f
    val dividerLines: Path by lazy { path("M4 12H92M148 12H236") }
    val dividerThin: Path by lazy { path("M20 15.5H86M154 15.5H220") }
    val dividerCurls: Path by lazy {
        path("M92 12C97 4 105 4 107 10.5C108 14.5 103 16 101.5 12.7M148 12C143 4 135 4 133 10.5C132 14.5 137 16 138.5 12.7")
    }
    val dividerDiamond: Path by lazy { path("M120 3.5L128.5 12L120 20.5L111.5 12Z") }

    private fun path(data: String): Path = PathParser().parsePathString(data).toPath()

    val brass: Brush = Brush.linearGradient(
        listOf(Palette.BrassShine, Palette.BrassMid, Palette.BrassDark),
        start = Offset(0f, 0f),
        end = Offset(CORNER, CORNER),
    )
}

/** Zeichnet die Lilie in das Rechteck ab [topLeft] mit Breite [width] (Höhe folgt dem Seitenverhältnis). */
internal fun DrawScope.drawFleur(topLeft: Offset, width: Float, color: Color) {
    val s = width / Ornaments.FLEUR_W
    translate(topLeft.x, topLeft.y) {
        scale(s, s, pivot = Offset.Zero) { drawPath(Ornaments.fleur, color) }
    }
}

/** Messingecke mit Niete. [flipX]/[flipY] spiegeln sie für die anderen drei Ecken. */
internal fun DrawScope.drawBrassCorner(topLeft: Offset, size: Float, flipX: Boolean = false, flipY: Boolean = false) {
    val s = size / Ornaments.CORNER
    val sx = if (flipX) -s else s
    val sy = if (flipY) -s else s
    // Spiegeln um die Mitte der Ecke: erst in die Mitte, spiegeln/skalieren, zurück.
    translate(topLeft.x + size / 2f, topLeft.y + size / 2f) {
        scale(sx, sy, pivot = Offset.Zero) {
            translate(-Ornaments.CORNER / 2f, -Ornaments.CORNER / 2f) {
                drawPath(Ornaments.cornerPlate, Ornaments.brass)
                drawPath(Ornaments.cornerPlate, Color(0xFF4E3812), style = Stroke(width = 0.9f))
                drawPath(Ornaments.cornerShine, Color(0x8CFFF1C4), style = Stroke(width = 0.8f))
                drawCircle(Color(0xFFF1D48A), radius = 3.2f, center = Offset(8.5f, 8.5f))
                drawCircle(Color(0xFF4E3812), radius = 3.2f, center = Offset(8.5f, 8.5f), style = Stroke(width = 0.9f))
            }
        }
    }
}

/** Zeichnet die Zierlinie mittig in die Fläche. */
internal fun DrawScope.drawFlourishDivider(color: Color) {
    val s = minOf(size.width / Ornaments.DIVIDER_W, size.height / Ornaments.DIVIDER_H)
    val dx = (size.width - Ornaments.DIVIDER_W * s) / 2f
    val dy = (size.height - Ornaments.DIVIDER_H * s) / 2f
    translate(dx, dy) {
        scale(s, s, pivot = Offset.Zero) {
            drawPath(Ornaments.dividerLines, color, style = Stroke(width = 1.2f))
            drawPath(Ornaments.dividerThin, color, style = Stroke(width = 0.7f))
            drawPath(Ornaments.dividerCurls, color, style = Stroke(width = 1.4f, cap = StrokeCap.Round))
            drawPath(Ornaments.dividerDiamond, color)
            drawCircle(color, radius = 1.7f, center = Offset(111f, 12f))
            drawCircle(color, radius = 1.7f, center = Offset(129f, 12f))
        }
    }
}

/** Zierlinie unter Titeln und zwischen Abschnitten. */
@Composable
internal fun FlourishDivider(color: Color, modifier: Modifier = Modifier, width: Dp = 200.dp, height: Dp = 20.dp) {
    Canvas(modifier.size(width, height)) { drawFlourishDivider(color) }
}

/** Lilie als eigenständiges Element, z. B. auf leeren Seiten. */
@Composable
internal fun Fleur(color: Color, modifier: Modifier = Modifier, width: Dp = 40.dp) {
    Canvas(modifier.size(width, width * (Ornaments.FLEUR_H / Ornaments.FLEUR_W))) {
        drawFleur(Offset.Zero, size.width, color)
    }
}

/** Messingecken an allen vier Ecken der Fläche, leicht über den Rand hinaus. */
internal fun Modifier.brassCorners(size: Dp = 16.dp, overhang: Dp = 2.dp): Modifier = drawWithContent {
    drawContent()
    val c = size.toPx()
    val o = overhang.toPx()
    drawBrassCorner(Offset(-o, -o), c)
    drawBrassCorner(Offset(this.size.width - c + o, -o), c, flipX = true)
    drawBrassCorner(Offset(-o, this.size.height - c + o), c, flipY = true)
    drawBrassCorner(Offset(this.size.width - c + o, this.size.height - c + o), c, flipX = true, flipY = true)
}
