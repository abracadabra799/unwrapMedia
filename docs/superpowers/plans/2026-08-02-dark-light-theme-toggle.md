# Dark/Light Theme Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a light theme alongside the app's existing dark theme, toggleable from a menu, persisted across restarts, with zero changes required at the ~130 existing `AppColors.X` call sites.

**Architecture:** `Theme.kt`'s `AppColors`/`AppTypography`/`AppScrollbarStyle` become `@Composable`-computed properties reading from a new `LocalThemePalette` CompositionLocal (the same pattern Compose's own `MaterialTheme.colorScheme` already uses) instead of plain hardcoded constants. A new `ThemePreference.kt` persists the chosen `ThemeMode` via `java.util.prefs.Preferences`. `Main.kt` wraps its content in a new `AppTheme(mode)` composable and adds a menu checkbox to toggle it.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop (Compose 1.7.3), `java.util.prefs.Preferences` (JDK standard library, no new dependency), `kotlin.test` (JUnit5 platform).

## Global Constraints

- Every existing `AppColors.X` call site (17 files, ~130 occurrences) must keep compiling completely unchanged.
- `Color.Black` canvas backgrounds in `FfmpegVideoPlayer.kt`/`FfmpegAudioPlayer.kt`/`PixelInspectorPreview.kt`/`GifFilmstripPlayer.kt` are explicitly out of scope -- stay fixed black in both themes, do not touch.
- Exactly two themes (`DARK`, `LIGHT`), manually toggled -- no "follow system theme" auto-detection, no per-element customization.
- Theme choice persists across restarts via `java.util.prefs.Preferences`.
- Toggle lives in the existing `MenuBar` (`Main.kt`) as a new `Menu("보기")` with a `CheckboxItem("라이트 테마", ...)`.
- Light palette values (exact, from the approved spec):
  - `background=#FFFFFF surface=#F3F4F6 panel=#ECEEF1 border=#D0D3D8`
  - `dividerHighlight=#FFFFFF dividerShadow=#B8BCC2`
  - `neonGreen=#1A7F37 neonBlue=#0969DA neonPurple=#8250DF neonRed=#CF222E neonYellow=#9A6700`
  - `textPrimary=#1A1D22 textSecondary=#57606A textMuted=#8B949E`
  - `selection=#CFE3FA`
- Spec reference: `docs/superpowers/specs/2026-08-02-dark-light-theme-toggle-design.md`.

---

## Task 1: Theme.kt -- palettes, `ThemeMode`, `AppColors`/`AppTypography`/`AppScrollbarStyle` become theme-aware

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/Theme.kt` (full rewrite)

**Interfaces:**
- Produces (package `com.multiviewer.ui`, consumed by Tasks 2/3):
  ```kotlin
  enum class ThemeMode { DARK, LIGHT }
  @Composable fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit)
  ```
  Plus `AppColors.FrameTypeI`/`FrameTypeP`/`FrameTypeB` (new, for Task 3's `GopAnalysisView.kt` fix), and all 15 pre-existing `AppColors` properties, `AppTypography`, `AppScrollbarStyle` (all now `@Composable`-computed but with unchanged names/types).

Important finding from research: `AppTypography` and `AppScrollbarStyle` are currently plain top-level `val`s whose initializers read `AppColors.TextPrimary`/`TextSecondary`/`TextMuted` directly -- once those become `@Composable get()` properties, `AppTypography`/`AppScrollbarStyle` **must also** become `@Composable get()` properties, or the file won't compile (a top-level `val` initializer cannot call a `@Composable` getter). Both are used exclusively from composable contexts today (`MaterialTheme(typography = AppTypography, ...)`, `CompositionLocalProvider(LocalScrollbarStyle provides AppScrollbarStyle)`, and `AppTypography.labelLarge.copy(...)` calls throughout UI files), so this conversion is safe.

- [ ] **Step 1: Replace the full contents of `Theme.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { DARK, LIGHT }

private data class ThemePalette(
    val background: Color, val surface: Color, val panel: Color, val border: Color,
    val dividerHighlight: Color, val dividerShadow: Color,
    val neonGreen: Color, val neonBlue: Color, val neonPurple: Color, val neonRed: Color, val neonYellow: Color,
    val textPrimary: Color, val textSecondary: Color, val textMuted: Color,
    val selection: Color,
    val frameTypeI: Color, val frameTypeP: Color, val frameTypeB: Color,
)

private val DarkPalette = ThemePalette(
    background = Color(0xFF1A1D22), surface = Color(0xFF242930), panel = Color(0xFF2A2F36), border = Color(0xFF30363D),
    dividerHighlight = Color(0xFF3D444C), dividerShadow = Color(0xFF0D0F12),
    neonGreen = Color(0xFF39FF14), neonBlue = Color(0xFF00F3FF), neonPurple = Color(0xFFBC13FE), neonRed = Color(0xFFFF3131), neonYellow = Color(0xFFFFF01F),
    textPrimary = Color(0xFFC9D1D9), textSecondary = Color(0xFF8B949E), textMuted = Color(0xFF484F58),
    selection = Color(0xFF264F78),
    frameTypeI = Color(0xFFE06C75), frameTypeP = Color(0xFF7EC699), frameTypeB = Color(0xFF6CA6E0),
)

private val LightPalette = ThemePalette(
    background = Color(0xFFFFFFFF), surface = Color(0xFFF3F4F6), panel = Color(0xFFECEEF1), border = Color(0xFFD0D3D8),
    dividerHighlight = Color(0xFFFFFFFF), dividerShadow = Color(0xFFB8BCC2),
    neonGreen = Color(0xFF1A7F37), neonBlue = Color(0xFF0969DA), neonPurple = Color(0xFF8250DF), neonRed = Color(0xFFCF222E), neonYellow = Color(0xFF9A6700),
    textPrimary = Color(0xFF1A1D22), textSecondary = Color(0xFF57606A), textMuted = Color(0xFF8B949E),
    selection = Color(0xFFCFE3FA),
    frameTypeI = Color(0xFFC53030), frameTypeP = Color(0xFF2F855A), frameTypeB = Color(0xFF2B6CB0),
)

private val LocalThemePalette = staticCompositionLocalOf { DarkPalette }

object AppColors {
    val Background: Color @Composable get() = LocalThemePalette.current.background
    val Surface: Color @Composable get() = LocalThemePalette.current.surface
    val Panel: Color @Composable get() = LocalThemePalette.current.panel
    val Border: Color @Composable get() = LocalThemePalette.current.border

    // A flat single-color divider line read as flat on the dark background -- these two, used
    // together (highlight on the side facing the notional light source, shadow on the other),
    // give panel-resize handles a raised-ridge look instead: lighter than Border catches light,
    // darker than Background casts a shadow.
    val DividerHighlight: Color @Composable get() = LocalThemePalette.current.dividerHighlight
    val DividerShadow: Color @Composable get() = LocalThemePalette.current.dividerShadow

    val NeonGreen: Color @Composable get() = LocalThemePalette.current.neonGreen
    val NeonBlue: Color @Composable get() = LocalThemePalette.current.neonBlue
    val NeonPurple: Color @Composable get() = LocalThemePalette.current.neonPurple
    val NeonRed: Color @Composable get() = LocalThemePalette.current.neonRed
    val NeonYellow: Color @Composable get() = LocalThemePalette.current.neonYellow

    val TextPrimary: Color @Composable get() = LocalThemePalette.current.textPrimary
    val TextSecondary: Color @Composable get() = LocalThemePalette.current.textSecondary
    val TextMuted: Color @Composable get() = LocalThemePalette.current.textMuted

    val Selection: Color @Composable get() = LocalThemePalette.current.selection
    // Marks the byte range for whatever node is selected in the structure tree (HexView) --
    // NeonBlue instead of the previous mustard/gold, both to read more clearly on the dark
    // background and to stay visually distinct from HexView's own manual drag-selection
    // highlight, which is green.
    val Highlight: Color @Composable get() = NeonBlue.copy(alpha = 0.35f)

    // Frame-type legend/bar colors for GopAnalysisView -- a separate muted palette from the Neon
    // accents above (neon reads fine for a single small badge but is overwhelming across a wide
    // row of adjacent bars), promoted here (rather than staying as GopAnalysisView-local
    // constants) so they can be theme-aware via the same CompositionLocal-backed mechanism.
    val FrameTypeI: Color @Composable get() = LocalThemePalette.current.frameTypeI
    val FrameTypeP: Color @Composable get() = LocalThemePalette.current.frameTypeP
    val FrameTypeB: Color @Composable get() = LocalThemePalette.current.frameTypeB
}

val AppTypography: Typography
    @Composable get() = Typography(
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            letterSpacing = 0.5.sp,
            color = AppColors.TextPrimary
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = AppColors.TextSecondary
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = AppColors.TextPrimary
        )
    )

// Compose Desktop's default ScrollbarStyle uses a low-alpha black thumb, tuned for a light
// background -- against this app's dark panels it's essentially invisible. Provided app-wide via
// LocalScrollbarStyle in Main.kt so every scrollbar (GOP graph, summary panels, etc.) is visible.
val AppScrollbarStyle: ScrollbarStyle
    @Composable get() = ScrollbarStyle(
        minimalHeight = 16.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 300,
        unhoverColor = AppColors.TextMuted.copy(alpha = 0.6f),
        hoverColor = AppColors.TextSecondary,
    )

// Wraps app content with the chosen theme's palette (available to AppColors/AppTypography/
// AppScrollbarStyle via LocalThemePalette) and the matching Material3 color scheme. Replaces
// Main.kt's previous direct `MaterialTheme(colorScheme = darkColorScheme(...), ...)` call.
@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val palette = if (mode == ThemeMode.LIGHT) LightPalette else DarkPalette
    CompositionLocalProvider(LocalThemePalette provides palette) {
        val colorScheme = if (mode == ThemeMode.LIGHT) {
            lightColorScheme(background = AppColors.Background)
        } else {
            darkColorScheme(background = AppColors.Background)
        }
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography) {
            content()
        }
    }
}
```

- [ ] **Step 2: Compile the whole project**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL. This is the critical check for this task -- it confirms every one of the ~130 existing `AppColors.X`/`AppTypography.X`/`AppScrollbarStyle` call sites across all 17 consumer files still compiles now that those are `@Composable`-computed properties instead of plain constants. If it fails, the error will name the exact file/line using one of these from a non-composable context -- that call site needs to be looked at individually (report it rather than guessing a fix).

- [ ] **Step 3: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, same pass count as before this change (this task changes no runtime logic reachable by existing tests -- `LocalThemePalette`'s default is `DarkPalette`, identical to the previous hardcoded values, so nothing here should be able to fail a test that passed before).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/Theme.kt
git commit -m "feat: make AppColors/AppTypography/AppScrollbarStyle theme-aware"
```

---

## Task 2: Theme preference persistence (`ThemePreference.kt`)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/ThemePreference.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ThemePreferenceTest.kt`

**Interfaces:**
- Consumes (from Task 1, package `com.multiviewer.ui`): `ThemeMode`.
- Produces (consumed by Task 3): `fun loadThemeMode(): ThemeMode`, `fun saveThemeMode(mode: ThemeMode)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/ui/ThemePreferenceTest.kt`:

```kotlin
package com.multiviewer.ui

import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePreferenceTest {
    // Matches ThemePreference.kt's own THEME_MODE_KEY value -- kept as a literal here (rather
    // than importing the private constant) since tests intentionally exercise the public
    // loadThemeMode/saveThemeMode functions, only reaching into the raw Preferences node to set
    // up a known "nothing saved yet" starting state.
    private val prefs = Preferences.userNodeForPackage(AppColors::class.java)

    @Test
    fun `defaults to DARK when nothing has been saved`() {
        prefs.remove("themeMode")
        assertEquals(ThemeMode.DARK, loadThemeMode())
    }

    @Test
    fun `round-trips LIGHT`() {
        saveThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, loadThemeMode())
    }

    @Test
    fun `round-trips DARK`() {
        saveThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, loadThemeMode())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.ThemePreferenceTest"`
Expected: compile failure -- `loadThemeMode`/`saveThemeMode` are unresolved references (the implementation file doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/ThemePreference.kt`:

```kotlin
package com.multiviewer.ui

import java.util.prefs.Preferences

// Uses java.util.prefs (JDK standard library, no new dependency) as this app's first-ever
// persisted preference -- backed by the OS's native preference store (plist on macOS, registry
// on Windows), a single small key read/write with no measurable cost at startup.
private val themePreferences: Preferences = Preferences.userNodeForPackage(AppColors::class.java)
private const val THEME_MODE_KEY = "themeMode"

fun loadThemeMode(): ThemeMode =
    if (themePreferences.get(THEME_MODE_KEY, ThemeMode.DARK.name) == ThemeMode.LIGHT.name) {
        ThemeMode.LIGHT
    } else {
        ThemeMode.DARK
    }

fun saveThemeMode(mode: ThemeMode) {
    themePreferences.put(THEME_MODE_KEY, mode.name)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.ThemePreferenceTest"`
Expected: all 3 tests PASS.

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ThemePreference.kt app/src/test/kotlin/com/multiviewer/ui/ThemePreferenceTest.kt
git commit -m "feat: persist theme mode choice via java.util.prefs"
```

---

## Task 3: Wire the toggle into Main.kt and fix bypass colors

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (state + `Menu("보기")` + `AppTheme` wiring)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/HexView.kt` (`SelectedByteHighlight`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt` (`IFrameColor`/`PFrameColor`/`BFrameColor`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/BoxTreeView.kt` (selected-row text color)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/Components.kt` (`PreviewCaption` text color)

**Interfaces:**
- Consumes (from Task 1): `ThemeMode`, `AppTheme(mode, content)`, `AppColors.FrameTypeI`/`FrameTypeP`/`FrameTypeB`. (from Task 2): `loadThemeMode()`, `saveThemeMode(mode)`.

- [ ] **Step 1: Add theme state to `Main.kt`**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, find:

```kotlin
    Window(onCloseRequest = ::exitApplication, title = "unwrapMedia", state = windowState) {
        MenuBar {
```

Replace with:

```kotlin
    Window(onCloseRequest = ::exitApplication, title = "unwrapMedia", state = windowState) {
        var themeMode by remember { mutableStateOf(loadThemeMode()) }
        MenuBar {
```

- [ ] **Step 2: Add the "보기" menu with a theme checkbox**

In the same file, find the end of the existing `MenuBar` block:

```kotlin
                Item(
                    "오디오 추출 (.m4a)",
                    enabled = hasAudioTrack,
                    onClick = { currentTab?.let { extractAudioTrackFromCurrentFile(appState, it) } },
                )
            }
        }
```

Replace with (adds a new `Menu("보기")` right before the `MenuBar`'s closing brace):

```kotlin
                Item(
                    "오디오 추출 (.m4a)",
                    enabled = hasAudioTrack,
                    onClick = { currentTab?.let { extractAudioTrackFromCurrentFile(appState, it) } },
                )
            }
            Menu("보기") {
                CheckboxItem(
                    "라이트 테마",
                    checked = themeMode == ThemeMode.LIGHT,
                    onCheckedChange = { checked ->
                        themeMode = if (checked) ThemeMode.LIGHT else ThemeMode.DARK
                        saveThemeMode(themeMode)
                    },
                )
            }
        }
```

- [ ] **Step 3: Wrap content in `AppTheme` instead of the old direct `MaterialTheme` call**

In the same file, find:

```kotlin
        MaterialTheme(colorScheme = darkColorScheme(background = AppColors.Background), typography = AppTypography) {
          CompositionLocalProvider(LocalScrollbarStyle provides AppScrollbarStyle) {
```

Replace with (same indentation, only the opening call itself changes -- everything from `CompositionLocalProvider` down through the file's closing braces is untouched):

```kotlin
        AppTheme(themeMode) {
          CompositionLocalProvider(LocalScrollbarStyle provides AppScrollbarStyle) {
```

- [ ] **Step 4: Fix `HexView.kt`'s `SelectedByteHighlight`**

In `app/src/main/kotlin/com/multiviewer/ui/HexView.kt`, find:

```kotlin
private val SelectedByteHighlight = Color(0xFF39FF14).copy(alpha = 0.35f)
```

Delete this line entirely (it's a top-level `val`, not inside a `@Composable` function -- it can no longer read `AppColors.NeonGreen`, which is now `@Composable`-only, so the fix is to inline the expression at each of its two usage sites instead of keeping a standalone constant).

Then find both usage sites:

```kotlin
                                        withStyle(SpanStyle(background = SelectedByteHighlight)) { append(hex) }
```

and

```kotlin
                                    withStyle(SpanStyle(background = SelectedByteHighlight)) { append(char) }
```

Replace `SelectedByteHighlight` with `AppColors.NeonGreen.copy(alpha = 0.35f)` at both sites (both are already inside this composable's rendering code, so `AppColors.NeonGreen` resolves fine there).

- [ ] **Step 5: Fix `GopAnalysisView.kt`'s frame-type colors**

In `app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`, find:

```kotlin
// Muted, desaturated palette instead of the app's full-saturation neon accents -- neon reads fine
// for a single small badge but was overwhelming across a wide row of adjacent bars.
private val IFrameColor = Color(0xFFE06C75)
private val PFrameColor = Color(0xFF7EC699)
private val BFrameColor = Color(0xFF6CA6E0)

private fun colorForFrameType(type: Char) = when (type) {
    'I' -> IFrameColor
    'P' -> PFrameColor
    'B' -> BFrameColor
    else -> AppColors.TextSecondary
}
```

Replace with (the muted palette moved into `AppColors.FrameTypeI`/`FrameTypeP`/`FrameTypeB` in Task 1; `colorForFrameType` becomes `@Composable` since it now reads `@Composable`-computed properties -- its call sites are already inside composable rendering code):

```kotlin
@Composable
private fun colorForFrameType(type: Char): Color = when (type) {
    'I' -> AppColors.FrameTypeI
    'P' -> AppColors.FrameTypeP
    'B' -> AppColors.FrameTypeB
    else -> AppColors.TextSecondary
}
```

- [ ] **Step 6: Fix `BoxTreeView.kt`'s selected-row text color**

In `app/src/main/kotlin/com/multiviewer/ui/BoxTreeView.kt`, find:

```kotlin
                Text(text = buildLabel(row.node), color = if (isSelected) Color.White else AppColors.TextPrimary)
```

Replace with (both branches become `AppColors.TextPrimary` -- verified against the palette values that this keeps good contrast against `AppColors.Selection` in both themes: dark mode's `TextPrimary` `#C9D1D9` on `Selection` `#264F78` is still light-on-dark, near the old pure white; light mode's `TextPrimary` `#1A1D22` on `Selection` `#CFE3FA` is dark-on-light -- so the conditional is now redundant and collapses to one expression):

```kotlin
                Text(text = buildLabel(row.node), color = AppColors.TextPrimary)
```

- [ ] **Step 7: Fix `Components.kt`'s `PreviewCaption` text color**

In `app/src/main/kotlin/com/multiviewer/ui/Components.kt`, find:

```kotlin
        style = AppTypography.labelLarge.copy(fontSize = 12.sp, color = Color.White),
```

Replace with (the scrim behind this text, `AppColors.Background.copy(alpha = 0.75f)` on the line above, is already theme-aware from Task 1 -- pairing the text with `AppColors.TextPrimary` keeps text/scrim contrast consistent in both themes: near-white text on a near-black scrim in dark mode, near-black text on a near-white scrim in light mode):

```kotlin
        style = AppTypography.labelLarge.copy(fontSize = 12.sp, color = AppColors.TextPrimary),
```

- [ ] **Step 8: Compile the whole project**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt app/src/main/kotlin/com/multiviewer/ui/HexView.kt app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt app/src/main/kotlin/com/multiviewer/ui/BoxTreeView.kt app/src/main/kotlin/com/multiviewer/ui/Components.kt
git commit -m "feat: wire dark/light theme toggle into the menu bar and fix bypass colors"
```

---

## Task 4: Controller-performed manual verification

This task has no subagent dispatch -- run it directly in the controlling session, matching this project's established precedent for real runtime/manual verification (e.g. `docs/superpowers/plans/2026-08-01-gif-animation-playback.md` Task 3).

- [ ] **Step 1: Launch the app**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:run
```

- [ ] **Step 2: Verify against the plan's Global Constraints**

Confirm each of the following, and note any that fail:
- App opens in dark mode by default on a fresh Preferences state (or whatever was last saved, if this machine has run the app before this feature existed -- either is correct, since the default is `DARK`).
- The menu bar has a new "보기" menu with a "라이트 테마" checkbox item.
- Checking it switches the whole window to the light palette immediately -- background, panels, borders, text, and the Neon accent colors (check a few different screens: image inspector, video inspector with GOP frame bars, hex view with a selected byte range, a tree-view row selection) all read clearly with good contrast, nothing illegible or invisible.
- Un-checking it switches back to dark immediately.
- Video/audio/image preview canvases stay black in both themes (not switched to white).
- Quit the app (with light theme checked) and relaunch: it reopens in light theme (persistence confirmed). Toggle back to dark, quit, relaunch: reopens in dark.

- [ ] **Step 3: Update the progress ledger**

Append a summary line to `.git/sdd/progress.md` recording Task 1-3 commit ranges and the outcome of this manual verification (pass, or any issues found and how they were resolved).
