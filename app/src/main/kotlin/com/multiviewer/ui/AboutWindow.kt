package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import org.jetbrains.skia.Image as SkiaImage
import java.io.File

@Composable
fun AboutWindow(
    language: AppLanguage,
    onCloseRequest: () -> Unit,
    onCheckUpdate: (() -> Unit)? = null,
) {
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(560.dp, 620.dp),
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

    Window(
        onCloseRequest = onCloseRequest,
        title = I18n.titleAboutWindow(language),
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
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Header section with Icon and App Name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (appIcon != null) {
                        Image(
                            painter = appIcon,
                            contentDescription = "unwrapMedia Logo",
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "unwrapMedia",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.NeonBlue,
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = AppColors.NeonGreen.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.padding(bottom = 2.dp),
                            ) {
                                Text(
                                    "v${I18n.APP_VERSION}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.NeonGreen,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            if (onCheckUpdate != null) {
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = onCheckUpdate,
                                    modifier = Modifier.height(24.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        I18n.menuCheckForUpdates(language),
                                        fontSize = 11.sp,
                                        color = AppColors.NeonBlue,
                                    )
                                }
                            }
                        }
                        Text(
                            if (language == AppLanguage.KO) "멀티미디어 컨테이너 & 비트스트림 & 코덱 정밀 분석 도구" else "Multi-Media Container & Bitstream & Codec Inspection Tool",
                            fontSize = 12.sp,
                            color = AppColors.TextSecondary,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Scrollable content area
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(AppColors.Surface, RoundedCornerShape(8.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
                        .padding(16.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (language == AppLanguage.KO) "주요 지원 기능" else "Key Features & Capabilities",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                    )

                    FeatureItem("📷 이미지 포맷", "JPEG (Exif, XMP, ICC, Marker), PNG, WebP, GIF, HEIC/HEIF, AVIF")
                    FeatureItem("🎬 비디오 컨테이너", "MP4, MOV, MKV, WebM, AVI, TS, FLV Box Tree 구조 파싱")
                    FeatureItem("📱 모션포토", "삼성/구글 모션포토 v1.0 (MicroVideo) & v2.0 (MotionPhoto) 생성 및 분석")
                    FeatureItem("🔆 HDR 게인맵", "Adobe HDR Gain Map, Ultra HDR (v1.0), Apple/ISO 21496-1 분석 및 추출")
                    FeatureItem("🎞️ GOP & 필름스트립", "I/P/B 프레임 간격, PTS 타임라인, 썸네일 필름스트립, 원본 팝업뷰")
                    FeatureItem("🧬 비트스트림 & 코덱", "H.264/AVC, H.265/HEVC, AV1 SPS/PPS/VPS 파라미터 세트 분석")
                    FeatureItem("🔢 Hex Data Viewer", "010 Editor 스타일 실시간 자료형 인스펙터, 검색, 주소 점프, 포맷 복사")
                    FeatureItem("⚖️ 비교 및 벤치마크", "좌우 나란히 비교(동기화 줌/팬), PSNR · SSIM · VMAF 인코딩 화질 측정")

                    HorizontalDivider(color = AppColors.Border, modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        if (language == AppLanguage.KO) "실행 환경 정보" else "Environment Information",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                    )

                    val osName = System.getProperty("os.name") ?: "Unknown"
                    val osArch = System.getProperty("os.arch") ?: "Unknown"
                    val javaVersion = System.getProperty("java.version") ?: "Unknown"
                    val javaVendor = System.getProperty("java.vendor") ?: "Unknown"

                    EnvRow("운영체제 (OS)", "$osName ($osArch)")
                    EnvRow("자바 런타임 (JRE)", "$javaVersion ($javaVendor)")
                    EnvRow("라이선스", "Open Source (unwrapMedia)")
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onCloseRequest,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.NeonBlue, contentColor = Color.Black),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(if (language == AppLanguage.KO) "닫기" else "Close", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(title: String, desc: String) {
    Column {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.NeonYellow)
        Text(desc, fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, top = 1.dp))
    }
}

@Composable
private fun EnvRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.width(130.dp))
        Text(value, fontSize = 11.sp, color = AppColors.TextPrimary, fontFamily = FontFamily.Monospace)
    }
}
