package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.parseXmpDocument
import org.w3c.dom.Element
import org.w3c.dom.Node

const val GMP_CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
const val GMP_CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
const val GMP_ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"
private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

private val CAMERA_PROPERTIES = setOf(
    "MotionPhoto", "MotionPhotoVersion", "MotionPhotoPresentationTimestampUs",
    "MicroVideo", "MicroVideoVersion", "MicroVideoOffset", "MicroVideoPresentationTimestampUs",
)
private val ITEM_PROPERTIES = setOf("Mime", "Semantic", "Length", "Padding")

/** One `Container:Item` of the directory, in `rdf:Seq` order; values are the raw XMP strings. */
data class GmpDirectoryItem(val index: Int, val properties: Map<String, String>) {
    val mime: String? get() = properties["Mime"]
    val semantic: String? get() = properties["Semantic"]
    val length: String? get() = properties["Length"]
    val padding: String? get() = properties["Padding"]
}

/**
 * The Motion Photo 1.0 view of a primary image's XMP. Properties are matched by namespace URI, never by
 * prefix (Samsung writes the Camera namespace as `GCamera:`). A Motion Photo property under any other URI
 * goes to [wrongNamespace] instead, because a spec reader would not find it.
 */
data class GmpXmp(
    val camera: Map<String, String>,
    val wrongNamespace: List<String>,
    /** Null when there is no `Container:Directory` in the Container namespace. */
    val directory: List<GmpDirectoryItem>?,
)

/** Throws on malformed XML; the caller reports `gmp.xmp.parse`. */
fun parseGmpXmp(xmpText: String): GmpXmp {
    val document = parseXmpDocument(xmpText)
    val camera = linkedMapOf<String, String>()
    val wrong = mutableListOf<String>()
    var directory: List<GmpDirectoryItem>? = null
    val all = document.getElementsByTagNameNS("*", "*")
    for (i in 0 until all.length) {
        val element = all.item(i) as? Element ?: continue
        val attributes = element.attributes
        for (a in 0 until attributes.length) {
            val attr = attributes.item(a)
            val name = attr.localName ?: continue
            if (attr.namespaceURI == GMP_CAMERA_NS) {
                camera.putIfAbsent(name, attr.nodeValue.trim())
            } else if (name in CAMERA_PROPERTIES && attr.namespaceURI != XMLNS_NS) {
                wrong += "$name (${attr.namespaceURI ?: "네임스페이스 없음"})"
            }
        }
        val local = element.localName ?: continue
        if (element.namespaceURI == GMP_CAMERA_NS && !hasElementChildren(element)) {
            camera.putIfAbsent(local, element.textContent.trim())
        } else if (local in CAMERA_PROPERTIES && element.namespaceURI != GMP_CAMERA_NS) {
            wrong += "$local (${element.namespaceURI ?: "네임스페이스 없음"})"
        }
        if (local == "Directory") {
            if (element.namespaceURI == GMP_CONTAINER_NS) {
                if (directory == null) directory = readDirectory(element, wrong)
            } else {
                wrong += "Directory (${element.namespaceURI ?: "네임스페이스 없음"})"
            }
        }
    }
    return GmpXmp(camera, wrong.distinct(), directory)
}

private fun readDirectory(directory: Element, wrong: MutableList<String>): List<GmpDirectoryItem> {
    val seq = childElements(directory).firstOrNull { it.namespaceURI == RDF_NS && it.localName == "Seq" } ?: return emptyList()
    return childElements(seq).filter { it.namespaceURI == RDF_NS && it.localName == "li" }.mapIndexed { index, li ->
        val properties = linkedMapOf<String, String>()
        collectItemProperties(li, properties, wrong)
        GmpDirectoryItem(index, properties)
    }
}

// Item properties may be attributes (Samsung: on the nested Container:Item) or child elements (rdf:Description form).
private fun collectItemProperties(element: Element, properties: MutableMap<String, String>, wrong: MutableList<String>) {
    val attributes = element.attributes
    for (a in 0 until attributes.length) {
        val attr = attributes.item(a)
        val name = attr.localName ?: continue
        if (attr.namespaceURI == GMP_ITEM_NS) {
            properties.putIfAbsent(name, attr.nodeValue.trim())
        } else if (name in ITEM_PROPERTIES && attr.namespaceURI != XMLNS_NS) {
            wrong += "Item:$name (${attr.namespaceURI ?: "네임스페이스 없음"})"
        }
    }
    for (child in childElements(element)) {
        val name = child.localName ?: continue
        if (child.namespaceURI == GMP_ITEM_NS && !hasElementChildren(child)) {
            properties.putIfAbsent(name, child.textContent.trim())
        } else {
            if (name in ITEM_PROPERTIES) wrong += "Item:$name (${child.namespaceURI ?: "네임스페이스 없음"})"
            collectItemProperties(child, properties, wrong)
        }
    }
}

private fun childElements(element: Element): List<Element> {
    val nodes = element.childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

private fun hasElementChildren(element: Element): Boolean {
    val nodes = element.childNodes
    return (0 until nodes.length).any { nodes.item(it).nodeType == Node.ELEMENT_NODE }
}
