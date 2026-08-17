package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppleAuxiliaryMetadataTest {

    @Test
    fun `builds Auxiliary Images node correlating HDR gain map, disparity, and semantic mattes`() {
        val metaNode = buildSyntheticHeifMetaNode()
        val auxNode = buildAppleAuxiliaryNode(metaNode)

        assertNotNull(auxNode, "Auxiliary Images node should not be null")
        assertEquals("Auxiliary Images", auxNode.type)
        assertEquals(3, auxNode.children.size)

        // Child 1: HDR Gain Map (Item 2)
        val hdrChild = auxNode.children.find { it.type.contains("HDR Gain Map") }
        assertNotNull(hdrChild, "HDR Gain Map child should exist")
        assertEquals("HDR_GAIN_MAP", hdrChild.fields.find { it.name == "Role" }?.value)
        assertEquals("2", hdrChild.fields.find { it.name == "Item ID" }?.value)
        assertEquals("1", hdrChild.fields.find { it.name == "Primary Item ID" }?.value)
        assertEquals("512x384", hdrChild.fields.find { it.name == "Resolution" }?.value)

        // Child 2: Disparity Map (Item 3)
        val disparityChild = auxNode.children.find { it.type.contains("Disparity") }
        assertNotNull(disparityChild, "Disparity child should exist")
        assertEquals("DISPARITY", disparityChild.fields.find { it.name == "Role" }?.value)

        // Child 3: Sky Matte (Item 4)
        val skyChild = auxNode.children.find { it.type.contains("Sky") }
        assertNotNull(skyChild, "Sky Matte child should exist")
        assertEquals("SKY", skyChild.fields.find { it.name == "Role" }?.value)
    }

    @Test
    fun `detects dangling reference when target item does not exist`() {
        val metaNode = buildSyntheticHeifMetaNodeWithDanglingRef()
        val auxNode = buildAppleAuxiliaryNode(metaNode)

        assertNotNull(auxNode)
        val childWithDangling = auxNode.children.find { it.fields.any { f -> f.name == "Item ID" && f.value == "2" } }
        assertNotNull(childWithDangling)
        assertTrue(childWithDangling.warnings.any { it.contains("Dangling") || it.contains("not found") })
    }

    private fun buildSyntheticHeifMetaNode(): BoxNode {
        // pitm: item 1
        val pitm = BoxNode("pitm", 0, 8, 12, fields = listOf(BoxField("item_ID", "1", 8, 2)))

        // iinf containing infe for item 1, 2, 3, 4
        val infe1 = BoxNode("infe", 12, 8, 20, fields = listOf(BoxField("item_ID", "1", 16, 2), BoxField("item_type", "hvc1", 18, 4)))
        val infe2 = BoxNode("infe", 32, 8, 20, fields = listOf(BoxField("item_ID", "2", 36, 2), BoxField("item_type", "hvc1", 38, 4)))
        val infe3 = BoxNode("infe", 52, 8, 20, fields = listOf(BoxField("item_ID", "3", 56, 2), BoxField("item_type", "hvc1", 58, 4)))
        val infe4 = BoxNode("infe", 72, 8, 20, fields = listOf(BoxField("item_ID", "4", 76, 2), BoxField("item_type", "hvc1", 78, 4)))
        val iinf = BoxNode("iinf", 12, 8, 80, children = listOf(infe1, infe2, infe3, infe4))

        // iprp: ipco (properties) & ipma (associations)
        // ipco properties:
        // 1: ispe for item 1 (4032x3024)
        // 2: ispe for item 2 (512x384)
        // 3: auxC for item 2 ("urn:com:apple:photo:2020:aux:hdrgainmap")
        // 4: auxC for item 3 ("urn:com:apple:photo:2018:aux:disparity")
        // 5: auxC for item 4 ("urn:com:apple:photo:2019:aux:semanticsegmentationsky")
        val ispe1 = BoxNode("ispe", 100, 8, 20, fields = listOf(BoxField("image_width", "4032", 108, 4), BoxField("image_height", "3024", 112, 4)))
        val ispe2 = BoxNode("ispe", 120, 8, 20, fields = listOf(BoxField("image_width", "512", 128, 4), BoxField("image_height", "384", 132, 4)))
        val auxC2 = BoxNode("auxC", 140, 8, 40, fields = listOf(BoxField("aux_type", "urn:com:apple:photo:2020:aux:hdrgainmap", 148, 30)))
        val auxC3 = BoxNode("auxC", 180, 8, 40, fields = listOf(BoxField("aux_type", "urn:com:apple:photo:2018:aux:disparity", 188, 30)))
        val auxC4 = BoxNode("auxC", 220, 8, 40, fields = listOf(BoxField("aux_type", "urn:com:apple:photo:2019:aux:semanticsegmentationsky", 228, 40)))
        val ipco = BoxNode("ipco", 100, 8, 160, children = listOf(ispe1, ispe2, auxC2, auxC3, auxC4))

        // ipma:
        // item 1 -> prop 1
        // item 2 -> prop 2, prop 3
        // item 3 -> prop 4
        // item 4 -> prop 5
        val ipmaItem1 = BoxNode("item_1", 260, 4, 10, fields = listOf(BoxField("property_index", "1", 264, 1)))
        val ipmaItem2 = BoxNode("item_2", 270, 4, 10, fields = listOf(BoxField("property_index", "2", 274, 1), BoxField("property_index", "3", 275, 1)))
        val ipmaItem3 = BoxNode("item_3", 280, 4, 10, fields = listOf(BoxField("property_index", "4", 284, 1)))
        val ipmaItem4 = BoxNode("item_4", 290, 4, 10, fields = listOf(BoxField("property_index", "5", 294, 1)))
        val ipma = BoxNode("ipma", 260, 8, 40, children = listOf(ipmaItem1, ipmaItem2, ipmaItem3, ipmaItem4))

        val iprp = BoxNode("iprp", 100, 8, 200, children = listOf(ipco, ipma))

        // iref: auxl references
        // from item 2 -> to item 1
        // from item 3 -> to item 1
        // from item 4 -> to item 1
        val auxl2 = BoxNode("auxl", 300, 8, 16, fields = listOf(BoxField("from_item_ID", "2", 308, 2), BoxField("to_item_ID[0]", "1", 312, 2)))
        val auxl3 = BoxNode("auxl", 316, 8, 16, fields = listOf(BoxField("from_item_ID", "3", 324, 2), BoxField("to_item_ID[0]", "1", 328, 2)))
        val auxl4 = BoxNode("auxl", 332, 8, 16, fields = listOf(BoxField("from_item_ID", "4", 340, 2), BoxField("to_item_ID[0]", "1", 344, 2)))
        val iref = BoxNode("iref", 300, 8, 48, children = listOf(auxl2, auxl3, auxl4))

        return BoxNode("meta", 0, 8, 400, children = listOf(pitm, iinf, iprp, iref))
    }

    private fun buildSyntheticHeifMetaNodeWithDanglingRef(): BoxNode {
        val pitm = BoxNode("pitm", 0, 8, 12, fields = listOf(BoxField("item_ID", "1", 8, 2)))
        val infe1 = BoxNode("infe", 12, 8, 20, fields = listOf(BoxField("item_ID", "1", 16, 2), BoxField("item_type", "hvc1", 18, 4)))
        val infe2 = BoxNode("infe", 32, 8, 20, fields = listOf(BoxField("item_ID", "2", 36, 2), BoxField("item_type", "hvc1", 38, 4)))
        val iinf = BoxNode("iinf", 12, 8, 40, children = listOf(infe1, infe2))

        val auxC2 = BoxNode("auxC", 140, 8, 40, fields = listOf(BoxField("aux_type", "urn:com:apple:photo:2020:aux:hdrgainmap", 148, 30)))
        val ipco = BoxNode("ipco", 100, 8, 60, children = listOf(auxC2))

        val ipmaItem2 = BoxNode("item_2", 270, 4, 10, fields = listOf(BoxField("property_index", "1", 274, 1)))
        val ipma = BoxNode("ipma", 260, 8, 20, children = listOf(ipmaItem2))
        val iprp = BoxNode("iprp", 100, 8, 80, children = listOf(ipco, ipma))

        // auxl pointing to non-existent item 999
        val auxl2 = BoxNode("auxl", 300, 8, 16, fields = listOf(BoxField("from_item_ID", "2", 308, 2), BoxField("to_item_ID[0]", "999", 312, 2)))
        val iref = BoxNode("iref", 300, 8, 16, children = listOf(auxl2))

        return BoxNode("meta", 0, 8, 400, children = listOf(pitm, iinf, iprp, iref))
    }
}
