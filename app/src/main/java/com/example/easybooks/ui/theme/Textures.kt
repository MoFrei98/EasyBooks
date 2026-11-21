package com.example.easybooks.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Oberflächen aus Papier, Leder und Holz. Alle Strukturen werden einmal beim Start im Code
 * erzeugt (keine Bilddateien) und nahtlos gekachelt.
 */
internal object Textures {

    /** Feines Rauschen in Braun: Papierfasern, Lederporen. */
    val grain: Bitmap by lazy { speckles(size = 160, seed = 11) }

    /** Weiche, große Flecken für vergilbtes Papier (wird vergrößert gezeichnet). */
    val blotches: Bitmap by lazy { clouds(size = 192, seed = 5) }

    /** Holzmaserung für Regale und den Lesetisch. */
    val wood: Bitmap by lazy { woodGrain(width = 512, height = 256, seed = 3) }

    /** Erzeugt alle Strukturen im Voraus, damit das erste Bild nicht stockt. */
    fun warmUp() {
        grain
        blotches
        wood
    }

    /** Pinsel, der [bitmap] gekachelt und um [scale] vergrößert zeichnet. */
    fun tiled(bitmap: Bitmap, scale: Float = 1f, dx: Float = 0f, dy: Float = 0f): ShaderBrush =
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                BitmapShader(bitmap, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT).apply {
                    setLocalMatrix(Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(dx, dy)
                    })
                }
        }

    val grainBrush: ShaderBrush by lazy { tiled(grain) }
    val blotchBrush: ShaderBrush by lazy { tiled(blotches, scale = 3.2f) }
    val woodBrush: ShaderBrush by lazy { tiled(wood, scale = 1.5f) }

    // ---------------------------------------------------------------- Erzeugung

    private fun speckles(size: Int, seed: Int): Bitmap {
        val random = Random(seed)
        val pixels = IntArray(size * size) {
            val v = random.nextFloat()
            // Wenige dunkle Punkte, viele kaum sichtbare.
            val alpha = (v.pow(3) * 0.22f * 255).toInt().coerceIn(0, 255)
            (alpha shl 24) or (0x3C shl 16) or (0x28 shl 8) or 0x14
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun clouds(size: Int, seed: Int): Bitmap {
        val noise = PeriodicNoise(seed)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val u = x / size.toFloat()
            val v = y / size.toFloat()
            val n = 0.55f * noise.sample(u, v, 4) + 0.3f * noise.sample(u, v, 8) + 0.15f * noise.sample(u, v, 16)
            val alpha = (((n - 0.42f) * 2.2f).coerceIn(0f, 1f) * 255).toInt()
            pixels[y * size + x] = (alpha shl 24) or (0x8A shl 16) or (0x5C shl 8) or 0x26
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun woodGrain(width: Int, height: Int, seed: Int): Bitmap {
        val noise = PeriodicNoise(seed)
        val random = Random(seed)
        val dark = Palette.WoodDark
        val light = Palette.WoodLight
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val u = x / width.toFloat()
            val v = y / height.toFloat()
            // Feine Jahresringe, leicht gewellt, dazu längliche Fasern entlang der Maserung.
            val warp = 0.45f * noise.sample(u, v, 4) + 0.12f * noise.sample(u, v, 8)
            val rings = sin(2 * PI * (v * 22 + warp)).toFloat() * 0.5f + 0.5f
            val fibers = noise.sample(u, v * 4f, 32)
            val t = (0.32f + 0.22f * rings.pow(1.3f) + 0.3f * fibers + 0.1f * random.nextFloat()).coerceIn(0f, 1f)
            val r = dark.red + (light.red - dark.red) * t
            val g = dark.green + (light.green - dark.green) * t
            val b = dark.blue + (light.blue - dark.blue) * t
            pixels[y * width + x] = Color(r, g, b).toArgbInt()
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun Color.toArgbInt(): Int =
        (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
}

/** Glattes, in beiden Richtungen nahtloses Wertrauschen (0..1). */
internal class PeriodicNoise(seed: Int) {
    private val lattice = FloatArray(64 * 64).also { values ->
        val random = Random(seed)
        for (i in values.indices) values[i] = random.nextFloat()
    }

    /** @param cells Gitterzellen pro Kachel (höchstens 64) */
    fun sample(u: Float, v: Float, cells: Int): Float {
        val x = u * cells
        val y = v * cells
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        fun at(i: Int, j: Int): Float = lattice[Math.floorMod(j, cells) * 64 + Math.floorMod(i, cells)]
        val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * fx
        val bottom = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * fx
        return top + (bottom - top) * fy
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)
}

// -------------------------------------------------------------------- Flächen

/** Pergament (helles Design) oder dunkles Holz im Kerzenlicht (dunkles Design). */
internal fun Modifier.parchmentBackground(dark: Boolean): Modifier = drawBehind { drawParchment(dark) }

internal fun DrawScope.drawParchment(dark: Boolean) {
    if (dark) {
        drawRect(Palette.Night)
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x2EF0AA46), Color.Transparent),
                center = Offset(size.width / 2f, 0f),
                radius = size.width * 1.1f,
            )
        )
        drawRect(Textures.grainBrush, alpha = 0.55f)
    } else {
        drawRect(Palette.Parchment)
        drawRect(Textures.blotchBrush, alpha = 0.2f)
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x66FFFAE6), Color.Transparent),
                center = Offset(size.width / 2f, 0f),
                radius = size.width * 1.2f,
            )
        )
        drawRect(Textures.grainBrush)
    }
}

/** Mahagoni-Leder für Leisten. */
internal fun Modifier.leatherBackground(night: Boolean, flip: Boolean = false): Modifier = drawBehind {
    val top = if (night) Palette.LeatherNight else Palette.Leather
    val bottom = if (night) Palette.LeatherNightLight else Palette.LeatherLight
    drawRect(Brush.verticalGradient(if (flip) listOf(bottom, top) else listOf(top, bottom)))
    drawRect(Textures.grainBrush, alpha = 0.9f)
}

/** Holz, z. B. der Lesetisch hinter den Seiten. */
internal fun Modifier.woodBackground(dark: Boolean): Modifier = drawBehind { drawWood(dark) }

internal fun DrawScope.drawWood(dark: Boolean) {
    drawRect(Textures.woodBrush)
    drawRect(
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.35f),
            0.3f to Color.Transparent,
            0.7f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.45f),
        )
    )
    if (dark) drawRect(Color.Black.copy(alpha = 0.45f))
}
