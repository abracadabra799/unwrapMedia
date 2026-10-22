# Motion Photo Builder Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "모션포토 생성" offers a Google-spec mode (output passes every Google Motion Photo 1.0 rule) and a
Samsung-compatible mode (SEF kept; only the unavoidable "bytes after the video" rules fail).

**Architecture:** `MotionPhotoBuilder.createMotionPhoto` gains a `layout: MotionPhotoLayout` parameter
(`GOOGLE_SPEC` default, `SAMSUNG_COMPATIBLE`). The JPEG and HEIC writers branch on it; the XMP builders/merger stop
writing `Item:Padding` on non-first items. The Tools menu exposes both modes. Acceptance is a round-trip test through
`MotionPhotoIntegrityAnalyzer` / `googleSpec`.

**Tech Stack:** Kotlin, JDK 21, Compose Desktop Material3, kotlin.test; ffmpeg/sips for test inputs.

Spec: `docs/superpowers/specs/2026-10-22-motion-photo-builder-modes-design.md`.

## Global Constraints

- Google-spec output: no CRITICAL and no WARNING `googleSpec` rows when saved under the default `<name>_MP.<ext>` name.
- Samsung-compatible output: CRITICAL rows ⊆ {`gmp.layout.video_last`, `gmp.mpvd.last`, `gmp.mpvd.length`}; WARNING rows ⊆ {`gmp.filename`}.
- `Item:Padding` only on the first Directory item; JPEG primary Padding omitted when 0; HEIC/AVIF primary Padding = 8.
- No MicroVideo fields in Google-spec mode. MicroVideo v1 creation unchanged.
- User-visible strings bilingual via `I18n`; Material3 only; no blocking I/O on the UI thread (creation already runs in `runInBackground`).
- Work in `.worktrees/google-motion-photo-spec`, branch `feat/google-motion-photo-spec`; check `git branch --show-current` before each commit.

---

### Task 6: Builder modes with round-trip spec acceptance

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt` (enum, `createMotionPhoto`, `createGoogleMotionPhoto`, `createSamsungHeicMotionPhoto`, `buildGoogleMotionPhotoXmp`, `buildGoogleMotionPhotoHeicXmp`, `mergeMotionPhotoXmp`)
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (`createMotionPhotoFromCurrentTab` ~line 184-260, Tools menu ~line 620-630)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/I18n.kt` (menu labels, success message noting SEF not kept in Google-spec mode)
- Modify: `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt` (only assertions about `Item:Padding` on the video item / primary Padding=0)
- Create: `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoBuilderModesTest.kt`

**Interfaces:**
- Consumes: `MotionPhotoIntegrityAnalyzer.analyze(file, root)`, `report.googleSpec` (`ruleId`, `severity`), `parseFile(File)`, test resource `/google-motion-photo/samsung-style.jpg` (JPEG with SEF trailer).
- Produces: `enum class MotionPhotoLayout { GOOGLE_SPEC, SAMSUNG_COMPATIBLE }` (package `com.multiviewer.parser`); `MotionPhotoBuilder.createMotionPhoto(imageFile, videoFile, outputFile, version = V2_MOTION_PHOTO, layout = MotionPhotoLayout.GOOGLE_SPEC)`.

- [ ] **Step 1: Write the failing acceptance test**

`app/src/test/kotlin/com/multiviewer/ui/MotionPhotoBuilderModesTest.kt`:

```kotlin
package com.multiviewer.ui

import com.multiviewer.parser.MotionPhotoBuilder
import com.multiviewer.parser.MotionPhotoLayout
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.parseFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionPhotoBuilderModesTest {
    private val dir: File = Files.createTempDirectory("mp-builder-modes").toFile().apply { deleteOnExit() }

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }
    }

    private fun stillJpeg() = File(dir, "still.jpg").also {
        if (!it.exists()) run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=64x48", "-frames:v", "1", it.absolutePath)
    }
    private fun stillHeic() = File(dir, "still.heic").also {
        if (!it.exists()) run("sips", "-s", "format", "heic", stillJpeg().absolutePath, "--out", it.absolutePath)
    }
    private fun video() = File(dir, "clip.mp4").also {
        if (!it.exists()) run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=8:duration=1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", it.absolutePath)
    }
    private fun samsungSource() = File(dir, "src-sef.jpg").also {
        if (!it.exists()) File(javaClass.getResource("/google-motion-photo/samsung-style.jpg")!!.toURI()).copyTo(it)
    }

    private fun build(image: File, name: String, layout: MotionPhotoLayout): MotionPhotoIntegrityReport {
        val out = File(dir, name)
        MotionPhotoBuilder.createMotionPhoto(image, video(), out, layout = layout)
        return MotionPhotoIntegrityAnalyzer.analyze(out, parseFile(out))
    }

    private fun MotionPhotoIntegrityReport.ids(severity: SefIntegritySeverity) =
        googleSpec.filter { it.severity == severity }.map { it.ruleId }.toSet()

    private fun assertGoogleClean(report: MotionPhotoIntegrityReport) {
        val bad = report.googleSpec.filter { it.severity == SefIntegritySeverity.CRITICAL || it.severity == SefIntegritySeverity.WARNING }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        assertEquals(SefIntegritySeverity.PASS, report.decodeChecks.single().severity)
    }

    @Test fun `Google-spec JPEG from a plain JPEG passes every rule`() =
        assertGoogleClean(build(stillJpeg(), "plain_MP.jpg", MotionPhotoLayout.GOOGLE_SPEC))

    @Test fun `Google-spec JPEG from a SEF source drops the SEF and passes every rule`() =
        assertGoogleClean(build(samsungSource(), "sef_MP.jpg", MotionPhotoLayout.GOOGLE_SPEC))

    @Test fun `Google-spec HEIC passes every rule`() =
        assertGoogleClean(build(stillHeic(), "still_MP.heic", MotionPhotoLayout.GOOGLE_SPEC))

    @Test fun `Samsung-compatible JPEG only fails the bytes-after-video rule`() {
        val report = build(stillJpeg(), "plain_motion.jpg", MotionPhotoLayout.SAMSUNG_COMPATIBLE)
        assertEquals(setOf("gmp.layout.video_last"), report.ids(SefIntegritySeverity.CRITICAL), report.googleSpec.joinToString("\n"))
        assertTrue(report.ids(SefIntegritySeverity.WARNING) - "gmp.filename" == emptySet<String>(), report.googleSpec.joinToString("\n"))
        assertTrue(MotionPhotoFormat.SAMSUNG_SEF in report.detectedFormats)
        assertEquals(SefIntegritySeverity.PASS, report.decodeChecks.single().severity)
    }

    @Test fun `Samsung-compatible HEIC only fails the bytes-after-video rules`() {
        val report = build(stillHeic(), "still_motion.heic", MotionPhotoLayout.SAMSUNG_COMPATIBLE)
        assertTrue(report.ids(SefIntegritySeverity.CRITICAL) - setOf("gmp.layout.video_last", "gmp.mpvd.last", "gmp.mpvd.length") == emptySet<String>(),
            report.googleSpec.joinToString("\n"))
        assertTrue(report.ids(SefIntegritySeverity.WARNING) - "gmp.filename" == emptySet<String>(), report.googleSpec.joinToString("\n"))
        assertEquals(SefIntegritySeverity.PASS, report.decodeChecks.single().severity)
    }
}
```

- [ ] **Step 2: Run it and record the RED output**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.MotionPhotoBuilderModesTest'`
Expected: compilation FAIL (`Unresolved reference 'MotionPhotoLayout'`). Then, after adding only the enum and the parameter (ignored), run again and record which rows fail per test (e.g. `gmp.item.padding_first_only`, `gmp.layout.video_last`) — this is the baseline the implementation must remove.

- [ ] **Step 3: Implement**

1. Add `enum class MotionPhotoLayout { GOOGLE_SPEC, SAMSUNG_COMPATIBLE }` next to `MotionPhotoFormatVersion`; add `layout: MotionPhotoLayout = MotionPhotoLayout.GOOGLE_SPEC` to `createMotionPhoto`, `createGoogleMotionPhoto` and `createSamsungHeicMotionPhoto`, threading it through.
2. XMP (all modes): `buildGoogleMotionPhotoXmp`, `buildGoogleMotionPhotoHeicXmp` and `mergeMotionPhotoXmp` must not write `Item:Padding` on any item other than the first; the JPEG primary Padding attribute is written only when > 0; HEIC primary Padding stays 8. In GOOGLE_SPEC mode omit the `GCamera:MicroVideo*` attributes (keep them in SAMSUNG_COMPATIBLE for compatibility; they are INFO only). When merging an existing directory that already has a GainMap item, keep it before the MotionPhoto item and keep its Length.
3. JPEG GOOGLE_SPEC: output = `[injected-XMP JPEG bytes (primary + any trailing gain-map JPEG of the base, i.e. the base bytes without the SEF region)][MP4]`; MotionPhoto `Item:Length` = MP4 size; primary Padding = 0 (omitted) unless the base contains bytes between the primary EOI and the video that are not described by a directory item — then those bytes must be described (GainMap item) or removed; never left as an unexplained gap. No SEF blocks, SEFH or SEFT.
4. JPEG SAMSUNG_COMPATIBLE: keep the current byte layout; only the XMP changes from item 2 apply.
5. HEIC GOOGLE_SPEC: output = `[base boxes with XMP item][mpvd(MP4)]` with no `sefd` box; MotionPhoto `Item:Length` = MP4 size (so file length − Length = mpvd payload start). HEIC SAMSUNG_COMPATIBLE: current layout with the item-2 XMP fixes.
6. `Main.kt`: `createMotionPhotoFromCurrentTab` takes a `layout` parameter; default save name `"${name}_MP.$ext"` for GOOGLE_SPEC, `"${name}_motion.$ext"` for SAMSUNG_COMPATIBLE (MicroVideo keeps `_microvideo`). Tools menu: replace the single v2 item with two items calling it with each layout; labels from new `I18n` functions `menuCreateMotionPhotoGoogle(language)` = "모션포토 생성 (구글 스펙)" / "Create Motion Photo (Google spec)" and `menuCreateMotionPhotoSamsung(language)` = "모션포토 생성 (삼성 호환)" / "Create Motion Photo (Samsung compatible)". In GOOGLE_SPEC mode, when the source had SEF data, the success toast appends "원본의 삼성 SEF 데이터는 구글 스펙상 보존되지 않았습니다" / "The source's Samsung SEF data was not kept (not allowed by the Google spec)" — expose whether SEF was dropped as the return value of `createMotionPhoto` (`Boolean`, true when source SEF blocks were dropped).
7. Update `MotionPhotoBuilderTest` assertions that expected `Item:Padding="0"` on the video item or a `Padding="0"` primary — to the new rule. Do not weaken unrelated assertions.

- [ ] **Step 4: GREEN**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.MotionPhotoBuilderModesTest' --tests 'com.multiviewer.parser.MotionPhotoBuilderTest'`, then `./gradlew :app:test` (ThumbnailDiskCacheTest known flaky) and `./gradlew :app:compileKotlin`.
Expected: all pass.

- [ ] **Step 5: Manual check with a real Samsung source (not committed)**

Copy `~/Downloads/20260718_200439.jpg` (or any Samsung still in ~/Downloads) and a real MP4 to a temp dir, create both modes via a small Kotlin test scratch or the CLI-free path in the acceptance test pattern, and run `./gradlew -q :app:run --args="check <out> --decode"`; record the non-PASS/INFO `google_motion_photo` rows. Ultra HDR sources: Google-spec output must include a GainMap item (or the check must show why not).

- [ ] **Step 6: Commit**

```bash
git branch --show-current   # must print feat/google-motion-photo-spec
git add app/src docs
git commit -m "feat: create motion photos in Google-spec or Samsung-compatible mode"
```
