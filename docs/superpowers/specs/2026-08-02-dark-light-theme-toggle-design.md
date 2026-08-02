# Dark/Light Theme Toggle Design

## Goal

Add a light theme alongside the app's existing dark theme, with a menu toggle the user can switch at any time, remembered across restarts. Today the app has exactly one hardcoded dark palette (`Theme.kt`'s `AppColors`); this design makes that palette theme-aware without touching the ~130 existing call sites that already reference it.

## Background

`AppColors` (`app/src/main/kotlin/com/multiviewer/ui/Theme.kt`) is a plain Kotlin `object` with 15 hardcoded `Color(0x...)` constants (background/surface/panel/border tones, five "Neon" accent colors, three text tones, selection/highlight). It's referenced across 17 files and roughly 130 call sites (`Modifier.background(AppColors.X)`, `color = AppColors.X`, etc.). `Main.kt`'s top-level `App` composable wraps everything in `MaterialTheme(colorScheme = darkColorScheme(background = AppColors.Background), typography = AppTypography)` — the app has never had a non-dark code path.

Two categories of color don't go through `AppColors` at all and would look broken (or simply wrong) if left dark-only in an otherwise-light window: `HexView.kt`'s `SelectedByteHighlight` constant (a duplicate of `NeonGreen`'s hex value) and `GopAnalysisView.kt`'s `IFrameColor`/`PFrameColor`/`BFrameColor` frame-type legend colors (a separate muted red/green/blue triad), plus scattered `Color.White`/`Color.Gray` text/border literals (e.g. `BoxTreeView.kt`'s selected-item text, `Components.kt`). By contrast, the `Color.Black` backgrounds used throughout the video/audio/image preview canvases (`FfmpegVideoPlayer.kt`, `FfmpegAudioPlayer.kt`, `PixelInspectorPreview.kt`, `GifFilmstripPlayer.kt`) are deliberately **excluded** from this design — media preview canvases conventionally stay black regardless of app theme (matching Preview.app, most video players, etc.), confirmed with the user.

The app currently has zero settings persistence of any kind (window size/position is recreated fresh every launch) — this design introduces the app's first persisted preference.

## Design

### A. `ThemeMode` and palettes (extending `Theme.kt`)

```kotlin
enum class ThemeMode { DARK, LIGHT }

private data class ThemePalette(
    val background: Color, val surface: Color, val panel: Color, val border: Color,
    val dividerHighlight: Color, val dividerShadow: Color,
    val neonGreen: Color, val neonBlue: Color, val neonPurple: Color, val neonRed: Color, val neonYellow: Color,
    val textPrimary: Color, val textSecondary: Color, val textMuted: Color,
    val selection: Color,
)

private val DarkPalette = ThemePalette(
    background = Color(0xFF1A1D22), surface = Color(0xFF242930), panel = Color(0xFF2A2F36), border = Color(0xFF30363D),
    dividerHighlight = Color(0xFF3D444C), dividerShadow = Color(0xFF0D0F12),
    neonGreen = Color(0xFF39FF14), neonBlue = Color(0xFF00F3FF), neonPurple = Color(0xFFBC13FE), neonRed = Color(0xFFFF3131), neonYellow = Color(0xFFFFF01F),
    textPrimary = Color(0xFFC9D1D9), textSecondary = Color(0xFF8B949E), textMuted = Color(0xFF484F58),
    selection = Color(0xFF264F78),
)

private val LightPalette = ThemePalette(
    background = Color(0xFFFFFFFF), surface = Color(0xFFF3F4F6), panel = Color(0xFFECEEF1), border = Color(0xFFD0D3D8),
    dividerHighlight = Color(0xFFFFFFFF), dividerShadow = Color(0xFFB8BCC2),
    neonGreen = Color(0xFF1A7F37), neonBlue = Color(0xFF0969DA), neonPurple = Color(0xFF8250DF), neonRed = Color(0xFFCF222E), neonYellow = Color(0xFF9A6700),
    textPrimary = Color(0xFF1A1D22), textSecondary = Color(0xFF57606A), textMuted = Color(0xFF8B949E),
    selection = Color(0xFFCFE3FA),
)

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.DARK }
private val LocalThemePalette = staticCompositionLocalOf { DarkPalette }
```

`AppColors`'s existing 15 properties become computed, e.g. `val Background: Color @Composable get() = LocalThemePalette.current.background` (same pattern Compose's own `MaterialTheme.colorScheme.primary` already uses) -- every existing `AppColors.X` call site keeps compiling unchanged, since all of them already run inside `@Composable` functions. `Highlight` stays derived (`NeonBlue.copy(alpha = 0.35f)`), automatically following whichever palette is active.

A new composable, `AppTheme(mode: ThemeMode, content: @Composable () -> Unit)`, provides both `LocalThemeMode` and `LocalThemePalette` (mapping `mode` to `DarkPalette`/`LightPalette`) and wraps `content` in `MaterialTheme(colorScheme = if (mode == ThemeMode.LIGHT) lightColorScheme(background = ...) else darkColorScheme(background = ...), typography = AppTypography)` -- replacing `Main.kt`'s current hardcoded `MaterialTheme(colorScheme = darkColorScheme(...))` call.

### B. Persistence (new file `ThemePreference.kt`)

```kotlin
private val prefs = Preferences.userNodeForPackage(AppColors::class.java) // package com.multiviewer.ui, same package as Theme.kt
private const val THEME_MODE_KEY = "themeMode"

fun loadThemeMode(): ThemeMode =
    if (prefs.get(THEME_MODE_KEY, ThemeMode.DARK.name) == ThemeMode.LIGHT.name) ThemeMode.LIGHT else ThemeMode.DARK

fun saveThemeMode(mode: ThemeMode) {
    prefs.put(THEME_MODE_KEY, mode.name)
}
```

Uses `java.util.prefs.Preferences` (JDK standard library, no new dependency) -- backed by the OS's native preference store (plist on macOS, registry on Windows), a single small key read/write with no measurable startup cost.

### C. Wiring in `Main.kt`

`App`'s top-level composable reads `var themeMode by remember { mutableStateOf(loadThemeMode()) }`, wraps its content in `AppTheme(themeMode) { ... }` (replacing the current direct `MaterialTheme(...)` call), and passes a `(ThemeMode) -> Unit` setter down to the menu bar that both updates `themeMode` and calls `saveThemeMode(it)`.

The `MenuBar` (`Main.kt`, alongside the existing File/모션포토/비트스트림 추출 menus) gets a new `Menu("보기")` containing a `CheckboxItem("라이트 테마", checked = themeMode == ThemeMode.LIGHT, onCheckedChange = { setThemeMode(if (it) ThemeMode.LIGHT else ThemeMode.DARK) })`.

### D. Colors that bypass `AppColors`

- `HexView.kt`'s `SelectedByteHighlight` constant (currently its own hardcoded `Color(0xFF39FF14)`, a duplicate of `NeonGreen`'s dark-mode value): replace with a direct reference to `AppColors.NeonGreen`, removing the duplication and making it theme-aware for free.
- `GopAnalysisView.kt`'s `IFrameColor`/`PFrameColor`/`BFrameColor`: this is a separate muted red/green/blue triad, not a reuse of the Neon accents, so it gets its own light-mode variants (exact values finalized during implementation, following the same "same hue, darker/more saturated for contrast on white" principle as the Neon palette above).
- Scattered `Color.White` text-on-highlighted-background usages (e.g. `BoxTreeView.kt`'s selected tree-item text, `Components.kt`): replaced with `AppColors.TextPrimary`, which resolves to near-white in dark mode (functionally unchanged) and near-black in light mode. Verified against the `Selection` palette entries above that this keeps sufficient contrast in both themes (dark: white-on-`#264F78`; light: near-black-on-`#CFE3FA`).
- `Color.Black` canvas backgrounds in `FfmpegVideoPlayer.kt`/`FfmpegAudioPlayer.kt`/`PixelInspectorPreview.kt`/`GifFilmstripPlayer.kt`: left untouched, deliberately fixed black in both themes.

## Non-Goals

- No "follow system theme" auto-detection -- purely a manual, persisted user choice.
- No per-element theme customization (accent color picker, etc.) -- exactly two fixed themes.
- No changes to the file-open/parsing/decoding pipeline -- this work is UI-styling only and has no measurable effect on load time (confirmed with the user: `Preferences` read is a single cheap key lookup at startup, `CompositionLocal` reads are the same zero-cost mechanism `MaterialTheme.colorScheme` already uses today).
- No redesign of `AppTypography` (font sizes/weights) or `AppScrollbarStyle`'s shape/thickness -- only the colors those already reference via `AppColors` change; the structural values stay the same in both themes.
