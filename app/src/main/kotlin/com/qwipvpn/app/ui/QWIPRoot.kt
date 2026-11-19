@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qwipvpn.app.ui

import android.net.*
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.qwipvpn.app.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qwipvpn.app.ClientViewModel
import com.qwipvpn.core.*
import kotlinx.coroutines.launch

private val light = lightColorScheme(primary=Color(0xFF3153C8),onPrimary=Color.White,
    background=Color(0xFFF2F2F7),surface=Color(0xFFF2F2F7),surfaceContainer=Color.White,
    secondary=Color(0xFF535C76),secondaryContainer=Color(0xFFE4EAFE))
private val dark = darkColorScheme(primary=Color(0xFFAAC5FF),onPrimary=Color(0xFF112550),
    background=Color(0xFF11131C),surface=Color(0xFF11131C),surfaceContainer=Color(0xFF1B2031),
    secondary=Color(0xFFB8C4E2),secondaryContainer=Color(0xFF28334D))

@Composable
fun QWIPRoot(
    model: ClientViewModel,
    onConnect: () -> Unit,
    showVpnDisclosure: Boolean,
    onDisclosureChoice: (Boolean) -> Unit,
    onImportFile: () -> Unit,
    onQrImage: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
) {
    val strings = LocalResources.current
    val store by model.store.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val ready by model.ready.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val version by model.engineVersion.collectAsStateWithLifecycle()
    val lastError by model.lastError.collectAsStateWithLifecycle()
    val probes by model.probes.collectAsStateWithLifecycle()
    val probing by model.probing.collectAsStateWithLifecycle()
    val preparing by model.preparing.collectAsStateWithLifecycle()
    val requestedTab by model.requestedTab.collectAsStateWithLifecycle()
    val serverEditor by model.serverEditor.collectAsStateWithLifecycle()
    var sourceDetails by remember { mutableStateOf<Source?>(null) }
    var profileActions by remember { mutableStateOf<Profile?>(null) }
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner,ready){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_START)model.opened()else if(event==Lifecycle.Event.ON_STOP)model.backgrounded()}
        lifecycleOwner.lifecycle.addObserver(observer)
        if(ready&&lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))model.opened()
        onDispose{lifecycleOwner.lifecycle.removeObserver(observer)}
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(requestedTab){requestedTab?.let{tab=it;model.requestedTab.value=null}}
    var page by rememberSaveable { mutableStateOf("settings") }
    val settingsListState = rememberLazyListState()
    var locations by rememberSaveable { mutableStateOf(false) }
    val add by model.importOpen.collectAsStateWithLifecycle()
    val draft by model.importDraft.collectAsStateWithLifecycle()
    var scanner by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted)scanner=true else model.cameraDenied()
    }
    fun scanQr() {
        model.closeImport()
        if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)scanner=true
        else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }
    val isDark = store.settings.theme == "dark" || store.settings.theme == "system" && isSystemInDarkTheme()
    val scheme = if (isDark) dark else light
    SideEffect {
        var owner = context
        while (owner is android.content.ContextWrapper && owner !is android.app.Activity) owner=owner.baseContext
        (owner as? android.app.Activity)?.let { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window,view).apply {
                isAppearanceLightStatusBars=!isDark
                isAppearanceLightNavigationBars=!isDark
            }
        }
    }
    val titles = mapOf("settings" to strings.getString(R.string.settings),"profiles" to strings.getString(R.string.profiles),"dns" to strings.getString(R.string.dns_connection),
        "routing" to strings.getString(R.string.routing),"geo" to strings.getString(R.string.geodata),"appearance" to strings.getString(R.string.appearance),"diagnostics" to strings.getString(R.string.diagnostics),"network-diagnostics" to strings.getString(R.string.network_diagnostics),"about" to strings.getString(R.string.about)) +
        mapOf("subscriptions" to strings.getString(R.string.subscriptions),"statistics" to strings.getString(R.string.statistics),"history" to strings.getString(R.string.connection_history),"advanced" to strings.getString(R.string.advanced_settings),"routing-profiles" to strings.getString(R.string.routing),"probes" to strings.getString(R.string.latency_check),"speed" to strings.getString(R.string.speed_test),"widgets" to strings.getString(R.string.widgets),"auto-rules" to strings.getString(R.string.auto_rules),"apps" to strings.getString(R.string.apps_title),"age" to strings.getString(R.string.age_keys))
    fun back(){page=when(page){"routing","geo-tags"->"routing-profiles";"auto-rules","apps"->"dns";else->"settings"}}
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); model.message.value = null } }
    BackHandler(tab == 1 && page != "settings") { back() }
    MaterialTheme(colorScheme=scheme) {
        if (showVpnDisclosure) {
            AlertDialog(
                onDismissRequest = { onDisclosureChoice(false) },
                title = { Text(strings.getString(R.string.vpn_disclosure_title)) },
                text = {
                    Text(strings.getString(R.string.vpn_disclosure_body),
                        modifier = Modifier.verticalScroll(rememberScrollState()))
                },
                confirmButton = {
                    TextButton(onClick = { onDisclosureChoice(true) }) {
                        Text(strings.getString(R.string.vpn_disclosure_accept))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDisclosureChoice(false) }) { Text(strings.getString(R.string.cancel)) }
                }
            )
        }
        Scaffold(
            topBar = {
                if (tab == 0) TopAppBar(title = {}, navigationIcon = { IconButton(onClick={locations=true},enabled=store.profiles.isNotEmpty(),modifier=Modifier.testTag("locations-search")) { Icon(QWIPIcons.Search,strings.getString(R.string.search_location)) } },
                    actions = { IconButton(onClick=model::openImport,enabled=!busy,modifier=Modifier.testTag("add-config")) { Icon(QWIPIcons.Add,strings.getString(R.string.add_config)) } },colors=TopAppBarDefaults.topAppBarColors(containerColor=scheme.background))
                else TopAppBar(title={ Text(titles[page] ?: strings.getString(R.string.settings)) },navigationIcon={ if (page != "settings") IconButton(onClick={back()}) { Icon(QWIPIcons.ArrowBack,strings.getString(R.string.back)) } })
            },
            bottomBar = { Surface(shape=RoundedCornerShape(32.dp),color=scheme.surfaceContainer,shadowElevation=6.dp,modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp).navigationBarsPadding()){ NavigationBar(containerColor=Color.Transparent,windowInsets=WindowInsets(0.dp)) {
                NavigationBarItem(selected=tab==0,onClick={tab=0},icon={ Icon(QWIPIcons.Shield,strings.getString(R.string.connection)) },label={ Text(strings.getString(R.string.connection)) },modifier=Modifier.testTag("tab-connect"))
                NavigationBarItem(selected=tab==1,onClick={if(tab==1)page="settings";tab=1},icon={ Icon(QWIPIcons.Settings,strings.getString(R.string.settings)) },label={ Text(strings.getString(R.string.settings)) },modifier=Modifier.testTag("tab-settings"))
            } } }, snackbarHost={ SnackbarHost(snack) }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally) {
                if (busy) Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                    LinearProgressIndicator(Modifier.weight(1f)); TextButton(onClick=model::cancel) { Text(strings.getString(R.string.cancel_action)) }
                }
                Box(Modifier.widthIn(max=620.dp).fillMaxSize()) {
                    if (tab == 0) ConnectionScreen(store,if(preparing&&connection.phase !in listOf(TunnelPhase.CONNECTED,TunnelPhase.CONNECTING))connection.copy(phase=TunnelPhase.CONNECTING)else connection,ready,busy,
                        onConnect=onConnect,onStop=model::disconnect,onReconnect=model::reconnect,onLocations={locations=true},onAdd=model::openImport,
                        probes=probes,probing=probing,onProbe={model.probeServers(it)},onSelect=model::select,onFavorite=model::favorite,
                        onCollapse=model::collapse,onCollapseAll=model::collapseAll,onSource={sourceDetails=it},onRefreshSource=model::refreshSource,onProfileActions={profileActions=it})
                    else SettingsScreen(page,store,connection,version,busy,
                        onPage={page=it},onSettings=model::settings,onAdd=model::openImport,onRefresh=model::refresh,
                        onGeo={download,urls -> model.geo(download,urls)},onExport=onExport,onRestore=onRestore,
                        onDeleteProfile={id -> model.action { model.repository.removeProfile(id) } },
                        onDeleteSource={id -> model.action { model.repository.removeSource(id) } },
                        onEdit={id,config -> model.action(strings.getString(R.string.import_failed),strings.getString(R.string.config_saved)) { model.repository.editProfile(id,config) } },model=model,listState=settingsListState)
                }
            }
        }
        if (locations) LocationSheet(store,busy,onDismiss={locations=false},onSelect={model.select(it);locations=false},onFavorite=model::favorite,onRefresh=model::refresh,onAdd={locations=false;model.openImport()},probes=probes,probing=probing,onProbe={model.probeServers()})
        if (add) ImportSheet(draft,{model.importDraft.value=it},busy,lastError,onCancel=model::cancel,onDismiss={ if (!busy) model.closeImport() },
            onImport={model.import(it) { model.closeImport(clear=true) } },onFile={model.closeImport();onImportFile()},onScan=::scanQr,onQrImage={model.closeImport();onQrImage()},onManual={model.closeImport();model.editServer()})
        if(scanner)QrScannerScreen(onClose={scanner=false;model.openImport()},onImage={scanner=false;onQrImage()},onDetected={if(scanner){scanner=false;model.acceptQr(it)}})
        serverEditor?.let { ServerEditorSheet(model,it,model::closeServerEditor) }
        sourceDetails?.let{source->SourceDetailsSheet(model,store.sources.firstOrNull{it.id==source.id}?:source){sourceDetails=null}}
        profileActions?.let{profile->ProfileActionsSheet(model,profile,onDismiss={profileActions=null},onForm={profileActions=null;model.editServer(profile)})}
    }
}

@Composable fun networkAvailable(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(ConnectivityManager::class.java) }
    var available by remember { mutableStateOf(manager.activeNetwork != null) }
    DisposableEffect(manager) {
        val networks = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { synchronized(networks) { networks += network; available = networks.isNotEmpty() } }
            override fun onLost(network: Network) { synchronized(networks) { networks -= network; available = networks.isNotEmpty() } }
        }
        manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return available
}
