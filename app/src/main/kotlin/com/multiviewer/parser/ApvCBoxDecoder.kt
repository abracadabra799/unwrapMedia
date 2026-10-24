package com.multiviewer.parser

object ApvCBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int,
                        size: Long, warnings: List<String>): BoxNode {
        val w=warnings.toMutableList()
        val fields=mutableListOf<BoxField>()
        val entries=mutableListOf<BoxNode>()
        var failed=false
        var limited=false
        fun node(summary: String? = null)=BoxNode(type,offset,headerSize,size,fields=fields.toList(),children=entries.toList(),warnings=w.toList(),summary=summary,interpretation=if(failed) ValueInterpretation.UNPARSABLE else if(limited) ValueInterpretation.UNINTERPRETED else ValueInterpretation.PARSED)
        if(offset<0 || headerSize<0 || size<headerSize || offset>reader.length || size>reader.length-offset || size-headerSize<6) {
            failed=true
            w+="Truncated APV configuration header / APV 설정 헤더 잘림"
            return node()
        }
        val start=offset+headerSize
        val end=offset+size
        val version=reader.readUInt8(start)
        val flags=reader.readUInt32(start) and 0xffffff
        val configVersion=reader.readUInt8(start+4)
        val count=reader.readUInt8(start+5)
        val headers=listOf(
            BoxField("version",version.toString(),start,1),
            BoxField("flags",flags.toString(),start+1,3),
            BoxField("configuration_version",configVersion.toString(),start+4,1),
            BoxField("number_of_configuration_entry",count.toString(),start+5,1),
        )
        if(version!=0 || flags!=0L || configVersion!=1) {
            fields+=headers
            failed=true
            w+="Unsupported APV configuration version/flags / 미지원 APV 설정 버전 또는 플래그"
            return node()
        }
        var pos=start+6
        var frameCount=0
        var firstFrame: BoxNode?=null
        var stopped=false
        for(i in 0 until count) {
            if(end-pos<2) { failed=true; w+="Truncated APV entry / APV 항목 잘림"; break }
            val entryStart=pos
            val pbuType=reader.readUInt8(pos)
            val frameInfoCount=reader.readUInt8(pos+1)
            val entryFields=listOf(BoxField("pbu_type",pbuType.toString(),pos,1),BoxField("number_of_frame_info",frameInfoCount.toString(),pos+1,1))
            pos+=2
            val frames=mutableListOf<BoxNode>()
            for(j in 0 until frameInfoCount) {
                if(frameCount==4096) { limited=true; w+="APV frame inspection limit / APV 프레임 검사 한도"; stopped=true; break }
                if(end-pos<14) { failed=true; w+="Truncated APV frame info / APV 프레임 정보 잘림"; stopped=true; break }
                val begin=pos
                val frameFlags=reader.readUInt8(pos)
                val chroma=reader.readUInt8(pos+12)
                val colorPresent=frameFlags and 2!=0
                val ff=mutableListOf(
                    BoxField("profile_idc",reader.readUInt8(pos+1).toString(),pos+1,1),
                    BoxField("level_idc",reader.readUInt8(pos+2).toString(),pos+2,1),
                    BoxField("band_idc",reader.readUInt8(pos+3).toString(),pos+3,1),
                    BoxField("frame_width",reader.readUInt32(pos+4).toString(),pos+4,4),
                    BoxField("frame_height",reader.readUInt32(pos+8).toString(),pos+8,4),
                    BoxField("chroma_format_idc",(chroma ushr 4).toString(),pos+12,1),
                    BoxField("bit_depth",(8+(chroma and 15)).toString(),pos+12,1),
                    BoxField("bit_depth_minus8",(chroma and 15).toString(),pos+12,1),
                    BoxField("reserved_zero_6bits",(frameFlags ushr 2).toString(),pos,1),
                    BoxField("color_description_present_flag",colorPresent.toString(),pos,1),
                    BoxField("capture_time_distance_ignored",(frameFlags and 1!=0).toString(),pos,1),
                    BoxField("capture_time_distance",reader.readUInt8(pos+13).toString(),pos+13,1),
                )
                val fw=mutableListOf<String>()
                if(frameFlags ushr 2!=0) fw+="Nonzero APV reserved bits / APV 예약 비트 오류"
                pos+=14
                if(colorPresent) {
                    if(end-pos<4) { failed=true; fw+="Truncated APV color description / APV 색 정보 잘림"; stopped=true }
                    else {
                        ff+=BoxField("color_primaries",reader.readUInt8(pos).toString(),pos,1)
                        ff+=BoxField("transfer_characteristics",reader.readUInt8(pos+1).toString(),pos+1,1)
                        ff+=BoxField("matrix_coefficients",reader.readUInt8(pos+2).toString(),pos+2,1)
                        val range=reader.readUInt8(pos+3)
                        ff+=BoxField("full_range_flag",(range and 128!=0).toString(),pos+3,1)
                        ff+=BoxField("reserved_zero_7bits",(range and 127).toString(),pos+3,1)
                        if(range and 127!=0) fw+="Nonzero APV color reserved bits / APV 색 정보 예약 비트 오류"
                        pos+=4
                    }
                }
                val frame=BoxNode("frame_info",begin,0,pos-begin,fields=ff,warnings=fw,interpretation=if(stopped && failed) ValueInterpretation.UNPARSABLE else ValueInterpretation.PARSED)
                frames+=frame
                if(firstFrame==null) firstFrame=frame
                frameCount++
                w+=fw
                if(stopped) break
            }
            entries+=BoxNode("configuration_entry",entryStart,2,pos-entryStart,fields=entryFields,children=frames,interpretation=if(failed) ValueInterpretation.UNPARSABLE else if(limited) ValueInterpretation.UNINTERPRETED else ValueInterpretation.PARSED)
            if(stopped) break
        }
        firstFrame?.let { fields+=it.fields.take(7) }
        fields+=headers
        if(firstFrame==null) { failed=true; w+="No complete APV frame info / 완전한 APV 프레임 정보 없음" }
        if(pos<end) {
            fields+=BoxField("unparsed_configuration_bytes",(end-pos).toString(),pos,end-pos, ValueInterpretation.UNINTERPRETED)
            if (!stopped) w+="Unparsed APV configuration bytes / 미해석 APV 설정 바이트"
        }
        val first=firstFrame
        return node(first?.let {
            fun value(name: String)=it.fields.first { f->f.name==name }.value
            "profile=${value("profile_idc")}, level=${value("level_idc")}, ${value("frame_width")}x${value("frame_height")}" })
    }
}
