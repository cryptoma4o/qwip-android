@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qwipvpn.app.ui

import android.os.SystemClock
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.qwipvpn.app.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qwipvpn.core.QrDecoder
import com.qwipvpn.core.QrPayload
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/** Own scanner UI, CameraX lifecycle and local ZXing decoder. No captured photos. */
@Composable fun QrScannerScreen(onClose: () -> Unit,onImage: () -> Unit,onDetected: (String) -> Unit) {
    val strings = LocalResources.current
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    val currentResult by rememberUpdatedState(onDetected)
    val preview=remember { PreviewView(context).apply { implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FILL_CENTER } }
    var streaming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf(strings.getString(R.string.qr_camera_hint)) }
    DisposableEffect(lifecycle,preview) {
        val disposed=AtomicBoolean(false);val delivered=AtomicBoolean(false)
        val worker=Executors.newSingleThreadExecutor { Thread(it,"QWIP-QR") }
        val main=ContextCompat.getMainExecutor(context)
        val capture=Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val selector=ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280,720),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
        val analysis=ImageAnalysis.Builder().setResolutionSelector(selector).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var last=0L
        analysis.setAnalyzer(worker) { image ->
            try {
                val now=SystemClock.elapsedRealtime()
                if(disposed.get() || delivered.get() || now-last<220)return@setAnalyzer
                last=now
                val plane=image.planes.first()
                val bytes=QrDecoder.copyLuma(plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride)
                val raw=QrDecoder.decodeLuma(bytes,image.width,image.height) ?: return@setAnalyzer
                val value=runCatching { QrPayload.normalize(raw) }.getOrNull()
                if(value==null) main.execute { if(!disposed.get())hint=strings.getString(R.string.qr_wrong_camera_hint) }
                else if(delivered.compareAndSet(false,true)) main.execute { if(!disposed.get())currentResult(value) }
            } catch (_: Exception) { /* Dropped/unsupported frames are retried without logging their contents. */ }
            finally { image.close() }
        }
        var provider: ProcessCameraProvider?=null
        var camera: Camera?=null
        val streamObserver=Observer<PreviewView.StreamState> { state ->
            streaming=state==PreviewView.StreamState.STREAMING
            if(streaming)error=null
        }
        val errorObserver=Observer<CameraState> { state ->
            if(state.error!=null)error=strings.getString(R.string.camera_unavailable)
        }
        preview.previewStreamState.observe(lifecycle,streamObserver)
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(!disposed.get()) {
                try {
                    provider=future.get()
                    val lens=if(provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    if(ContextCompat.checkSelfPermission(context,android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        camera=provider!!.bindToLifecycle(lifecycle,lens,capture,analysis)
                        camera!!.cameraInfo.cameraState.observe(lifecycle,errorObserver)
                    } else error=strings.getString(R.string.camera_permission_image)
                } catch (_: Exception) { error=strings.getString(R.string.camera_open_failed) }
            }
        },main)
        onDispose {
            disposed.set(true);analysis.clearAnalyzer()
            preview.previewStreamState.removeObserver(streamObserver)
            camera?.cameraInfo?.cameraState?.removeObserver(errorObserver)
            provider?.unbind(capture,analysis)
            worker.shutdown()
        }
    }
    LaunchedEffect(streaming) {
        if(!streaming) {
            delay(8_000)
            if(lifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !streaming && error==null)error=strings.getString(R.string.camera_timeout)
        }
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val dialogView=LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window,dialogView).apply { isAppearanceLightStatusBars=false;isAppearanceLightNavigationBars=false }
            }
        }
        MaterialTheme(colorScheme=darkColorScheme(background=Color.Black,surface=Color.Black,primary=Color(0xFF8DBAFF))) {
            Scaffold(topBar={TopAppBar(title={Text(strings.getString(R.string.scan_qr))},navigationIcon={IconButton(onClick=onClose){Icon(QWIPIcons.ArrowBack,strings.getString(R.string.close_scanner))}})}) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
                    AndroidView(factory={preview},modifier=Modifier.fillMaxSize())
                    if(error==null)Box(Modifier.align(Alignment.Center).fillMaxWidth(.76f).aspectRatio(1f).border(2.dp,Color.White,RoundedCornerShape(20.dp)))
                    if(!streaming && error==null)CircularProgressIndicator(Modifier.align(Alignment.Center))
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha=.76f)).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Text(error ?: hint,textAlign=TextAlign.Center,color=Color.White,style=MaterialTheme.typography.bodyLarge)
                        Text(strings.getString(R.string.image_local_processing),color=Color.White.copy(alpha=.7f),style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center)
                        OutlinedButton(onClick=onImage) { Text(strings.getString(R.string.qr_from_image)) }
                    }
                }
            }
        }
    }
}
