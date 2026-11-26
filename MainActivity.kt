package com.mnasser.cobaltremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Ink = Color(0xFF0A101D)
private val SurfaceCard = Color(0xFF131D30)
private val SurfaceElevated = Color(0xFF1C2A42)
private val BorderSubtle = Color(0xFF283A57)
private val Accent = Color(0xFF2EE6CE)
private val AccentDark = Color(0xFF062925)
private val YoutubeRed = Color(0xFFFF3D57)
private val KodiBlue = Color(0xFF29B6F6)
private val Danger = Color(0xFFFF5C72)
private val Warning = Color(0xFFFFC861)
private val TextPrimary = Color(0xFFF3F6FB)
private val TextMuted = Color(0xFF94A3B8)

private enum class RemoteTab { REMOTE, KODI, APPS }
private enum class KodiSection { MOVIES, SERIES }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val prefs = getSharedPreferences("cobalt_remote", MODE_PRIVATE)
        val secure = SecureStore(this)
        setContent {
            val scheme = darkColorScheme(
                primary = Accent,
                onPrimary = AccentDark,
                background = Ink,
                onBackground = TextPrimary,
                surface = SurfaceCard,
                onSurface = TextPrimary,
                surfaceVariant = SurfaceElevated,
                onSurfaceVariant = TextMuted,
                outline = BorderSubtle
            )
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Ink,
                        contentColor = TextPrimary
                    ) {
                        RemoteScreen(
                            savedIp = prefs.getString("tv_ip", "") ?: "",
                            savedKey = secure.read("client_key"),
                            savedFingerprint = prefs.getString("tv_fingerprint", null),
                            savedKodiMoviesJson = prefs.getString("kodi_movies_cache", null),
                            savedKodiShowsJson = prefs.getString("kodi_shows_cache", null),
                            onSave = { ip, key, fp ->
                                prefs.edit()
                                    .putString("tv_ip", ip)
                                    .putString("tv_fingerprint", fp)
                                    .apply()
                                secure.write("client_key", key)
                            },
                            onCacheKodiMovies = { json ->
                                prefs.edit().putString("kodi_movies_cache", json).apply()
                            },
                            onCacheKodiShows = { json ->
                                prefs.edit().putString("kodi_shows_cache", json).apply()
                            },
                            onClear = {
                                secure.remove("client_key")
                                prefs.edit().remove("tv_fingerprint").apply()
                            }
                        )
                    }
                }
            }
        }
    }
}

private class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("cobalt_secure", Context.MODE_PRIVATE)
    private val alias = "cobalt_remote_aes_v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun write(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit().putString(
            name,
            android.util.Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), android.util.Base64.NO_WRAP)
        ).apply()
    }

    fun read(name: String): String? = runCatching {
        val raw = android.util.Base64.decode(prefs.getString(name, null), android.util.Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size)))
    }.getOrNull()

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }
}

private suspend fun discoverLgTv(context: Context): String? = withContext(Dispatchers.IO) {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val lock = runCatching { wifi?.createMulticastLock("cobalt_ssdp")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
    try {
        val fastResult = CompletableDeferred<String?>()
        coroutineScope {
            val ssdpJob = launch {
                discoverViaSsdp()?.let { if (!fastResult.isCompleted) fastResult.complete(it) }
            }
            val nsdJob = launch {
                discoverViaNsd(context)?.let { if (!fastResult.isCompleted) fastResult.complete(it) }
            }
            val found = withTimeoutOrNull(3800) { fastResult.await() }
            ssdpJob.cancel()
            nsdJob.cancel()
            if (found != null) return@coroutineScope found
            scanLocalSubnetForWebOs(context)
        }
    } finally {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }
}

private fun discoverViaSsdp(): String? {
    val queries = listOf(
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:lge-com:service:webos-second-screen:1\r\n\r\n",
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n"
    )
    return runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = 2800
            val group = InetAddress.getByName("239.255.255.250")
            queries.forEach { q ->
                val bytes = q.toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
            }
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 3200
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length)
                    if (response.contains("lge-com", true) ||
                        response.contains("webos", true) ||
                        response.contains("LG", true)
                    ) {
                        val ip = packet.address?.hostAddress
                        if (!ip.isNullOrBlank() && probePort(ip, 3001, 400)) return@use ip
                    }
                } catch (_: Exception) {
                    break
                }
            }
            null
        }
    }.getOrNull()
}

private suspend fun discoverViaNsd(context: Context): String? = withContext(Dispatchers.Main) {
    val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@withContext null
    val result = CompletableDeferred<String?>()
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType.contains("webos", true) || service.serviceName.contains("LG", true)) {
                @Suppress("DEPRECATION")
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        if (!result.isCompleted) result.complete(s.host?.hostAddress)
                    }
                })
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            if (!result.isCompleted) result.complete(null)
            runCatching { nsd.stopServiceDiscovery(this) }
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
    }
    runCatching { nsd.discoverServices("_webos-second-screen._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
        .onFailure { if (!result.isCompleted) result.complete(null) }
    try {
        withTimeoutOrNull(3500) { result.await() }
    } finally {
        runCatching { nsd.stopServiceDiscovery(listener) }
    }
}

private suspend fun scanLocalSubnetForWebOs(context: Context): String? = coroutineScope {
    val localIp = getLocalIpv4(context) ?: return@coroutineScope null
    val prefix = localIp.substringBeforeLast('.')
    val found = CompletableDeferred<String?>()
    val jobs = (1..254).map { lastOctet ->
        launch(Dispatchers.IO) {
            val candidate = "$prefix.$lastOctet"
            if (candidate == localIp || found.isCompleted) return@launch
            if (probePort(candidate, 3001, 350)) {
                if (!found.isCompleted) found.complete(candidate)
            }
        }
    }
    val res = withTimeoutOrNull(2800) { found.await() }
    jobs.forEach { it.cancel() }
    res
}

private fun getLocalIpv4(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val lp = cm?.getLinkProperties(cm.activeNetwork)
    lp?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}.getOrNull()

private fun probePort(ip: String, port: Int, timeoutMs: Int): Boolean = runCatching {
    Socket().use { s ->
        s.connect(InetSocketAddress(ip, port), timeoutMs)
        true
    }
}.getOrDefault(false)

private fun parseHexColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
}

private fun fallbackIconForApp(app: TvApp): ImageVector {
    val key = "${app.id} ${app.title}".lowercase()
    return when {
        "youtube" in key || "cobalt" in key -> Icons.Rounded.SmartDisplay
        "kodi" in key || "xbmc" in key -> Icons.Rounded.LocalMovies
        "netflix" in key || "prime" in key || "shahid" in key || "disney" in key || "watch" in key -> Icons.Rounded.Movie
        "browser" in key || "web" in key -> Icons.Rounded.Public
        "setting" in key -> Icons.Rounded.Settings
        "livetv" in key || "tv" in key -> Icons.Rounded.LiveTv
        "hdmi" in key || "input" in key -> Icons.Rounded.SettingsInputHdmi
        "music" in key || "spotify" in key || "audio" in key -> Icons.Rounded.MusicNote
        "photo" in key || "media" in key || "video" in key || "plex" in key -> Icons.Rounded.PermMedia
        "store" in key || "discovery" in key || "content" in key -> Icons.Rounded.ShoppingBag
        "game" in key -> Icons.Rounded.SportsEsports
        else -> Icons.Rounded.Apps
    }
}

@Composable
private fun RemoteScreen(
    savedIp: String,
    savedKey: String?,
    savedFingerprint: String?,
    savedKodiMoviesJson: String?,
    savedKodiShowsJson: String?,
    onSave: (String, String, String?) -> Unit,
    onCacheKodiMovies: (String) -> Unit,
    onCacheKodiShows: (String) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var ip by remember { mutableStateOf(savedIp) }
    var typed by remember { mutableStateOf("") }
    var lastSentText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("جارٍ تهيئة الاتصال…") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var imeFocused by remember { mutableStateOf(false) }
    var pairingKey by remember { mutableStateOf(savedKey) }
    var certificatePin by remember { mutableStateOf(savedFingerprint) }
    var client by remember { mutableStateOf<WebOsClient?>(null) }
    var apps by remember { mutableStateOf<List<TvApp>>(emptyList()) }
    var volume by remember { mutableFloatStateOf(20f) }
    var activeTab by remember { mutableStateOf(RemoteTab.REMOTE) }
    var showConnectionPanel by remember { mutableStateOf(savedIp.isBlank()) }
    var connectJob by remember { mutableStateOf<Job?>(null) }

    // Kodi Movies & TV Shows State (Port 9090 automatic, zero settings clutter!)
    var kodiSection by remember { mutableStateOf(KodiSection.MOVIES) }
    var kodiMovies by remember { mutableStateOf(KodiClient.deserializeMovies(savedKodiMoviesJson)) }
    var kodiShows by remember { mutableStateOf(KodiClient.deserializeShows(savedKodiShowsJson)) }
    var kodiFilter by remember { mutableStateOf("") }
    var kodiLoading by remember { mutableStateOf(false) }
    var kodiStatus by remember { mutableStateOf<String?>(null) }

    // Selected TV Show Episodes Dialog
    var selectedShow by remember { mutableStateOf<KodiShow?>(null) }
    var showEpisodes by remember { mutableStateOf<List<KodiEpisode>>(emptyList()) }
    var episodesLoading by remember { mutableStateOf(false) }
    var selectedSeason by remember { mutableIntStateOf(-1) }

    // YouTube Search Dialog State
    var showYtDialog by remember { mutableStateOf(false) }
    var ytSearching by remember { mutableStateOf(false) }
    var ytSearchQuery by remember { mutableStateOf("") }
    var ytResults by remember { mutableStateOf<List<YouTubeSearchResult>>(emptyList()) }
    var ytError by remember { mutableStateOf<String?>(null) }

    val tvImageLoader = remember(client) {
        ImageLoader.Builder(context).apply {
            client?.let { okHttpClient(it.imageHttpClient()) }
        }.build()
    }

    val kodiClient = remember(ip) {
        if (ip.isBlank()) null else KodiClient(ip.trim(), 9090)
    }

    val imeQueue = remember { Channel<suspend (WebOsClient) -> Unit>(Channel.UNLIMITED) }

    LaunchedEffect(Unit) {
        for (action in imeQueue) {
            val c = client ?: continue
            runCatching { action(c) }.onFailure { err ->
                if (err is WebOsConnectionException) {
                    connected = false
                    status = err.message ?: "انقطع الاتصال"
                }
            }
        }
    }

    fun resolveYouTubeAppId(foregroundId: String?): String {
        if (!foregroundId.isNullOrBlank() &&
            (foregroundId.contains("youtube", true) || foregroundId.contains("cobalt", true))
        ) {
            return foregroundId
        }
        apps.firstOrNull { it.id.contains("cobalt", true) || it.title.contains("cobalt", true) }?.let { return it.id }
        apps.firstOrNull { it.id.contains("youtube", true) || it.title.contains("youtube", true) }?.let { return it.id }
        return "youtube.leanback.v4"
    }

    fun resolveKodiAppId(): String {
        apps.firstOrNull {
            it.id.contains("kodi", true) || it.id.contains("xbmc", true) || it.title.contains("kodi", true)
        }?.let { return it.id }
        return "org.xbmc.kodi"
    }

    suspend fun ensureKodiReady(kodi: KodiClient): Boolean {
        if (kodi.ping()) return true
        val tv = client ?: return false
        kodiStatus = "جارٍ تشغيل كودي على التلفزيون…"
        runCatching { tv.launchApp(resolveKodiAppId()) }
        repeat(18) {
            delay(850)
            if (kodi.ping()) return true
        }
        return false
    }

    fun refreshKodiLibrary(autoLaunch: Boolean = false) {
        val kodi = kodiClient ?: run {
            kodiStatus = "اتصل بالتلفزيون أولاً"
            return
        }
        scope.launch {
            kodiLoading = true
            kodiStatus = "جارٍ جلب مكتبة كودي…"
            val ready = if (autoLaunch) ensureKodiReady(kodi) else kodi.ping()
            if (!ready) {
                kodiLoading = false
                kodiStatus = "كودي غير مفتوح — اضغط تحديث لتشغيله تلقائياً"
                return@launch
            }
            val moviesRes = runCatching { kodi.getMovies() }
            val showsRes = runCatching { kodi.getTvShows() }
            moviesRes.onSuccess { list ->
                kodiMovies = list
                onCacheKodiMovies(KodiClient.serializeMovies(list))
            }
            showsRes.onSuccess { list ->
                kodiShows = list
                onCacheKodiShows(KodiClient.serializeShows(list))
            }
            kodiLoading = false
            kodiStatus = "تم التحديث: ${kodiMovies.size} فيلم • ${kodiShows.size} مسلسل"
        }
    }

    fun playKodiMovieDirectly(movie: KodiMovie) {
        val kodi = kodiClient ?: return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch {
            kodiLoading = true
            kodiStatus = "جارٍ تشغيل: ${movie.title}…"
            if (!ensureKodiReady(kodi)) {
                kodiLoading = false
                kodiStatus = "تعذر الوصول إلى كودي — تأكد من تفعيل التحكم من الأنظمة الأخرى"
                return@launch
            }
            runCatching { kodi.playMovie(movie.movieId, movie.file) }
                .onSuccess {
                    status = "يُعرض الآن: ${movie.title}"
                    kodiStatus = "تم تشغيل الفيلم: ${movie.title}"
                }
                .onFailure { kodiStatus = "تعذر تشغيل الفيلم: ${it.message}" }
            kodiLoading = false
        }
    }

    fun openShowEpisodes(show: KodiShow) {
        val kodi = kodiClient ?: return
        selectedShow = show
        showEpisodes = emptyList()
        selectedSeason = -1
        episodesLoading = true
        scope.launch {
            if (!ensureKodiReady(kodi)) {
                episodesLoading = false
                kodiStatus = "تعذر الاتصال بكودي لجلب الحلقات"
                return@launch
            }
            runCatching { kodi.getEpisodes(show.tvshowId) }
                .onSuccess { eps ->
                    showEpisodes = eps
                    selectedSeason = eps.map { it.season }.distinct().minOrNull() ?: -1
                }
                .onFailure { kodiStatus = "تعذر جلب الحلقات: ${it.message}" }
            episodesLoading = false
        }
    }

    fun playKodiEpisodeDirectly(ep: KodiEpisode, showTitle: String) {
        val kodi = kodiClient ?: return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch {
            kodiStatus = "جارٍ تشغيل: $showTitle - ح${ep.episode}…"
            if (!ensureKodiReady(kodi)) {
                kodiStatus = "تعذر الوصول إلى كودي"
                return@launch
            }
            runCatching { kodi.playEpisode(ep.episodeId, ep.file) }
                .onSuccess {
                    status = "يُعرض الآن: $showTitle (م${ep.season} ح${ep.episode})"
                    kodiStatus = "تم تشغيل الحلقة ${ep.episode} من $showTitle"
                    selectedShow = null
                }
                .onFailure { kodiStatus = "تعذر تشغيل الحلقة: ${it.message}" }
        }
    }

    fun connect(address: String, fallbackToDiscovery: Boolean = false) {
        val cleanIp = address.trim()
        if (cleanIp.isBlank()) return
        connectJob?.cancel()
        connectJob = scope.launch {
            ip = cleanIp
            busy = true
            status = "جارٍ الاتصال بـ $cleanIp…"
            var attempt = 0
            var succeeded = false
            while (attempt < 2 && !succeeded) {
                val candidate = WebOsClient(
                    host = cleanIp,
                    clientKey = pairingKey,
                    pinnedFingerprint = certificatePin,
                    onImeFocusChanged = { focused ->
                        scope.launch(Dispatchers.Main) { imeFocused = focused }
                    },
                    onDisconnected = { reason ->
                        scope.launch(Dispatchers.Main) {
                            connected = false
                            imeFocused = false
                            status = reason
                        }
                    }
                )
                val result = runCatching { candidate.connect() }
                if (result.isSuccess) {
                    client?.disconnect()
                    client = candidate
                    connected = true
                    succeeded = true
                    showConnectionPanel = false
                    pairingKey = result.getOrThrow()
                    certificatePin = candidate.certificateFingerprint() ?: certificatePin
                    onSave(cleanIp, pairingKey!!, certificatePin)
                    status = "متصل • $cleanIp"
                    runCatching { candidate.getVolume()?.let { volume = it.toFloat() } }
                    runCatching { apps = candidate.getApps() }
                } else {
                    candidate.disconnect()
                    status = result.exceptionOrNull()?.message ?: "تعذر الاتصال بـ $cleanIp"
                    attempt++
                    if (attempt < 2) delay(900)
                }
            }
            busy = false
            if (!succeeded) {
                showConnectionPanel = true
                if (fallbackToDiscovery) {
                    status = "جارٍ البحث التلقائي عن تلفزيون LG…"
                    busy = true
                    val found = discoverLgTv(context)
                    busy = false
                    if (found != null && found != cleanIp) {
                        connect(found, fallbackToDiscovery = false)
                    } else if (found == null) {
                        status = "لم يُعثر على التلفزيون؛ تأكد من تشغيله ونفس الواي فاي"
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (savedIp.isNotBlank()) {
            connect(savedIp, fallbackToDiscovery = true)
        } else {
            busy = true
            status = "جارٍ البحث التلقائي عن تلفزيون LG…"
            val found = discoverLgTv(context)
            busy = false
            if (found != null) {
                connect(found)
            } else {
                showConnectionPanel = true
                status = "لم يُعثر على التلفزيون؛ أدخل عنوان IP أو أعد البحث"
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !connected && !busy && ip.isNotBlank()) {
                connect(ip, fallbackToDiscovery = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun command(hapticFeedback: Boolean = true, block: suspend (WebOsClient) -> Unit) {
        if (hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val c = client ?: run {
            status = "التلفزيون غير متصل؛ جارٍ إعادة المحاولة…"
            if (ip.isNotBlank() && !busy) connect(ip)
            return
        }
        scope.launch {
            runCatching { block(c) }.onFailure { err ->
                if (err is WebOsConnectionException) {
                    connected = false
                    status = "انقطع الاتصال؛ جارٍ إعادة الاتصال…"
                    if (ip.isNotBlank() && !busy) {
                        delay(800)
                        connect(ip)
                    }
                } else {
                    status = err.message ?: "لم يستجب التلفزيون لهذا الأمر"
                }
            }
        }
    }

    fun onLiveTextChange(newText: String) {
        val oldText = lastSentText
        typed = newText
        lastSentText = newText
        if (!connected || client == null || !imeFocused) return

        when {
            newText == oldText -> Unit
            newText.startsWith(oldText) -> {
                val added = newText.substring(oldText.length)
                if (added.isNotEmpty()) imeQueue.trySend { it.insertText(added, replace = false) }
            }
            oldText.startsWith(newText) -> {
                val deletedCount = oldText.length - newText.length
                if (deletedCount > 0) imeQueue.trySend { it.deleteCharacters(deletedCount) }
            }
            else -> imeQueue.trySend { it.insertText(newText, replace = true) }
        }
    }

    fun playVideoOnCobalt(videoId: String, title: String? = null) {
        command { c ->
            val fg = c.getForegroundAppId()
            val ytId = resolveYouTubeAppId(fg)
            c.playYouTubeVideo(ytId, videoId)
            status = if (title != null) "جارٍ التشغيل: $title" else "تم إرسال الفيديو إلى كوبالت"
        }
    }

    fun submitTextOrYouTubeSearch(forceYouTube: Boolean = false) {
        val query = typed.trim()
        if (query.isEmpty()) {
            if (imeFocused) command { it.sendEnter() }
            return
        }

        val directVideoId = WebOsClient.extractYouTubeVideoId(query)
        if (directVideoId != null) {
            playVideoOnCobalt(directVideoId)
            typed = ""
            lastSentText = ""
            return
        }

        if (!forceYouTube && imeFocused) {
            command { c ->
                if (lastSentText != query) c.insertText(query, replace = true)
                c.sendEnter()
            }
            typed = ""
            lastSentText = ""
            return
        }

        ytSearchQuery = query
        showYtDialog = true
        ytSearching = true
        ytError = null
        ytResults = emptyList()
        scope.launch {
            runCatching { WebOsClient.searchYouTube(query) }
                .onSuccess { list ->
                    ytResults = list
                    if (list.isEmpty()) ytError = "لم يتم العثور على نتائج لهذا البحث"
                }
                .onFailure { err ->
                    ytError = "تعذر البحث في يوتيوب: ${err.message ?: "تحقق من الإنترنت"}"
                }
            ytSearching = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Sleek Top Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFF14B8A6)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.SettingsRemote, contentDescription = null, tint = AccentDark, modifier = Modifier.size(22.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showConnectionPanel = !showConnectionPanel }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cobalt Remote", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (connected) Accent else if (busy) Warning else Danger)
                    )
                }
                Text(
                    text = status,
                    color = if (connected) Accent else TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = { showConnectionPanel = !showConnectionPanel },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (showConnectionPanel) SurfaceElevated else SurfaceCard)
            ) {
                Icon(
                    imageVector = if (connected) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                    contentDescription = "إعدادات الاتصال",
                    tint = if (connected) Accent else Warning,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = { command { it.powerOff() } },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Danger.copy(alpha = 0.16f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "إيقاف التلفزيون",
                    tint = Danger,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 2. Collapsible TV Connection Panel
        AnimatedVisibility(visible = showConnectionPanel) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("IP التلفزيون (مثال 192.168.1.17)", fontSize = 12.sp, color = TextMuted) },
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = TextPrimary),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = BorderSubtle,
                            focusedContainerColor = Ink,
                            unfocusedContainerColor = Ink
                        )
                    )
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "جارٍ البحث عن تلفزيون LG…"
                                val found = discoverLgTv(context)
                                busy = false
                                if (found != null) connect(found) else status = "لم يُعثر عليه؛ أدخل IP يدوياً"
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Button(
                        enabled = !busy && ip.isNotBlank(),
                        onClick = { connect(ip) },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
                    ) {
                        Text(if (busy) "…" else "اتصال", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(status, color = if (connected) Accent else Warning, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(
                        onClick = {
                            client?.disconnect()
                            client = null
                            connected = false
                            imeFocused = false
                            onClear()
                            pairingKey = null
                            certificatePin = null
                            status = "تم مسح الاقتران"
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("مسح الاقتران", color = Danger, fontSize = 11.sp)
                    }
                }
            }
        }

        // 3. 3-Tab Mode Switcher (Remote | Kodi | TV Apps)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceCard)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TabPill(
                label = "الريموت",
                icon = Icons.Rounded.SettingsRemote,
                selected = activeTab == RemoteTab.REMOTE,
                accentColor = Accent,
                modifier = Modifier.weight(1f)
            ) { activeTab = RemoteTab.REMOTE }

            TabPill(
                label = "كودي",
                icon = Icons.Rounded.LocalMovies,
                selected = activeTab == RemoteTab.KODI,
                accentColor = KodiBlue,
                modifier = Modifier.weight(1f)
            ) {
                activeTab = RemoteTab.KODI
                if (kodiMovies.isEmpty() && kodiShows.isEmpty() && !kodiLoading && ip.isNotBlank()) {
                    refreshKodiLibrary(autoLaunch = false)
                }
            }

            TabPill(
                label = "التطبيقات",
                icon = Icons.Rounded.Apps,
                selected = activeTab == RemoteTab.APPS,
                accentColor = Accent,
                modifier = Modifier.weight(1f)
            ) {
                activeTab = RemoteTab.APPS
                if (apps.isEmpty() && connected) command(false) { apps = it.getApps() }
            }
        }

        // 4. Main Content Area
        when (activeTab) {
            RemoteTab.REMOTE -> {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // D-Pad ON TOP
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            SideNavButton(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع") { command { it.button("BACK") } }
                            SideNavButton(Icons.Rounded.Close, "خروج") { command { it.button("EXIT") } }
                        }

                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Box(
                                modifier = Modifier
                                    .size(196.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceCard)
                                    .border(1.5.dp, BorderSubtle, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                DpadArrow(Icons.Rounded.KeyboardArrowUp, Modifier.align(Alignment.TopCenter).padding(top = 6.dp)) { command { it.button("UP") } }
                                DpadArrow(Icons.Rounded.KeyboardArrowDown, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) { command { it.button("DOWN") } }
                                DpadArrow(Icons.Rounded.KeyboardArrowLeft, Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) { command { it.button("LEFT") } }
                                DpadArrow(Icons.Rounded.KeyboardArrowRight, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) { command { it.button("RIGHT") } }

                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(Accent, Color(0xFF14B8A6))))
                                        .clickable { command { it.button("ENTER") } },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("OK", color = AccentDark, fontWeight = FontWeight.Black, fontSize = 17.sp)
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            SideNavButton(Icons.Rounded.Home, "الرئيسية") { command { it.button("HOME") } }
                            SideNavButton(Icons.Rounded.Menu, "القائمة") { command { it.button("MENU") } }
                        }
                    }

                    // Touchpad + Dedicated Vertical Scroll Wheel BELOW D-Pad
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .heightIn(min = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .background(Brush.verticalGradient(listOf(SurfaceElevated, SurfaceCard)))
                                .border(1.dp, BorderSubtle, RoundedCornerShape(22.dp))
                                .pointerInput(client) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        client?.movePointer((drag.x * 1.15f).toInt(), (drag.y * 1.15f).toInt())
                                    }
                                }
                                .pointerInput(client) {
                                    detectTapGestures(
                                        onTap = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            client?.clickPointer()
                                        },
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            client?.clickPointer()
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = Accent.copy(alpha = 0.65f), modifier = Modifier.size(28.dp))
                                Text("لوحة لمس المؤشر", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                                Text("اسحب للتحريك • انقر للاختيار", fontSize = 11.sp, color = TextMuted)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .background(SurfaceCard)
                                .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    client?.scrollPointer(120)
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "تمرير لأعلى", tint = Accent, modifier = Modifier.size(24.dp))
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(SurfaceElevated)
                                    .pointerInput(client) {
                                        var acc = 0f
                                        detectVerticalDragGestures(
                                            onDragEnd = { acc = 0f },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                acc += dragAmount
                                                if (kotlin.math.abs(acc) >= 22f) {
                                                    val step = if (acc < 0f) 100 else -100
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    client?.scrollPointer(step)
                                                    acc = 0f
                                                }
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Rounded.UnfoldMore, contentDescription = "بكرة التمرير", tint = Accent, modifier = Modifier.size(20.dp))
                                    Text("بكرة", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    client?.scrollPointer(-120)
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "تمرير لأسفل", tint = Accent, modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    // Volume & Media Control Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SmallIconKey(Icons.Rounded.VolumeOff, "كتم") { command { it.button("MUTE") } }
                            SmallIconKey(Icons.Rounded.VolumeDown, "خفض الصوت") {
                                volume = (volume - 2f).coerceAtLeast(0f)
                                command { it.button("VOLUMEDOWN") }
                            }
                            Slider(
                                value = volume,
                                onValueChange = { volume = it },
                                onValueChangeFinished = { command(false) { it.setVolume(volume.toInt()) } },
                                valueRange = 0f..100f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Accent,
                                    activeTrackColor = Accent,
                                    inactiveTrackColor = BorderSubtle
                                )
                            )
                            Text(
                                text = "${volume.toInt()}%",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(34.dp),
                                textAlign = TextAlign.Center
                            )
                            SmallIconKey(Icons.Rounded.VolumeUp, "رفع الصوت") {
                                volume = (volume + 2f).coerceAtMost(100f)
                                command { it.button("VOLUMEUP") }
                            }
                        }

                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MediaKey(Icons.Rounded.FastRewind, Modifier.weight(1f)) { command { it.button("REWIND") } }
                                MediaKey(Icons.Rounded.PlayArrow, Modifier.weight(1f)) { command { it.button("PLAY") } }
                                MediaKey(Icons.Rounded.Pause, Modifier.weight(1f)) { command { it.button("PAUSE") } }
                                MediaKey(Icons.Rounded.Stop, Modifier.weight(1f)) { command { it.button("STOP") } }
                                MediaKey(Icons.Rounded.FastForward, Modifier.weight(1f)) { command { it.button("FASTFORWARD") } }
                            }
                        }
                    }
                }
            }

            RemoteTab.KODI -> {
                // Clean Kodi Library Tab (Movies + TV Series, no Port/Password clutter!)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Movies / Series sub-tabs + Refresh
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceCard)
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TabPill(
                                label = "الأفلام (${kodiMovies.size})",
                                icon = Icons.Rounded.Movie,
                                selected = kodiSection == KodiSection.MOVIES,
                                accentColor = KodiBlue,
                                modifier = Modifier.weight(1f)
                            ) { kodiSection = KodiSection.MOVIES }

                            TabPill(
                                label = "المسلسلات (${kodiShows.size})",
                                icon = Icons.Rounded.Tv,
                                selected = kodiSection == KodiSection.SERIES,
                                accentColor = KodiBlue,
                                modifier = Modifier.weight(1f)
                            ) { kodiSection = KodiSection.SERIES }
                        }

                        FilledTonalIconButton(
                            onClick = { refreshKodiLibrary(autoLaunch = true) },
                            enabled = !kodiLoading,
                            modifier = Modifier.size(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = SurfaceCard, contentColor = KodiBlue)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "تحديث مكتبة كودي", modifier = Modifier.size(20.dp))
                        }
                    }

                    // Compact filter search bar
                    OutlinedTextField(
                        value = kodiFilter,
                        onValueChange = { kodiFilter = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text(
                                if (kodiSection == KodiSection.MOVIES) "ابحث في الأفلام بالاسم أو السنة…" else "ابحث في المسلسلات…",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        },
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = TextPrimary),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KodiBlue,
                            unfocusedBorderColor = BorderSubtle,
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceCard
                        ),
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = KodiBlue, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (kodiFilter.isNotEmpty()) {
                                IconButton(onClick = { kodiFilter = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "مسح", tint = TextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    )

                    kodiStatus?.let { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (kodiLoading) {
                                CircularProgressIndicator(color = KodiBlue, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            }
                            Text(msg, color = KodiBlue, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    if (kodiSection == KodiSection.MOVIES) {
                        val filteredMovies = remember(kodiMovies, kodiFilter) {
                            val q = kodiFilter.trim()
                            if (q.isEmpty()) kodiMovies
                            else kodiMovies.filter {
                                it.title.contains(q, true) || it.originalTitle.contains(q, true) || it.year.toString().contains(q)
                            }
                        }
                        if (filteredMovies.isEmpty()) {
                            EmptyKodiState(
                                hasCache = kodiMovies.isNotEmpty(),
                                loading = kodiLoading,
                                label = "أفلام",
                                onRefresh = { refreshKodiLibrary(autoLaunch = true) }
                            )
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredMovies, key = { it.movieId }) { movie ->
                                    val durationText = remember(movie.runtimeSec) { KodiClient.formatRuntime(movie.runtimeSec) }
                                    val metaBadge = remember(movie.year, durationText) {
                                        listOfNotNull(
                                            movie.year.takeIf { it > 0 }?.toString(),
                                            durationText.takeIf { it.isNotEmpty() }
                                        ).joinToString(" • ")
                                    }
                                    KodiPosterCard(
                                        title = movie.title,
                                        fallbackQuery = movie.originalTitle,
                                        isSeries = false,
                                        initialPosterUrl = movie.posterUrl,
                                        topBadge = metaBadge,
                                        onClick = { playKodiMovieDirectly(movie) }
                                    )
                                }
                            }
                        }
                    } else {
                        val filteredShows = remember(kodiShows, kodiFilter) {
                            val q = kodiFilter.trim()
                            if (q.isEmpty()) kodiShows
                            else kodiShows.filter {
                                it.title.contains(q, true) || it.originalTitle.contains(q, true) || it.year.toString().contains(q)
                            }
                        }
                        if (filteredShows.isEmpty()) {
                            EmptyKodiState(
                                hasCache = kodiShows.isNotEmpty(),
                                loading = kodiLoading,
                                label = "مسلسلات",
                                onRefresh = { refreshKodiLibrary(autoLaunch = true) }
                            )
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredShows, key = { it.tvshowId }) { show ->
                                    val metaBadge = remember(show.year, show.episodeCount) {
                                        listOfNotNull(
                                            show.year.takeIf { it > 0 }?.toString(),
                                            show.episodeCount.takeIf { it > 0 }?.let { "${it} ح" }
                                        ).joinToString(" • ")
                                    }
                                    KodiPosterCard(
                                        title = show.title,
                                        fallbackQuery = show.originalTitle,
                                        isSeries = true,
                                        initialPosterUrl = show.posterUrl,
                                        topBadge = metaBadge,
                                        onClick = { openShowEpisodes(show) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            RemoteTab.APPS -> {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("تطبيقات التلفزيون", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                        FilledTonalButton(
                            onClick = { command(false) { apps = it.getApps() } },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("تحديث", fontSize = 12.sp)
                        }
                    }
                    if (apps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(18.dp))
                                .background(SurfaceCard),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (connected) "اضغط تحديث لعرض تطبيقات التلفزيون" else "اتصل بالتلفزيون لعرض التطبيقات",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(apps, key = { it.id }) { app ->
                                val badgeColor = parseHexColor(app.iconColor, SurfaceElevated)
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(SurfaceCard)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                                        .clickable { command { it.launchApp(app.id) } }
                                        .padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(50.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(badgeColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = fallbackIconForApp(app),
                                            contentDescription = app.title,
                                            tint = Color.White,
                                            modifier = Modifier.size(26.dp)
                                        )
                                        if (!app.icon.isNullOrBlank()) {
                                            AsyncImage(
                                                model = app.icon,
                                                imageLoader = tvImageLoader,
                                                contentDescription = app.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    Text(
                                        text = app.title,
                                        fontSize = 11.sp,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Smart Keyboard & YouTube Search Dock
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceCard)
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { onLiveTextChange(it) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        if (imeFocused) "كتابة مباشرة في التلفزيون…" else "ابحث في يوتيوب أو الصق رابط فيديو…",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                },
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = TextPrimary),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (imeFocused) Accent else YoutubeRed,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Ink,
                    unfocusedContainerColor = Ink
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitTextOrYouTubeSearch(forceYouTube = false) }),
                trailingIcon = {
                    if (typed.isNotEmpty()) {
                        IconButton(onClick = {
                            val len = typed.length
                            typed = ""
                            lastSentText = ""
                            if (imeFocused) imeQueue.trySend { it.deleteCharacters(len) }
                        }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "مسح", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            )

            if (imeFocused) {
                FilledTonalIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (typed.isNotEmpty()) {
                            typed = typed.dropLast(1)
                            lastSentText = typed
                        }
                        imeQueue.trySend { it.deleteCharacters(1) }
                    },
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = SurfaceElevated, contentColor = TextPrimary)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "حذف حرف", modifier = Modifier.size(19.dp))
                }
            }

            FilledIconButton(
                onClick = { submitTextOrYouTubeSearch(forceYouTube = !imeFocused) },
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (imeFocused) Accent else YoutubeRed,
                    contentColor = if (imeFocused) AccentDark else Color.White
                )
            ) {
                Icon(
                    imageVector = if (imeFocused) Icons.AutoMirrored.Rounded.KeyboardReturn else Icons.Rounded.Search,
                    contentDescription = if (imeFocused) "إرسال" else "بحث في يوتيوب",
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }

    // TV Show Episodes Picker Dialog
    selectedShow?.let { show ->
        val seasons = remember(showEpisodes) { showEpisodes.map { it.season }.distinct().sorted() }
        val visibleEpisodes = remember(showEpisodes, selectedSeason) {
            if (selectedSeason < 0) showEpisodes else showEpisodes.filter { it.season == selectedSeason }
        }
        Dialog(
            onDismissRequest = { selectedShow = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.80f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(SurfaceCard)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(22.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Tv, contentDescription = null, tint = KodiBlue, modifier = Modifier.size(22.dp))
                            Text(show.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { selectedShow = null }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "إغلاق", tint = TextMuted)
                        }
                    }

                    if (seasons.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(seasons) { s ->
                                FilterChip(
                                    selected = selectedSeason == s,
                                    onClick = { selectedSeason = s },
                                    label = { Text("الموسم $s", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = KodiBlue,
                                        selectedLabelColor = Ink
                                    )
                                )
                            }
                        }
                    }

                    when {
                        episodesLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = KodiBlue)
                            }
                        }
                        visibleEpisodes.isEmpty() -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("لا توجد حلقات متاحة لهذا المسلسل", color = TextMuted, fontSize = 13.sp)
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(visibleEpisodes, key = { it.episodeId }) { ep ->
                                    val dur = remember(ep.runtimeSec) { KodiClient.formatRuntime(ep.runtimeSec) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Ink)
                                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                                            .clickable { playKodiEpisodeDirectly(ep, show.title) }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(KodiBlue.copy(alpha = 0.18f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("${ep.episode}", color = KodiBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(ep.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("الموسم ${ep.season} • الحلقة ${ep.episode}", color = TextMuted, fontSize = 11.sp)
                                                if (dur.isNotEmpty()) Text("• $dur", color = Accent, fontSize = 11.sp)
                                            }
                                        }
                                        if (ep.playcount > 0) {
                                            Icon(Icons.Rounded.CheckCircle, contentDescription = "تمت المشاهدة", tint = Accent.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                                        }
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = "تشغيل", tint = KodiBlue, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // In-App YouTube Search Results Dialog
    if (showYtDialog) {
        Dialog(
            onDismissRequest = { showYtDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.82f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(SurfaceCard)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(22.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.SmartDisplay, contentDescription = null, tint = YoutubeRed, modifier = Modifier.size(24.dp))
                            Text(
                                text = "نتائج: $ytSearchQuery",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { showYtDialog = false }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "إغلاق", tint = TextMuted)
                        }
                    }

                    when {
                        ytSearching -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(color = YoutubeRed)
                                    Text("جارٍ البحث في يوتيوب…", color = TextMuted, fontSize = 13.sp)
                                }
                            }
                        }
                        ytError != null -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(ytError!!, color = Warning, fontSize = 13.sp, textAlign = TextAlign.Center)
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(ytResults, key = { it.videoId }) { video ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Ink)
                                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                                            .clickable {
                                                playVideoOnCobalt(video.videoId, video.title)
                                                showYtDialog = false
                                                typed = ""
                                                lastSentText = ""
                                            }
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(116.dp)
                                                .height(66.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(SurfaceElevated)
                                        ) {
                                            AsyncImage(
                                                model = video.thumbnailUrl,
                                                contentDescription = video.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            if (video.duration.isNotBlank()) {
                                                Text(
                                                    text = video.duration,
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(4.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Color.Black.copy(alpha = 0.78f))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = video.title,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (video.channel.isNotBlank()) {
                                                Text(
                                                    text = video.channel,
                                                    color = TextMuted,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Rounded.CastConnected,
                                            contentDescription = "تشغيل على التلفزيون",
                                            tint = Accent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KodiPosterCard(
    title: String,
    fallbackQuery: String,
    isSeries: Boolean,
    initialPosterUrl: String?,
    topBadge: String,
    onClick: () -> Unit
) {
    var resolvedPoster by remember(initialPosterUrl, fallbackQuery) { mutableStateOf(initialPosterUrl) }

    // If Kodi only had a local USB path and no IMDb ID, lazily look up the poster online by title!
    LaunchedEffect(initialPosterUrl, fallbackQuery) {
        if (resolvedPoster.isNullOrBlank() && fallbackQuery.isNotBlank()) {
            resolvedPoster = KodiClient.searchPosterByTitle(fallbackQuery, isSeries)
        }
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .background(SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSeries) Icons.Rounded.Tv else Icons.Rounded.Movie,
                contentDescription = null,
                tint = KodiBlue.copy(alpha = 0.45f),
                modifier = Modifier.size(34.dp)
            )
            if (!resolvedPoster.isNullOrBlank()) {
                AsyncImage(
                    model = resolvedPoster,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (topBadge.isNotBlank()) {
                Text(
                    text = topBadge,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(KodiBlue.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSeries) Icons.Rounded.PlaylistPlay else Icons.Rounded.PlayArrow,
                    contentDescription = if (isSeries) "الحلقات" else "تشغيل",
                    tint = Ink,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun EmptyKodiState(
    hasCache: Boolean,
    loading: Boolean,
    label: String,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceCard)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.LocalMovies, contentDescription = null, tint = KodiBlue.copy(alpha = 0.7f), modifier = Modifier.size(40.dp))
            Text(
                text = if (!hasCache) "اضغط الزر بالأسفل لتشغيل كودي وجلب قائمة ال$label وحفظها هنا" else "لا توجد نتائج مطابقة للبحث",
                color = TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            if (!hasCache) {
                Button(
                    onClick = onRefresh,
                    enabled = !loading,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KodiBlue, contentColor = Ink)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("جلب مكتبة كودي", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) SurfaceElevated else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) accentColor else TextMuted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = if (selected) TextPrimary else TextMuted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SideNavButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = TextPrimary, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 11.sp, color = TextMuted, maxLines = 1)
    }
}

@Composable
private fun DpadArrow(icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(52.dp)) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun SmallIconKey(icon: ImageVector, desc: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(10.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = SurfaceElevated, contentColor = TextPrimary)
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MediaKey(icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = SurfaceElevated, contentColor = TextPrimary)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}
