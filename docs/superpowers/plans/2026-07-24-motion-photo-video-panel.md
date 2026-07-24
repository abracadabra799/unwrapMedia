# Motion Photo Video Preview Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third panel to `ImageInspectorUI`'s top row that plays a Motion Photo's embedded video live, only when one is present.

**Architecture:** A new private composable, `MotionPhotoVideoPreview`, lazily extracts `tab.embeddedVideo`'s byte range to a temp file (via the existing `extractEmbeddedVideo`) inside a `LaunchedEffect`, then hands that file to the existing `VlcVideoPlayer`. The top `Row` gains a third `weight(1f)` `Box` wrapping it, present only when `tab.embeddedVideo != null` — `Row`'s equal weighting automatically reflows from 2-way to 3-way split with no manual width math.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop, `kotlinx-coroutines-core:1.8.0` (existing transitive dependency via Compose runtime — confirmed present via `./gradlew app:dependencies`, no new dependency added).

## Global Constraints

- Uses `tab.embeddedVideo` (the full motion clip), not `tab.motionPhotoPreview` (confirmed with user).
- Extraction is lazy — only happens when the third panel actually composes (i.e., only for files that have `embeddedVideo`), via `LaunchedEffect`, not eagerly in `AppState.openFile`.
- The extracted temp file is deleted when the composable leaves composition (`DisposableEffect`'s `onDispose`), with `deleteOnExit()` as a backstop.
- Files without `tab.embeddedVideo` must render byte-identical to today — no layout change, no extra `Box`.
- No changes to `Main.kt`'s existing "Save extracted video" menu action, `VideoInspectorUI.kt`, or `MotionPhotoExtractor.kt` — all reused as-is.
- Spec: `docs/superpowers/specs/2026-07-24-motion-photo-video-panel-design.md`.

---

### Task 1: Add the Motion Photo video panel

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`

**Interfaces:**
- Consumes: `TabState.embeddedVideo: EmbeddedVideo?` (existing, `AppState.kt`), `EmbeddedVideo` and `extractEmbeddedVideo(source: File, video: EmbeddedVideo, destination: File)` (existing, `com.multiviewer.parser.MotionPhotoExtractor.kt`), `VlcVideoPlayer(file: File, modifier: Modifier = Modifier)` (existing, `com.multiviewer.ui.VlcVideoPlayer.kt`, same package — no import needed).
- Produces: nothing consumed by other tasks — this is the only task in this plan.

No automated test: `ImageInspectorUI.kt` has no existing Compose UI test coverage and this is a pure Compose-layer addition, matching this project's established convention for this file (confirmed in the prior HEIC-fallback plan).

- [ ] **Step 1: Add the new imports**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, replace the import block:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
```

with:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.extractEmbeddedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
```

- [ ] **Step 2: Add the third panel to the top `Row`**

In the same file, replace:

```kotlin
                    // Right Panel: Primary Image View
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, AppColors.Border)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        forensic.bitmap?.let {
                            PixelInspectorPreview(it)
                        } ?: Text(
                            if (forensic.isDecodingFallback) "Decoding via ffmpeg..." else "Primary Image Decoding Failed",
                            color = if (forensic.isDecodingFallback) AppColors.TextSecondary else AppColors.NeonRed,
                            fontSize = 12.sp,
                        )
                        
                        Text("PRIMARY IMAGE VIEW", 
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp), 
                            style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = AppColors.NeonGreen)
                        )
                    }
                }
```

with:

```kotlin
                    // Middle Panel: Primary Image View
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, AppColors.Border)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        forensic.bitmap?.let {
                            PixelInspectorPreview(it)
                        } ?: Text(
                            if (forensic.isDecodingFallback) "Decoding via ffmpeg..." else "Primary Image Decoding Failed",
                            color = if (forensic.isDecodingFallback) AppColors.TextSecondary else AppColors.NeonRed,
                            fontSize = 12.sp,
                        )
                        
                        Text("PRIMARY IMAGE VIEW", 
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp), 
                            style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = AppColors.NeonGreen)
                        )
                    }

                    // Right Panel: Motion Photo Video (only when the file has an embedded motion video)
                    val embeddedVideo = tab.embeddedVideo
                    if (embeddedVideo != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(0.5.dp, AppColors.Border)
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            MotionPhotoVideoPreview(tab, embeddedVideo)

                            Text("MOTION PHOTO VIDEO",
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                                style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = AppColors.NeonPurple)
                            )
                        }
                    }
                }
```

(Only the comment `// Right Panel: Primary Image View` → `// Middle Panel: Primary Image View` changes in the existing block — everything else there is untouched. The new `if (embeddedVideo != null) { ... }` block is added immediately after it, still inside the same `Row`.)

- [ ] **Step 3: Add the `MotionPhotoVideoPreview` composable**

In the same file, immediately after the closing `}` of the `ImageInspectorUI` function (i.e., directly before the existing `@Composable fun DetailedPropertiesPanel(tab: TabState) {` declaration), add:

```kotlin
@Composable
private fun MotionPhotoVideoPreview(tab: TabState, video: EmbeddedVideo) {
    var extractedFile by remember(tab.file, video) { mutableStateOf<File?>(null) }

    LaunchedEffect(tab.file, video) {
        val temp = withContext(Dispatchers.IO) {
            val dest = File.createTempFile("motion-photo-preview-", ".${video.extension}")
            dest.deleteOnExit()
            extractEmbeddedVideo(tab.file, video, dest)
            dest
        }
        extractedFile = temp
    }

    DisposableEffect(tab.file, video) {
        onDispose { extractedFile?.delete() }
    }

    val file = extractedFile
    if (file != null) {
        VlcVideoPlayer(file, modifier = Modifier.fillMaxSize())
    } else {
        Text("Extracting motion video...", color = Color.Gray, fontSize = 12.sp)
    }
}
```

- [ ] **Step 4: Run the full test suite to check for regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed (confirms the file compiles cleanly — no automated test exists for this new UI code itself).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "Add Motion Photo video preview panel to ImageInspectorUI"
```

- [ ] **Step 6: Build and run the app**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:run`
Expected: The app window opens with no build errors.

- [ ] **Step 7: Manually verify against a real Motion Photo file**

Open a Motion Photo HEIC or JPEG with a detected embedded video (e.g. one of the Samsung motion photos in `/Users/dong.kim/Downloads/` used during the earlier HEIC/ffmpeg investigation — files like `20260715_223835.heic` or `20260718_200439.jpg`, both of which have a companion `..._motion.mp4`).

Expected:
- Three panels appear in the top row: embedded thumbnail (left, blue label), primary image (middle, green label), motion video (right, purple label "MOTION PHOTO VIDEO").
- The motion video panel briefly shows "Extracting motion video..." then displays the `VlcVideoPlayer` UI (play button, "Decoding stream..." until played) and plays correctly when clicked, consistent with the mp4 playback fix already verified working.

- [ ] **Step 8: Manually verify against an ordinary image (no regression)**

Open an image file with no embedded motion video (e.g. `/Library/User Pictures/Flowers/Whiterose.heic`, or any plain `.jpg`).

Expected: Exactly the same 2-panel layout as before this change — no third panel, no "Extracting motion video..." text, nothing different.
