# XMP viewer word wrap

Long XMP lines should wrap to the current viewport like an editor. Remove the unbounded horizontal scroll measurement from FileXmpWindow and explicitly enable softWrap. Preserve existing XML formatting, selection, original copy text, and vertical scrolling. No parser changes or dependencies.
