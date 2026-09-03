package com.multiviewer.update

import java.io.File
import java.net.URI

data class UpdateConfig(
    val repoUrl: String = DEFAULT_REPO_URL,
    val apiToken: String = "",
) {
    companion object {
        const val DEFAULT_REPO_URL = "https://github.sec.samsung.net/jieun81-kim/unwrapMedia"

        private val configFile: File by lazy {
            val dir = File(System.getProperty("user.home"), ".unwrapMedia")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "update_config.json")
        }

        @Volatile
        private var cachedConfig: UpdateConfig? = null

        fun load(): UpdateConfig {
            cachedConfig?.let { return it }
            val envUrl = System.getenv("UNWRAP_MEDIA_UPDATE_URL")?.trim()
            if (!envUrl.isNullOrEmpty()) {
                val cfg = UpdateConfig(repoUrl = envUrl)
                cachedConfig = cfg
                return cfg
            }

            if (!configFile.exists()) {
                val defaultCfg = UpdateConfig()
                cachedConfig = defaultCfg
                return defaultCfg
            }

            return try {
                val text = configFile.readText()
                val repoUrl = extractJsonString(text, "repoUrl")?.takeIf { it.isNotBlank() } ?: DEFAULT_REPO_URL
                val apiToken = extractJsonString(text, "apiToken") ?: ""
                val cfg = UpdateConfig(repoUrl.trimEnd('/'), apiToken.trim())
                cachedConfig = cfg
                cfg
            } catch (_: Exception) {
                UpdateConfig()
            }
        }

        fun save(config: UpdateConfig) {
            cachedConfig = config
            try {
                val cleanUrl = config.repoUrl.trim().trimEnd('/')
                val json = """
                    {
                      "repoUrl": "${escapeJson(cleanUrl)}",
                      "apiToken": "${escapeJson(config.apiToken.trim())}"
                    }
                """.trimIndent()
                configFile.writeText(json)
            } catch (e: Exception) {
                System.err.println("Failed to save update config: $e")
            }
        }

        fun resetToDefault(): UpdateConfig {
            val defaultCfg = UpdateConfig()
            save(defaultCfg)
            return defaultCfg
        }

        private fun escapeJson(str: String): String =
            str.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun extractJsonString(json: String, key: String): String? {
            val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            return pattern.find(json)?.groupValues?.get(1)
        }
    }
}
