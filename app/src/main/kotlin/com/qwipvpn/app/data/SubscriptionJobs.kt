package com.qwipvpn.app.data

import android.Manifest
import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import com.qwipvpn.core.ClientStore
import com.qwipvpn.app.MainActivity
import com.qwipvpn.app.R
import com.qwipvpn.app.QWIPApplication
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

object SubscriptionJobs {
    fun schedule(context:Context,store:ClientStore){
        val scheduler=context.getSystemService(JobScheduler::class.java)
        for((id,enabled)in listOf(3201 to store.sources.any{it.autoUpdate},3202 to store.settings.expiryReminders)){
            if(!enabled){scheduler.cancel(id);continue}
            if(scheduler.getPendingJob(id)!=null)continue
            val job=JobInfo.Builder(id,ComponentName(context,SubscriptionJobService::class.java)).setPersisted(true)
                .setPeriodic(if(id==3201)3_600_000L else 21_600_000L).setExtras(PersistableBundle().apply{putBoolean("refresh",id==3201)})
            if(id==3201)job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            scheduler.schedule(job.build())
        }
    }
    fun remind(context:Context,store:ClientStore){
        if(!store.settings.expiryReminders)return
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=context.getSystemService(NotificationManager::class.java);if(!manager.areNotificationsEnabled())return
        manager.createNotificationChannel(NotificationChannel("subscription-expiry",context.getString(R.string.expiry_reminders),NotificationManager.IMPORTANCE_DEFAULT))
        val prefs=context.getSharedPreferences("expiry-reminders",Context.MODE_PRIVATE);val now=System.currentTimeMillis()/1000;val day=java.time.LocalDate.now().toString()
        for(source in store.sources){
            val expires=source.usage?.expires?:continue;if(expires-now !in 1L..259200L)continue
            if(prefs.getString(source.id,null)==day)continue
            val open=PendingIntent.getActivity(context,3202,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(source.id.hashCode(),Notification.Builder(context,"subscription-expiry").setSmallIcon(R.drawable.ic_vpn_notification)
                .setContentTitle(context.getString(R.string.expiry_reminder_title)).setContentText(context.getString(R.string.expiry_reminder_body)).setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
            prefs.edit().putString(source.id,day).apply()
        }
    }
}
class SubscriptionJobService:JobService(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs=ConcurrentHashMap<Int,Job>()
    override fun onStartJob(params:JobParameters):Boolean {
        jobs[params.jobId]=scope.launch{
            try{
                val app=application as QWIPApplication;app.repository.load()
                if(params.extras.getBoolean("refresh"))withTimeout(120_000){app.repository.refreshDue()}
                SubscriptionJobs.remind(this@SubscriptionJobService,app.repository.store.value)
            }catch(cancel:CancellationException){throw cancel}catch(_:Exception){/* keep the previous complete subscription */}
            finally{jobs.remove(params.jobId);if(isActive)jobFinished(params,false)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{jobs.remove(params.jobId)?.cancel();return true}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
