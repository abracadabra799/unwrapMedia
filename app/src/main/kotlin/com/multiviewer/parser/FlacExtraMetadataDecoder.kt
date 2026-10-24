package com.multiviewer.parser

/** FLAC metadata whose payload was previously shown only as a byte count. */
internal object FlacExtraMetadataDecoder : BoxDecoder {
    override fun decode(reader: ByteReader,type: String,offset: Long,headerSize: Int,size: Long,warnings: List<String>): BoxNode {
        val start=offset+headerSize
        val length=size-headerSize
        val w=warnings.toMutableList()
        val fields=mutableListOf<BoxField>()
        val children=mutableListOf<BoxNode>()
        var interpretation=ValueInterpretation.PARSED
        fun node(summary: String?=null)=BoxNode(type,offset,headerSize,size,fields=fields,children=children,warnings=w,summary=summary,interpretation=interpretation)
        if(type=="APPLICATION") {
            if(length<4) { interpretation=ValueInterpretation.UNPARSABLE; w+="Truncated FLAC application ID / FLAC 애플리케이션 ID 잘림"; return node() }
            fields+=BoxField("application_id",reader.readFourCC(start),start,4)
            val dataLength=length-4
            val shown=minOf(dataLength,256L).toInt()
            fields+=BoxField("application_data_length",dataLength.toString(),start+4,dataLength)
            if(dataLength>0) {
                val hex=reader.readBytes(start+4,shown).joinToString(" ") { "%02x".format(it.toInt() and 255) }
                fields+=BoxField("raw_payload",hex,start+4,shown.toLong(),ValueInterpretation.UNINTERPRETED)
                fields+=BoxField("parsing_status","Unparsed application data / 미해석 애플리케이션 데이터",start+4,dataLength)
                if(dataLength>shown) fields+=BoxField("unshown_payload_bytes",(dataLength-shown).toString(),start+4+shown,dataLength-shown)
            }
            return node("$length byte(s)")
        }
        val count=length/18
        val actual=minOf(count,4096L)
        for(i in 0 until actual) {
            val pos=start+i*18
            val sample=reader.readUInt64(pos)
            val streamOffset=reader.readUInt64(pos+8)
            children+=BoxNode("SeekPoint",pos,0,18,fields=listOf(
                BoxField("sample_number",sample.toULong().toString(),pos,8),
                BoxField("stream_offset",streamOffset.toULong().toString(),pos+8,8),
                BoxField("frame_samples",reader.readUInt16(pos+16).toString(),pos+16,2),
                BoxField("placeholder",(sample==-1L).toString(),pos,8),
            ))
        }
        val remaining=length-actual*18
        if(remaining>0) fields+=BoxField("unparsed_seektable_bytes",remaining.toString(),start+actual*18,remaining, ValueInterpretation.UNINTERPRETED)
        if(length%18!=0L) { interpretation=ValueInterpretation.UNPARSABLE; w+="Truncated FLAC seek point / FLAC 탐색 지점 잘림" }
        if(actual<count) { if (interpretation == ValueInterpretation.PARSED) interpretation=ValueInterpretation.UNINTERPRETED; w+="FLAC seek point inspection limit / FLAC 탐색 지점 검사 한도" }
        return node("$count seek point(s)")
    }
}
