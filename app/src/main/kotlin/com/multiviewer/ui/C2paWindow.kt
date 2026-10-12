package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
        state = rememberWindowState(size = DpSize(1100.dp, 800.dp), position = WindowPosition(Alignment.Center)),
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

        Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val s = store
            s?.warnings?.forEach { Text("⚠ $it", color = AppColors.NeonYellow, fontSize = 12.sp) }
            when {
                loading -> Text(label("읽는 중…", "Reading…"), color = AppColors.TextPrimary)
                s == null || s.manifests.isEmpty() -> Text(label("C2PA 매니페스트를 읽을 수 없습니다. 구조 트리의 경고를 확인하세요.", "Cannot read C2PA manifests. Check warnings in the structure tree."), color = AppColors.NeonYellow)
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
                        LazyColumn(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                            items(s.manifests.size) { i ->
                                val mi = s.manifests[i]
                                val isActive = i == s.manifests.lastIndex
                                Text(
                                    (if (isActive) "★ " else "") + (mi.claim?.title ?: mi.label),
                                    color = if (i == selected) AppColors.NeonBlue else AppColors.TextPrimary,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth()
                                        .background(if (i == selected) AppColors.Selection else Color.Transparent)
                                        .clickable { selected = i }
                                        .padding(6.dp),
                                )
                                Text(mi.label, color = AppColors.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
                            }
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
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
private fun Row2(name: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, color = AppColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(200.dp))
        Text(value ?: "-", color = AppColors.TextPrimary, fontSize = 12.sp)
    }
}

@Composable
private fun OverviewTab(m: C2paManifest, label: (String, String) -> String) {
    val vReport = remember(m) { com.multiviewer.parser.c2pa.verifyC2paManifest(m) }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        item {
            Row2("label", m.label)
            val c = m.claim
            if (c == null) {
                Text(label("claim 디코딩 실패: ", "Claim decode failed: ") + (m.claimError ?: "?"), color = AppColors.NeonRed, fontSize = 12.sp)
                m.claimBytes?.let { Text(com.multiviewer.parser.c2pa.CborBytes(it).toDiagnostic(256), color = AppColors.TextSecondary, fontSize = 11.sp) }
            } else {
                Row2("claim", "${c.label} (v${c.version})")
                Row2("dc:title", c.title)
                Row2("dc:format", c.format)
                Row2("instanceID", c.instanceId)
                Row2("claim_generator", c.claimGenerator)
                Row2("claim_generator_info", c.generatorInfo.joinToString(", ").ifEmpty { null })
                Row2("alg", c.alg)
                Row2(label("assertion 참조 수", "assertion refs"), c.assertionRefs.size.toString())
            }
            val sig = m.signature
            Row2(label("서명 알고리즘", "signature alg"), sig?.algorithm)
            Row2(label("서명 인증서", "signer cert"), sig?.certificateSubject)
            Row2(label("발급자", "issuer"), sig?.certificateIssuer)
            Row2(label("인증서 체인 길이", "chain length"), sig?.chainLength?.toString())
            sig?.error?.let { Text(label("서명 정보 오류: ", "Signature info error: ") + it, color = AppColors.NeonRed, fontSize = 12.sp) }
            m.warnings.forEach { Text("⚠ $it", color = AppColors.NeonYellow, fontSize = 12.sp) }

            Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp).background(AppColors.Surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label("암호화 서명 검증 결과", "Cryptographic Verification"), color = AppColors.NeonBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                val badgeColor = when (vReport.state) {
                    com.multiviewer.parser.c2pa.VerificationState.VALID -> AppColors.NeonBlue
                    com.multiviewer.parser.c2pa.VerificationState.UNTRUSTED -> AppColors.NeonYellow
                    com.multiviewer.parser.c2pa.VerificationState.INVALID -> AppColors.NeonRed
                    com.multiviewer.parser.c2pa.VerificationState.UNSUPPORTED -> AppColors.TextSecondary
                }
                Row2(label("서명 검증 상태", "Verification State"), vReport.summaryMessage)
                Row2(label("서명 유효성", "Signature Validity"), if (vReport.signatureValid) label("유효함 (PASS)", "Valid (PASS)") else label("불일치/실패 (FAIL)", "Invalid (FAIL)"))
                Row2(label("인증서 체인", "Certificate Chain"), if (vReport.chainValid) label("유효함 (체인 길이: ${vReport.chainLength})", "Valid (Length: ${vReport.chainLength})") else label("체인 검증 실패", "Invalid"))
                Row2(label("신뢰 수준", "Trust Level"), if (vReport.isTestCredential) label("테스트/자체 서명 자격 증명 (Untrusted Test)", "Test / Self-signed (Untrusted)") else label("공식 신뢰 체인 (Trusted)", "Trusted"))
                Row2(label("어설션 해시 일치", "Assertion Hashes"), "${vReport.matchedAssertionCount} / ${vReport.totalAssertionCount}")
                vReport.errors.forEach { Text("⚠ $it", color = AppColors.NeonRed, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun ActionsTab(m: C2paManifest, label: (String, String) -> String) {
    val actions = m.assertions.flatMap { (it.content as? C2paAssertionContent.Actions)?.actions ?: emptyList() }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        if (actions.isEmpty()) item { Text(label("actions 없음", "No actions"), color = AppColors.TextSecondary, fontSize = 12.sp) }
        items(actions) { a ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(a.action, color = AppColors.NeonBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row2("softwareAgent", a.softwareAgent)
                Row2("when", a.whenTime)
                Row2("digitalSourceType", a.digitalSourceType)
                Row2("parameters", a.parameters)
            }
        }
    }
}

@Composable
private fun IngredientsTab(m: C2paManifest, store: C2paManifestStore, label: (String, String) -> String, onSelectManifest: (Int) -> Unit) {
    val ingredients = m.assertions.mapNotNull { (it.content as? C2paAssertionContent.Ingredient)?.ingredient }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        if (ingredients.isEmpty()) item { Text(label("ingredient 없음", "No ingredients"), color = AppColors.TextSecondary, fontSize = 12.sp) }
        items(ingredients) { ing ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(ing.title ?: "?", color = AppColors.NeonBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row2("dc:format", ing.format)
                Row2("relationship", ing.relationship)
                Row2("instanceID", ing.instanceId)
                val target = c2paManifestIndex(ing.manifestRef, store.manifests.map { it.label })
                val inStore = target != null
                Row2(label("참조 매니페스트", "manifest ref"), ing.manifestRef?.let { it + if (inStore == true) label("  (이 파일에 있음)", "  (in this store)") else "" })
                if (target != null) Text(label("참조 매니페스트 열기", "Open referenced manifest"),
                    color = AppColors.NeonBlue, modifier = Modifier.clickable { onSelectManifest(target) }.padding(4.dp))
                ing.thumbnailRef?.let { ref ->
                    val label2 = ref.substringAfterLast('/')
                    val thumb = m.assertions.firstOrNull { it.label == label2 }?.content as? C2paAssertionContent.Thumbnail
                    thumb?.let { ThumbnailImage(it.bytes, label) }
                }
            }
        }
    }
}

@Composable
private fun AssertionsTab(m: C2paManifest, label: (String, String) -> String) {
    var open by remember(m) { mutableStateOf<Int?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        items(m.assertions.size) { i ->
            val a = m.assertions[i]
            val kind = when (val c = a.content) {
                is C2paAssertionContent.Actions -> label("작업 이력 (${c.actions.size})", "actions (${c.actions.size})")
                is C2paAssertionContent.Ingredient -> label("재료", "ingredient")
                is C2paAssertionContent.Thumbnail -> "${label("썸네일", "thumbnail")} ${c.mimeType ?: ""} ${c.bytes.size} B"
                is C2paAssertionContent.Cbor -> "cbor"
                is C2paAssertionContent.Json -> "json"
                is C2paAssertionContent.Failed -> "${label("실패", "FAILED")}: ${c.reason}"
                C2paAssertionContent.Empty -> label("비어 있음", "empty")
            }
            Text(
                "${a.label}  [${a.contentType}]  $kind  @0x${a.fileOffset.toString(16).uppercase()}",
                color = if (a.content is C2paAssertionContent.Failed) AppColors.NeonRed else AppColors.TextPrimary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { open = if (open == i) null else i }.padding(vertical = 4.dp),
            )
            if (open == i) {
                val text = when (val c = a.content) {
                    is C2paAssertionContent.Cbor -> a.diagnostic
                    is C2paAssertionContent.Json -> c.text
                    is C2paAssertionContent.Actions -> a.diagnostic
                    is C2paAssertionContent.Ingredient -> a.diagnostic
                    is C2paAssertionContent.Failed -> c.reason + "\n" + com.multiviewer.parser.c2pa.CborBytes(a.rawBytes).toDiagnostic(256)
                    is C2paAssertionContent.Thumbnail -> "${c.bytes.size} ${label("바이트", "bytes")}"
                    C2paAssertionContent.Empty -> ""
                }
                Text(text, color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun ThumbnailTab(m: C2paManifest, label: (String, String) -> String) {
    val thumb = m.assertions.firstOrNull { it.label.startsWith("c2pa.thumbnail.claim") }?.content as? C2paAssertionContent.Thumbnail
    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        if (thumb == null) Text(label("claim 썸네일 없음", "No claim thumbnail"), color = AppColors.TextSecondary, fontSize = 12.sp)
        else ThumbnailImage(thumb.bytes, label)
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
            runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
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

    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            loading -> item { Text(label("하드 바인딩 검증 및 해시 계산 중…", "Verifying hard binding and calculating hashes…"), color = AppColors.TextSecondary, fontSize = 12.sp) }
            report == null -> item { Text(label("바인딩 정보를 읽을 수 없습니다", "Unable to read binding information"), color = AppColors.NeonRed, fontSize = 12.sp) }
            else -> {
                val r = report!!
                val comp = r.comparison
                item {
                    Column(modifier = Modifier.fillMaxWidth().background(AppColors.Surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(label("바인딩 검증 결과", "Binding Verification"), color = AppColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val badgeColor = when (comp.status) {
                                com.multiviewer.parser.c2pa.BindingStatus.MATCH -> AppColors.NeonBlue
                                com.multiviewer.parser.c2pa.BindingStatus.MISMATCH -> AppColors.NeonRed
                                com.multiviewer.parser.c2pa.BindingStatus.UNSUPPORTED -> AppColors.NeonYellow
                            }
                            val badgeText = when (comp.status) {
                                com.multiviewer.parser.c2pa.BindingStatus.MATCH -> label("일치 (PASS)", "MATCH (PASS)")
                                com.multiviewer.parser.c2pa.BindingStatus.MISMATCH -> label("불일치 (MISMATCH)", "MISMATCH (FAIL)")
                                com.multiviewer.parser.c2pa.BindingStatus.UNSUPPORTED -> label("지원 안 됨", "UNSUPPORTED")
                            }
                            Text(badgeText, color = badgeColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Row2(label("해시 알고리즘", "Hash algorithm"), comp.algorithm)
                        Row2(label("해시 대상 바이트", "Hashed bytes"), "${comp.hashedByteCount} B")
                        Row2(label("제외 바이트", "Excluded bytes"), "${comp.excludedByteCount} B")
                        Row2(label("저장된 해시", "Stored hash"), comp.storedHashHex)
                        Row2(label("계산된 해시", "Computed hash"), comp.computedHashHex)
                        comp.error?.let { Text(label("오류: ", "Error: ") + it, color = AppColors.NeonRed, fontSize = 12.sp) }
                    }
                }

                item {
                    Text(label("편집 영향도 진단 (Edit Impact Diagnostics)", "Edit Impact Diagnostics"), color = AppColors.NeonBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                item {
                    val impact = r.editImpact
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ImpactCard(
                            title = label("모션포토 비디오 추가/편집", "Motion Photo Video Append/Edit"),
                            safe = impact.motionPhotoVideoSafe,
                            reason = impact.motionPhotoVideoReason,
                            label = label
                        )
                        ImpactCard(
                            title = label("삼성 SEF 트레일러 추가/수정", "Samsung SEF Trailer Append/Edit"),
                            safe = impact.sefTrailerSafe,
                            reason = impact.sefTrailerReason,
                            label = label
                        )
                        ImpactCard(
                            title = label("XMP / Exif 메타데이터 편집", "XMP / Exif Metadata Edit"),
                            safe = impact.xmpMetadataSafe,
                            reason = impact.xmpMetadataReason,
                            label = label
                        )
                    }
                }

                item {
                    Text(label("파일 구조 및 C2PA 바인딩 커버리지", "Structure & C2PA Binding Coverage"), color = AppColors.NeonBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                items(r.coverageItems) { item ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, color = AppColors.TextPrimary, fontSize = 12.sp)
                            Text("@0x${item.offset.toString(16).uppercase()} (${item.size} B) · ${item.impactNote}", color = AppColors.TextMuted, fontSize = 10.sp)
                        }
                        val statusText = if (item.status == com.multiviewer.parser.c2pa.CoverageStatus.HASHED) label("보호됨 (Hashed)", "Hashed") else label("제외됨 (Excluded)", "Excluded")
                        val statusColor = if (item.status == com.multiviewer.parser.c2pa.CoverageStatus.HASHED) AppColors.NeonBlue else AppColors.NeonYellow
                        Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImpactCard(title: String, safe: Boolean, reason: String, label: (String, String) -> String) {
    Column(modifier = Modifier.fillMaxWidth().background(AppColors.Surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                if (safe) label("안전 (바인딩 유지)", "Safe (Binding preserved)") else label("위험 (바인딩 손상)", "Breaks binding"),
                color = if (safe) AppColors.NeonBlue else AppColors.NeonRed,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
        Text(reason, color = AppColors.TextSecondary, fontSize = 11.sp)
    }
}

