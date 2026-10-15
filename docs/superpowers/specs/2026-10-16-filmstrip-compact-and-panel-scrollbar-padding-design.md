# Filmstrip Compact Size & Panel Scrollbar Margin Design

**Date:** 2026-10-16  
**Status:** Approved  

## Problems

1. **Filmstrip panel too tall:** After increasing `gopFilmstripSplit` to `0.4f`, the filmstrip takes 60% of the vertical space, which users feel is too large and dominates the view. The user requested reducing it by about half.
2. **Detail panel scrollbar overlays text:** In `DetailedPropertiesPanel` (Overview, Detailed Properties, Warnings tabs), the `VerticalScrollbar` floats directly over the right edge of `LazyColumn` content without safe margin, partially obscuring text and table values.

## Solutions

1. **Compact Filmstrip Panel Size:**
   - Change `gopFilmstripSplit` in `VideoInspectorUI.kt` from `0.4f` to `0.68f` (GOP 68% : Filmstrip 32%).
   - This reduces the filmstrip height by about half from the previous 60% share, restoring a balanced, compact proportion while retaining the unclipped bottom label track (18dp).
2. **Prevent Detail Panel Scrollbar Text Clipping:**
   - In `ImageInspectorUI.kt`:
     - `OverviewTabContent`: add safe end padding (`contentPadding = PaddingValues(end = 14.dp)` or `Modifier.padding(end = 14.dp)`).
     - `DetailPropertiesTabContent`: add end padding (`Modifier.padding(end = 14.dp)` to LazyColumn).
     - `WarningsTabContent`: add end padding (`Modifier.padding(end = 14.dp)` to LazyColumn).
   - Keeps `VerticalScrollbar` neatly aligned at `Alignment.CenterEnd` at the outer panel border while guaranteeing 14dp clearance so text is 100% unobscured.

## Verification
- Compile and test with `./gradlew :app:compileKotlin` and `./gradlew :app:test`.
- Rerun app: verify compact filmstrip height and clean scrollbar spacing in all three property panel tabs.
