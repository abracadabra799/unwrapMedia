package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.update.UpdateChecker
import com.multiviewer.update.UpdateInfo
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Desktop
import java.io.File
import java.net.URI

enum class UpdateState {
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    ERROR,
}

@Composable
fun UpdateWindow(
    language: AppLanguage,
    onCloseRequest: () -> Unit,
) {
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(520.dp, 440.dp),
    )

    val appIcon = remember {
        try {
            val iconBytes = File("icons/app_source.png").takeIf { it.exists() }?.readBytes()
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("icons/app_source.png")?.readBytes()
            iconBytes?.let { BitmapPainter(SkiaImage.makeFromEncoded(it).toComposeImageBitmap()) }
        } catch (_: Exception) {
            null
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var currentState by remember { mutableStateOf(UpdateState.CHECKING) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(-1L) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var isDownloadCanceled by remember { mutableStateOf(false) }

    var updateConfig by remember { mutableStateOf(com.multiviewer.update.UpdateConfig.load()) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var inputRepoUrl by remember(isSettingsOpen) { mutableStateOf(updateConfig.repoUrl) }
    var inputApiToken by remember(isSettingsOpen) { mutableStateOf(updateConfig.apiToken) }

    fun checkUpdate() {
        currentState = UpdateState.CHECKING
        isSettingsOpen = false
        coroutineScope.launch {
            val result = UpdateChecker.checkForUpdates(I18n.APP_VERSION, updateConfig)
            result.onSuccess { info ->
                updateInfo = info
                currentState = if (info.hasUpdate) {
                    UpdateState.UPDATE_AVAILABLE
                } else {
                    UpdateState.UP_TO_DATE
                }
            }.onFailure { e ->
                errorMessage = e.localizedMessage ?: e.message ?: "Unknown error"
                currentState = UpdateState.ERROR
            }
        }
    }

    LaunchedEffect(Unit) {
        checkUpdate()
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = I18n.titleUpdateWindow(language),
        state = windowState,
        icon = appIcon,
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                onCloseRequest()
                true
            } else {
                false
            }
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AppColors.Background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                // Header section with Icon and Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (appIcon != null) {
                        Image(
                            painter = appIcon,
                            contentDescription = "unwrapMedia Logo",
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                    Column {
                        Text(
                            "unwrapMedia",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.NeonBlue,
                        )
                        Text(
                            I18n.titleUpdateWindow(language),
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { isSettingsOpen = !isSettingsOpen },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(if (isSettingsOpen) "닫기" else "⚙️ 저장소 설정", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Content body depending on state or settings
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSettingsOpen) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(AppColors.Surface, RoundedCornerShape(8.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
                                .padding(16.dp),
                        ) {
                            Text(
                                "사내 Git 업데이트 저장소 설정",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AppColors.TextPrimary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "사내 GitHub Enterprise, GitLab 또는 파일 릴리즈 저장소 URL을 입력하세요.",
                                fontSize = 11.sp,
                                color = AppColors.TextSecondary,
                            )
                            Spacer(Modifier.height(14.dp))

                            Text("저장소 주소 (Repository URL):", fontSize = 12.sp, color = AppColors.TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = inputRepoUrl,
                                onValueChange = { inputRepoUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("https://git.company.com/group/unwrapMedia", fontSize = 12.sp) },
                            )

                            Spacer(Modifier.height(10.dp))

                            Text("접근 토큰 (선택 사항, 비공개 저장소인 경우):", fontSize = 12.sp, color = AppColors.TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = inputApiToken,
                                onValueChange = { inputApiToken = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("Personal Access Token (glpat-..., ghp-...)", fontSize = 12.sp) },
                            )

                            Spacer(Modifier.weight(1f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = {
                                    val def = com.multiviewer.update.UpdateConfig.resetToDefault()
                                    updateConfig = def
                                    inputRepoUrl = def.repoUrl
                                    inputApiToken = def.apiToken
                                }) {
                                    Text("기본값 복원", fontSize = 12.sp, color = AppColors.TextSecondary)
                                }

                                Row {
                                    OutlinedButton(onClick = { isSettingsOpen = false }) {
                                        Text("취소", fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            val newCfg = com.multiviewer.update.UpdateConfig(
                                                repoUrl = inputRepoUrl.trim().ifEmpty { com.multiviewer.update.UpdateConfig.DEFAULT_REPO_URL },
                                                apiToken = inputApiToken.trim(),
                                            )
                                            com.multiviewer.update.UpdateConfig.save(newCfg)
                                            updateConfig = newCfg
                                            checkUpdate()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.NeonBlue),
                                    ) {
                                        Text("저장 및 다시 확인", fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    } else {
                        when (currentState) {
                        UpdateState.CHECKING -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AppColors.NeonBlue, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(16.dp))
                                Text(I18n.updateChecking(language), color = AppColors.TextPrimary, fontSize = 14.sp)
                            }
                        }

                        UpdateState.UP_TO_DATE -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Up to date",
                                    tint = AppColors.NeonGreen,
                                    modifier = Modifier.size(54.dp),
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    I18n.updateUpToDate(language),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.TextPrimary,
                                )
                            }
                        }

                        UpdateState.UPDATE_AVAILABLE -> {
                            val info = updateInfo
                            if (info != null) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // Version banner
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            I18n.updateAvailableHeader(language, info.latestVersion),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = AppColors.NeonGreen,
                                        )
                                        if (info.releaseDate.isNotEmpty()) {
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                info.releaseDate,
                                                fontSize = 11.sp,
                                                color = AppColors.TextSecondary,
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    // Release Notes Area
                                    Text(
                                        I18n.updateReleaseNotesTitle(language),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.TextSecondary,
                                    )
                                    Spacer(Modifier.height(6.dp))

                                    val scrollState = rememberScrollState()
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .background(AppColors.Surface, RoundedCornerShape(6.dp))
                                            .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                                            .padding(12.dp)
                                            .verticalScroll(scrollState),
                                    ) {
                                        Text(
                                            info.releaseNotes.ifEmpty { "최신 릴리즈 내용이 등록되지 않았습니다." },
                                            fontSize = 12.sp,
                                            color = AppColors.TextPrimary,
                                            fontFamily = FontFamily.SansSerif,
                                            lineHeight = 18.sp,
                                        )
                                    }
                                }
                            }
                        }

                        UpdateState.DOWNLOADING -> {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val percent = (downloadProgress * 100).toInt()
                                Text(
                                    I18n.updateDownloading(language, percent),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.TextPrimary,
                                )
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                    color = AppColors.NeonGreen,
                                    trackColor = AppColors.Border,
                                )
                                Spacer(Modifier.height(10.dp))
                                if (totalBytes > 0) {
                                    val mbRead = downloadedBytes.toDouble() / (1024 * 1024)
                                    val mbTotal = totalBytes.toDouble() / (1024 * 1024)
                                    Text(
                                        String.format("%.1f MB / %.1f MB", mbRead, mbTotal),
                                        fontSize = 12.sp,
                                        color = AppColors.TextSecondary,
                                    )
                                }
                            }
                        }

                        UpdateState.READY_TO_INSTALL -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Ready",
                                    tint = AppColors.NeonBlue,
                                    modifier = Modifier.size(48.dp),
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    I18n.updateDownloadComplete(language),
                                    fontSize = 13.sp,
                                    color = AppColors.TextPrimary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }

                        UpdateState.ERROR -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Error",
                                    tint = AppColors.NeonRed,
                                    modifier = Modifier.size(48.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    errorMessage.ifEmpty { "업데이트 확인 중 오류가 발생했습니다." },
                                    fontSize = 13.sp,
                                    color = AppColors.NeonRed,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

                Spacer(Modifier.height(16.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (currentState) {
                        UpdateState.CHECKING -> {
                            OutlinedButton(onClick = onCloseRequest) {
                                Text(I18n.updateBtnClose(language))
                            }
                        }

                        UpdateState.UP_TO_DATE -> {
                            Button(
                                onClick = onCloseRequest,
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.NeonBlue),
                            ) {
                                Text(I18n.updateBtnClose(language), color = Color.White)
                            }
                        }

                        UpdateState.UPDATE_AVAILABLE -> {
                            val info = updateInfo
                            if (info?.releaseUrl?.isNotEmpty() == true) {
                                TextButton(onClick = {
                                    try {
                                        Desktop.getDesktop().browse(URI.create(info.releaseUrl))
                                    } catch (_: Exception) {}
                                }) {
                                    Text(I18n.updateBtnViewOnWeb(language), fontSize = 12.sp, color = AppColors.TextSecondary)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = onCloseRequest) {
                                Text(I18n.updateBtnLater(language))
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val dlUrl = info?.downloadUrl
                                    if (dlUrl.isNullOrEmpty() || dlUrl.startsWith("http").not()) {
                                        // Web fallback if direct download URL is absent
                                        try {
                                            Desktop.getDesktop().browse(URI.create(info?.releaseUrl ?: UpdateChecker.getReleasesWebUrl(updateConfig)))
                                        } catch (_: Exception) {}
                                        onCloseRequest()
                                    } else {
                                        // Start direct download
                                        currentState = UpdateState.DOWNLOADING
                                        isDownloadCanceled = false
                                        val tempDir = File(System.getProperty("java.io.tmpdir"), "unwrapMediaUpdate")
                                        val targetFile = File(tempDir, info.downloadFileName ?: "unwrapMedia-update-installer")
                                        coroutineScope.launch {
                                            val dlResult = UpdateChecker.downloadInstaller(
                                                downloadUrl = dlUrl,
                                                targetFile = targetFile,
                                                config = updateConfig,
                                                onProgress = { p, read, total ->
                                                    downloadProgress = p
                                                    downloadedBytes = read
                                                    totalBytes = total
                                                },
                                                isCanceled = { isDownloadCanceled },
                                            )
                                            dlResult.onSuccess { file ->
                                                downloadedFile = file
                                                currentState = UpdateState.READY_TO_INSTALL
                                            }.onFailure { err ->
                                                if (err !is InterruptedException) {
                                                    errorMessage = err.localizedMessage ?: err.message ?: "Download failed"
                                                    currentState = UpdateState.ERROR
                                                }
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.NeonGreen),
                            ) {
                                Text(I18n.updateBtnDownload(language), color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }

                        UpdateState.DOWNLOADING -> {
                            OutlinedButton(onClick = {
                                isDownloadCanceled = true
                                currentState = UpdateState.UPDATE_AVAILABLE
                            }) {
                                Text("취소")
                            }
                        }

                        UpdateState.READY_TO_INSTALL -> {
                            Button(
                                onClick = {
                                    downloadedFile?.let { UpdateChecker.launchInstallerAndExit(it) }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.NeonGreen),
                            ) {
                                Text(I18n.updateBtnInstallAndRestart(language), color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }

                        UpdateState.ERROR -> {
                            OutlinedButton(onClick = onCloseRequest) {
                                Text(I18n.updateBtnClose(language))
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { checkUpdate() }) {
                                Text(I18n.updateBtnRetry(language))
                            }
                        }
                    }
                }

                if (!isSettingsOpen) {
                    Text(
                        "현재 업데이트 저장소: ${updateConfig.repoUrl}",
                        fontSize = 11.sp,
                        color = AppColors.TextSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
