package com.qwipvpn.core

import java.nio.ByteBuffer
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

/** Pure on-device QR decoding. No frame is persisted or sent over the network. */
object QrDecoder {
    private val reader=ThreadLocal.withInitial { MultiFormatReader().apply { setHints(mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8")) } }
    fun copyLuma(buffer: ByteBuffer,width: Int,height: Int,rowStride: Int,pixelStride: Int): ByteArray {
        require(width>0 && height>0 && width.toLong()*height<=4_194_304 && rowStride>0 && pixelStride>0)
        val input=buffer.duplicate();val start=input.position()
        require(start.toLong()+(height-1L)*rowStride+(width-1L)*pixelStride<input.limit())
        return ByteArray(width*height).also { bytes ->
            for (y in 0 until height) {
                if(pixelStride==1) { input.position(start+y*rowStride);input.get(bytes,y*width,width) }
                else for (x in 0 until width) bytes[y*width+x]=input.get(start+y*rowStride+x*pixelStride)
            }
        }
    }
    fun decodeLuma(bytes: ByteArray,width: Int,height: Int): String? {
        require(width>0 && height>0 && width.toLong()*height<=4_194_304 && bytes.size>=width*height)
        return decode(PlanarYUVLuminanceSource(bytes,width,height,0,0,width,height,false))
    }
    fun decodeArgb(pixels: IntArray,width: Int,height: Int): String? {
        require(width>0 && height>0 && width.toLong()*height<=4_194_304 && pixels.size==width*height)
        val opaque=IntArray(pixels.size) { index ->
            val color=pixels[index]
            val a=color ushr 24
            if(a==255) color else {
                val r=(((color ushr 16) and 255)*a+255*(255-a))/255
                val g=(((color ushr 8) and 255)*a+255*(255-a))/255
                val b=((color and 255)*a+255*(255-a))/255
                (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return decode(RGBLuminanceSource(width,height,opaque))
    }
    private fun decode(source: LuminanceSource): String? {
        val scanner=reader.get()
        return try { scanner.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text?.takeIf { it.toByteArray().size<=65_536 } }
        catch (_: ReaderException) { null }
        finally { scanner.reset() }
    }
}
