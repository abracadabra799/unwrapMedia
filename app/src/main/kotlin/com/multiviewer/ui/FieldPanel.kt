package com.multiviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.BoxNode
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.ls.DOMImplementationLS
import org.xml.sax.InputSource

@Composable
fun FieldPanel(node: BoxNode?) {
    if (node == null) return
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        item {
            Column {
                MetadataRow("Type", node.type)
                MetadataRow("Offset", "${node.offset} (0x${node.offset.toString(16).uppercase()})")
                MetadataRow("Size", "${node.size}")
                MetadataRow("Header size", "${node.headerSize}")
                MetadataRow("Payload size", "${node.size - node.headerSize}")
                if (node.children.isNotEmpty()) {
                    MetadataRow("Children", "${node.children.size}")
                }
                if (node.warnings.isNotEmpty()) {
                    Text("Warnings:", modifier = Modifier.padding(top = 4.dp))
                    node.warnings.forEach { warning ->
                        Text("- $warning", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        items(node.fields) { field ->
            if (field.name == "xmp") {
                XmpFieldDisplay(field.value)
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${field.name}: ", modifier = Modifier.padding(end = 4.dp))
                    Text(field.value)
                }
            }
        }
        val grid = node.grid
        if (grid != null) {
            item {
                GridDisplay(grid)
            }
        }
    }
}

// XMP is a raw XML packet, often several KB. Cramming it into the same inline Row as a normal
// field made every wrapped line start mid-column with no indentation, so a giant unbroken blob of
// XML was effectively unreadable. Pretty-printing it and giving it the panel's full width (instead
// of sharing a row with the label) lets it wrap at natural tag boundaries.
@Composable
private fun XmpFieldDisplay(raw: String) {
    val formatted = remember(raw) { prettyPrintXmlOrRaw(raw) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("xmp:")
        Text(
            formatted,
            style = AppTypography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier.padding(top = 2.dp, start = 8.dp),
        )
    }
}

fun prettyPrintXmlOrRaw(raw: String): String {
    return try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Untrusted file content -- block DOCTYPE/external entities outright (XMP never
            // legitimately needs either) to avoid XXE rather than trying to sanitize inputs.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(raw)))
        val domImplLS = doc.implementation.getFeature("LS", "3.0") as DOMImplementationLS
        val serializer = domImplLS.createLSSerializer().apply {
            domConfig.setParameter("format-pretty-print", true)
        }
        val output = domImplLS.createLSOutput().apply {
            encoding = "UTF-8"
            characterStream = StringWriter()
        }
        serializer.write(doc, output)
        (output.characterStream as StringWriter).toString().trim()
    } catch (e: Exception) {
        raw // not well-formed XML (or parsing failed) -- show the original text rather than nothing
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", modifier = Modifier.padding(end = 4.dp))
        Text(value)
    }
}
