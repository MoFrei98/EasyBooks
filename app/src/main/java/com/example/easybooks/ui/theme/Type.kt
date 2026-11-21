package com.example.easybooks.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.easybooks.R

/** Schreibschrift für große Titel ("Bibliothek", Dialogtitel). Unter etwa 28 sp schwer lesbar. */
internal val Tangerine = FontFamily(Font(R.font.tangerine_bold, FontWeight.Bold))

/** Nachbildung der Fell-Typen aus dem 17. Jahrhundert: Buchtitel. */
internal val FellEnglish = FontFamily(Font(R.font.im_fell_english, FontWeight.Normal))

/** Kapitälchen der Fell-Typen: Knöpfe, Schilder, Filter. */
internal val FellEnglishSc = FontFamily(Font(R.font.im_fell_english_sc, FontWeight.Normal))

/** Gut lesbare Buchschrift für kleine Texte. Eine variable Schriftdatei für alle Stärken. */
@OptIn(ExperimentalTextApi::class)
internal val Alegreya = FontFamily(
    Font(R.font.alegreya, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.alegreya, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.alegreya, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** True, wenn die App ihre verzierten Schriften zeigt (Einstellung "Schrift der App"). */
val LocalDecorated = staticCompositionLocalOf { true }

/**
 * Schriften der Oberfläche. Mit [decorated] = false bleibt es bei der Systemschrift;
 * Farben und Verzierungen gelten trotzdem.
 */
internal fun appTypography(decorated: Boolean): Typography {
    val base = Typography()
    if (!decorated) return base
    fun TextStyle.alegreya(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        copy(fontFamily = Alegreya, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)
    fun TextStyle.fell(size: Int, line: Int) =
        copy(fontFamily = FellEnglish, fontWeight = FontWeight.Normal, fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp)
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Tangerine, fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontFamily = Tangerine, fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontFamily = Tangerine, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.fell(32, 40),
        headlineMedium = base.headlineMedium.fell(28, 36),
        headlineSmall = base.headlineSmall.fell(24, 32),
        titleLarge = base.titleLarge.fell(23, 29),
        titleMedium = base.titleMedium.fell(18, 24),
        titleSmall = base.titleSmall.fell(16, 21),
        bodyLarge = base.bodyLarge.alegreya(17, 24),
        bodyMedium = base.bodyMedium.alegreya(15, 21),
        bodySmall = base.bodySmall.alegreya(14, 19),
        labelLarge = base.labelLarge.copy(fontFamily = FellEnglishSc, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.02.em),
        labelMedium = base.labelMedium.alegreya(14, 18, FontWeight.Medium),
        labelSmall = base.labelSmall.alegreya(12, 16, FontWeight.Medium),
    )
}

/** Besondere Schriftstile, die es nur in Easy Books gibt. */
internal object EasyBooksType {
    /** Seitentitel in der Leiste, z. B. "Bibliothek". */
    val screenTitle: TextStyle
        @Composable @ReadOnlyComposable get() = if (LocalDecorated.current) {
            TextStyle(fontFamily = Tangerine, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 46.sp)
        } else MaterialTheme.typography.titleLarge

    /** Titel in Dialogen, z. B. "Einstellungen". */
    val dialogTitle: TextStyle
        @Composable @ReadOnlyComposable get() = if (LocalDecorated.current) {
            TextStyle(fontFamily = Tangerine, fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 44.sp)
        } else MaterialTheme.typography.headlineSmall

    /** Große Überschrift auf leeren Seiten. */
    val emptyTitle: TextStyle
        @Composable @ReadOnlyComposable get() = if (LocalDecorated.current) {
            TextStyle(fontFamily = Tangerine, fontWeight = FontWeight.Bold, fontSize = 46.sp, lineHeight = 48.sp)
        } else MaterialTheme.typography.titleLarge

    /** Schrift auf Messing- und Lederschildern. */
    val plaque: TextStyle
        @Composable @ReadOnlyComposable get() = if (LocalDecorated.current) {
            TextStyle(fontFamily = FellEnglishSc, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.02.em)
        } else MaterialTheme.typography.labelLarge

    /** Kleine Schilder (Filter, Zähler). */
    val plaqueSmall: TextStyle
        @Composable @ReadOnlyComposable get() = if (LocalDecorated.current) {
            TextStyle(fontFamily = FellEnglishSc, fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.02.em)
        } else MaterialTheme.typography.labelLarge
}
