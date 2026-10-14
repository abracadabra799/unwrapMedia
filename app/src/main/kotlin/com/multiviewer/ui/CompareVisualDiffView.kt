package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.multiviewer.parser.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import kotlin.math.floor

/** Keep the playback clock smooth without starting two ffmpeg processes for every 40 ms tick. */
internal fun comparisonPreviewTime(seconds: Double, playing: Boolean): Double =
    if (playing) floor(seconds.coerceAtLeast(0.0) * 4.0) / 4.0 else seconds

// -------------------------------------------------------------------------------------------------
// 3. Visual Diff View (Split Wiper, Side by Side, Difference Heatmap + Video Timeline Sync)
// -------------------------------------------------------------------------------------------------

@Composable
internal fun VisualDiffView(
    language: AppLanguage,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    quadInfos: List<CompareMediaInfo?> = emptyList(),
    captureMismatches: List<String>,
    activeSlot: CompareSlot = CompareSlot.SLOT_A,
    onSetActiveSlot: (CompareSlot) -> Unit = {},
) {
    if (quadInfos.size >= 4 && quadInfos[0] != null && quadInfos[1] != null && quadInfos[2] != null && quadInfos[3] != null) {
        val bitmaps = quadInfos.map { it?.bitmap }
        val labels = listOf("1 (A)", "2 (B)", "3 (C)", "4 (D)")
        QuadCompareView(
            bitmaps = bitmaps,
            infos = quadInfos,
            labels = labels,
            activeSlot = activeSlot,
            onSetActiveSlot = onSetActiveSlot,
            language = language,
        )
        return
    }

    if (infoA == null || infoB == null) {
        EmptyComparePlaceholder(language)
        return
    }

    val isVideoCompare = infoA.isVideo || infoB.isVideo
    val maxDuration = maxOf(infoA.durationSeconds, infoB.durationSeconds).coerceAtLeast(0.1)

    var mode by remember { mutableStateOf(VisualCompareMode.SPLIT_WIPER) }
    var wiperPos by remember { mutableStateOf(0.5f) }
    var currentPts by remember { mutableStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(false) }

    var frameBitmapA by remember(infoA.file) { mutableStateOf(infoA.bitmap) }
    var frameBitmapB by remember(infoB.file) { mutableStateOf(infoB.bitmap) }

    // Synchronized Video playback timer
    LaunchedEffect(isPlaying, maxDuration) {
        if (isPlaying) {
            while (true) {
                delay(40L)
                val nextPts = currentPts + 0.04
                if (nextPts >= maxDuration) {
                    currentPts = 0.0
                    isPlaying = false
                    break
                } else {
                    currentPts = nextPts
                }
            }
        }
    }

    // A slow decoder keeps at most one job per side and catches up to the latest sampled time.
    // Closing or changing files cancels the jobs and rejects late callbacks.
    val previewPts = comparisonPreviewTime(currentPts, isPlaying)
    val schedulerA = remember(infoA.file, infoA.isVideo) {
        if (infoA.isVideo) ComparisonFrameScheduler<ImageBitmap>(
            decode = { pts, done -> FrameFullSizeDecoder.decodeFrameAsync(infoA.file, pts, done) },
            publish = { bitmap -> if (bitmap != null) frameBitmapA = bitmap },
        ) else null
    }
    val schedulerB = remember(infoB.file, infoB.isVideo) {
        if (infoB.isVideo) ComparisonFrameScheduler<ImageBitmap>(
            decode = { pts, done -> FrameFullSizeDecoder.decodeFrameAsync(infoB.file, pts, done) },
            publish = { bitmap -> if (bitmap != null) frameBitmapB = bitmap },
        ) else null
    }
    DisposableEffect(schedulerA, schedulerB) {
        onDispose { schedulerA?.close(); schedulerB?.close() }
    }
    LaunchedEffect(previewPts, schedulerA, schedulerB) {
        schedulerA?.request(previewPts)
        schedulerB?.request(previewPts)
    }
    LaunchedEffect(infoA.bitmap, infoA.isVideo) { if (!infoA.isVideo) frameBitmapA = infoA.bitmap }
    LaunchedEffect(infoB.bitmap, infoB.isVideo) { if (!infoB.isVideo) frameBitmapB = infoB.bitmap }

    val displayBitmapA = frameBitmapA ?: infoA.bitmap
    val displayBitmapB = frameBitmapB ?: infoB.bitmap

    var metrics by remember(infoA.file, infoB.file) { mutableStateOf<StillImageQualityMetrics?>(null) }
    var metricsLoading by remember(infoA.file, infoB.file) { mutableStateOf(false) }
    var metricsFailed by remember(infoA.file, infoB.file) { mutableStateOf(false) }
    // Latched true once Diff Heatmap mode has actually been selected for this file pair, so the
    // PSNR/SSIM ffmpeg passes below don't run on every Visual-tab entry -- only when the mode that
    // actually needs them has been opened at least once.
    var metricsRequested by remember(infoA.file, infoB.file) { mutableStateOf(false) }

    // Side-effect, not an inline composition-time write: setting metricsRequested directly in the
    // `when (mode)` block below would be a backwards write (reading it via this LaunchedEffect's
    // key list in the same composition pass that wrote it).
    LaunchedEffect(mode, infoA.file, infoB.file) {
        if (mode == VisualCompareMode.DIFF_HEATMAP) metricsRequested = true
    }

    LaunchedEffect(infoA.file, infoB.file, isVideoCompare, metricsRequested) {
        if (isVideoCompare || !metricsRequested) return@LaunchedEffect
        metricsLoading = true
        metricsFailed = false
        val result = withContext(Dispatchers.IO) { computeStillImageQualityMetrics(infoA.file, infoB.file) { !isActive } }
        metrics = result
        metricsFailed = result == null
        metricsLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Mode Selector Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = mode == VisualCompareMode.SPLIT_WIPER,
                onClick = { mode = VisualCompareMode.SPLIT_WIPER },
                label = { Text(if (language == AppLanguage.KO) "좌우 분할 슬라이더 (Wiper)" else "Split Wiper") },
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = mode == VisualCompareMode.SIDE_BY_SIDE,
                onClick = { mode = VisualCompareMode.SIDE_BY_SIDE },
                label = { Text(if (language == AppLanguage.KO) "좌우 나란히 보기 (Side-by-Side)" else "Side by Side") },
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = mode == VisualCompareMode.DIFF_HEATMAP,
                onClick = { mode = VisualCompareMode.DIFF_HEATMAP },
                label = { Text(if (language == AppLanguage.KO) "차이점 마스크 (Diff Heatmap)" else "Diff Heatmap") },
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = mode == VisualCompareMode.BLINK,
                onClick = { mode = VisualCompareMode.BLINK },
                label = { Text(if (language == AppLanguage.KO) "깜빡임 비교 (Blink)" else "Blink / Flicker") },
            )
        }

        // Synchronized Video Timeline Controller (Displayed when video is present)
        if (isVideoCompare) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { isPlaying = !isPlaying }, modifier = Modifier.size(32.dp)) {
                        Text(if (isPlaying) "⏸" else "▶", fontSize = 16.sp)
                    }
                    IconButton(
                        onClick = { currentPts = (currentPts - 0.04).coerceAtLeast(0.0) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Text("◀", fontSize = 12.sp)
                    }
                    IconButton(
                        onClick = { currentPts = (currentPts + 0.04).coerceAtMost(maxDuration) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Text("▶", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Slider(
                        value = currentPts.toFloat(),
                        onValueChange = { currentPts = it.toDouble() },
                        valueRange = 0f..maxDuration.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "%02d:%05.2f / %02d:%05.2f".format((currentPts / 60).toInt(), currentPts % 60, (maxDuration / 60).toInt(), maxDuration % 60),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // View Area
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (displayBitmapA == null || displayBitmapB == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(if (language == AppLanguage.KO) "미디어 프레임 디코딩 중..." else "Decoding media frames...", fontSize = 12.sp, color = Color.White)
                }
            } else {
                when (mode) {
                    VisualCompareMode.SPLIT_WIPER -> {
                        WiperCanvas(displayBitmapA, displayBitmapB, wiperPos, onWiperChanged = { wiperPos = it })
                    }
                    VisualCompareMode.SIDE_BY_SIDE -> {
                        SideBySideCompareView(
                            bitmapA = displayBitmapA,
                            bitmapB = displayBitmapB,
                            infoA = infoA,
                            infoB = infoB,
                            labelA = if (infoA.isVideo) "Video A" else "Image A",
                            labelB = if (infoB.isVideo) "Video B" else "Image B",
                            activeSlot = activeSlot,
                            onSetActiveSlot = onSetActiveSlot,
                            language = language,
                        )
                    }
                    VisualCompareMode.BLINK -> {
                        BlinkCompareView(
                            bitmapA = displayBitmapA,
                            bitmapB = displayBitmapB,
                            infoA = infoA,
                            infoB = infoB,
                            labelA = if (infoA.isVideo) "Video A" else "Image A",
                            labelB = if (infoB.isVideo) "Video B" else "Image B",
                            language = language,
                        )
                    }
                    VisualCompareMode.DIFF_HEATMAP -> {
                        val diffBitmap = remember(displayBitmapA, displayBitmapB) { computeDiffBitmap(displayBitmapA, displayBitmapB) }

                        if (diffBitmap != null) {
                            Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Image(bitmap = diffBitmap, contentDescription = "Diff Heatmap", modifier = Modifier.fillMaxSize())
                                Column(
                                    modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.7f)).padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        if (language == AppLanguage.KO) "🔍 차이점 마스크 (변화가 있는 픽셀이 밝게 표시됨)" else "🔍 Diff Mask (Changed pixels highlighted)",
                                        color = Color.Yellow,
                                        fontSize = 11.sp,
                                    )
                                    if (!isVideoCompare) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val metricsText = when {
                                                metricsLoading -> if (language == AppLanguage.KO) "PSNR/SSIM 계산 중..." else "Computing PSNR/SSIM..."
                                                metricsFailed -> if (language == AppLanguage.KO) "PSNR/SSIM 계산 실패" else "PSNR/SSIM computation failed"
                                                metrics != null -> "PSNR: ${"%.2f".format(metrics!!.psnrDb)} dB | SSIM: ${"%.4f".format(metrics!!.ssim)}"
                                                else -> ""
                                            }
                                            if (metricsText.isNotEmpty()) {
                                                Text(metricsText, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            }
                                            if (captureMismatches.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    if (language == AppLanguage.KO) "⚠️ 촬영조건 다름" else "⚠️ Capture conditions differ",
                                                    color = Color(0xFFFFB74D),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaOsdBadge(
    label: String,
    info: CompareMediaInfo?,
    nativeSize: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.70f),
        shape = RoundedCornerShape(bottomEnd = 6.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (info != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = info.file.name,
                        color = Color(0xFF61AFEF),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val details = buildList {
                if (nativeSize != null && nativeSize.first > 0 && nativeSize.second > 0) {
                    add("${nativeSize.first}x${nativeSize.second}")
                }
                if (info != null && info.fileSize > 0) {
                    add("%.2f MB".format(info.fileSize / (1024.0 * 1024.0)))
                }
                val ext = info?.file?.extension?.uppercase()
                if (!ext.isNullOrBlank()) add(ext)
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" • "),
                    color = Color(0xFFABB2BF),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SideBySideCompareView(
    bitmapA: ImageBitmap,
    bitmapB: ImageBitmap,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    labelA: String,
    labelB: String,
    activeSlot: CompareSlot = CompareSlot.SLOT_A,
    onSetActiveSlot: (CompareSlot) -> Unit = {},
    language: AppLanguage,
) {
    var scale by remember(bitmapA, bitmapB) { mutableStateOf(1f) }
    var offset by remember(bitmapA, bitmapB) { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(Size.Zero) }
    var hoverNativePixel by remember(bitmapA, bitmapB) { mutableStateOf<Pair<Int, Int>?>(null) }
    // What ContentScale.Fit actually draws in each pane -- pan is bounded against this rather than
    // the pane-sized layer, so a letterboxed image can't be dragged out of view (see clampPanOffset).
    // The two panes share one scale/offset but can hold differently-shaped images, so each pane
    // clamps against its own fitted size.
    val fittedSizeA = fittedContentSize(paneSize, Size(bitmapA.width.toFloat(), bitmapA.height.toFloat()))
    val fittedSizeB = fittedContentSize(paneSize, Size(bitmapB.width.toFloat(), bitmapB.height.toFloat()))

    fun applyZoomPreset(targetScale: Float) {
        scale = targetScale
        offset = clampPanOffset(offset, paneSize, scale, fittedSizeA)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .clipToBounds(),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Pane A
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .clickable { onSetActiveSlot(CompareSlot.SLOT_A) }
                    .border(
                        if (activeSlot == CompareSlot.SLOT_A) 2.dp else 1.dp,
                        if (activeSlot == CompareSlot.SLOT_A) Color(0xFF61AFEF) else AppColors.Border.copy(alpha = 0.5f),
                        RoundedCornerShape(4.dp),
                    )
                    .onGloballyPositioned { paneSize = it.size.toSize() }
                    .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                        scale = newScale
                        offset = clampPanOffset(rawOffset, paneSize, newScale, fittedSizeA)
                        event.changes.forEach { it.consume() }
                    }
                    .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
                        val pos = event.changes.firstOrNull()?.position
                        hoverNativePixel = pos?.let {
                            screenPointToNativePixel(it, paneSize, Size(bitmapA.width.toFloat(), bitmapA.height.toFloat()), scale, offset)
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit, pass = PointerEventPass.Initial) {
                        hoverNativePixel = null
                    }
                    .pointerInput(bitmapA, bitmapB) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset = clampPanOffset(offset + dragAmount, paneSize, scale, fittedSizeA)
                        }
                    }
                    .pointerInput(bitmapA, bitmapB) {
                        detectTapGestures(
                            onTap = { tapPosition ->
                                offset = panToPoint(offset, paneSize, scale, tapPosition, fittedSizeA)
                            },
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    bitmap = bitmapA,
                    contentDescription = labelA,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
                    contentScale = ContentScale.Fit,
                )
                if (LocalShowPixelGrid.current) {
                    PixelGridOverlay(
                        nativeSize = Size(bitmapA.width.toFloat(), bitmapA.height.toFloat()),
                        scale = scale,
                        modifier = Modifier.graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
                    )
                }
                MediaOsdBadge(
                    label = labelA,
                    info = infoA,
                    nativeSize = bitmapA.width to bitmapA.height,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Pane B
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .clickable { onSetActiveSlot(CompareSlot.SLOT_B) }
                    .border(
                        if (activeSlot == CompareSlot.SLOT_B) 2.dp else 1.dp,
                        if (activeSlot == CompareSlot.SLOT_B) Color(0xFF98C379) else AppColors.Border.copy(alpha = 0.5f),
                        RoundedCornerShape(4.dp),
                    )
                    .onGloballyPositioned { paneSize = it.size.toSize() }
                    .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                        scale = newScale
                        offset = clampPanOffset(rawOffset, paneSize, newScale, fittedSizeB)
                        event.changes.forEach { it.consume() }
                    }
                    .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
                        val pos = event.changes.firstOrNull()?.position
                        hoverNativePixel = pos?.let {
                            screenPointToNativePixel(it, paneSize, Size(bitmapB.width.toFloat(), bitmapB.height.toFloat()), scale, offset)
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit, pass = PointerEventPass.Initial) {
                        hoverNativePixel = null
                    }
                    .pointerInput(bitmapA, bitmapB) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset = clampPanOffset(offset + dragAmount, paneSize, scale, fittedSizeB)
                        }
                    }
                    .pointerInput(bitmapA, bitmapB) {
                        detectTapGestures(
                            onTap = { tapPosition ->
                                offset = panToPoint(offset, paneSize, scale, tapPosition, fittedSizeB)
                            },
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    bitmap = bitmapB,
                    contentDescription = labelB,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
                    contentScale = ContentScale.Fit,
                )
                if (LocalShowPixelGrid.current) {
                    PixelGridOverlay(
                        nativeSize = Size(bitmapB.width.toFloat(), bitmapB.height.toFloat()),
                        scale = scale,
                        modifier = Modifier.graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
                    )
                }
                MediaOsdBadge(
                    label = labelB,
                    info = infoB,
                    nativeSize = bitmapB.width to bitmapB.height,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }

        hoverNativePixel?.let { (nx, ny) ->
            val skiaA = bitmapA.asSkiaBitmap()
            val skiaB = bitmapB.asSkiaBitmap()
            val colorA = if (nx < skiaA.width && ny < skiaA.height) skiaA.getColor(nx, ny) else null
            val colorB = if (nx < skiaB.width && ny < skiaB.height) skiaB.getColor(nx, ny) else null
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Text(
                    buildString {
                        append("(%d, %d)  ".format(nx, ny))
                        colorA?.let { append("A: #%06X  ".format(it and 0xFFFFFF)) }
                        colorB?.let { append("B: #%06X".format(it and 0xFFFFFF)) }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // FastStone Style Zoom Control Toolbar (Fit, 100%, 200%, 400%)
        Surface(
            color = Color.Black.copy(alpha = 0.80f),
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🔍 %.1fx".format(scale),
                    fontSize = 11.sp,
                    color = Color.Yellow,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp),
                )
                val presets = listOf("Fit" to 1f, "100%" to 1f, "200%" to 2f, "400%" to 4f)
                presets.forEach { (text, s) ->
                    Text(
                        text = text,
                        fontSize = 10.sp,
                        color = if (scale == s && text != "Fit") Color.Yellow else Color.White,
                        modifier = Modifier
                            .clickable {
                                if (text == "Fit") {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    applyZoomPreset(s)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * FastStone-style Blink / Flicker comparison view that rapidly or manually alternates
 * between Bitmap A and Bitmap B to expose minute visual compression/alignment differences.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BlinkCompareView(
    bitmapA: ImageBitmap,
    bitmapB: ImageBitmap,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    labelA: String,
    labelB: String,
    language: AppLanguage,
) {
    var showA by remember { mutableStateOf(true) }
    var autoBlink by remember { mutableStateOf(false) }
    var blinkSpeedMs by remember { mutableStateOf(300L) }
    var scale by remember(bitmapA, bitmapB) { mutableStateOf(1f) }
    var offset by remember(bitmapA, bitmapB) { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(Size.Zero) }

    val currentBitmap = if (showA) bitmapA else bitmapB
    val currentInfo = if (showA) infoA else infoB
    val currentLabel = if (showA) labelA else labelB

    val fittedSize = fittedContentSize(paneSize, Size(currentBitmap.width.toFloat(), currentBitmap.height.toFloat()))

    LaunchedEffect(autoBlink, blinkSpeedMs) {
        while (autoBlink) {
            delay(blinkSpeedMs)
            showA = !showA
        }
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        // Blink Control Toolbar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { showA = !showA },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showA) Color(0xFF61AFEF) else Color(0xFF98C379),
                    ),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(
                        text = if (showA) "현재: [A] 전환(Space)" else "현재: [B] 전환(Space)",
                        fontSize = 11.sp,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.width(12.dp))

                FilterChip(
                    selected = autoBlink,
                    onClick = { autoBlink = !autoBlink },
                    label = { Text(if (autoBlink) "⏹ 자동 깜빡임 정지" else "▶ 자동 깜빡임 시작") },
                    modifier = Modifier.height(32.dp),
                )

                if (autoBlink) {
                    Spacer(Modifier.width(16.dp))
                    Text("주기: ${blinkSpeedMs}ms", fontSize = 11.sp, color = AppColors.TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = blinkSpeedMs.toFloat(),
                        onValueChange = { blinkSpeedMs = it.toLong() },
                        valueRange = 100f..1000f,
                        modifier = Modifier.width(150.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = if (language == AppLanguage.KO) "💡 스페이스바를 눌러 수동 교차 비교 가능" else "💡 Tap Spacebar to toggle A/B",
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary,
                )
            }
        }

        // View Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Spacebar) {
                        showA = !showA
                        true
                    } else false
                }
                .onGloballyPositioned { paneSize = it.size.toSize() }
                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                    scale = newScale
                    offset = clampPanOffset(rawOffset, paneSize, newScale, fittedSize)
                    event.changes.forEach { it.consume() }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offset = clampPanOffset(offset + dragAmount, paneSize, scale, fittedSize)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                bitmap = currentBitmap,
                contentDescription = currentLabel,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                        transformOrigin = TransformOrigin(0f, 0f),
                    ),
                contentScale = ContentScale.Fit,
            )

            if (LocalShowPixelGrid.current) {
                PixelGridOverlay(
                    nativeSize = Size(currentBitmap.width.toFloat(), currentBitmap.height.toFloat()),
                    scale = scale,
                    modifier = Modifier.graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                        transformOrigin = TransformOrigin(0f, 0f),
                    ),
                )
            }

            MediaOsdBadge(
                label = currentLabel,
                info = currentInfo,
                nativeSize = currentBitmap.width to currentBitmap.height,
                modifier = Modifier.align(Alignment.TopStart),
            )

            // Zoom Presets
            Surface(
                color = Color.Black.copy(alpha = 0.80f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🔍 %.1fx".format(scale),
                        fontSize = 11.sp,
                        color = Color.Yellow,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    val presets = listOf("Fit" to 1f, "100%" to 1f, "200%" to 2f, "400%" to 4f)
                    presets.forEach { (text, s) ->
                        Text(
                            text = text,
                            fontSize = 10.sp,
                            color = if (scale == s && text != "Fit") Color.Yellow else Color.White,
                            modifier = Modifier
                                .clickable {
                                    if (text == "Fit") {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = s
                                        offset = clampPanOffset(offset, paneSize, scale, fittedSize)
                                    }
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * FastStone-style Quad Compare View (4-split 2x2 grid) with synchronized zoom,
 * pan, native pixel inspector, and timeline playback.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QuadCompareView(
    bitmaps: List<ImageBitmap?>,
    infos: List<CompareMediaInfo?>,
    labels: List<String>,
    activeSlot: CompareSlot,
    onSetActiveSlot: (CompareSlot) -> Unit,
    language: AppLanguage,
) {
    val nonNullBitmaps = bitmaps.filterNotNull()
    var scale by remember(nonNullBitmaps) { mutableStateOf(1f) }
    var offset by remember(nonNullBitmaps) { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(Size.Zero) }
    var hoverNativePixel by remember(nonNullBitmaps) { mutableStateOf<Pair<Int, Int>?>(null) }

    val fittedSizes = bitmaps.map { bm ->
        if (bm != null) fittedContentSize(paneSize, Size(bm.width.toFloat(), bm.height.toFloat())) else paneSize
    }
    val primaryFittedSize = fittedSizes.firstOrNull() ?: paneSize

    fun applyZoomPreset(targetScale: Float) {
        scale = targetScale
        offset = clampPanOffset(offset, paneSize, scale, primaryFittedSize)
    }

    val slots = listOf(CompareSlot.SLOT_A, CompareSlot.SLOT_B, CompareSlot.SLOT_C, CompareSlot.SLOT_D)
    val slotColors = listOf(Color(0xFF61AFEF), Color(0xFF98C379), Color(0xFFE5C07B), Color(0xFFE06C75))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .clipToBounds(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            for (row in 0..1) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0..1) {
                        val index = row * 2 + col
                        val slot = slots.getOrElse(index) { CompareSlot.SLOT_A }
                        val bitmap = bitmaps.getOrNull(index)
                        val info = infos.getOrNull(index)
                        val label = labels.getOrElse(index) { "Media ${index + 1}" }
                        val slotColor = slotColors.getOrElse(index) { Color.Cyan }
                        val fitted = fittedSizes.getOrElse(index) { paneSize }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clipToBounds()
                                .clickable { onSetActiveSlot(slot) }
                                .border(
                                    if (activeSlot == slot) 2.dp else 1.dp,
                                    if (activeSlot == slot) slotColor else AppColors.Border.copy(alpha = 0.5f),
                                    RoundedCornerShape(4.dp),
                                )
                                .onGloballyPositioned { paneSize = it.size.toSize() }
                                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                                    val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                                    scale = newScale
                                    offset = clampPanOffset(rawOffset, paneSize, newScale, fitted)
                                    event.changes.forEach { it.consume() }
                                }
                                .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
                                    val pos = event.changes.firstOrNull()?.position
                                    if (bitmap != null && pos != null) {
                                        hoverNativePixel = screenPointToNativePixel(pos, paneSize, Size(bitmap.width.toFloat(), bitmap.height.toFloat()), scale, offset)
                                    }
                                }
                                .onPointerEvent(PointerEventType.Exit, pass = PointerEventPass.Initial) {
                                    hoverNativePixel = null
                                }
                                .pointerInput(bitmap) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        offset = clampPanOffset(offset + dragAmount, paneSize, scale, fitted)
                                    }
                                }
                                .pointerInput(bitmap) {
                                    detectTapGestures(
                                        onTap = { tapPosition ->
                                            offset = panToPoint(offset, paneSize, scale, tapPosition, fitted)
                                        },
                                        onDoubleTap = {
                                            scale = 1f
                                            offset = Offset.Zero
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap,
                                    contentDescription = label,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offset.x,
                                            translationY = offset.y,
                                            transformOrigin = TransformOrigin(0f, 0f),
                                        ),
                                    contentScale = ContentScale.Fit,
                                )
                                if (LocalShowPixelGrid.current) {
                                    PixelGridOverlay(
                                        nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                                        scale = scale,
                                        modifier = Modifier.graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offset.x,
                                            translationY = offset.y,
                                            transformOrigin = TransformOrigin(0f, 0f),
                                        ),
                                    )
                                }
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = slotColor, modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = info?.file?.name ?: if (language == AppLanguage.KO) "로딩 중..." else "Loading...",
                                        fontSize = 11.sp,
                                        color = AppColors.TextSecondary,
                                    )
                                }
                            }

                            MediaOsdBadge(
                                label = label,
                                info = info,
                                nativeSize = bitmap?.let { it.width to it.height },
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                        }

                        if (col == 0) {
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
                if (row == 0) {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        hoverNativePixel?.let { (nx, ny) ->
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Text(
                    buildString {
                        append("(%d, %d)  ".format(nx, ny))
                        bitmaps.forEachIndexed { i, bm ->
                            if (bm != null) {
                                val skia = bm.asSkiaBitmap()
                                val color = if (nx < skia.width && ny < skia.height) skia.getColor(nx, ny) else null
                                val slotLetter = listOf("A", "B", "C", "D").getOrElse(i) { "${i+1}" }
                                color?.let { append("%s: #%06X  ".format(slotLetter, it and 0xFFFFFF)) }
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // FastStone Style Zoom Toolbar
        Surface(
            color = Color.Black.copy(alpha = 0.80f),
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🔍 %.1fx".format(scale),
                    fontSize = 11.sp,
                    color = Color.Yellow,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp),
                )
                val presets = listOf("Fit" to 1f, "100%" to 1f, "200%" to 2f, "400%" to 4f)
                presets.forEach { (text, s) ->
                    Text(
                        text = text,
                        fontSize = 10.sp,
                        color = if (scale == s && text != "Fit") Color.Yellow else Color.White,
                        modifier = Modifier
                            .clickable {
                                if (text == "Fit") {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    applyZoomPreset(s)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WiperCanvas(
    bitmapA: ImageBitmap,
    bitmapB: ImageBitmap,
    wiperPos: Float,
    onWiperChanged: (Float) -> Unit,
) {
    Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures { change, _ ->
                val newPos = (change.position.x / size.width).coerceIn(0f, 1f)
                onWiperChanged(newPos)
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val splitX = w * wiperPos

        drawImage(bitmapB, dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()))

        clipRect(left = 0f, top = 0f, right = splitX, bottom = h) {
            drawImage(bitmapA, dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()))
        }

        drawLine(
            color = Color.Cyan,
            start = Offset(splitX, 0f),
            end = Offset(splitX, h),
            strokeWidth = 2.5f,
        )

        drawCircle(
            color = Color.Cyan,
            radius = 12f,
            center = Offset(splitX, h / 2f),
        )
        drawCircle(
            color = Color.Black,
            radius = 6f,
            center = Offset(splitX, h / 2f),
        )
    }
}

// Maps a pointer position (in box-local coordinates -- the same space Compose pointer events
// already report relative to the Box they're attached to) through the fitted-content + user-zoom/
// pan transform this view's graphicsLayer applies, down to a native pixel coordinate in the
// displayed bitmap -- or null if the pointer is outside the drawn image (a letterbox bar, or an
// unmeasured box). Uses the same (pointerPos - offset) / scale inversion `panToPoint`
// (PixelInspectorPreview.kt) already establishes to recover a pre-zoom, box-local content point;
// this additionally subtracts the letterbox origin and divides by fitScale to reach native pixels.
// Hand-verified: at scale=1/offset=Zero with no letterboxing this is the identity mapping; with a
// 200x100 image letterboxed into a 100x100 box, the box center (50,50) correctly resolves to the
// native center (100,50), and a point in the letterbox margin resolves to null; at scale=2 with an
// arbitrary pan (offset=(-50,-25)), pointer (60,40) resolves to native (110,15), and forward-
// transforming (110,15) through the same formula (screenX = offset.x + scale*(letterboxX0 +
// nativeX*fitScale)) returns exactly (60,40), confirming the inversion round-trips correctly.
internal fun screenPointToNativePixel(pointerPos: Offset, boxSize: Size, nativeSize: Size, scale: Float, offset: Offset): Pair<Int, Int>? {
    if (nativeSize.width <= 0f || nativeSize.height <= 0f || boxSize.width <= 0f || boxSize.height <= 0f) return null
    val contentSize = fittedContentSize(boxSize, nativeSize)
    val fitScale = contentSize.width / nativeSize.width
    if (fitScale <= 0f) return null
    val letterboxX0 = (boxSize.width - contentSize.width) / 2f
    val letterboxY0 = (boxSize.height - contentSize.height) / 2f
    val lx = (pointerPos.x - offset.x) / scale
    val ly = (pointerPos.y - offset.y) / scale
    val nativeX = ((lx - letterboxX0) / fitScale).toInt()
    val nativeY = ((ly - letterboxY0) / fitScale).toInt()
    if (nativeX < 0 || nativeX >= nativeSize.width.toInt() || nativeY < 0 || nativeY >= nativeSize.height.toInt()) return null
    return nativeX to nativeY
}

private fun computeDiffBitmap(bmA: ImageBitmap, bmB: ImageBitmap): ImageBitmap? {
    return try {
        val skiaA = bmA.asSkiaBitmap()
        val skiaB = bmB.asSkiaBitmap()
        val w = minOf(skiaA.width, skiaB.width)
        val h = minOf(skiaA.height, skiaB.height)

        val bufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pA = skiaA.getColor(x, y)
                val pB = skiaB.getColor(x, y)

                val rA = (pA shr 16) and 0xFF
                val gA = (pA shr 8) and 0xFF
                val bA = pA and 0xFF

                val rB = (pB shr 16) and 0xFF
                val gB = (pB shr 8) and 0xFF
                val bB = pB and 0xFF

                val diffR = Math.abs(rA - rB)
                val diffG = Math.abs(gA - gB)
                val diffB = Math.abs(bA - bB)
                val totalDiff = minOf(255, (diffR + diffG + diffB) * 3)

                val color = if (totalDiff > 0) {
                    (0xFF shl 24) or (totalDiff shl 16) or ((totalDiff / 2) shl 8) or 0x00
                } else {
                    (0xFF shl 24) or 0x101010
                }
                bufferedImage.setRGB(x, y, color)
            }
        }
        bufferedImage.toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}
