package com.qwipvpn.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.test.*

class QrTests {
    private val url="https://example.invalid/s/synthetic%2Ftoken?access=one%2Btwo+three"
    private val size=256
    private fun pixels(): ByteArray {
        val matrix=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,size,size)
        return ByteArray(size*size) { i -> if(matrix[i%size,i/size])0 else 255.toByte() }
    }
    @Test fun httpsQrKeepsOpaquePathAndQueryExact() { assertEquals(url,QrPayload.normalize("  $url\n")) }
    @Test fun nativeShareLinkQrKeepsUnicodeAndCredentialsExact() {
        val value="vless://00000000-0000-4000-8000-000000000001@example.invalid:443?security=reality&type=tcp#Germany%20%2B"
        assertEquals(value,QrPayload.normalize(value))
    }
    @Test fun compatibilityWrappersPreserveEncodedTokensAndPlusSigns() {
        assertEquals(url,QrPayload.normalize("happ://add/$url"))
        assertEquals(url,QrPayload.normalize("qwip://import?url="+URLEncoder.encode(url,"UTF-8")))
        assertEquals(url,QrPayload.normalize("happ://add/"+URLEncoder.encode(url,"UTF-8")))
    }
    @Test fun fullJsonQrDoesNotProjectOrRoundOpaqueNumbers() {
        val json="""{"outbounds":[{"protocol":"freedom"}],"opaque":900719925474099312345}"""
        assertEquals(json,QrPayload.normalize(json))
    }
    @Test fun base64QrIsDecodedLocally() { assertEquals(url,QrPayload.normalize(Base64.getEncoder().encodeToString(url.toByteArray()))) }
    @Test fun unrelatedQrCannotLaunchBrowserOrBeTreatedAsVpn() {
        listOf("WIFI:T:WPA;S:synthetic;P:synthetic;;","javascript:alert(1)","mailto:test@example.invalid","ftp://example.invalid/file","plain text").forEach { assertFails(it) { QrPayload.normalize(it) } }
    }
    @Test fun nestedOrOversizedQrIsRejectedWithoutRecursionOrMemoryGrowth() {
        assertFails { QrPayload.normalize("happ://add/".repeat(4)+url) }
        assertFails { QrPayload.normalize("x".repeat(65_537)) }
    }
    @Test fun paddedCameraPlanesAndPixelStrideDecodeCorrectly() {
        val original=pixels()
        for (pixelStride in listOf(1,2)) {
            val rowStride=size*pixelStride+13;val offset=11
            val buffer=ByteBuffer.allocate(offset+rowStride*size)
            for(y in 0 until size)for(x in 0 until size)buffer.put(offset+y*rowStride+x*pixelStride,original[y*size+x])
            buffer.position(offset)
            val bytes=QrDecoder.copyLuma(buffer,size,size,rowStride,pixelStride)
            assertContentEquals(original,bytes);assertEquals(url,QrDecoder.decodeLuma(bytes,size,size))
            assertEquals(offset,buffer.position())
        }
    }
    @Test fun cameraOrientationAndInvertedQrAreSupported() {
        var frame=pixels()
        repeat(4) {
            assertEquals(url,QrDecoder.decodeLuma(frame,size,size))
            frame=ByteArray(frame.size) { i -> val x=i%size;val y=i/size;frame[(size-1-x)*size+y] }
        }
        assertEquals(url,QrDecoder.decodeLuma(ByteArray(frame.size) { (255-(frame[it].toInt() and 255)).toByte() },size,size))
    }
    @Test fun transparentImageBackgroundIsCompositedToWhite() {
        val pixels=pixels().map { if(it==0.toByte())0xff000000.toInt() else 0 }.toIntArray()
        assertEquals(url,QrDecoder.decodeArgb(pixels,size,size))
    }
    @Test fun blankOrMalformedFramesDoNotProduceAResult() {
        assertNull(QrDecoder.decodeLuma(ByteArray(size*size) { 255.toByte() },size,size))
        assertFails { QrDecoder.copyLuma(ByteBuffer.allocate(3),20,20,20,1) }
        assertFails { QrDecoder.decodeLuma(byteArrayOf(1),Int.MAX_VALUE,Int.MAX_VALUE) }
    }
}
