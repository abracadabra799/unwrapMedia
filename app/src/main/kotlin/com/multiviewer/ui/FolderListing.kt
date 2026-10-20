package com.multiviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.multiviewer.parser.ContainerFormat
import com.multiviewer.parser.confidentFormat
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
    /** True when the folder exists but could not be listed (permission or access restriction). */
    val unreadable: Boolean = false,
    /**
     * Files listed because of their content rather than their extension (absolute path -> detected format).
     * Files with a supported extension are not sniffed (a photo folder would cost one read per file), so they
     * never appear here.
     */
    val contentFormats: Map<String, ContainerFormat> = emptyMap(),
) {
    /** The extension to pick an icon/category by: the detected format's for content-listed files. No disk access. */
    fun kindExtension(file: File): String =
        contentFormats[file.absolutePath]?.extensions?.first() ?: file.extension.lowercase(Locale.US)

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
        val UNREADABLE = FolderListing(emptyList(), emptyList(), available = false, unreadable = true)
    }
}

private val BY_LOWERCASE_NAME = compareBy<File> { it.name.lowercase(Locale.US) }

// Unsupported-extension files are sniffed (first bytes read) to find mis-named media. Bounded so a huge or
// remote folder of documents doesn't turn one listing into thousands of reads; obvious non-media is skipped.
private const val MAX_CONTENT_SNIFFS_PER_LISTING = 200
private val NON_MEDIA_EXTENSIONS = setOf(
    "txt", "md", "json", "xml", "csv", "tsv", "log", "ini", "cfg", "conf", "yaml", "yml", "toml", "plist",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "rtf", "odt",
    "zip", "7z", "rar", "gz", "tgz", "bz2", "xz", "tar", "jar", "apk", "dmg", "iso", "exe", "msi", "dll", "so", "dylib",
    "html", "htm", "css", "js", "ts", "kt", "kts", "java", "py", "c", "cpp", "h", "sh", "bat", "cmd", "ps1",
    "srt", "vtt", "ass", "ttf", "otf", "woff", "woff2", "db", "sqlite", "lnk", "url", "thumb", "tmp", "part",
)

/**
 * Lists [dir] once (one `listFiles` plus a stat per entry). Blocking file I/O: call it off the UI thread,
 * e.g. through [rememberFolderListing]. Never throws.
 */
fun listFolder(dir: File?): FolderListing {
    if (dir == null) return FolderListing.UNAVAILABLE
    return try {
        if (!dir.exists() || !dir.isDirectory) return FolderListing.UNAVAILABLE
        val entries = dir.listFiles() ?: return FolderListing.UNREADABLE
        val media = ArrayList<File>()
        val subfolders = ArrayList<File>()
        val contentFormats = HashMap<String, ContainerFormat>()
        var sniffed = 0
        for (entry in entries) {
            if (entry.isHidden) continue
            if (entry.isDirectory) {
                subfolders += entry
            } else if (entry.isFile) {
                val ext = entry.extension.lowercase(Locale.US)
                if (ext in ALL_SUPPORTED_MEDIA_EXTENSIONS) {
                    media += entry
                } else if (ext !in NON_MEDIA_EXTENSIONS && sniffed < MAX_CONTENT_SNIFFS_PER_LISTING) {
                    sniffed++
                    confidentFormat(entry)?.let { format ->
                        media += entry
                        contentFormats[entry.absolutePath] = format
                    }
                }
            }
        }
        FolderListing(
            media.sortedWith(BY_LOWERCASE_NAME), subfolders.sortedWith(BY_LOWERCASE_NAME), available = true,
            contentFormats = contentFormats,
        )
    } catch (_: SecurityException) {
        FolderListing.UNREADABLE
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
