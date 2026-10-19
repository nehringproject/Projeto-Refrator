package dev.agentworkbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Attachment UI for chat bubbles: parsing of the attachment manifest that rides along with a user
 * message, a process-wide thumbnail loader (off the main thread, EXIF aware, LRU cached), the
 * thumbnail / gallery / file chip composables and a full-screen zoomable image viewer.
 *
 * Only platform APIs and Compose foundation are used. No network, no extra dependencies.
 */

// ---------------------------------------------------------------------------------------------
// Manifest parsing
// ---------------------------------------------------------------------------------------------

/**
 * Separates the typed user text from the list of attachments appended to the message. A message
 * with attachments looks like:
 *
 *     "<user text>" + ATTACHMENT_MANIFEST_MARKER + "- attachments/foo.jpg (image/jpeg)\n- ..."
 */
internal const val ATTACHMENT_MANIFEST_MARKER = "\n\nAnexos disponíveis no workspace:\n"

/** Result of [parseAttachmentManifest]: what the bubble should show as text, and the files. */
internal data class ParsedAttachments(val visibleText: String, val relativePaths: List<String>)

/** Placeholder text the app sends when the user only attaches files and does not type anything. */
private const val ANALYZE_ATTACHMENTS_PROMPT = "Analise os anexos enviados."

/**
 * Splits a stored user message into the visible text and the attachment paths it references.
 * When there is no marker the whole text is returned and the path list is empty. When the typed
 * text is only the automatic "analyse the attachments" prompt, the visible text is blank so the
 * bubble shows just the attachments.
 */
internal fun parseAttachmentManifest(text: String): ParsedAttachments {
    val markerAt = text.indexOf(ATTACHMENT_MANIFEST_MARKER)
    if (markerAt < 0) return ParsedAttachments(visibleText = text, relativePaths = emptyList())

    val userText = text.substring(0, markerAt)
    val paths = text.substring(markerAt + ATTACHMENT_MANIFEST_MARKER.length)
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("-") }
        .map { it.removePrefix("-").trim() }
        .map { stripMimeSuffix(it) }
        .filter { it.isNotEmpty() }
        .toList()

    val visible = if (paths.isNotEmpty() && userText.trim() == ANALYZE_ATTACHMENTS_PROMPT) "" else userText
    return ParsedAttachments(visibleText = visible, relativePaths = paths)
}

/** "attachments/foo.jpg (image/jpeg)" becomes "attachments/foo.jpg". Entries without a mime suffix pass through. */
private fun stripMimeSuffix(entry: String): String {
    if (!entry.endsWith(")")) return entry
    val open = entry.lastIndexOf(" (")
    return if (open > 0) entry.substring(0, open).trimEnd() else entry
}

private val attachmentImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")

/** True when the path's extension is one of the image types the bubbles know how to decode. */
internal fun isImagePath(path: String): Boolean {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in attachmentImageExtensions
}

// ---------------------------------------------------------------------------------------------
// Thumbnail loading and cache
// ---------------------------------------------------------------------------------------------

/** Pixels each cached bitmap may take is bounded by 1/8 of the app heap, shared across all callers. */
private val attachmentThumbCacheKb = ((Runtime.getRuntime().maxMemory() / 8L) / 1024L).toInt()

/** Process-wide cache of decoded bitmaps. Keys include path, last-modified time and target size. */
private val attachmentThumbCache: LruCache<String, ImageBitmap> =
    object : LruCache<String, ImageBitmap>(attachmentThumbCacheKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height.toLong() * 4L) / 1024L).coerceAtLeast(1L).toInt()
    }

private fun attachmentThumbKey(file: File, maxPx: Int): String =
    "${file.absolutePath}|${file.lastModified()}|$maxPx"

/**
 * Returns the decoded image for [file], scaled so its longest side is about [maxPx] pixels, or
 * null while it is loading or when decoding fails. Decoding runs on Dispatchers.IO; a cache hit
 * is returned synchronously so re-entering a list does not flash a placeholder.
 */
@Composable
internal fun rememberThumbnail(file: File, maxPx: Int): ImageBitmap? =
    rememberAttachmentThumbLoad(file, maxPx).bitmap

/** Loading outcome: [bitmap] when ready, [failed] when decoding was attempted and did not work. */
private class AttachmentThumbLoad(val bitmap: ImageBitmap?, val failed: Boolean)

@Composable
private fun rememberAttachmentThumbLoad(file: File, maxPx: Int): AttachmentThumbLoad {
    val safePx = maxPx.coerceAtLeast(1)
    val key = attachmentThumbKey(file, safePx)
    var load by remember(key) {
        mutableStateOf(attachmentThumbCache.get(key)?.let { AttachmentThumbLoad(it, failed = false) })
    }
    LaunchedEffect(key) {
        if (load == null) {
            val decoded = withContext(Dispatchers.IO) { loadAttachmentImage(file, safePx) }
            if (decoded != null) attachmentThumbCache.put(key, decoded)
            load = AttachmentThumbLoad(decoded, failed = decoded == null)
        }
    }
    return load ?: AttachmentThumbLoad(bitmap = null, failed = false)
}

/** Blocking decode; always call from a background thread. */
private fun loadAttachmentImage(file: File, maxPx: Int): ImageBitmap? =
    decodeAttachmentBitmap(file, maxPx)?.asImageBitmap()

/**
 * Decodes with BitmapFactory (two passes: bounds first, then a sampled decode) and applies EXIF
 * orientation. Falls back to ImageDecoder, which handles formats BitmapFactory may refuse (HEIC on
 * some devices).
 */
private fun decodeAttachmentBitmap(file: File, maxPx: Int): Bitmap? {
    if (!file.isFile) return null
    val path = file.absolutePath

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return decodeWithImageDecoder(file, maxPx)

    val options = BitmapFactory.Options().apply {
        inSampleSize = attachmentSampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeFile(path, options) ?: return decodeWithImageDecoder(file, maxPx)
    return applyExifOrientation(file, decoded)
}

/** Power-of-two sample size so the longest side stays at or above [maxPx]. */
private fun attachmentSampleSize(width: Int, height: Int, maxPx: Int): Int {
    val largest = max(width, height)
    var sample = 1
    while (largest / (sample * 2) >= maxPx) sample *= 2
    return sample
}

/** ImageDecoder fallback (minSdk is 28, so it is always present). It applies EXIF orientation itself. */
private fun decodeWithImageDecoder(file: File, maxPx: Int): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val largest = max(info.size.width, info.size.height)
        if (largest > maxPx) {
            val scale = maxPx.toFloat() / largest
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
} catch (e: Exception) {
    null
}

/**
 * Rotates or flips [source] according to the EXIF orientation tag of [file]. Uses
 * android.media.ExifInterface because androidx.exifinterface is not on the classpath.
 */
private fun applyExifOrientation(file: File, source: Bitmap): Bitmap {
    val orientation = try {
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> return source
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return source
    }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (rotated !== source) source.recycle()
    return rotated
}

// ---------------------------------------------------------------------------------------------
// Thumbnail and gallery composables
// ---------------------------------------------------------------------------------------------

/** Gallery shows at most this many tiles; the last one carries a "+N" overlay for the rest. */
private const val GALLERY_MAX_TILES = 4

/** Viewer images are decoded to about this many pixels on the longest side. */
private const val VIEWER_IMAGE_PX = 2048

/** Largest zoom factor the viewer allows. */
private const val MAX_VIEWER_SCALE = 5f

/** Zoom factor reached by a double tap when the photo is at 1x. */
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Square-ish thumbnail for one attachment: shimmer placeholder while loading, crossfade when the
 * image arrives, a small warning glyph when decoding fails. Use [onClick] to make it tappable with
 * a press-scale and ripple.
 */
@Composable
internal fun AttachmentThumb(
    file: File,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
) {
    val maxPx = with(LocalDensity.current) { (size * 2f).roundToPx() }
    AttachmentTile(
        file = file,
        maxPx = maxPx,
        modifier = modifier.size(size),
        corner = corner,
        contentScale = ContentScale.Crop,
        overflow = 0,
        onClick = onClick,
    )
}

/**
 * Images attached to one message, for chat bubbles. One image is a wide 4:3 tile; two sit side by
 * side; three or more form a 2-column grid of up to [GALLERY_MAX_TILES] tiles, with "+N" on the
 * last visible tile when more exist. [onOpen] receives the index into [files].
 */
@Composable
internal fun AttachmentGallery(
    files: List<File>,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 260.dp,
    onOpen: (index: Int) -> Unit,
) {
    if (files.isEmpty()) return
    val maxPx = with(LocalDensity.current) { (maxWidth * 2f).roundToPx() }
    val gap = 4.dp

    if (files.size == 1) {
        AttachmentTile(
            file = files[0],
            maxPx = maxPx,
            modifier = modifier.width(maxWidth).aspectRatio(4f / 3f),
            corner = 16.dp,
            contentScale = ContentScale.Crop,
            overflow = 0,
            onClick = { onOpen(0) },
        )
        return
    }

    val visible = files.take(GALLERY_MAX_TILES)
    val hidden = files.size - visible.size
    Column(
        modifier = modifier.width(maxWidth),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        visible.chunked(2).forEachIndexed { row, rowFiles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                rowFiles.forEachIndexed { col, file ->
                    val index = row * 2 + col
                    AttachmentTile(
                        file = file,
                        maxPx = maxPx,
                        modifier = Modifier.weight(1f).aspectRatio(1f),
                        corner = 12.dp,
                        contentScale = ContentScale.Crop,
                        overflow = if (index == visible.lastIndex) hidden else 0,
                        onClick = { onOpen(index) },
                    )
                }
                // A lone tile on the last row keeps the column width instead of stretching.
                if (rowFiles.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Shared tile used by [AttachmentThumb] and [AttachmentGallery]. The press-scale observes the
 * interaction source only, so the clickable underneath still receives taps and nothing is consumed.
 */
@Composable
private fun AttachmentTile(
    file: File,
    maxPx: Int,
    modifier: Modifier,
    corner: Dp,
    contentScale: ContentScale,
    overflow: Int,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(corner)
    val source = remember { MutableInteractionSource() }
    val load = rememberAttachmentThumbLoad(file, maxPx)
    val fade by animateFloatAsState(
        targetValue = if (load.bitmap != null) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "attachmentThumbFade",
    )

    val base = modifier.clip(shape).background(WorkbenchTokens.SurfaceHigh)
    val interactive = if (onClick != null) {
        base
            .pressScale(interactionSource = source, pressedScale = 0.96f)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
    } else {
        base
    }

    Box(modifier = interactive) {
        val bitmap = load.bitmap
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = file.name,
                contentScale = contentScale,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = fade },
            )
            load.failed -> Text(
                text = "⚠",
                color = WorkbenchTokens.TextFaint,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Box(modifier = Modifier.matchParentSize().shimmer())
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflow",
                    color = WorkbenchTokens.Text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Small pill with a document glyph and the file name, for attachments that are not images. The
 * name is ellipsised when it does not fit.
 */
@Composable
internal fun FileAttachmentChip(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(50))
            .background(WorkbenchTokens.SurfaceHigh)
            .border(1.dp, WorkbenchTokens.Border, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "📄", fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            color = WorkbenchTokens.Text,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Full-screen viewer
// ---------------------------------------------------------------------------------------------

/**
 * Full-screen, black viewer with horizontal paging between [files]. Starts at [startIndex], fades
 * in, shows a "n / total" counter and a close button. Pinch to zoom and drag to pan while zoomed;
 * double tap toggles between 1x and [DOUBLE_TAP_SCALE]. Swiping is left to the pager at 1x.
 */
@Composable
internal fun ImageViewerDialog(files: List<File>, startIndex: Int, onDismiss: () -> Unit) {
    if (files.isEmpty()) return

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val fade by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "imageViewerFade",
    )
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, files.lastIndex),
        pageCount = { files.size },
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade }
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AttachmentViewerPage(file = files[page], active = pagerState.currentPage == page)
            }

            // Counter centred, close button at the end. Sits over the pager; it does not consume touches.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${files.size}",
                    color = WorkbenchTokens.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(50))
                        .background(WorkbenchTokens.SurfaceHigh.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(WorkbenchTokens.SurfaceHigh)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✕", color = WorkbenchTokens.Text, fontSize = 16.sp)
                }
            }
        }
    }
}

/**
 * One page of the viewer. Zoom and pan state belong to this page and go back to 1x as soon as the
 * page is no longer the current one.
 */
@Composable
private fun AttachmentViewerPage(file: File, active: Boolean) {
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val load = rememberAttachmentThumbLoad(file, VIEWER_IMAGE_PX)

    LaunchedEffect(active) {
        if (!active) {
            zoomScale = 1f
            panOffset = Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            // Pinch and pan. The gesture is only consumed for a multi-touch pinch or while zoomed;
            // at 1x a one-finger drag is left alone so the HorizontalPager can change page.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val down = event.changes.any { it.pressed }
                        if (down) {
                            val pointers = event.changes.count { it.pressed }
                            if (pointers > 1 || zoomScale > 1f) {
                                val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                val zoomChange = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                val nextScale = (zoomScale * zoomChange).coerceIn(1f, MAX_VIEWER_SCALE)
                                val factor = nextScale / zoomScale
                                // Keep the point under the fingers fixed while zooming.
                                val pivot = if (centroid != Offset.Unspecified) centroid - center else Offset.Zero
                                panOffset = clampViewerPan(
                                    pivot * (1f - factor) + panOffset * factor + pan,
                                    nextScale,
                                    viewport,
                                )
                                zoomScale = nextScale
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (down)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (zoomScale > 1f) {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        } else {
                            val center = Offset(viewport.width / 2f, viewport.height / 2f)
                            zoomScale = DOUBLE_TAP_SCALE
                            panOffset = clampViewerPan(
                                (center - tap) * (DOUBLE_TAP_SCALE - 1f),
                                DOUBLE_TAP_SCALE,
                                viewport,
                            )
                        }
                    },
                )
            },
    ) {
        val bitmap = load.bitmap
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = panOffset.x
                        translationY = panOffset.y
                    },
            )
            load.failed -> Text(
                text = "Não foi possível abrir esta imagem",
                color = WorkbenchTokens.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
                color = WorkbenchTokens.TextMuted,
                strokeWidth = 2.dp,
            )
        }
    }
}

/** Limits the pan so the zoomed image cannot be dragged past its own edges. */
private fun clampViewerPan(offset: Offset, scale: Float, viewport: IntSize): Offset {
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}
