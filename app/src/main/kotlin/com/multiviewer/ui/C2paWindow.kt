package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.c2pa.C2paAssertionContent
import com.multiviewer.parser.c2pa.C2paManifest
import com.multiviewer.parser.c2pa.C2paManifestStore
import com.multiviewer.parser.c2pa.readC2paManifestStore
import com.multiviewer.parser.c2pa.toDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun C2paWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english

    Window(
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(size = DpSize(1150.dp, 840.dp), position = WindowPosition(Alignment.Center)),
        title = "${I18n.menuC2pa(language)} - ${tab.file.name}",
    ) {
        var store by remember(tab.file) { mutableStateOf<C2paManifestStore?>(null) }
        var loading by remember(tab.file) { mutableStateOf(true) }
        var selected by remember(tab.file) { mutableStateOf(0) }
        var tabIndex by remember(tab.file) { mutableStateOf(0) }

        LaunchedEffect(tab.file) {
            val root = tab.root
            store = if (root == null || tab.isLoading) null else withContext(Dispatchers.IO) { readC2paManifestStore(tab.file, root) }
            selected = (store?.manifests?.size ?: 1) - 1
            loading = false
        }

        Column(
            modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val s = store
            s?.warnings?.forEach { Text("⚠ $it", color = AppColors.NeonYellow, fontSize = 12.sp) }
            when {
                loading -> Text(label("읽는 중…", "Reading…"), color = AppColors.TextPrimary)
                s == null || s.manifests.isEmpty() -> Text(
                    label("C2PA 매니페스트를 읽을 수 없습니다. 구조 트리의 경고를 확인하세요.", "Cannot read C2PA manifests. Check warnings in the structure tree."),
                    color = AppColors.NeonYellow
                )
                else -> {
                    val m = s.manifests.getOrNull(selected) ?: s.manifests.last()
                    val vReport = remember(m) { com.multiviewer.parser.c2pa.verifyC2paManifest(m) }
                    val sigStatusLabel = when (vReport.state) {
                        com.multiviewer.parser.c2pa.VerificationState.VALID -> label("서명 검증 완료 (공식)", "signature valid (trusted)")
                        com.multiviewer.parser.c2pa.VerificationState.UNTRUSTED -> label("서명 검증 완료 (테스트/자체 서명)", "signature valid (test/self-signed)")
                        com.multiviewer.parser.c2pa.VerificationState.INVALID -> label("서명 검증 실패 (변조 의심)", "signature invalid (tampered)")
                        com.multiviewer.parser.c2pa.VerificationState.UNSUPPORTED -> label("서명 검증 미지원", "signature unsupported")
                    }
                    Text(
                        "${s.location.container} · ${label("매니페스트", "manifests")} ${s.manifests.size} · $sigStatusLabel",
                        color = AppColors.TextPrimary, fontSize = 13.sp,
                    )
                    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Left Manifest List Card
                        Column(
                            modifier = Modifier
                                .width(280.dp)
                                .fillMaxHeight()
                                .border(1.dp, AppColors.Border.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                .background(AppColors.Surface, RoundedCornerShape(4.dp))
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().background(AppColors.Panel).padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    label("매니페스트 목록 (Manifests)", "Manifests"),
                                    color = AppColors.TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.6f))
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(s.manifests.size) { i ->
                                    if (i > 0) C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.35f))
                                    val mi = s.manifests[i]
                                    val isActive = i == s.manifests.lastIndex
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (i == selected) AppColors.Selection else Color.Transparent)
                                            .clickable { selected = i }
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            (if (isActive) "★ " else "") + (mi.claim?.title ?: mi.label),
                                            color = if (i == selected) AppColors.NeonBlue else AppColors.TextPrimary,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.sp,
                                        )
                                        Text(mi.label, color = AppColors.TextMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }

                        // Right Detailed Tab Panel
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val tabs = listOf(
                                label("개요", "Overview"),
                                label("작업 이력", "Actions"),
                                label("재료", "Ingredients"),
                                label("어설션", "Assertions"),
                                label("하드 바인딩", "Hard Binding"),
                                label("썸네일", "Thumbnail"),
                            )
                            TabRow(selectedTabIndex = tabIndex) {
                                tabs.forEachIndexed { i, t -> Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(t) }) }
                            }
                            SelectionContainer {
                                when (tabIndex) {
                                    0 -> OverviewTab(m, ::label)
                                    1 -> ActionsTab(m, ::label)
                                    2 -> IngredientsTab(m, s, ::label) { selected = it; tabIndex = 0 }
                                    3 -> AssertionsTab(m, ::label)
                                    4 -> HardBindingTab(tab, s, ::label)
                                    else -> ThumbnailTab(m, ::label)
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
private fun C2paTableCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, AppColors.Border.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
            .background(AppColors.Surface, RoundedCornerShape(4.dp)),
        content = content
    )
}

@Composable
private fun C2paTableVerticalDivider(color: Color = AppColors.Border.copy(alpha = 0.5f)) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(color)
    )
}

@Composable
private fun C2paTableHorizontalDivider(color: Color = AppColors.Border.copy(alpha = 0.5f)) {
    HorizontalDivider(color = color, thickness = 1.dp)
}

@Composable
private fun C2paSectionHeader(title: String) {
    Text(
        text = title,
        color = AppColors.NeonBlue,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

@Composable
private fun C2paKeyValueTable(
    items: List<Pair<String, String?>>,
    keyWidth: Dp = 220.dp,
    label: (String, String) -> String,
) {
    C2paTableCard {
        // Table Header
        Row(
            modifier = Modifier.fillMaxWidth().background(AppColors.Panel).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(keyWidth).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(label("속성 항목 (Property)", "Property"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
            Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(label("설정 값 (Value)", "Value"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.6f))

        items.forEachIndexed { idx, (k, v) ->
            if (idx > 0) C2paTableHorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(keyWidth).background(AppColors.Panel.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(k, color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                C2paTableVerticalDivider()
                Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(v ?: "-", color = AppColors.TextPrimary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun C2paKeyValueRow(key: String, value: String?, isMonospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(180.dp).background(AppColors.Panel.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(key, color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        C2paTableVerticalDivider()
        Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = value ?: "-",
                color = AppColors.TextPrimary,
                fontSize = if (isMonospace) 10.sp else 11.sp,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}

@Composable
private fun C2paVerificationRow(
    item: String,
    details: String,
    badgeText: String,
    badgeColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(180.dp).background(AppColors.Panel.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(item, color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        C2paTableVerticalDivider()
        Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(details, color = AppColors.TextPrimary, fontSize = 11.sp)
        }
        C2paTableVerticalDivider()
        Box(Modifier.width(140.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(badgeText, color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun C2paVerificationTable(
    vReport: com.multiviewer.parser.c2pa.C2paVerificationReport,
    label: (String, String) -> String,
) {
    C2paTableCard {
        // Table Header
        Row(
            modifier = Modifier.fillMaxWidth().background(AppColors.Panel).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(180.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(label("검증 항목", "Verification Item"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
            Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(label("검증 상세 내용", "Verification Details"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
            Box(Modifier.width(140.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(label("상태 / 판정", "Status / Verdict"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.6f))

        // Row 1: Verification State
        val stateBadgeColor = when (vReport.state) {
            com.multiviewer.parser.c2pa.VerificationState.VALID -> AppColors.NeonBlue
            com.multiviewer.parser.c2pa.VerificationState.UNTRUSTED -> AppColors.NeonYellow
            com.multiviewer.parser.c2pa.VerificationState.INVALID -> AppColors.NeonRed
            com.multiviewer.parser.c2pa.VerificationState.UNSUPPORTED -> AppColors.TextSecondary
        }
        val stateText = when (vReport.state) {
            com.multiviewer.parser.c2pa.VerificationState.VALID -> label("공식 인증 (VALID)", "VALID")
            com.multiviewer.parser.c2pa.VerificationState.UNTRUSTED -> label("테스트/자체 서명", "UNTRUSTED")
            com.multiviewer.parser.c2pa.VerificationState.INVALID -> label("서명 실패/변조", "INVALID")
            com.multiviewer.parser.c2pa.VerificationState.UNSUPPORTED -> label("미지원", "UNSUPPORTED")
        }
        C2paVerificationRow(
            item = label("서명 검증 상태", "Verification State"),
            details = vReport.summaryMessage,
            badgeText = stateText,
            badgeColor = stateBadgeColor,
        )
        C2paTableHorizontalDivider()

        // Row 2: Signature Validity
        C2paVerificationRow(
            item = label("서명 유효성", "Signature Validity"),
            details = if (vReport.signatureValid) label("클레임 바이트에 대한 서명이 유효함", "Signature matches claim bytes") else label("서명 불일치 또는 데이터 변조 감지", "Signature mismatch or tampered"),
            badgeText = if (vReport.signatureValid) label("유효 (PASS)", "PASS") else label("실패 (FAIL)", "FAIL"),
            badgeColor = if (vReport.signatureValid) AppColors.NeonBlue else AppColors.NeonRed,
        )
        C2paTableHorizontalDivider()

        // Row 3: Certificate Chain
        C2paVerificationRow(
            item = label("인증서 체인", "Certificate Chain"),
            details = if (vReport.chainValid) label("X.509 인증서 체인 유효 (체인 길이: ${vReport.chainLength})", "Valid X.509 chain (Length: ${vReport.chainLength})") else label("인증서 체인 검증 실패", "Invalid certificate chain"),
            badgeText = if (vReport.chainValid) label("유효 (PASS)", "PASS") else label("실패 (FAIL)", "FAIL"),
            badgeColor = if (vReport.chainValid) AppColors.NeonBlue else AppColors.NeonRed,
        )
        C2paTableHorizontalDivider()

        // Row 4: Trust Level
        C2paVerificationRow(
            item = label("신뢰 수준", "Trust Level"),
            details = if (vReport.isTestCredential) label("테스트 또는 자체 서명 자격 증명 사용", "Test or self-signed certificate") else label("공식 신뢰 루트 인증 기관 체인", "Trusted certificate authority"),
            badgeText = if (vReport.isTestCredential) label("테스트 (Untrusted)", "Untrusted") else label("신뢰됨 (Trusted)", "Trusted"),
            badgeColor = if (vReport.isTestCredential) AppColors.NeonYellow else AppColors.NeonBlue,
        )
        C2paTableHorizontalDivider()

        // Row 5: Assertion Hashes
        val allMatched = vReport.matchedAssertionCount == vReport.totalAssertionCount && vReport.totalAssertionCount > 0
        C2paVerificationRow(
            item = label("어설션 해시 일치", "Assertion Hashes"),
            details = "${vReport.matchedAssertionCount} / ${vReport.totalAssertionCount} ${label("어설션 해시 검증 완료", "assertions verified")}",
            badgeText = if (allMatched) label("전체 일치", "ALL MATCHED") else label("일부 불일치", "MISMATCH"),
            badgeColor = if (allMatched) AppColors.NeonBlue else AppColors.NeonYellow,
        )
    }
}

@Composable
private fun OverviewTab(m: C2paManifest, label: (String, String) -> String) {
    val vReport = remember(m) { com.multiviewer.parser.c2pa.verifyC2paManifest(m) }
    val c = m.claim
    val sig = m.signature

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            // Claim decode failure banner if any
            if (c == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().border(1.dp, AppColors.NeonRed, RoundedCornerShape(4.dp))
                        .background(AppColors.Surface).padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label("claim 디코딩 실패: ", "Claim decode failed: ") + (m.claimError ?: "?"), color = AppColors.NeonRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        m.claimBytes?.let { Text(com.multiviewer.parser.c2pa.CborBytes(it).toDiagnostic(256), color = AppColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
            }

            // Manifest & Claim Properties Table
            C2paSectionHeader(label("매니페스트 및 클레임 속성 (Manifest & Claim Properties)", "Manifest & Claim Properties"))
            C2paKeyValueTable(
                items = listOf(
                    label("매니페스트 레이블", "Manifest Label") to m.label,
                    label("클레임 식별자", "Claim Label") to (c?.let { "${it.label} (v${it.version})" } ?: "-"),
                    label("제목 (dc:title)", "Title (dc:title)") to (c?.title ?: "-"),
                    label("포맷 (dc:format)", "Format (dc:format)") to (c?.format ?: "-"),
                    label("인스턴스 ID (instanceID)", "Instance ID") to (c?.instanceId ?: "-"),
                    label("생성기 (claim_generator)", "Claim Generator") to (c?.claimGenerator ?: "-"),
                    label("생성기 정보 (generator_info)", "Generator Info") to (c?.generatorInfo?.joinToString(", ")?.ifEmpty { null } ?: "-"),
                    label("해시 알고리즘 (alg)", "Hash Algorithm") to (c?.alg ?: "-"),
                    label("어설션 참조 수", "Assertion References") to (c?.assertionRefs?.size?.toString() ?: "0"),
                    label("서명 알고리즘", "Signature Algorithm") to (sig?.algorithm ?: "-"),
                    label("서명 인증서 주체", "Signer Certificate Subject") to (sig?.certificateSubject ?: "-"),
                    label("인증서 발급자", "Certificate Issuer") to (sig?.certificateIssuer ?: "-"),
                    label("인증서 체인 길이", "Chain Length") to (sig?.chainLength?.toString() ?: "-"),
                ),
                label = label,
            )
        }

        item {
            // Cryptographic Verification Table
            C2paSectionHeader(label("암호화 서명 검증 결과 (Cryptographic Verification)", "Cryptographic Verification Results"))
            C2paVerificationTable(vReport = vReport, label = label)
        }

        if (sig?.error != null || m.warnings.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().border(1.dp, AppColors.NeonYellow.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .background(AppColors.Surface).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    sig?.error?.let { Text("⚠ " + label("서명 정보 오류: ", "Signature error: ") + it, color = AppColors.NeonRed, fontSize = 11.sp) }
                    m.warnings.forEach { Text("⚠ $it", color = AppColors.NeonYellow, fontSize = 11.sp) }
                    vReport.errors.forEach { Text("⚠ $it", color = AppColors.NeonRed, fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
private fun ActionsTab(m: C2paManifest, label: (String, String) -> String) {
    val actions = m.assertions.flatMap { (it.content as? C2paAssertionContent.Actions)?.actions ?: emptyList() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            C2paSectionHeader(label("C2PA 생성 및 편집 작업 이력 (Actions History)", "C2PA Actions History"))
            C2paTableCard {
                // Table Header
                Row(
                    modifier = Modifier.fillMaxWidth().background(AppColors.Panel).height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(36.dp).padding(horizontal = 6.dp, vertical = 7.dp)) {
                        Text("#", color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(170.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("작업 (Action)", "Action"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(160.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("소프트웨어 (Software)", "Software Agent"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(140.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("일시 (When)", "Timestamp"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(140.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("디지털 출처 (Source)", "Digital Source"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("파라미터 (Parameters)", "Parameters"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.6f))

                if (actions.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(label("기록된 작업 이력(Action)이 없습니다.", "No actions recorded in this manifest."), color = AppColors.TextSecondary, fontSize = 11.sp)
                    }
                } else {
                    actions.forEachIndexed { idx, a ->
                        if (idx > 0) C2paTableHorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(36.dp).padding(horizontal = 6.dp, vertical = 7.dp)) {
                                Text("${idx + 1}", color = AppColors.TextMuted, fontSize = 11.sp)
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.width(170.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(a.action, color = AppColors.NeonBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.width(160.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(a.softwareAgent ?: "-", color = AppColors.TextPrimary, fontSize = 11.sp)
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.width(140.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(a.whenTime ?: "-", color = AppColors.TextPrimary, fontSize = 11.sp)
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.width(140.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(a.digitalSourceType ?: "-", color = AppColors.TextSecondary, fontSize = 11.sp)
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(a.parameters ?: "-", color = AppColors.TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IngredientsTab(
    m: C2paManifest,
    store: C2paManifestStore,
    label: (String, String) -> String,
    onSelectManifest: (Int) -> Unit,
) {
    val ingredients = m.assertions.mapNotNull { (it.content as? C2paAssertionContent.Ingredient)?.ingredient }
    var expandedIndex by remember(m) { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            C2paSectionHeader(label("구성 요소 및 재료 목록 (Ingredients)", "Ingredients"))
            C2paTableCard {
                // Table Header
                Row(
                    modifier = Modifier.fillMaxWidth().background(AppColors.Panel).height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(36.dp).padding(horizontal = 6.dp, vertical = 7.dp)) {
                        Text("#", color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(180.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("제목 (Title)", "Title"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(100.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("포맷 (Format)", "Format"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(120.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("관계 (Relationship)", "Relationship"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(180.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("인스턴스 ID (Instance ID)", "Instance ID"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("참조 매니페스트 (Manifest Ref)", "Manifest Ref"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(80.dp).padding(horizontal = 8.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text(label("썸네일", "Thumbnail"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.6f))

                if (ingredients.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(label("기록된 재료(Ingredient) 정보가 없습니다.", "No ingredients found in this manifest."), color = AppColors.TextSecondary, fontSize = 11.sp)
                    }
                } else {
                    ingredients.forEachIndexed { idx, ing ->
                        if (idx > 0) C2paTableHorizontalDivider()
                        val target = c2paManifestIndex(ing.manifestRef, store.manifests.map { it.label })
                        val inStore = target != null
                        val hasThumb = ing.thumbnailRef != null

                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(36.dp).padding(horizontal = 6.dp, vertical = 7.dp)) {
                                Text("${idx + 1}", color = AppColors.TextMuted, fontSize = 11.sp)
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.width(180.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(ing.title ?: "-", color = AppColors.NeonBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.width(100.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(ing.format ?: "-", color = AppColors.TextPrimary, fontSize = 11.sp)
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.width(120.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(ing.relationship ?: "-", color = AppColors.TextPrimary, fontSize = 11.sp)
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.width(180.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(ing.instanceId ?: "-", color = AppColors.TextSecondary, fontSize = 10.sp)
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(ing.manifestRef ?: "-", color = AppColors.TextPrimary, fontSize = 11.sp)
                                    if (target != null) {
                                        Text(
                                            "↗ " + label("참조 매니페스트 열기", "Open Manifest"),
                                            color = AppColors.NeonBlue,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable { onSelectManifest(target) }
                                        )
                                    }
                                }
                            }
                            C2paTableVerticalDivider()
                            Box(Modifier.width(80.dp).padding(horizontal = 8.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                                if (hasThumb) {
                                    Text(
                                        if (expandedIndex == idx) label("닫기", "Hide") else label("보기", "View"),
                                        color = AppColors.NeonBlue,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable { expandedIndex = if (expandedIndex == idx) null else idx }
                                    )
                                } else {
                                    Text("-", color = AppColors.TextMuted, fontSize = 11.sp)
                                }
                            }
                        }

                        if (expandedIndex == idx && hasThumb) {
                            C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.35f))
                            Box(
                                modifier = Modifier.fillMaxWidth().background(AppColors.Background.copy(alpha = 0.5f)).padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val label2 = ing.thumbnailRef!!.substringAfterLast('/')
                                val thumb = m.assertions.firstOrNull { it.label == label2 }?.content as? C2paAssertionContent.Thumbnail
                                if (thumb != null) {
                                    ThumbnailImage(thumb.bytes, label)
                                } else {
                                    Text(label("썸네일 데이터를 찾을 수 없습니다", "Thumbnail data not found"), color = AppColors.TextMuted, fontSize = 11.sp)
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
private fun AssertionsTab(m: C2paManifest, label: (String, String) -> String) {
    var open by remember(m) { mutableStateOf<Int?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            C2paSectionHeader(label("어설션 데이터 목록 (Assertions)", "Assertions List") + " (${m.assertions.size})")
            C2paTableCard {
                // Table Header
                Row(
                    modifier = Modifier.fillMaxWidth().background(AppColors.Panel).height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(36.dp).padding(horizontal = 6.dp, vertical = 7.dp)) {
                        Text("#", color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(220.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("레이블 (Label)", "Label"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(130.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("유형 (Content Type)", "Content Type"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("종류 및 요약 (Kind)", "Kind & Summary"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(110.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(label("오프셋 (Offset)", "File Offset"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                    Box(Modifier.width(65.dp).padding(horizontal = 8.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text(label("상세", "Details"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.6f))

                m.assertions.forEachIndexed { i, a ->
                    if (i > 0) C2paTableHorizontalDivider()
                    val kind = when (val c = a.content) {
                        is C2paAssertionContent.Actions -> label("작업 이력 (${c.actions.size}개)", "actions (${c.actions.size})")
                        is C2paAssertionContent.Ingredient -> label("재료 (Ingredient)", "ingredient")
                        is C2paAssertionContent.Thumbnail -> "${label("썸네일", "thumbnail")} ${c.mimeType ?: ""} (${c.bytes.size} B)"
                        is C2paAssertionContent.Cbor -> "CBOR Binary"
                        is C2paAssertionContent.Json -> "JSON Document"
                        is C2paAssertionContent.Failed -> "${label("파싱 실패", "FAILED")}: ${c.reason}"
                        C2paAssertionContent.Empty -> label("비어 있음", "empty")
                    }
                    val isFailed = a.content is C2paAssertionContent.Failed
                    val isOpen = open == i

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .background(if (isOpen) AppColors.Selection else Color.Transparent)
                            .clickable { open = if (isOpen) null else i },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(36.dp).padding(horizontal = 6.dp, vertical = 7.dp)) {
                            Text("${i + 1}", color = AppColors.TextMuted, fontSize = 11.sp)
                        }
                        C2paTableVerticalDivider()
                        Box(Modifier.width(220.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                            Text(a.label, color = if (isFailed) AppColors.NeonRed else AppColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        C2paTableVerticalDivider()
                        Box(Modifier.width(130.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                            Text(a.contentType, color = AppColors.TextSecondary, fontSize = 11.sp)
                        }
                        C2paTableVerticalDivider()
                        Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                            Text(kind, color = if (isFailed) AppColors.NeonRed else AppColors.TextPrimary, fontSize = 11.sp)
                        }
                        C2paTableVerticalDivider()
                        Box(Modifier.width(110.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                            Text("0x${a.fileOffset.toString(16).uppercase()}", color = AppColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        C2paTableVerticalDivider()
                        Box(Modifier.width(65.dp).padding(horizontal = 8.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                            Text(if (isOpen) "▲" else "▼", color = AppColors.NeonBlue, fontSize = 10.sp)
                        }
                    }

                    if (isOpen) {
                        C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.4f))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppColors.Panel.copy(alpha = 0.6f))
                                .padding(12.dp)
                        ) {
                            val text = when (val c = a.content) {
                                is C2paAssertionContent.Cbor -> a.diagnostic
                                is C2paAssertionContent.Json -> c.text
                                is C2paAssertionContent.Actions -> a.diagnostic
                                is C2paAssertionContent.Ingredient -> a.diagnostic
                                is C2paAssertionContent.Failed -> c.reason + "\n" + com.multiviewer.parser.c2pa.CborBytes(a.rawBytes).toDiagnostic(256)
                                is C2paAssertionContent.Thumbnail -> "${c.bytes.size} ${label("바이트 바이너리 데이터", "bytes binary thumbnail data")}"
                                C2paAssertionContent.Empty -> label("내용이 비어 있습니다.", "Empty assertion content.")
                            }
                            Text(
                                text = text,
                                color = AppColors.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailTab(m: C2paManifest, label: (String, String) -> String) {
    val thumb = m.assertions.firstOrNull { it.label.startsWith("c2pa.thumbnail.claim") }?.content as? C2paAssertionContent.Thumbnail
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        C2paSectionHeader(label("클레임 대표 썸네일 (Claim Thumbnail)", "Claim Thumbnail"))
        C2paTableCard {
            if (thumb == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(label("매니페스트에 등록된 대표 썸네일이 없습니다.", "No claim thumbnail registered in this manifest."), color = AppColors.TextSecondary, fontSize = 12.sp)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().background(AppColors.Panel).padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${thumb.mimeType ?: "image/jpeg"} · ${thumb.bytes.size} B", color = AppColors.TextSecondary, fontSize = 11.sp)
                }
                C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.6f))
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    ThumbnailImage(thumb.bytes, label)
                }
            }
        }
    }
}

internal fun c2paManifestIndex(reference: String?, labels: List<String>): Int? {
    val prefix = "self#jumbf=/c2pa/"
    if (reference == null || !reference.startsWith(prefix)) return null
    return labels.indexOf(reference.removePrefix(prefix)).takeIf { it >= 0 }
}

@Composable
private fun ThumbnailImage(bytes: ByteArray, label: (String, String) -> String) {
    var bitmap by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(bytes) { mutableStateOf(true) }
    LaunchedEffect(bytes) {
        bitmap = withContext(Dispatchers.Default) {
            runCatching { SkiaImage.makeFromEncoded(bytes).use { it.toComposeImageBitmap() } }.getOrNull()
        }
        loading = false
    }
    when {
        loading -> Text(label("썸네일 읽는 중…", "Reading thumbnail…"), color = AppColors.TextSecondary, fontSize = 12.sp)
        bitmap == null -> Text(label("썸네일 디코딩 실패", "Thumbnail decode failed"), color = AppColors.NeonRed, fontSize = 12.sp)
        else -> Image(bitmap = bitmap!!, contentDescription = null, modifier = Modifier.heightIn(max = 360.dp))
    }
}

@Composable
private fun HardBindingTab(tab: TabState, store: C2paManifestStore, label: (String, String) -> String) {
    var report by remember(tab.file) { mutableStateOf<com.multiviewer.parser.c2pa.C2paHardBindingReport?>(null) }
    var loading by remember(tab.file) { mutableStateOf(true) }

    LaunchedEffect(tab.file) {
        val root = tab.root
        report = if (root == null) null else withContext(Dispatchers.IO) {
            com.multiviewer.parser.c2pa.verifyC2paHardBinding(tab.file, root, store)
        }
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when {
            loading -> item {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(label("하드 바인딩 검증 및 해시 계산 중…", "Verifying hard binding and calculating hashes…"), color = AppColors.TextSecondary, fontSize = 12.sp)
                }
            }
            report == null -> item {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(label("바인딩 정보를 읽을 수 없습니다", "Unable to read binding information"), color = AppColors.NeonRed, fontSize = 12.sp)
                }
            }
            else -> {
                val r = report!!
                val comp = r.comparison

                // Table 1: Binding Verification & Hash Comparison
                item {
                    C2paSectionHeader(label("바인딩 검증 결과 및 해시 비교 (Binding Verification)", "Binding Verification & Hash Comparison"))
                    C2paTableCard {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth().background(AppColors.Panel).height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(180.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text(label("검증 항목", "Item"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                            Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text(label("상세 내용 / 해시 값", "Value / Hash"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                            Box(Modifier.width(140.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text(label("상태 / 판정", "Status"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.6f))

                        val statusColor = when (comp.status) {
                            com.multiviewer.parser.c2pa.BindingStatus.MATCH -> AppColors.NeonBlue
                            com.multiviewer.parser.c2pa.BindingStatus.MISMATCH -> AppColors.NeonRed
                            com.multiviewer.parser.c2pa.BindingStatus.UNSUPPORTED -> AppColors.NeonYellow
                        }
                        val statusText = when (comp.status) {
                            com.multiviewer.parser.c2pa.BindingStatus.MATCH -> label("일치 (PASS)", "MATCH (PASS)")
                            com.multiviewer.parser.c2pa.BindingStatus.MISMATCH -> label("불일치 (MISMATCH)", "MISMATCH (FAIL)")
                            com.multiviewer.parser.c2pa.BindingStatus.UNSUPPORTED -> label("지원 안 됨", "UNSUPPORTED")
                        }

                        C2paVerificationRow(
                            item = label("바인딩 검증 상태", "Binding Status"),
                            details = if (comp.status == com.multiviewer.parser.c2pa.BindingStatus.MATCH)
                                label("계산된 해시와 매니페스트 저장 해시가 정확히 일치함", "Computed hash matches stored hash")
                            else
                                label("해시 불일치 (파일 컨텐츠 변조 감지)", "Hash mismatch (content modified)"),
                            badgeText = statusText,
                            badgeColor = statusColor,
                        )
                        C2paTableHorizontalDivider()

                        C2paKeyValueRow(label("해시 알고리즘", "Hash Algorithm"), comp.algorithm)
                        C2paTableHorizontalDivider()
                        C2paKeyValueRow(label("해시 대상 바이트", "Hashed Bytes"), "${comp.hashedByteCount} B")
                        C2paTableHorizontalDivider()
                        C2paKeyValueRow(label("제외 바이트", "Excluded Bytes"), "${comp.excludedByteCount} B")
                        C2paTableHorizontalDivider()
                        C2paKeyValueRow(label("저장된 해시", "Stored Hash"), comp.storedHashHex, isMonospace = true)
                        C2paTableHorizontalDivider()
                        C2paKeyValueRow(label("계산된 해시", "Computed Hash"), comp.computedHashHex, isMonospace = true)
                    }
                }

                // Table 2: Edit Impact Diagnostics
                item {
                    val impact = r.editImpact
                    C2paSectionHeader(label("편집 시나리오별 바인딩 영향도 진단 (Edit Impact Diagnostics)", "Edit Impact Diagnostics"))
                    C2paTableCard {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth().background(AppColors.Panel).height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(220.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text(label("편집 시나리오", "Edit Scenario"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                            Box(Modifier.width(130.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text(label("안전 여부", "Safety"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                            Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text(label("영향도 분석 및 근거", "Reason / Diagnostic"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.6f))

                        val scenarios = listOf(
                            Triple(label("모션포토 비디오 추가/편집", "Motion Photo Video Append/Edit"), impact.motionPhotoVideoSafe, impact.motionPhotoVideoReason),
                            Triple(label("삼성 SEF 트레일러 추가/수정", "Samsung SEF Trailer Append/Edit"), impact.sefTrailerSafe, impact.sefTrailerReason),
                            Triple(label("XMP / Exif 메타데이터 편집", "XMP / Exif Metadata Edit"), impact.xmpMetadataSafe, impact.xmpMetadataReason),
                        )
                        scenarios.forEachIndexed { idx, (title, safe, reason) ->
                            if (idx > 0) C2paTableHorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(220.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(title, color = AppColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                                C2paTableVerticalDivider()
                                Box(Modifier.width(130.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        if (safe) label("안전 (유지)", "SAFE") else label("위험 (손상)", "BREAKS"),
                                        color = if (safe) AppColors.NeonBlue else AppColors.NeonRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                                C2paTableVerticalDivider()
                                Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(reason, color = AppColors.TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // Table 3: File Structure & C2PA Binding Coverage
                item {
                    C2paSectionHeader(label("파일 구조 및 C2PA 바인딩 커버리지 (Structure & Binding Coverage)", "File Structure & C2PA Binding Coverage"))
                    C2paTableCard {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth().background(AppColors.Panel).height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(36.dp).padding(horizontal = 6.dp, vertical = 7.dp)) {
                                Text("#", color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                            Box(Modifier.width(200.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(label("구조 항목 (Structure Item)", "Structure Item"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                            Box(Modifier.width(120.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(label("오프셋 (Offset)", "File Offset"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                            Box(Modifier.width(90.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(label("크기 (Size)", "Size"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                            Box(Modifier.width(130.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(label("보호 상태 (Status)", "Coverage Status"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            C2paTableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                Text(label("영향도 분석 비고 (Impact Note)", "Impact Note"), color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        C2paTableHorizontalDivider(AppColors.Border.copy(alpha = 0.6f))

                        r.coverageItems.forEachIndexed { idx, item ->
                            if (idx > 0) C2paTableHorizontalDivider()
                            val isHashed = item.status == com.multiviewer.parser.c2pa.CoverageStatus.HASHED
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(36.dp).padding(horizontal = 6.dp, vertical = 7.dp)) {
                                    Text("${idx + 1}", color = AppColors.TextMuted, fontSize = 11.sp)
                                }
                                C2paTableVerticalDivider()
                                Box(Modifier.width(200.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                    Text(item.name, color = AppColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                                C2paTableVerticalDivider()
                                Box(Modifier.width(120.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                    Text("0x${item.offset.toString(16).uppercase()}", color = AppColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                C2paTableVerticalDivider()
                                Box(Modifier.width(90.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                    Text("${item.size} B", color = AppColors.TextPrimary, fontSize = 11.sp)
                                }
                                C2paTableVerticalDivider()
                                Box(Modifier.width(130.dp).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                    Text(
                                        if (isHashed) label("보호됨 (Hashed)", "Hashed") else label("제외됨 (Excluded)", "Excluded"),
                                        color = if (isHashed) AppColors.NeonBlue else AppColors.NeonYellow,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                C2paTableVerticalDivider()
                                Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                                    Text(item.impactNote, color = AppColors.TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
