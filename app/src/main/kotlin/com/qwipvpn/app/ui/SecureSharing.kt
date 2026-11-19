package com.qwipvpn.app.ui

import android.content.*
import android.os.*
import androidx.core.content.FileProvider
import com.qwipvpn.core.Profile
import com.qwipvpn.core.ServerSummary
import com.qwipvpn.app.engine.XrayEngine
import java.io.File
import java.util.UUID

class ShareFileProvider:FileProvider()
object SecureSharing {
    fun copy(context:Context,text:String){
        val clipboard=context.getSystemService(ClipboardManager::class.java);val label="QWIP-"+UUID.randomUUID()
        val clip=ClipData.newPlainText(label,text)
        clip.description.extras=PersistableBundle().apply{putBoolean("android.content.extra.IS_SENSITIVE",true)}
        clipboard.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({runCatching{if(clipboard.primaryClipDescription?.label==label)clipboard.clearPrimaryClip()}},120_000)
    }
    fun payload(profile:Profile):String=if(profile.origin!="json"&&ServerSummary.read(profile)?.composite==false)runCatching{XrayEngine.shareLink(profile.configuration).trim().ifBlank{profile.configuration}}.getOrDefault(profile.configuration)else profile.configuration
    fun shareText(context:Context,text:String,title:String){context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    fun shareFile(context:Context,text:String,title:String){
        val folder=File(context.cacheDir,"exports").apply{mkdirs()}
        folder.listFiles()?.sortedByDescending{it.lastModified()}?.drop(2)?.forEach{it.delete()}
        val file=File(folder,"QWIP-${UUID.randomUUID()}.json");file.writeText(text)
        val uri=FileProvider.getUriForFile(context,context.packageName+".share-files",file)
        val intent=Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData=ClipData.newRawUri("QWIP",uri)
        context.startActivity(Intent.createChooser(intent,title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
