package com.qwipvpn.app.surfaces

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.RemoteViews
import com.qwipvpn.core.*
import com.qwipvpn.app.R
import com.qwipvpn.app.data.WidgetStore
import kotlin.math.cos
import kotlin.math.sin

object WidgetRenderer {
    fun color(hex:String):Int=runCatching{if(hex.length==9){val value=hex.drop(1).toLong(16);((value and 255)shl 24 or(value ushr 8)).toInt()}else Color.parseColor(hex)}.getOrDefault(Color.rgb(14,138,71))
    private fun luminance(color:Int)=(Color.red(color)*.2126+Color.green(color)*.7152+Color.blue(color)*.0722)/255
    fun textColor(context:Context,theme:WidgetTheme):Int=when(theme.textTone){
        "light"->Color.WHITE;"dark"->Color.rgb(20,25,22);"custom"->color(theme.textColor)
        else->{val light=when(theme.background){"solid"->luminance(color(theme.solid));"gradient"->(luminance(color(theme.gradientStart))+luminance(color(theme.gradientEnd)))/2;"photo"->(theme.photoLuminance*(1-theme.photoDim)).toDouble();else->if(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)0.1 else 0.98};if(light<.55)Color.WHITE else Color.rgb(20,25,22)}
    }
    fun background(context:Context,theme:WidgetTheme,width:Int=700,height:Int=360):Bitmap{
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val radius=minOf(width,height)*.12f;val rect=RectF(0f,0f,width.toFloat(),height.toFloat());canvas.clipPath(Path().apply{addRoundRect(rect,radius,radius,Path.Direction.CW)})
        when(theme.background){
            "gradient"->{val angle=theme.angle*Math.PI/180;val dx=sin(angle)*width/2;val dy=cos(angle)*height/2;paint.shader=LinearGradient((width/2-dx).toFloat(),(height/2-dy).toFloat(),(width/2+dx).toFloat(),(height/2+dy).toFloat(),color(theme.gradientStart),color(theme.gradientEnd),Shader.TileMode.CLAMP);canvas.drawRect(rect,paint)}
            "photo"->{val photo=WidgetStore(context).photo(theme.photo)?.let{BitmapFactory.decodeFile(it.path)};canvas.drawColor(color(theme.solid));photo?.let{val ratio=maxOf(width.toFloat()/it.width,height.toFloat()/it.height);val w=it.width*ratio;val h=it.height*ratio;canvas.drawBitmap(it,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),paint);it.recycle()};canvas.drawColor(Color.argb((theme.photoDim*255).toInt(),0,0,0))}
            "solid"->canvas.drawColor(color(theme.solid))
            else->canvas.drawColor(if(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)Color.rgb(28,32,30)else Color.WHITE)
        }
        return bitmap
    }
    fun render(context:Context,id:Int,snapshot:SurfaceSnapshot,preferences:WidgetPreferences,theme:WidgetTheme,interactive:Boolean=true,previewWidth:Int=320,previewHeight:Int=170):RemoteViews{
        val state=snapshot.settled();val connected=state.phase==TunnelPhase.CONNECTED;val pending=state.phase in listOf(TunnelPhase.CONNECTING,TunnelPhase.DISCONNECTING)
        val options=if(id>0)AppWidgetManager.getInstance(context).getAppWidgetOptions(id)else android.os.Bundle()
        val width=if(id>0)options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180)else previewWidth
        val height=if(id>0)options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,170)else previewHeight
        val fontScale=context.resources.configuration.fontScale
        val compact=preferences.layout in listOf("compact","icon")||width<160||height<130||fontScale>1.3f
        val largeButton=preferences.layout=="button"&&width>=100&&height>=100
        val views=RemoteViews(context.packageName,if(largeButton)R.layout.vpn_widget_button else R.layout.vpn_widget)
        val vertical=if(largeButton||height<90)0 else if(height<130)8 else 16
        val horizontal=if(largeButton)0 else if(width<160)4 else 16
        fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
        views.setViewPadding(R.id.widget_content,dp(horizontal),dp(vertical),dp(horizontal),dp(vertical))
        views.setImageViewBitmap(R.id.widget_background,background(context,theme))
        val status=context.getString(when(state.phase){TunnelPhase.CONNECTED->R.string.connected;TunnelPhase.CONNECTING->R.string.connecting;TunnelPhase.DISCONNECTING->R.string.disconnecting;else->R.string.disconnected})
        fun styled(text:String)=SpannableString(text).apply{setSpan(TypefaceSpan(when(theme.font){"rounded"->"casual";"serif"->"serif";"mono"->"monospace";else->"sans-serif"}),0,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
        views.setTextViewText(R.id.widget_status,styled(status))
        val server=(if(connected)state.serverName else state.selectedServerName.ifBlank{state.serverName}).let{if(preferences.showFlag)it else it.codePoints().filter{p->p !in 0x1F1E6..0x1F1FF}.toArray().let{points->String(points,0,points.size).trim()}}
        views.setTextViewText(R.id.widget_server,styled(server))
        val textColor=textColor(context,theme);for(view in listOf(R.id.widget_brand,R.id.widget_status,R.id.widget_server,R.id.widget_timer))views.setTextColor(view,textColor)
        val iconOnly=preferences.layout in listOf("icon","button")||width<160||height<85*fontScale
        views.setViewVisibility(R.id.widget_text,if(iconOnly)View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_brand,if(compact)View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_status,if(preferences.showStatus)View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_server,if(preferences.showServer&&server.isNotBlank()&&!compact)View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_timer,if(preferences.showTimer&&connected&&state.startedAt in 1..SystemClock.elapsedRealtime())View.VISIBLE else View.GONE)
        views.setChronometer(R.id.widget_timer,state.startedAt,"%s",connected)
        views.setViewVisibility(R.id.widget_button,if(preferences.showButton||iconOnly)View.VISIBLE else View.GONE)
        val accent=color(preferences.accent?:if(connected)theme.accent else theme.inactive)
        val button=Bitmap.createBitmap(180,180,Bitmap.Config.ARGB_8888);val canvas=Canvas(button);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        if(preferences.layout=="ring"){paint.color=accent;paint.alpha=40;paint.style=Paint.Style.STROKE;paint.strokeWidth=12f;canvas.drawCircle(90f,90f,78f,paint);paint.alpha=255;canvas.drawArc(RectF(12f,12f,168f,168f),-90f,if(connected)360f else 75f,false,paint)}
        else{paint.color=accent;canvas.drawCircle(90f,90f,86f,paint)}
        views.setImageViewBitmap(R.id.widget_button_background,button);views.setInt(R.id.widget_power,"setColorFilter",if(preferences.layout=="ring")accent else Color.WHITE)
        val command=if(connected||pending)"disconnect"else"connect"
        if(interactive){views.setOnClickPendingIntent(R.id.widget_button,ControlIntents.pending(context,command,id));views.setOnClickPendingIntent(R.id.widget_root,ControlIntents.pending(context,"open",id+1_000_000))}
        views.setContentDescription(R.id.widget_button,context.getString(if(connected||pending)R.string.disconnect else R.string.connect))
        return views
    }
}
