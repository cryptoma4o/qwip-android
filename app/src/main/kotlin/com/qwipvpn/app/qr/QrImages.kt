package com.qwipvpn.app.qr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.qwipvpn.core.QrDecoder
import java.io.ByteArrayOutputStream

object QrImages {
    /** Read only the user-selected image; no gallery/storage permission or cache file. */
    fun decode(context: Context,uri: Uri): String {
        val data=context.contentResolver.openInputStream(uri)?.use { input ->
            val out=ByteArrayOutputStream();val chunk=ByteArray(16_384)
            while(true) { val count=input.read(chunk);if(count<0)break;require(out.size()+count<=32*1024*1024);out.write(chunk,0,count) }
            out.toByteArray()
        } ?: error("Image unavailable")
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0)
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600)sample*=2
        val options=BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888 }
        val image=BitmapFactory.decodeByteArray(data,0,data.size,options) ?: error("Image unavailable")
        return try {
            val pixels=IntArray(image.width*image.height)
            image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
            QrDecoder.decodeArgb(pixels,image.width,image.height) ?: error("QR not found")
        } finally { image.recycle() }
    }
}
