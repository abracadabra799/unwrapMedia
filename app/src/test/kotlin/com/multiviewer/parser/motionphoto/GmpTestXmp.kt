package com.multiviewer.parser.motionphoto

/** Samsung-shaped XMP (attributes on rdf:Description, one Container:Item per rdf:li); each test overrides one part. */
internal fun gmpXmp(
    cameraNs: String = GMP_CAMERA_NS,
    camera: Map<String, String> = mapOf(
        "MotionPhoto" to "1", "MotionPhotoVersion" to "1", "MotionPhotoPresentationTimestampUs" to "0",
    ),
    items: List<Map<String, String>>? = listOf(
        mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"),
        mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
    ),
    directoryNs: String = GMP_CONTAINER_NS,
    itemNs: String = GMP_ITEM_NS,
    extraDescriptionAttrs: String = "",
): String {
    val cameraAttrs = camera.entries.joinToString("\n") { (k, v) -> "      GCamera:$k=\"$v\"" }
    val directory = items?.let { list ->
        val lis = list.joinToString("\n") { props ->
            val attrs = props.entries.joinToString(" ") { (k, v) -> "Item:$k=\"$v\"" }
            "          <rdf:li rdf:parseType=\"Resource\"><Container:Item $attrs/></rdf:li>"
        }
        "      <Container:Directory>\n        <rdf:Seq>\n$lis\n        </rdf:Seq>\n      </Container:Directory>"
    } ?: ""
    return """<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
      xmlns:GCamera="$cameraNs"
      xmlns:Container="$directoryNs"
      xmlns:Item="$itemNs"
$cameraAttrs $extraDescriptionAttrs>
$directory
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>"""
}
