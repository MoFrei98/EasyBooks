package com.example.easybooks.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Farben der alten Bücherei: Holz, Leder, Messing, Pergament und Tinte.
 * Jede Farbe steht für ein Material und hat in der App eine feste Aufgabe.
 */
internal object Palette {
    /** Mahagoni-Leder: Leisten oben und unten, Einbände. */
    val Leather = Color(0xFF3B1F1A)
    val LeatherLight = Color(0xFF5E3526)
    val LeatherNight = Color(0xFF1E110C)
    val LeatherNightLight = Color(0xFF2D1810)

    /** Linien, Rahmen und Symbole auf Papier. */
    val Saddle = Color(0xFF8B5A2B)

    /** Messing: Knöpfe, Schilder, Ecken, aktive Filter. */
    val Brass = Color(0xFFB8913F)
    val BrassLight = Color(0xFFEBCD86)
    val BrassShine = Color(0xFFF4D994)
    val BrassMid = Color(0xFFC49A45)
    val BrassDark = Color(0xFF6E5020)

    /** Schrift auf Messing. */
    val OnBrass = Color(0xFF2E1E08)

    /** Pergament: Hintergrund der Bibliothek und der Dialoge. */
    val Parchment = Color(0xFFF0E4C8)
    val ParchmentDeep = Color(0xFFE4D2A9)

    /** Tinte: Schrift auf Papier. */
    val Ink = Color(0xFF2B1D12)
    val InkSoft = Color(0xFF5B4632)

    /** Bibliotheksgrün: dunkles Design, App-Icon. */
    val Green = Color(0xFF2F4A2F)
    val Night = Color(0xFF121914)
    val NightText = Color(0xFFE9DAB8)
    val NightMuted = Color(0xFFBCA77E)

    /** Siegelrot: Lesebändchen, Fortschritt, Hinweise. */
    val Seal = Color(0xFF8E2A1E)
    val SealLight = Color(0xFFD06A52)

    /** Regalholz und Lesetisch. */
    val Wood = Color(0xFF4A2A18)
    val WoodLight = Color(0xFF6B3F22)
    val WoodDark = Color(0xFF2C170D)

    /** Schrift auf Holz und Leder. */
    val OnWood = Color(0xFFF0E4C8)
}

internal val LightColors = lightColorScheme(
    primary = Palette.Saddle,
    onPrimary = Color(0xFFFFF8EC),
    primaryContainer = Palette.BrassLight,
    onPrimaryContainer = Palette.OnBrass,
    secondary = Color(0xFF7A5A22),
    onSecondary = Color(0xFFFFF8EC),
    secondaryContainer = Color(0xFFE9D7AE),
    onSecondaryContainer = Palette.Ink,
    tertiary = Palette.Seal,
    onTertiary = Color(0xFFFFF8EC),
    background = Palette.Parchment,
    onBackground = Palette.Ink,
    surface = Palette.Parchment,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.ParchmentDeep,
    onSurfaceVariant = Palette.InkSoft,
    surfaceContainerLowest = Color(0xFFF8EFDA),
    surfaceContainerLow = Color(0xFFF3E8CF),
    surfaceContainer = Color(0xFFEEE0C0),
    surfaceContainerHigh = Color(0xFFE9D9B5),
    surfaceContainerHighest = Color(0xFFE3D1A8),
    outline = Palette.Saddle,
    outlineVariant = Color(0xFFCDB68A),
    inverseSurface = Palette.Leather,
    inverseOnSurface = Palette.BrassLight,
    inversePrimary = Palette.BrassLight,
)

internal val DarkColors = darkColorScheme(
    primary = Palette.BrassLight,
    onPrimary = Color(0xFF3B2A0E),
    primaryContainer = Color(0xFF5A4318),
    onPrimaryContainer = Palette.BrassLight,
    secondary = Color(0xFFD9BE84),
    onSecondary = Color(0xFF33260C),
    secondaryContainer = Color(0xFF3A3020),
    onSecondaryContainer = Palette.NightText,
    tertiary = Palette.SealLight,
    onTertiary = Color(0xFF3A0E06),
    background = Palette.Night,
    onBackground = Palette.NightText,
    surface = Palette.Night,
    onSurface = Palette.NightText,
    surfaceVariant = Color(0xFF2B3329),
    onSurfaceVariant = Palette.NightMuted,
    surfaceContainerLowest = Color(0xFF0C110D),
    surfaceContainerLow = Color(0xFF161E18),
    surfaceContainer = Color(0xFF1B231C),
    surfaceContainerHigh = Color(0xFF232C24),
    surfaceContainerHighest = Color(0xFF2C352C),
    outline = Color(0xFF8C7445),
    outlineVariant = Color(0xFF3D3B2C),
    inverseSurface = Palette.NightText,
    inverseOnSurface = Palette.Leather,
    inversePrimary = Palette.Saddle,
)

/** Farben rund um die Seiten im Reader. */
data class ReaderColors(
    /** Papierfarbe einer Seite. */
    val paper: Color,
    /** Kanten der darunterliegenden Blätter. */
    val paperEdge: Color,
    /** Schatten am Bundsteg und beim Umblättern. */
    val shade: Color,
    /** Schriftfarbe auf dunklen Seiten. */
    val ink: Color,
    /** Seiten sehen alt aus (vergilbtes Papier, braune Tinte). */
    val aged: Boolean,
)

internal val LightReaderColors = ReaderColors(
    paper = Color(0xFFFFFDF8),
    paperEdge = Color(0xFFD9CFC1),
    shade = Color(0xFF3A2A1A),
    ink = Color(0xFF000000),
    aged = false,
)

internal val DarkReaderColors = ReaderColors(
    paper = Color(0xFF1F1D1A),
    paperEdge = Color(0xFF3A3631),
    shade = Color(0xFF000000),
    ink = Color(0xFFDCD5CA),
    aged = false,
)

/** Vergilbtes Papier, braune Tinte. */
internal val AgedLightReaderColors = ReaderColors(
    paper = Color(0xFFE8D6AC),
    paperEdge = Color(0xFFCDB487),
    shade = Color(0xFF3A2410),
    ink = Palette.Ink,
    aged = true,
)

/** Altes Papier im dunklen Design: dunkles Papier, warme helle Schrift. */
internal val AgedDarkReaderColors = ReaderColors(
    paper = Color(0xFF2A2118),
    paperEdge = Color(0xFF43372A),
    shade = Color(0xFF000000),
    ink = Color(0xFFE3D2AE),
    aged = true,
)

internal fun readerColors(dark: Boolean, aged: Boolean): ReaderColors = when {
    aged && dark -> AgedDarkReaderColors
    aged -> AgedLightReaderColors
    dark -> DarkReaderColors
    else -> LightReaderColors
}
