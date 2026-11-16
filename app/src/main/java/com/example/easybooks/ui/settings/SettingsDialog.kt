package com.example.easybooks.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.easybooks.R
import com.example.easybooks.data.AppFont
import com.example.easybooks.data.AppSettings
import com.example.easybooks.data.PageTurn
import com.example.easybooks.data.PaperStyle
import com.example.easybooks.data.ThemeMode
import com.example.easybooks.data.ZoomMode
import com.example.easybooks.ui.theme.DialogConfirmButton
import com.example.easybooks.ui.theme.OrnateDialog
import com.example.easybooks.ui.theme.Plaque
import com.example.easybooks.ui.theme.PlaqueStyle
import com.example.easybooks.ui.theme.SegmentedPlaques
import com.example.easybooks.ui.theme.brassSwitchColors

@Composable
fun SettingsDialog(
    settings: AppSettings,
    onThemeChange: (ThemeMode) -> Unit,
    onPageSizeChange: (ZoomMode) -> Unit,
    onPageTurnChange: (PageTurn) -> Unit,
    onPageTurnSoundChange: (Boolean) -> Unit,
    onAppFontChange: (AppFont) -> Unit,
    onPaperChange: (PaperStyle) -> Unit,
    onChooseLibraryFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    OrnateDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings),
        confirmButton = { DialogConfirmButton(stringResource(R.string.done), onDismiss) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.settings_library))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        settings.libraryLabel ?: stringResource(R.string.library_folder_none),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Hint(stringResource(R.string.library_folder_hint))
                }
                Plaque(
                    text = stringResource(if (settings.libraryTree != null) R.string.change else R.string.choose),
                    onClick = onChooseLibraryFolder,
                    style = PlaqueStyle.OUTLINE,
                    small = true,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            SectionTitle(stringResource(R.string.settings_theme))
            SegmentedPlaques(
                options = listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.DARK to stringResource(R.string.theme_dark),
                ),
                selected = settings.theme,
                onSelect = onThemeChange,
            )

            SectionTitle(stringResource(R.string.settings_app_font))
            SegmentedPlaques(
                options = listOf(
                    AppFont.NORMAL to stringResource(R.string.app_font_normal),
                    AppFont.DECORATED to stringResource(R.string.app_font_decorated),
                ),
                selected = settings.appFont,
                onSelect = onAppFontChange,
            )
            Hint(
                stringResource(
                    if (settings.appFont == AppFont.DECORATED) R.string.app_font_decorated_hint else R.string.app_font_normal_hint
                )
            )

            SectionTitle(stringResource(R.string.settings_paper))
            SegmentedPlaques(
                options = listOf(
                    PaperStyle.AGED to stringResource(R.string.paper_aged),
                    PaperStyle.PLAIN to stringResource(R.string.paper_plain),
                ),
                selected = settings.paper,
                onSelect = onPaperChange,
            )
            Hint(
                stringResource(if (settings.paper == PaperStyle.AGED) R.string.paper_aged_hint else R.string.paper_plain_hint)
            )

            SectionTitle(stringResource(R.string.settings_page_size))
            SegmentedPlaques(
                options = listOf(
                    ZoomMode.WIDE to stringResource(R.string.zoom_wide),
                    ZoomMode.NORMAL to stringResource(R.string.zoom_normal),
                    ZoomMode.CLOSE to stringResource(R.string.zoom_close),
                ),
                selected = settings.pageSize,
                onSelect = onPageSizeChange,
            )
            Hint(
                stringResource(
                    when (settings.pageSize) {
                        ZoomMode.WIDE -> R.string.zoom_wide_hint
                        ZoomMode.NORMAL -> R.string.zoom_normal_hint
                        ZoomMode.CLOSE -> R.string.zoom_close_hint
                    }
                )
            )

            SectionTitle(stringResource(R.string.settings_page_turn))
            SegmentedPlaques(
                options = listOf(
                    PageTurn.CURL to stringResource(R.string.page_turn_curl),
                    PageTurn.SLIDE to stringResource(R.string.page_turn_slide),
                ),
                selected = settings.pageTurn,
                onSelect = onPageTurnChange,
            )
            Hint(
                stringResource(
                    if (settings.pageTurn == PageTurn.CURL) R.string.page_turn_curl_hint else R.string.page_turn_slide_hint
                )
            )

            Spacer(Modifier.height(10.dp))
            SwitchRow(
                label = stringResource(R.string.page_turn_sound),
                hint = stringResource(R.string.page_turn_sound_hint),
                checked = settings.pageTurnSound,
                onCheckedChange = onPageTurnSoundChange,
            )
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Hint(hint)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = brassSwitchColors(),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
