package com.multiviewer.cli

import com.multiviewer.parser.buildMediaSummary
import com.multiviewer.parser.parseFile
import com.multiviewer.ui.*
import java.io.File
import kotlin.test.*

class AudioIntegrityJsonTest {
    @Test
    fun `CLI audio decode appears in check and saved case`() {
        val file = File("../docs/showcase-media/audio/gentle-tones.wav")
        assertTrue(file.isFile)
        val result = checkFile(file, includeCase = true, decode = true)
        assertIs<CheckResult.Success>(result)
        assertTrue(result.json.contains("\"audioIntegrity\""), result.json)
        assertTrue(result.json.contains("\"decodedSamples\": 238140"), result.json)
        assertTrue(result.analysisCaseJson!!.contains("\"audioIntegrity\""))
        assertFalse(result.analysisCaseJson!!.contains(file.absolutePath))
    }

    @Test
    fun `unrun audio is explicit in saved cases`() {
        val file = File("../docs/showcase-media/audio/gentle-tones.wav")
        val case = buildAnalysisCaseJson(file, emptyList(), buildMediaSummary(parseFile(file), file))
        assertTrue(case.contains("\"audioIntegrity\""), case)
        assertTrue(case.contains("\"status\": \"NOT_RUN\""), case)
    }
}
