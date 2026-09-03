package com.multiviewer.update

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpdateCheckerTest {

    @Test
    fun testIsNewerVersion() {
        assertTrue(UpdateChecker.isNewerVersion("1.10.0", "1.10.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.10.0", "1.11.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.10.0", "2.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("v1.10.0", "v1.11.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.9.9", "1.10.0"))

        assertFalse(UpdateChecker.isNewerVersion("1.10.0", "1.10.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.10.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.10.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewerVersion("2.0.0", "1.99.99"))
        assertFalse(UpdateChecker.isNewerVersion("v1.10.0", "1.10.0"))
    }

    @Test
    fun testGetReleasesWebUrl() {
        val gheConfig = UpdateConfig("https://github.sec.samsung.net/jieun81-kim/unwrapMedia")
        assertEquals("https://github.sec.samsung.net/jieun81-kim/unwrapMedia/releases", UpdateChecker.getReleasesWebUrl(gheConfig))

        val githubConfig = UpdateConfig("https://github.com/abracadabra799/unwrapMedia")
        assertEquals("https://github.com/abracadabra799/unwrapMedia/releases", UpdateChecker.getReleasesWebUrl(githubConfig))

        val gitlabConfig = UpdateConfig("https://gitlab.mycompany.com/multimedia/unwrapMedia")
        assertEquals("https://gitlab.mycompany.com/multimedia/unwrapMedia/-/releases", UpdateChecker.getReleasesWebUrl(gitlabConfig))

        val trailingSlashConfig = UpdateConfig("https://git.company.com/group/repo/")
        assertEquals("https://git.company.com/group/repo/releases", UpdateChecker.getReleasesWebUrl(trailingSlashConfig))
    }

    @Test
    fun testUpdateConfigSaveAndLoad() {
        val testUrl = "https://git.internal-corp.com/tools/unwrapMedia"
        val testToken = "glpat-secret12345"
        val original = UpdateConfig(testUrl, testToken)

        UpdateConfig.save(original)
        val loaded = UpdateConfig.load()

        assertEquals(testUrl, loaded.repoUrl)
        assertEquals(testToken, loaded.apiToken)

        // Reset to default
        val def = UpdateConfig.resetToDefault()
        assertEquals(UpdateConfig.DEFAULT_REPO_URL, def.repoUrl)
    }
}
