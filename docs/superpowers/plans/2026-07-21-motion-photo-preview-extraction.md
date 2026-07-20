# Motion Photo Preview Video Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second `MotionPhoto` menu item, `미리보기 동영상 추출`, that extracts Samsung's `MotionPhoto_AutoPlay` preview clip (distinct from the full-length video the existing `동영상 추출` extracts) and saves it to a file the user chooses.

**Architecture:** A new detection function mirrors the existing `findEmbeddedVideo`'s SEFD-field validation but selects by name (`MotionPhoto_AutoPlay`) instead of preferring `MotionPhoto_Data`. Wired into `TabState`/`Main.kt` the same way the existing video-extraction menu item already is.

**Tech Stack:** Kotlin 2.0.21, Compose Multiplatform Desktop 1.7.3, `kotlin.test`.

## Global Constraints

- No change to the existing `동영상 추출` item or `findEmbeddedVideo`'s detection/priority logic.
- The new menu item is enabled only when `MotionPhoto_AutoPlay` actually exists in the currently selected tab's file — disabled (not hidden, not an error) otherwise, including for HEIC-only (`mpvd`) and Google-XMP-only motion photos, which have no such field.
- Default save filename: `<original-name-without-extension>_preview.<ext>` — distinct from the main video's `_motion` suffix.
- Extension rule: identical to the existing video's — the preview's own `ftyp.major_brand`, `"qt  "` (trimmed: `"qt"`) → `.mov`, anything else → `.mp4`.

---

### Task 1: `findMotionPhotoPreview` + menu wiring

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoExtractorTest.kt`

**Interfaces:**
- Consumes: `BoxNode`, `EmbeddedVideo(start, end, extension)`, `findFirst` (all existing, `com.multiviewer.parser`), `extractEmbeddedVideo` (existing, used unchanged).
- Produces: `fun findMotionPhotoPreview(root: BoxNode): EmbeddedVideo?` (new, `com.multiviewer.parser`), `TabState.motionPhotoPreview: EmbeddedVideo?` (new).

- [ ] **Step 1: Write the failing tests**

Add these three tests to the end of the `MotionPhotoExtractorTest` class in `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoExtractorTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `findMotionPhotoPreview finds the MotionPhoto_AutoPlay field specifically, even when MotionPhoto_Data is also present`() {
        val autoPlayFtyp = BoxNode(
            type = "ftyp", offset = 200, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "mp42", 200, 4)),
        )
        val autoPlayField = BoxNode(
            type = "MotionPhoto_AutoPlay", offset = 196, headerSize = 4, size = 20,
            children = listOf(autoPlayFtyp),
        )
        val dataFtyp = BoxNode(
            type = "ftyp", offset = 300, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "mp42", 300, 4)),
        )
        val dataField = BoxNode(
            type = "MotionPhoto_Data", offset = 220, headerSize = 4, size = 4096,
            children = listOf(dataFtyp),
        )
        val sefd = BoxNode(
            type = "sefd", offset = 50, headerSize = 0, size = 4200,
            children = listOf(autoPlayField, dataField),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 4250, children = listOf(sefd))

        val preview = findMotionPhotoPreview(root)

        assertEquals(200L, preview?.start)
        assertEquals(216L, preview?.end)
        assertEquals("mp4", preview?.extension)
    }

    @Test
    fun `findMotionPhotoPreview returns null when only MotionPhoto_Data is present`() {
        val dataFtyp = BoxNode(
            type = "ftyp", offset = 220, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "mp42", 220, 4)),
        )
        val dataField = BoxNode(
            type = "MotionPhoto_Data", offset = 200, headerSize = 4, size = 4096,
            children = listOf(dataFtyp),
        )
        val sefd = BoxNode(type = "sefd", offset = 50, headerSize = 0, size = 4200, children = listOf(dataField))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 4250, children = listOf(sefd))

        assertEquals(null, findMotionPhotoPreview(root))
    }

    @Test
    fun `findMotionPhotoPreview returns null when the file has only an mpvd box (HEIC, no sefd)`() {
        val nestedFtyp = BoxNode(
            type = "ftyp", offset = 24, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "isom", 24, 4)),
        )
        val mpvd = BoxNode(type = "mpvd", offset = 16, headerSize = 8, size = 24, children = listOf(nestedFtyp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 40, children = listOf(mpvd))

        assertEquals(null, findMotionPhotoPreview(root))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoExtractorTest"`
Expected: FAIL with "Unresolved reference: findMotionPhotoPreview"

- [ ] **Step 3: Implement `findMotionPhotoPreview`**

In `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt`, add this function directly after `findEmbeddedVideo` (before `extractEmbeddedVideo`):

```kotlin
fun findMotionPhotoPreview(root: BoxNode): EmbeddedVideo? {
    val previewNode = findFirst(root) { it.type == "sefd" }
        ?.children
        ?.find { it.type == "MotionPhoto_AutoPlay" && it.children.firstOrNull()?.type == "ftyp" }
        ?: return null
    val majorBrand = previewNode.children.find { it.type == "ftyp" }
        ?.fields?.find { it.name == "major_brand" }?.value
    val extension = if (majorBrand?.trim() == "qt") "mov" else "mp4"
    return EmbeddedVideo(previewNode.offset + previewNode.headerSize, previewNode.offset + previewNode.size, extension)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoExtractorTest"`
Expected: PASS (11 tests: 8 existing + 3 new)

- [ ] **Step 5: Wire `motionPhotoPreview` into `AppState.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, add `findMotionPhotoPreview` to the existing import block (alongside `findEmbeddedVideo`):

```kotlin
import com.multiviewer.parser.findMotionPhotoPreview
```

Add a new field to `TabState`, directly after `embeddedVideo`:

```kotlin
    var motionPhotoPreview: EmbeddedVideo? by mutableStateOf(null)
```

In `openFile`, directly after the existing `tab.embeddedVideo = try { ... } catch (e: Exception) { null }` block, add:

```kotlin
            tab.motionPhotoPreview = try {
                findMotionPhotoPreview(root)
            } catch (e: Exception) {
                null
            }
```

- [ ] **Step 6: Add the menu item and extraction function in `Main.kt`**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, add this function directly after `extractMotionPhotoVideo`:

```kotlin
private fun extractMotionPhotoPreview(appState: AppState, tab: TabState) {
    val video = tab.motionPhotoPreview ?: return
    val dialog = FileDialog(null as Frame?, "Save extracted preview video", FileDialog.SAVE)
    dialog.file = "${tab.file.nameWithoutExtension}_preview.${video.extension}"
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName == null || directory == null) return
    val destination = File(directory, fileName)
    appState.statusMessage = try {
        extractEmbeddedVideo(tab.file, video, destination)
        "Saved to ${destination.name}"
    } catch (e: Exception) {
        "Failed to save: ${e.message ?: e.toString()}"
    }
}
```

In the `Menu("MotionPhoto") { ... }` block, add a second `Item` directly after the existing `동영상 추출` item:

```kotlin
                Item(
                    "미리보기 동영상 추출",
                    enabled = currentTab?.motionPhotoPreview != null,
                    onClick = { currentTab?.let { extractMotionPhotoPreview(appState, it) } },
                )
```

So the full `Menu("MotionPhoto") { ... }` block reads:

```kotlin
            Menu("MotionPhoto") {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                Item(
                    "동영상 추출",
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { currentTab?.let { extractMotionPhotoVideo(appState, it) } },
                )
                Item(
                    "미리보기 동영상 추출",
                    enabled = currentTab?.motionPhotoPreview != null,
                    onClick = { currentTab?.let { extractMotionPhotoPreview(appState, it) } },
                )
            }
```

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (153 existing + 3 new = 156)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt \
        app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
        app/src/main/kotlin/com/multiviewer/Main.kt \
        app/src/test/kotlin/com/multiviewer/parser/MotionPhotoExtractorTest.kt
git commit -m "feat: extract a motion photo's MotionPhoto_AutoPlay preview clip"
```

- [ ] **Step 9: Manual verification note**

Launch the app (`./gradlew :app:run`) and open a real Samsung Motion Photo JPEG (a file known to have both `MotionPhoto_AutoPlay` and `MotionPhoto_Data`, e.g. the same samples used to verify the earlier `MotionPhoto_Data` bug fix). Confirm `MotionPhoto > 미리보기 동영상 추출` is enabled and produces a short, playable clip distinct from (and shorter than) what `동영상 추출` produces. Confirm it's disabled for a HEIC-only or Google-XMP-only motion photo, and for an ordinary non-motion-photo file.
