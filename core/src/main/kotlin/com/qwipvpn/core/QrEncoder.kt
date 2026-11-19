package com.qwipvpn.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrEncoder {
    fun pixels(text:String,side:Int=640):IntArray {
        require(text.toByteArray().size in 1..4096 && side in 128..1024)
        val matrix=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,side,side,mapOf(EncodeHintType.CHARACTER_SET to "UTF-8",EncodeHintType.MARGIN to 4,EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        return IntArray(side*side){index->if(matrix[index%side,index/side])-0x1000000 else -1}
    }
}
