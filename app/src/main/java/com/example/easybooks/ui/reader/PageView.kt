package com.example.easybooks.ui.reader

import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.easybooks.R
import com.example.easybooks.data.PageTurn
import com.example.easybooks.data.ZoomMode
import com.example.easybooks.pdf.Crop
import com.example.easybooks.ui.theme.DarkReaderColors
import com.example.easybooks.ui.theme.LocalReaderColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PageHPadding = 12.dp
internal val PageHPaddingClose = 2.dp
private val PageVPadding = 16.dp
internal val ReflowVPadding = 10.dp

/** Andere Papierflecken für neu umbrochene Seiten als für PDF-Seiten mit gleicher Nummer. */
private const val REFLOW_SEED = 50021

/**
 * Eine einzelne PDF-Seite im Reader (Schriftgröße Klein oder Normal).
 *
 * Links und rechts wird nie etwas abgeschnitten: Die Seite ist höchstens so breit
 * wie der Bildschirm. Ist sie dadurch höher als der Bildschirm, wird vertikal gescrollt.
 *
 * @param pageOffset Abstand dieser Seite zur aktuell sichtbaren Position im Pager:
 *   0 = sichtbar, > 0 = liegt links (schon umgeblättert), < 0 = liegt rechts (kommt noch).
 * @param darkPages PDF-Seite dunkel darstellen (Dark Mode).
 * @param grabRootY Höhe, an der der Finger zuletzt aufgesetzt hat (Bildschirmkoordinaten, NaN = unbekannt).
 *   Beim Umblättern hebt sich die Seite zuerst an dieser Stelle.
 * @param onTap Tippen mit x-Position und Breite der Fläche (für die Blätter-Zonen).
 */
@Composable
internal fun PageView(
    vm: ReaderViewModel,
    page: Int,
    pageCount: Int,
    zoom: ZoomMode,
    pageTurn: PageTurn,
    darkPages: Boolean,
    pageOffset: () -> Float,
    grabRootY: () -> Float,
    onTap: (x: Float, width: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    PageBox(page, pageTurn, pageOffset, onTap, modifier) {
        val pageSize by produceState<Size?>(null, page) { value = vm.pageSize(page) }
        val crop = rememberResult(page, zoom) {
            when (zoom) {
                ZoomMode.WIDE -> Crop.FULL
                // "Normal": Ränder ganz weg, der Text füllt die Breite. ("Groß" wird neu
                // umbrochen und läuft über ReflowPageView.)
                ZoomMode.NORMAL, ZoomMode.CLOSE -> vm.pageCrop(page, tight = true)
            }
        }
        // Seitengröße der PDF in Punkten. Bewusst nicht "size" genannt: In den Zeichen-
        // Lambdas meint "size" die Fläche in Pixeln (DrawScope.size).
        val pdfSize = pageSize
        if (pdfSize == null || crop == null) return@PageBox

        // Seitenverhältnis des sichtbaren Ausschnitts.
        val aspect = (pdfSize.height * crop.height) / (pdfSize.width * crop.width)
        // Ohne Ränder zählt jeder Millimeter, dort ist der Abstand zum Bildschirmrand schmaler.
        val hPadding = if (zoom == ZoomMode.WIDE) PageHPadding else PageHPaddingClose
        val availableWidth = maxWidth - hPadding * 2
        val availableHeight = maxHeight - PageVPadding * 2
        val width = when (zoom) {
            ZoomMode.WIDE -> minOf(availableWidth, availableHeight / aspect)
            ZoomMode.NORMAL, ZoomMode.CLOSE -> availableWidth
        }
        val height = width * aspect
        val scrollable = height > availableHeight + 1.dp

        val widthPx = with(LocalDensity.current) { width.roundToPx() }
        val aged = LocalReaderColors.current.aged
        // Im Dark Mode und auf altem Papier erst wissen, ob es eine Bildseite ist, damit nichts aufblitzt.
        val imagePage = if (darkPages || aged) rememberResult(page, "image") { vm.isImagePage(page) } else false
        val bitmap = rememberResult(page, widthPx, crop, aged, darkPages, imagePage) {
            val raw = vm.renderPage(page, widthPx, crop)
            if (aged && imagePage != null) {
                withContext(Dispatchers.Default) { AgedPaper.print(raw, seed = page, dark = darkPages, picture = imagePage) }
            } else raw
        }

        Sheet(
            page = page,
            width = width,
            height = height,
            bitmap = bitmap,
            imagePage = imagePage,
            readFraction = if (pageCount > 1) page / (pageCount - 1f) else 0f,
            pageTurn = pageTurn,
            darkPages = darkPages,
            pageOffset = pageOffset,
            grabRootY = grabRootY,
            scrollable = scrollable,
        )
    }
}

/**
 * Eine neu umbrochene Bildschirmseite (Schriftgröße Groß). Sie füllt den Bildschirm,
 * gescrollt wird nie.
 */
@Composable
internal fun ReflowPageView(
    vm: ReaderViewModel,
    layout: ReflowLayout,
    index: Int,
    pageTurn: PageTurn,
    darkPages: Boolean,
    pageOffset: () -> Float,
    grabRootY: () -> Float,
    onTap: (x: Float, width: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    PageBox(index, pageTurn, pageOffset, onTap, modifier) {
        val frame = layout.params.frame
        val density = LocalDensity.current
        val screenPage = layout.pages.getOrNull(index)
        val imageOnly = screenPage?.imageOnly == true
        val aged = LocalReaderColors.current.aged
        val bitmap = rememberResult(layout.params, index, aged, darkPages) {
            val raw = vm.renderReflowPage(layout, index)
            if (aged && raw != null) {
                withContext(Dispatchers.Default) {
                    AgedPaper.print(raw, seed = REFLOW_SEED + index, dark = darkPages, picture = imageOnly)
                }
            } else raw
        }
        // Ganzseitige Bilder (z. B. Cover) liegen auf einem Blatt in ihrer eigenen Größe.
        val image = screenPage?.placements?.singleOrNull()?.takeIf { imageOnly }
        val count = layout.estimatedCount
        Sheet(
            page = index,
            width = with(density) { (image?.dstWidth ?: frame.width.toFloat()).toDp() },
            height = with(density) { (image?.dstHeight ?: frame.height.toFloat()).toDp() },
            bitmap = bitmap,
            imagePage = if (darkPages || aged) imageOnly else false,
            readFraction = if (count > 1) (index / (count - 1f)).coerceAtMost(1f) else 0f,
            pageTurn = pageTurn,
            darkPages = darkPages,
            pageOffset = pageOffset,
            grabRootY = grabRootY,
            scrollable = false,
        )
    }
}

/** Fläche einer Seite im Pager: Blätter-Zonen, Buch-Modus und Abstand zur Kamera-Aussparung. */
@Composable
private fun PageBox(
    page: Int,
    pageTurn: PageTurn,
    pageOffset: () -> Float,
    onTap: (x: Float, width: Float) -> Unit,
    modifier: Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val curl = pageTurn == PageTurn.CURL
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .then(if (curl) Modifier.curlTransform(page, pageOffset) else Modifier)
            .pointerInput(Unit) {
                detectTapGestures { offset -> currentOnTap(offset.x, size.width.toFloat()) }
            }
            .windowInsetsPadding(WindowInsets.displayCutout),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Die Seite als Blatt Papier; während des Umblätterns als gebogenes 3D-Blatt.
 *
 * @param imagePage `null`, solange noch unklar ist, ob die Seite ein Bild ist (Dark Mode)
 */
@Composable
private fun Sheet(
    page: Int,
    width: Dp,
    height: Dp,
    bitmap: ImageBitmap?,
    imagePage: Boolean?,
    readFraction: Float,
    pageTurn: PageTurn,
    darkPages: Boolean,
    pageOffset: () -> Float,
    grabRootY: () -> Float,
    scrollable: Boolean,
) {
    val curl = pageTurn == PageTurn.CURL
    val readerColors = LocalReaderColors.current
    val paperColor = readerColors.paper
    val nightFilter = remember { PageColors.night(DarkReaderColors.paper, DarkReaderColors.ink) }
    val shadowColor = readerColors.shade
    // Nur während die Seite gerade umgeblättert wird, zeichnen wir sie als gebogenes 3D-Blatt.
    val turning by remember(curl) {
        derivedStateOf { curl && pageOffset().let { it > 0f && it < 1f } }
    }
    val colorFilter = when {
        // Altes Papier: Die Seite ist schon auf das Papier gedruckt.
        readerColors.aged -> null
        !darkPages -> PageColors.day
        imagePage == true -> PageColors.dimmedImage
        else -> nightFilter
    }

    val paperTop = remember { FloatArray(1) }
    val screenHeight = LocalView.current.height.toFloat()
    val paper: @Composable () -> Unit = {
        if (turning) {
            Canvas(
                Modifier
                    .size(width, height)
                    .onGloballyPositioned { paperTop[0] = it.positionInRoot().y }
            ) {
                val top = paperTop[0]
                // Bei hohen Seiten ist nur ein Teil sichtbar. Griff und Perspektive
                // beziehen sich auf diesen sichtbaren Teil.
                val visibleTop = top.coerceAtLeast(0f)
                val visibleBottom = (top + size.height).coerceAtMost(screenHeight).coerceAtLeast(visibleTop + 1f)
                val y = grabRootY()
                val grabY = if (y.isNaN()) (visibleTop + visibleBottom) / 2f else y.coerceIn(visibleTop, visibleBottom)
                drawCurledPage(
                    progress = pageOffset(),
                    grab = ((grabY - visibleTop) / (visibleBottom - visibleTop)).coerceIn(0f, 1f),
                    visibleTop = visibleTop - top,
                    visibleBottom = visibleBottom - top,
                    bitmap = bitmap.takeIf { imagePage != null },
                    colorFilter = colorFilter,
                    paperColor = paperColor,
                    shadowColor = shadowColor,
                )
            }
        } else Paper(
            paperColor = paperColor,
            darkPaper = darkPages,
            readFraction = readFraction,
            dim = {
                // Die Seite darunter ist dunkler, solange sie noch bedeckt ist.
                val offset = pageOffset()
                if (curl && offset < 0f && offset > -1f) -offset else 0f
            },
            modifier = Modifier.size(width, height),
        ) {
            if (bitmap != null && imagePage != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.page_description, page + 1),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = colorFilter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (scrollable) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = PageVPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { paper() }
    } else {
        paper()
    }
}

/**
 * Lädt einen Wert im Hintergrund und liefert ihn nur, solange er zu den aktuellen
 * [keys] gehört. So wird beim Wechsel des Zooms kein veraltetes Bild verzerrt angezeigt.
 */
@Composable
private fun <T> rememberResult(vararg keys: Any?, load: suspend () -> T): T? {
    val keyList = keys.toList()
    val result by produceState<Pair<List<Any?>, T>?>(null, *keys) { value = keyList to load() }
    return result?.takeIf { it.first == keyList }?.second
}

/**
 * Buch-Modus: Alle Seiten bleiben an ihrem Platz liegen, statt mit dem Pager zur Seite
 * zu gleiten. Seiten mit kleinerer Nummer liegen oben. Die oberste Seite wird während
 * des Wischens als gebogenes 3D-Blatt gezeichnet (siehe [drawCurledPage]).
 */
private fun Modifier.curlTransform(page: Int, pageOffset: () -> Float): Modifier =
    zIndex(-page.toFloat()).graphicsLayer {
        val offset = pageOffset()
        // Natürliche Position wäre um offset Seitenbreiten verschoben – das heben wir auf.
        translationX = offset * size.width
        // Nur die beiden beteiligten Seiten sind sichtbar.
        alpha = if (offset <= -1f || offset >= 1f) 0f else 1f
    }
