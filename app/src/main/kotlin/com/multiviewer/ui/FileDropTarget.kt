package com.multiviewer.ui

import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import javax.swing.SwingUtilities

/**
 * Wires OS file-drag-and-drop onto [window] for a Compose Desktop window.
 *
 * Compose Desktop renders into a deeply-nested Skiko SkiaLayer several levels below
 * `window` (window -> JRootPane -> JLayeredPane -> ... -> SkiaLayer) -- that SkiaLayer
 * is the only real heavyweight/native surface actually receiving OS drag events.
 * Attaching a DropTarget to `window` or `window.contentPane` alone never sees a drag
 * at all (confirmed: dragEnter never fired). Attaching recursively to every component
 * in the tree reaches the SkiaLayer regardless of Compose Desktop's internal structure,
 * without depending on that structure by name/type.
 *
 * [onDragPosition] is called with the drag location converted to [window]-relative
 * coordinates while a file drag hovers over the window, and with `null` once the drag
 * leaves the window or a drop completes -- callers that don't need drag-hover feedback
 * can leave it as the no-op default. [onFilesDropped] is called with the raw dropped
 * files and the window-relative drop location; this helper only accepts/rejects the
 * drag flavor and reports what was dropped -- filtering and interpreting the file list
 * is the caller's job.
 */
fun attachFileDropTarget(
    window: Window,
    onDragPosition: (Point?) -> Unit = {},
    onFilesDropped: (files: List<File>, location: Point) -> Unit,
) {
    fun relativePoint(sourceComponent: Component, location: Point): Point =
        SwingUtilities.convertPoint(sourceComponent, location, window)

    val listener = object : DropTargetAdapter() {
        override fun dragEnter(dtde: DropTargetDragEvent) {
            if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                dtde.acceptDrag(DnDConstants.ACTION_COPY)
                onDragPosition(relativePoint(dtde.dropTargetContext.component, dtde.location))
            } else {
                dtde.rejectDrag()
            }
        }

        override fun dragOver(dtde: DropTargetDragEvent) {
            if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                dtde.acceptDrag(DnDConstants.ACTION_COPY)
                onDragPosition(relativePoint(dtde.dropTargetContext.component, dtde.location))
            } else {
                dtde.rejectDrag()
            }
        }

        override fun dragExit(dte: DropTargetEvent) {
            onDragPosition(null)
        }

        override fun drop(event: DropTargetDropEvent) {
            if (!event.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                event.rejectDrop()
                onDragPosition(null)
                return
            }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            try {
                @Suppress("UNCHECKED_CAST")
                val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                onFilesDropped(files, relativePoint(event.dropTargetContext.component, event.location))
                event.dropComplete(true)
            } catch (e: Exception) {
                event.dropComplete(false)
            } finally {
                onDragPosition(null)
            }
        }
    }

    fun attachRecursively(component: Component) {
        component.dropTarget = DropTarget(component, listener)
        if (component is Container) {
            for (child in component.components) attachRecursively(child)
        }
    }
    attachRecursively(window)
}
