package com.multiviewer.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamJsonTest {
    @Test
    fun `parses objects arrays strings numbers booleans and null`() {
        val v = parseJsonLoose("""{"a":[1,2.5,-3e2],"s":"x\"\\\/\né","t":true,"f":false,"z":null,"o":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"])
        assertEquals("x\"\\/\né", v["s"])
        assertEquals(true, v["t"])
        assertEquals(false, v["f"])
        assertTrue(v.containsKey("z"))
        assertEquals(null, v["z"])
        assertEquals(emptyMap<String, Any?>(), v["o"])
    }

    @Test
    fun `malformed input is null, not an exception`() {
        val bad = listOf("", "{", "{\"a\":}", "{\"a\":1,}", "[\"x\"", "tru", "{\"a\":1} trailing", "\"bad \\q escape\"", "\"raw\ncontrol\"", "\"\\u-123\"", "\"\\u+041\"", "\"\\u12\"")
        for (text in bad) assertEquals(null, parseJsonLoose(text), text)
    }

    @Test
    fun `unicode escapes decode four hex digits`() {
        assertEquals("Aé한", parseJsonLoose("\"\\u0041\\u00E9\\ud55c\""))
    }

    @Test
    fun `nesting deeper than the limit is refused`() {
        assertEquals(null, parseJsonLoose("[".repeat(100) + "]".repeat(100)))
        assertTrue(parseJsonLoose("[".repeat(10) + "]".repeat(10)) is List<*>)
    }

    // Shape of a real `claude -p --output-format stream-json --verbose` run (2.1.300), trimmed.
    private val successRun = listOf(
        """{"type":"system","subtype":"hook_started","hook_name":"SessionStart"}""",
        """{"type":"system","subtype":"init","tools":[],"model":"x"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"green"}]}}""",
        """{"type":"rate_limit_event"}""",
        """{"type":"result","subtype":"success","is_error":false,"api_error_status":null,"total_cost_usd":0.0433178,"result":"green","num_turns":1}""",
    ).joinToString("\n")

    @Test
    fun `the answer is the result event of a successful run`() {
        assertEquals(AiParsedOutput.Answer("green"), parseClaudeStreamJson(successRun + "\n"))
    }

    @Test
    fun `an error result is an error even with exit code zero`() {
        assertEquals(
            AiParsedOutput.Error("Invalid API key · Please run /login"),
            parseClaudeStreamJson("""{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key · Please run /login"}"""),
        )
        assertEquals(
            AiParsedOutput.Error("error_max_turns"),
            parseClaudeStreamJson("""{"type":"result","subtype":"error_max_turns","is_error":false}"""),
        )
    }

    @Test
    fun `no result event is an error, never the raw stream`() {
        val cut = successRun.substringBefore("""{"type":"result"""") + """{"type":"result","subtype":"succ"""
        assertIs<AiParsedOutput.Error>(parseClaudeStreamJson(cut))
        assertIs<AiParsedOutput.Error>(parseClaudeStreamJson("plain text warning\n"))
    }

    @Test
    fun `the last result event wins and stray lines are skipped`() {
        val out = listOf(
            """{"type":"result","subtype":"success","is_error":false,"result":"first"}""",
            "warning: something",
            """{"type":"result","subtype":"success","is_error":false,"result":"second"}""",
        ).joinToString("\n")
        assertEquals(AiParsedOutput.Answer("second"), parseClaudeStreamJson(out))
    }
}
