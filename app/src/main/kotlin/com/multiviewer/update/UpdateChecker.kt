package com.multiviewer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.system.exitProcess

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
    val releaseDate: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val downloadUrl: String?,
    val downloadFileName: String?,
)

object UpdateChecker {
    const val GITHUB_RELEASES_WEB_URL = "${UpdateConfig.DEFAULT_REPO_URL}/releases/latest"

    /**
     * Compares semantic version strings like "1.10.0" and "1.11.0".
     * Returns true if latest is strictly newer than current.
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        val currParts = current.removePrefix("v").removePrefix("V").split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.removePrefix("v").removePrefix("V").split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(currParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun getReleasesWebUrl(config: UpdateConfig = UpdateConfig.load()): String {
        val base = config.repoUrl.trimEnd('/')
        return if (base.contains("gitlab", ignoreCase = true) || base.contains("/-/")) {
            if (base.endsWith("/-/releases")) base else "$base/-/releases"
        } else {
            if (base.endsWith("/releases")) base else "$base/releases"
        }
    }

    /**
     * Fetches update information first from Git Releases API (GitHub / GHE / GitLab),
     * falling back to raw version.json.
     */
    suspend fun checkForUpdates(
        currentVersion: String,
        config: UpdateConfig = UpdateConfig.load(),
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val releasesWebUrl = getReleasesWebUrl(config)

            // 1. Try Git Releases API (GitHub, GitHub Enterprise, or GitLab)
            val gitResult = fetchFromGitRelease(currentVersion, config)
            if (gitResult != null) return@runCatching gitResult

            // 2. Fallback to raw version.json from repo
            val jsonResult = fetchFromVersionJson(currentVersion, config)
            if (jsonResult != null) return@runCatching jsonResult

            // Default: no update found or server not reachable
            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = currentVersion,
                hasUpdate = false,
                releaseDate = "",
                releaseNotes = "최신 버전 정보를 불러올 수 없습니다. (${config.repoUrl})",
                releaseUrl = releasesWebUrl,
                downloadUrl = null,
                downloadFileName = null,
            )
        }
    }

    private fun fetchFromGitRelease(currentVersion: String, config: UpdateConfig): UpdateInfo? {
        val repoUrl = config.repoUrl.trimEnd('/')
        val isGitLab = repoUrl.contains("gitlab", ignoreCase = true) || repoUrl.contains("/-/")

        return if (isGitLab) {
            fetchFromGitLabRelease(currentVersion, config)
                ?: fetchFromGitHubRelease(currentVersion, config)
        } else {
            fetchFromGitHubRelease(currentVersion, config)
                ?: fetchFromGitLabRelease(currentVersion, config)
        }
    }

    private fun fetchFromGitHubRelease(currentVersion: String, config: UpdateConfig): UpdateInfo? {
        return try {
            val uri = URI(config.repoUrl.trimEnd('/'))
            val host = uri.host ?: return null
            val path = uri.path.trim('/')
            val parts = path.split('/')
            if (parts.size < 2) return null
            val owner = parts[parts.size - 2]
            val repo = parts[parts.size - 1].removeSuffix(".git")

            val apiUrl = if (host.equals("github.com", ignoreCase = true)) {
                "https://api.github.com/repos/$owner/$repo/releases/latest"
            } else {
                val scheme = uri.scheme ?: "https"
                val portSuffix = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
                "$scheme://$host$portSuffix/api/v3/repos/$owner/$repo/releases/latest"
            }

            val headers = mutableMapOf(
                "Accept" to "application/vnd.github.v3+json",
                "User-Agent" to "unwrapMedia-Desktop-App",
            )
            if (config.apiToken.isNotBlank()) {
                headers["Authorization"] = "Bearer ${config.apiToken}"
            }

            val body = httpGet(apiUrl, headers) ?: return null
            val tagName = extractJsonString(body, "tag_name") ?: return null
            val latestVer = tagName.removePrefix("v").removePrefix("V")
            val releaseNotes = extractJsonString(body, "body") ?: ""
            val htmlUrl = extractJsonString(body, "html_url") ?: getReleasesWebUrl(config)
            val publishedAt = extractJsonString(body, "published_at")?.take(10) ?: ""

            var (downloadUrl, fileName) = findAssetDownloadUrl(body)

            // Direct tag release download fallback if assets weren't in the API payload
            if (downloadUrl == null) {
                val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
                fileName = if (isWindows) "unwrapMedia-Setup-v$latestVer.exe" else "unwrapMedia-v$latestVer.dmg"
                downloadUrl = "${config.repoUrl.trimEnd('/')}/releases/download/$tagName/$fileName"
            }

            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVer,
                hasUpdate = isNewerVersion(currentVersion, latestVer),
                releaseDate = publishedAt,
                releaseNotes = releaseNotes.replace("\\r\\n", "\n").replace("\\n", "\n"),
                releaseUrl = htmlUrl,
                downloadUrl = downloadUrl,
                downloadFileName = fileName,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromGitLabRelease(currentVersion: String, config: UpdateConfig): UpdateInfo? {
        return try {
            val uri = URI(config.repoUrl.trimEnd('/'))
            val host = uri.host ?: return null
            val path = uri.path.trim('/').removeSuffix(".git")
            val encodedProjectPath = path.replace("/", "%2F")
            val scheme = uri.scheme ?: "https"
            val portSuffix = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""

            val apiUrl = "$scheme://$host$portSuffix/api/v4/projects/$encodedProjectPath/releases/permalink/latest"

            val headers = mutableMapOf(
                "Accept" to "application/json",
                "User-Agent" to "unwrapMedia-Desktop-App",
            )
            if (config.apiToken.isNotBlank()) {
                headers["PRIVATE-TOKEN"] = config.apiToken
                headers["Authorization"] = "Bearer ${config.apiToken}"
            }

            val body = httpGet(apiUrl, headers) ?: return null
            val tagName = extractJsonString(body, "tag_name") ?: return null
            val latestVer = tagName.removePrefix("v").removePrefix("V")
            val releaseNotes = extractJsonString(body, "description") ?: ""
            val publishedAt = extractJsonString(body, "released_at")?.take(10)
                ?: extractJsonString(body, "created_at")?.take(10) ?: ""

            var (downloadUrl, fileName) = findAssetDownloadUrl(body)

            // Direct tag download fallback for GitLab
            if (downloadUrl == null) {
                val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
                fileName = if (isWindows) "unwrapMedia-Setup-v$latestVer.exe" else "unwrapMedia-v$latestVer.dmg"
                downloadUrl = "${config.repoUrl.trimEnd('/')}/-/releases/$tagName/downloads/$fileName"
            }

            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVer,
                hasUpdate = isNewerVersion(currentVersion, latestVer),
                releaseDate = publishedAt,
                releaseNotes = releaseNotes.replace("\\r\\n", "\n").replace("\\n", "\n"),
                releaseUrl = getReleasesWebUrl(config),
                downloadUrl = downloadUrl,
                downloadFileName = fileName,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromVersionJson(currentVersion: String, config: UpdateConfig): UpdateInfo? {
        val repoUrl = config.repoUrl.trimEnd('/')
        val candidateUrls = if (repoUrl.contains("github.com", ignoreCase = true)) {
            val clean = repoUrl.removePrefix("https://github.com/").removePrefix("http://github.com/")
            listOf(
                "https://raw.githubusercontent.com/$clean/main/version.json",
                "https://raw.githubusercontent.com/$clean/master/version.json",
            )
        } else if (repoUrl.contains("gitlab", ignoreCase = true)) {
            listOf(
                "$repoUrl/-/raw/main/version.json",
                "$repoUrl/-/raw/master/version.json",
            )
        } else {
            listOf(
                "$repoUrl/raw/main/version.json",
                "$repoUrl/raw/master/version.json",
                "$repoUrl/version.json",
            )
        }

        for (url in candidateUrls) {
            val info = tryFetchVersionJsonFromUrl(url, currentVersion, config)
            if (info != null) return info
        }
        return null
    }

    private fun tryFetchVersionJsonFromUrl(url: String, currentVersion: String, config: UpdateConfig): UpdateInfo? {
        return try {
            val body = httpGet(url) ?: return null
            val latestVer = extractJsonString(body, "version") ?: return null
            val releaseDate = extractJsonString(body, "releaseDate") ?: ""
            val releaseNotes = extractJsonString(body, "releaseNotes") ?: ""
            val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
            val downloadUrl = if (isWindows) {
                extractJsonString(body, "windowsUrl")
            } else {
                extractJsonString(body, "macUrl")
            }

            val fileName = if (isWindows) "unwrapMedia-Setup-v$latestVer.exe" else "unwrapMedia-v$latestVer.dmg"

            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVer,
                hasUpdate = isNewerVersion(currentVersion, latestVer),
                releaseDate = releaseDate,
                releaseNotes = releaseNotes.replace("\\n", "\n"),
                releaseUrl = getReleasesWebUrl(config),
                downloadUrl = downloadUrl,
                downloadFileName = fileName,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds suitable asset URL (.exe for Windows, .dmg for macOS) from GitHub or GitLab Release JSON.
     */
    private fun findAssetDownloadUrl(json: String): Pair<String?, String?> {
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        val targetExt = if (isWindows) ".exe" else ".dmg"

        // Search for browser_download_url (GitHub/GHE) or direct_asset_url / url (GitLab)
        val assetUrlRegex = Regex(""""(?:browser_download_url|direct_asset_url|url)"\s*:\s*"([^"]+$targetExt)"""", RegexOption.IGNORE_CASE)
        val match = assetUrlRegex.find(json)
        val url = match?.groupValues?.getOrNull(1)
        val fileName = url?.substringAfterLast('/')
        return url to fileName
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    /**
     * Safe HTTP GET using java.net.HttpURLConnection (available in all JVMs and java.base module).
     */
    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 6000): String? {
        return try {
            val conn = openConnectionWithRedirects(urlStr, headers, timeoutMs) ?: return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun openConnectionWithRedirects(urlStr: String, headers: Map<String, String>, timeoutMs: Int = 8000): HttpURLConnection? {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val url = URI.create(currentUrl).toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "unwrapMedia-Desktop-App")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connect()
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                val newLoc = conn.getHeaderField("Location") ?: return null
                currentUrl = newLoc
                conn.disconnect()
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            return conn
        }
        return null
    }

    /**
     * Downloads the installer file with progress callback (0.0 to 1.0, bytesRead, totalBytes).
     * Uses standard java.net.HttpURLConnection from java.base without external module dependencies.
     */
    suspend fun downloadInstaller(
        downloadUrl: String,
        targetFile: File,
        config: UpdateConfig = UpdateConfig.load(),
        onProgress: (progress: Float, bytesRead: Long, totalBytes: Long) -> Unit,
        isCanceled: () -> Boolean,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val headers = mutableMapOf(
                "User-Agent" to "unwrapMedia-Desktop-App",
            )
            if (config.apiToken.isNotBlank()) {
                headers["Authorization"] = "Bearer ${config.apiToken}"
            }

            val conn = openConnectionWithRedirects(downloadUrl, headers, timeoutMs = 30000)
                ?: throw IllegalStateException("Failed to connect to download URL")

            val totalBytes = conn.contentLengthLong
            targetFile.parentFile?.mkdirs()

            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesReadSoFar = 0L
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        if (isCanceled()) {
                            targetFile.delete()
                            throw InterruptedException("Download canceled by user")
                        }
                        output.write(buffer, 0, n)
                        bytesReadSoFar += n
                        val progress = if (totalBytes > 0) (bytesReadSoFar.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                        onProgress(progress, bytesReadSoFar, totalBytes)
                    }
                }
            }
            targetFile
        }
    }

    /**
     * Launches the downloaded installer and terminates the current process to allow seamless update.
     */
    fun launchInstallerAndExit(installerFile: File) {
        var launchSuccess = false
        try {
            val os = System.getProperty("os.name", "").lowercase()
            if (os.contains("win")) {
                // Windows: Run installer via cmd.exe start to support UAC elevation prompt and detached execution
                try {
                    ProcessBuilder("cmd.exe", "/c", "start", "", installerFile.absolutePath).start()
                    launchSuccess = true
                } catch (_: Exception) {
                    try {
                        if (java.awt.Desktop.isDesktopSupported()) {
                            java.awt.Desktop.getDesktop().open(installerFile)
                            launchSuccess = true
                        }
                    } catch (_: Exception) {}
                }
                if (!launchSuccess) {
                    ProcessBuilder(installerFile.absolutePath).start()
                    launchSuccess = true
                }
                Thread.sleep(1000)
            } else if (os.contains("mac")) {
                // macOS: Open the downloaded DMG
                ProcessBuilder("open", installerFile.absolutePath).start()
                launchSuccess = true
                Thread.sleep(500)
            } else {
                // Linux / other
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(installerFile)
                    launchSuccess = true
                }
                Thread.sleep(500)
            }
        } catch (e: Exception) {
            System.err.println("Failed to launch installer: $e")
        } finally {
            if (launchSuccess) {
                exitProcess(0)
            }
        }
    }
}
