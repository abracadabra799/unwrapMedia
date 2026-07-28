package com.multiviewer.cli

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.GridData
import com.multiviewer.parser.TableData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BoxNodeJsonTest {
    @Test
    fun `buildDumpJson wraps the tree with file name and size, omitting empty collections and null summary`() {
        val child = BoxNode(
            type = "SOF0", offset = 2L, headerSize = 4, size = 15L,
            fields = listOf(BoxField("width", "640", 9L, 2L)),
        )
        val root = BoxNode(type = "root", offset = 0L, headerSize = 0, size = 17L, children = listOf(child))
        val file = File.createTempFile("dump-test-", ".bin")
        file.deleteOnExit()
        file.writeBytes(ByteArray(17))

        val json = buildDumpJson(file, root)

        val expected = "{\n" +
            "  \"file\": \"${file.name}\",\n" +
            "  \"fileSize\": 17,\n" +
            "  \"root\": {\n" +
            "    \"type\": \"root\",\n" +
            "    \"offset\": 0,\n" +
            "    \"headerSize\": 0,\n" +
            "    \"size\": 17,\n" +
            "    \"children\": [\n" +
            "      {\n" +
            "        \"type\": \"SOF0\",\n" +
            "        \"offset\": 2,\n" +
            "        \"headerSize\": 4,\n" +
            "        \"size\": 15,\n" +
            "        \"fields\": [\n" +
            "          {\n" +
            "            \"name\": \"width\",\n" +
            "            \"value\": \"640\",\n" +
            "            \"offset\": 9,\n" +
            "            \"length\": 2\n" +
            "          }\n" +
            "        ]\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}"
        assertEquals(expected, json)
        file.delete()
    }

    @Test
    fun `includes summary, warnings, table, and grid only when present`() {
        val node = BoxNode(
            type = "QuantizationTable", offset = 0L, headerSize = 1, size = 65L,
            warnings = listOf("Declared length 999 extends past the end of the file"),
            summary = "precision=0, destination_id=0, quality~50%",
            table = TableData(columns = listOf("id", "name"), fieldWidths = listOf(4, 20), entriesStart = 10L, entryCount = 3L),
            grid = GridData(columns = 2, rows = 2, values = listOf("1", "2", "3", "4")),
        )

        val json = node.toJsonValue().render()

        assertEquals(
            "{\n" +
                "  \"type\": \"QuantizationTable\",\n" +
                "  \"offset\": 0,\n" +
                "  \"headerSize\": 1,\n" +
                "  \"size\": 65,\n" +
                "  \"summary\": \"precision=0, destination_id=0, quality~50%\",\n" +
                "  \"warnings\": [\n" +
                "    \"Declared length 999 extends past the end of the file\"\n" +
                "  ],\n" +
                "  \"table\": {\n" +
                "    \"columns\": [\n" +
                "      \"id\",\n" +
                "      \"name\"\n" +
                "    ],\n" +
                "    \"fieldWidths\": [\n" +
                "      4,\n" +
                "      20\n" +
                "    ],\n" +
                "    \"entriesStart\": 10,\n" +
                "    \"entryCount\": 3\n" +
                "  },\n" +
                "  \"grid\": {\n" +
                "    \"columns\": 2,\n" +
                "    \"rows\": 2,\n" +
                "    \"values\": [\n" +
                "      \"1\",\n" +
                "      \"2\",\n" +
                "      \"3\",\n" +
                "      \"4\"\n" +
                "    ]\n" +
                "  }\n" +
                "}",
            json,
        )
    }
}
