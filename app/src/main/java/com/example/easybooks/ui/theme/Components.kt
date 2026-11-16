package com.example.easybooks.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Aussehen eines Schilds. */
internal enum class PlaqueStyle {
    /** Poliertes Messing: wichtige Knöpfe, aktiver Filter. */
    BRASS,

    /** Leder mit Messingschrift. */
    LEATHER,

    /** Nur eingeprägter Rand: weitere Knöpfe, inaktive Filter. */
    OUTLINE,
}

internal val BrassBrush: Brush = Brush.verticalGradient(
    0f to Color(0xFFF2D68F),
    0.55f to Palette.BrassMid,
    1f to Color(0xFF94702E),
)

private val LeatherPlaqueBrush: Brush = Brush.verticalGradient(listOf(Color(0xFF6A3B29), Palette.Leather))

/**
 * Knopf als Messing- oder Lederschild.
 *
 * @param selected Wenn gesetzt, verhält sich das Schild wie eine Auswahl (z. B. ein Filter).
 */
@Composable
internal fun Plaque(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PlaqueStyle = PlaqueStyle.BRASS,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    small: Boolean = false,
    selected: Boolean? = null,
) {
    val dark = LocalDarkTheme.current
    val shape = RoundedCornerShape(6.dp)
    val background = when (style) {
        PlaqueStyle.BRASS -> BrassBrush
        PlaqueStyle.LEATHER -> LeatherPlaqueBrush
        PlaqueStyle.OUTLINE -> SolidColor(if (dark) Palette.BrassLight.copy(alpha = 0.06f) else Color(0x80FFFAEB))
    }
    val border = when (style) {
        PlaqueStyle.BRASS -> Palette.BrassDark
        PlaqueStyle.LEATHER -> Color(0xFF24120D)
        PlaqueStyle.OUTLINE -> if (dark) Palette.BrassLight.copy(alpha = 0.5f) else Palette.Saddle
    }
    val textColor = when (style) {
        PlaqueStyle.BRASS -> Palette.OnBrass
        PlaqueStyle.LEATHER -> Palette.BrassLight
        PlaqueStyle.OUTLINE -> if (dark) Palette.BrassLight else Palette.Leather
    }
    val interaction = if (selected != null) {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .shadow(if (style == PlaqueStyle.OUTLINE) 0.dp else 2.dp, shape, clip = false)
            .clip(shape)
            .background(background, shape)
            .border(1.dp, border, shape)
            .drawWithContent {
                drawContent()
                if (style == PlaqueStyle.BRASS) {
                    // Glanzkante oben, wie bei poliertem Metall.
                    drawLine(Color(0x99FFF5D2), Offset(4.dp.toPx(), 1.5.dp.toPx()), Offset(size.width - 4.dp.toPx(), 1.5.dp.toPx()), strokeWidth = 1.dp.toPx())
                }
            }
            .then(interaction)
            .heightIn(min = if (small) 38.dp else 46.dp)
            .padding(horizontal = if (small) 13.dp else 18.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(if (small) 16.dp else 20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = if (small) EasyBooksType.plaqueSmall else EasyBooksType.plaque,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Mehrere Schilder nebeneinander, von denen genau eines gewählt ist (Einstellungen). */
@Composable
internal fun <T> SegmentedPlaques(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    val shape = RoundedCornerShape(6.dp)
    val line = if (dark) Palette.BrassLight.copy(alpha = 0.45f) else Palette.Saddle
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .border(1.dp, line, shape)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, (value, label) ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(line.copy(alpha = 0.6f))
                )
            }
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp)
                    .background(if (on) BrassBrush else SolidColor(Color.Transparent))
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (on) FontWeight.Medium else FontWeight.Normal),
                    color = when {
                        on -> Palette.OnBrass
                        dark -> Palette.NightText
                        else -> Palette.Leather
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Ledereinband mit Messingecken um ein Cover. */
@Composable
internal fun LeatherFrame(
    modifier: Modifier = Modifier,
    frame: Dp = 6.dp,
    corner: Dp = 15.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val night = LocalDarkTheme.current
    val shape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 5.dp, bottomEnd = 5.dp)
    Box(
        modifier
            .shadow(4.dp, shape, clip = false)
            .brassCorners(corner)
            .clip(shape)
            .leatherBackground(night)
            .drawBehind {
                // Buchrücken links etwas dunkler.
                drawRect(Color.Black.copy(alpha = 0.35f), size = Size(3.dp.toPx(), size.height))
            }
            .padding(frame),
        content = content,
    )
}

/** Lesefortschritt als rotes Lesebändchen mit Kerbe. */
@Composable
internal fun RibbonProgress(progress: Float, modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val track = if (dark) Palette.BrassLight.copy(alpha = 0.22f) else Palette.Saddle.copy(alpha = 0.3f)
    val ribbon = if (dark) Palette.SealLight else Palette.Seal
    val p = progress.coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f) }
    ) {
        val h = 5.dp.toPx()
        val y = (size.height - h) / 2f
        drawRoundRect(track, topLeft = Offset(0f, y), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2f))
        if (p <= 0f) return@Canvas
        val x = size.width * p
        drawRoundRect(ribbon, topLeft = Offset(0f, y), size = Size(x, h), cornerRadius = CornerRadius(h / 2f))
        val w = 8.dp.toPx()
        val left = (x - w / 2f).coerceIn(0f, size.width - w)
        val notch = Path().apply {
            moveTo(left, 0f)
            lineTo(left + w, 0f)
            lineTo(left + w, size.height)
            lineTo(left + w / 2f, size.height - 3.5.dp.toPx())
            lineTo(left, size.height)
            close()
        }
        drawPath(notch, ribbon)
    }
}

/** Messingleiste unter einer Lederleiste, mit Lilien-Medaillon in der Mitte. */
@Composable
internal fun BrassRule(modifier: Modifier = Modifier, medallion: Boolean = true) {
    val night = LocalDarkTheme.current
    val leather = if (night) Palette.LeatherNight else Palette.Leather
    Canvas(
        modifier
            .fillMaxWidth()
            .height(7.dp)
    ) {
        val band = size.height * 0.72f
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF8C6A2C),
                0.45f to Color(0xFFE9CB80),
                1f to Color(0xFF9C7633),
                endY = band,
            ),
            size = Size(size.width, band),
        )
        drawRect(leather, topLeft = Offset(0f, band), size = Size(size.width, size.height - band))
        if (medallion) {
            val r = 12.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(BrassBrush, radius = r + 1.5.dp.toPx(), center = center)
            drawCircle(leather, radius = r, center = center)
            val w = 13.dp.toPx()
            drawFleur(Offset(center.x - w / 2f, center.y - w * 0.6f), w, Palette.BrassLight)
        }
    }
}

/** Lederleiste oben mit Messingsymbolen und Messingleiste darunter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LeatherTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    medallion: Boolean = true,
) {
    val night = LocalDarkTheme.current
    Column(modifier) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = Palette.BrassLight,
                navigationIconContentColor = Palette.BrassLight,
                actionIconContentColor = Palette.BrassLight,
            ),
            modifier = Modifier.leatherBackground(night),
        )
        BrassRule(medallion = medallion)
    }
}

/**
 * Dialog als Pergamentblatt mit Doppelrahmen, Messingecken, Titel und Zierlinie.
 * Ersetzt den AlertDialog von Material.
 */
@Composable
internal fun OrnateDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalDarkTheme.current
    val line = if (dark) Palette.Brass else Palette.Saddle
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .shadow(18.dp, RectangleShape, clip = false)
                    .brassCorners(22.dp, overhang = 5.dp)
                    .parchmentBackground(dark)
                    .drawWithContent {
                        drawContent()
                        val outer = 4.dp.toPx()
                        val inner = 8.dp.toPx()
                        drawRect(
                            line,
                            topLeft = Offset(outer, outer),
                            size = Size(size.width - 2 * outer, size.height - 2 * outer),
                            style = Stroke(width = 1.6.dp.toPx()),
                        )
                        drawRect(
                            line.copy(alpha = 0.7f),
                            topLeft = Offset(inner, inner),
                            size = Size(size.width - 2 * inner, size.height - 2 * inner),
                            style = Stroke(width = 0.7.dp.toPx()),
                        )
                    }
                    .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    if (icon != null) {
                        icon()
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        title,
                        style = EasyBooksType.dialogTitle,
                        color = if (dark) Palette.BrassLight else Palette.Leather,
                        textAlign = TextAlign.Center,
                    )
                    FlourishDivider(line, Modifier.padding(top = 2.dp, bottom = 10.dp), width = 170.dp, height = 16.dp)
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth(),
                        content = content,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/** Bestätigen-Knopf in einem Dialog: Messingschild. */
@Composable
internal fun DialogConfirmButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Plaque(text = text, onClick = onClick, enabled = enabled, small = true)
}

/** Abbrechen-Knopf in einem Dialog: nur Schrift. */
@Composable
internal fun DialogDismissButton(text: String, onClick: () -> Unit) {
    val dark = LocalDarkTheme.current
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = if (dark) Palette.BrassLight else Palette.Saddle),
    ) { Text(text, style = EasyBooksType.plaqueSmall) }
}

@Composable
internal fun brassSliderColors() = SliderDefaults.colors(
    thumbColor = Palette.BrassLight,
    activeTrackColor = Palette.Brass,
    inactiveTrackColor = Palette.BrassLight.copy(alpha = 0.25f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

@Composable
internal fun brassSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Palette.BrassShine,
    checkedTrackColor = if (LocalDarkTheme.current) Palette.LeatherNightLight else Palette.Leather,
    checkedBorderColor = Palette.BrassDark,
    uncheckedThumbColor = if (LocalDarkTheme.current) Palette.NightMuted else Palette.Saddle,
    uncheckedTrackColor = Color.Transparent,
    uncheckedBorderColor = if (LocalDarkTheme.current) Palette.NightMuted else Palette.Saddle,
)

@Composable
internal fun parchmentFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)
