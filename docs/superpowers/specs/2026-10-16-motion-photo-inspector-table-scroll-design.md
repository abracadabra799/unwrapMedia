# Motion Photo Inspector Table Scroll & Dynamic Row Height Design

**Date:** 2026-10-16  
**Status:** Approved  

## 1. Problem

In `MotionPhotoInspectorWindow`, table rows currently impose `maxLines = 1` or `maxLines = 2` with `TextOverflow.Ellipsis` on the Expected, Actual, and Detail columns. When text is long (such as detailed decoder logs, list of non-motion SEF keys, or extensive XMP descriptions), the text gets truncated and cut off by `...`. Furthermore, without a horizontal scrollbar, columns are constrained by the current window width.

## 2. Solution

1. **Dynamic Row Height & Word Wrap:**
   - Remove `maxLines` and `TextOverflow.Ellipsis` constraints on Expected, Actual, and Detail cells.
   - Text naturally wraps across multiple lines, and each row dynamically expands its height to fit the full content.

2. **Generous Column Widths:**
   - Status: `64.dp`
   - Check Item: `220.dp`
   - Expected: `260.dp`
   - Actual: `260.dp`
   - Detail: `520.dp`
   - Total table content width: `1350.dp`.

3. **Horizontal Scroll & Scrollbar:**
   - Wrap the cards container with a horizontal scroll modifier (`Modifier.horizontalScroll(hScroll)`).
   - Place a `HorizontalScrollbar(adapter = rememberScrollbarAdapter(hScroll))` at the bottom of the inspection table area.
   - Pair with `VerticalScrollbar(adapter = rememberScrollbarAdapter(vListState))` at the right edge so users can navigate both vertically and horizontally smoothly.

4. **Text Selection & Copying:**
   - Wrap table contents with `SelectionContainer` so engineers can drag-select and copy any part of long error logs, keys, or byte values directly.

## 3. Verification

- Run `./gradlew :app:compileKotlin` and `./gradlew :app:test`.
- Rerun app: open Motion Photo Inspector on files with long SEF keys and long error logs; verify rows expand to full text, horizontal scrollbar functions properly, and text is selectable.
