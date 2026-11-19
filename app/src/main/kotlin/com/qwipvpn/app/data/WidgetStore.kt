package com.qwipvpn.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.qwipvpn.core.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class WidgetStore(private val context:Context){
    private val vault=SecureVault(context)
    private val json=Json{ignoreUnknownKeys=true;encodeDefaults=true}
    private val photos=File(context.noBackupFilesDir,"widget-photos").apply{mkdirs()}
    fun library():WidgetLibrary=synchronized(lock){vault.read("widget-library")?.let{json.decodeFromString<WidgetLibrary>(it)}?:WidgetLibrary()}
    fun write(library:WidgetLibrary)=synchronized(lock){
        require(library.custom.size<=12);library.custom.forEach(WidgetTheme::validate);vault.write("widget-library",json.encodeToString(library))
        val used=library.custom.mapNotNull{it.photo}.toSet();photos.listFiles()?.filter{it.name.matches(Regex("widget-bg-[a-f0-9-]{36}\\.jpg"))&&it.name !in used}?.sortedByDescending{it.lastModified()}?.drop(24)?.forEach{it.delete()}
    }
    fun preferences(id:Int):WidgetPreferences=synchronized(lock){
        val all=vault.read("widget-options")?.let{json.decodeFromString<Map<String,WidgetPreferences>>(it)}.orEmpty();all[id.toString()]?:all["0"]?:WidgetPreferences()
    }
    fun save(id:Int,value:WidgetPreferences)=synchronized(lock){
        value.validate();val all=vault.read("widget-options")?.let{json.decodeFromString<Map<String,WidgetPreferences>>(it)}.orEmpty().toMutableMap();all[id.toString()]=value
        vault.write("widget-options",json.encodeToString(all.entries.toList().takeLast(100).associate{it.toPair()}))
    }
    fun remove(ids:IntArray)=synchronized(lock){
        val all=vault.read("widget-options")?.let{json.decodeFromString<Map<String,WidgetPreferences>>(it)}.orEmpty().filterKeys{key->ids.none{it==key.toIntOrNull()}}
        vault.write("widget-options",json.encodeToString(all))
    }
    fun clear()=synchronized(lock){vault.delete("widget-options");vault.delete("widget-library");photos.listFiles()?.forEach{it.delete()};Unit}
    fun photo(name:String?):File?=name?.takeIf{it.matches(Regex("widget-bg-[a-f0-9-]{36}\\.jpg"))}?.let{File(photos,it).takeIf(File::isFile)}
    fun importPhoto(uri:Uri):Pair<String,Float>{
        val data=context.contentResolver.openInputStream(uri)?.use{input->
            val bytes=java.io.ByteArrayOutputStream();val chunk=ByteArray(16384)
            while(true){val n=input.read(chunk);if(n<0)break;require(bytes.size()+n<=16*1024*1024);bytes.write(chunk,0,n)};bytes.toByteArray()
        }?:error("No image")
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<=200_000_000L)
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1800)sample*=2
        val decoded=BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("Image unavailable")
        val scale=minOf(1f,900f/maxOf(decoded.width,decoded.height));val bitmap=Bitmap.createScaledBitmap(decoded,(decoded.width*scale).toInt().coerceAtLeast(1),(decoded.height*scale).toInt().coerceAtLeast(1),true)
        if(bitmap!==decoded)decoded.recycle()
        val name="widget-bg-${UUID.randomUUID()}.jpg";val atomic=AtomicFile(File(photos,name));val stream=atomic.startWrite()
        try{require(bitmap.compress(Bitmap.CompressFormat.JPEG,85,stream));atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);bitmap.recycle();throw e}
        var light=0.0;var count=0
        for(y in 0 until bitmap.height step 16)for(x in 0 until bitmap.width step 16){val color=bitmap.getPixel(x,y);light+=(android.graphics.Color.red(color)*.2126+android.graphics.Color.green(color)*.7152+android.graphics.Color.blue(color)*.0722)/255;count++}
        bitmap.recycle();return name to (light/count.coerceAtLeast(1)).toFloat()
    }
    companion object{private val lock=Any()}
}
