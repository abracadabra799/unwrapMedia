package com.multiviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * What a folder explorer shows for one folder: the supported media files and the visible subfolders, each
 * sorted by lower-cased name. Hidden entries are left out of both. [available] is false when the folder is
 * missing, is not a directory, or could not be listed.
 */
data class FolderListing(
    val mediaFiles: List<File>,
    val subfolders: List<File>,
    val available: Boolean,
) {
    /** Entries whose name contains [query] (ignoring case); a blank query keeps everything. No disk access. */
    fun matching(query: String): FolderListing =
        if (query.isBlank()) {
            this
        } else {
            copy(
                mediaFiles = mediaFiles.filter { it.name.contains(query, ignoreCase = true) },
                subfolders = subfolders.filter { it.name.contains(query, ignoreCase = true) },
            )
        }

    companion object {
        val UNAVAILABLE = FolderListing(emptyList(), emptyList(), available = false)
    }
}

private val BY_LOWERCASE_NAME = compareBy<File> { it.name.lowercase(Locale.US) }

/**
 * Lists [dir] once (one `listFiles` plus a stat per entry). Blocking file I/O: call it off the UI thread,
 * e.g. through [rememberFolderListing]. Never throws.
 */
fun listFolder(dir: File?): FolderListing {
    if (dir == null) return FolderListing.UNAVAILABLE
    return try {
        if (!dir.exists() || !dir.isDirectory) return FolderListing.UNAVAILABLE
        val entries = dir.listFiles() ?: return FolderListing.UNAVAILABLE
        val media = ArrayList<File>()
        val subfolders = ArrayList<File>()
        for (entry in entries) {
            if (entry.isHidden) continue
            if (entry.isDirectory) {
                subfolders += entry
            } else if (entry.isFile && entry.extension.lowercase(Locale.US) in ALL_SUPPORTED_MEDIA_EXTENSIONS) {
                media += entry
            }
        }
        FolderListing(media.sortedWith(BY_LOWERCASE_NAME), subfolders.sortedWith(BY_LOWERCASE_NAME), available = true)
    } catch (_: Exception) {
        FolderListing.UNAVAILABLE
    }
}

/** The supported media files of [dir], sorted by name. Blocking: see [listFolder]. */
fun listFolderMediaFiles(dir: File?): List<File> = listFolder(dir).mediaFiles

private data class LoadedListing(val path: String?, val listing: FolderListing)

/**
 * [listFolder] for a composable, run on [Dispatchers.IO] whenever [dir] or [refreshKey] changes. Returns null
 * while the first listing of the current [dir] is loading, so a caller never acts on another folder's files; a
 * refresh of the same folder keeps showing the previous listing until the new one arrives.
 */
@Composable
fun rememberFolderListing(dir: File?, refreshKey: Any? = null): FolderListing? {
    val path = dir?.absolutePath
    val loaded by produceState<LoadedListing?>(null, path, refreshKey) {
        val listing = withContext(Dispatchers.IO) { listFolder(dir) }
        value = LoadedListing(path, listing)
    }
    return loaded?.takeIf { it.path == path }?.listing
}
