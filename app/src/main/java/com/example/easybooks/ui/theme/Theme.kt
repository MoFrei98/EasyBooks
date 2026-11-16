package com.example.easybooks.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** True, wenn die App gerade im dunklen Design läuft. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** Farben rund um die Seiten im Reader. */
val LocalReaderColors = staticCompositionLocalOf { AgedLightReaderColors }

/**
 * Stil "Alte Bücherei": Pergament, Leder, Messing und Holz.
 *
 * @param decorated Verzierte Schriften (sonst Systemschrift)
 * @param agedPaper Buchseiten im Reader sehen alt aus
 */
@Composable
fun EasyBooksTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    decorated: Boolean = true,
    agedPaper: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                // Oben liegt immer eine dunkle Lederleiste: helle Symbole.
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    val typography = remember(decorated) { appTypography(decorated) }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalDecorated provides decorated,
        LocalReaderColors provides readerColors(darkTheme, agedPaper),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = typography,
            content = content,
        )
    }
}
