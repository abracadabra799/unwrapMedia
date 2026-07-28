package com.multiviewer.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonValueTest {
    @Test
    fun `renders a flat object with string, number, and array values`() {
        val value = JsonValue.JObject(
            listOf(
                "name" to JsonValue.JString("test"),
                "count" to JsonValue.JNumber(5),
                "tags" to JsonValue.JArray(listOf(JsonValue.JString("a"), JsonValue.JString("b"))),
            ),
        )

        val expected = "{\n" +
            "  \"name\": \"test\",\n" +
            "  \"count\": 5,\n" +
            "  \"tags\": [\n" +
            "    \"a\",\n" +
            "    \"b\"\n" +
            "  ]\n" +
            "}"
        assertEquals(expected, value.render())
    }

    @Test
    fun `renders nested objects with increasing indentation`() {
        val value = JsonValue.JObject(
            listOf(
                "outer" to JsonValue.JObject(
                    listOf("inner" to JsonValue.JString("deep")),
                ),
            ),
        )

        val expected = "{\n" +
            "  \"outer\": {\n" +
            "    \"inner\": \"deep\"\n" +
            "  }\n" +
            "}"
        assertEquals(expected, value.render())
    }

    @Test
    fun `renders empty objects and arrays compactly`() {
        assertEquals("{}", JsonValue.JObject(emptyList()).render())
        assertEquals("[]", JsonValue.JArray(emptyList()).render())
    }

    @Test
    fun `escapes quotes, backslashes, and newlines in strings`() {
        // Actual characters in `input`: " a \ " b \ \ c \ n d  (a double-quote, then a, a
        // backslash, a double-quote, b, two backslashes, c, a literal newline, d)
        val input = "a\"b\\c\nd"
        val rendered = JsonValue.JString(input).render()
        // Expected characters in `rendered`: " a \" b \\ c \n d "  (each real double-quote and
        // backslash from the input is escaped, and the real newline becomes the two characters
        // backslash-n) -- verified by printing the actual char codes before writing this
        // assertion, not hand-derived.
        assertEquals("\"a\\\"b\\\\c\\nd\"", rendered)
    }
}
