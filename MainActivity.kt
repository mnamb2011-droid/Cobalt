package com.mnasser.cobaltremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Ink = Color(0xFF0B1220)
private val Panel = Color(0xFF172438)
private val Accent = Color(0xFF42E6D0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cobalt_remote", MODE_PRIVATE)
        val secure = SecureStore(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) {
                RemoteScreen(
                    savedIp = prefs.getString("tv_ip", "") ?: "",
                    savedKey = secure.read("client_key"),
                    savedFingerprint = prefs.getString("tv_fingerprint", null),
                    onSave = { ip, key, fp -> prefs.edit().putString("tv_ip", ip).putString("tv_fingerprint", fp).apply(); secure.write("client_key", key) },
                    onClear = { secure.remove("client_key"); prefs.edit().remove("tv_fingerprint").apply() }
                )
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
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    fun write(name: String, value: String) { val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key()); prefs.edit().putString(name, android.util.Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), android.util.Base64.NO_WRAP)).apply() }
    fun read(name: String): String? = runCatching { val raw = android.util.Base64.decode(prefs.getString(name, null), android.util.Base64.NO_WRAP); val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))); String(c.doFinal(raw.copyOfRange(12, raw.size))) }.getOrNull()
    fun remove(name: String) { prefs.edit().remove(name).apply() }
}

private suspend fun discoverLgTv(context: Context): String? = withContext(Dispatchers.Main) {
    val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val result = CompletableDeferred<String?>()
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType.contains("webos", true) || service.serviceName.contains("LG", true)) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(s: NsdServiceInfo) { if (!result.isCompleted) result.complete(s.host?.hostAddress) }
                })
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) { if (!result.isCompleted) result.complete(null); runCatching { nsd.stopServiceDiscovery(this) } }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
    }
    runCatching { nsd.discoverServices("_webos-second-screen._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { result.complete(null) }
    try { withTimeout(9000) { result.await() } } catch (_: Exception) { null } finally { runCatching { nsd.stopServiceDiscovery(listener) } }
}

@Composable
private fun RemoteScreen(savedIp: String, savedKey: String?, savedFingerprint: String?, onSave: (String, String, String?) -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(savedIp) }
    var typed by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("ابحث عن التلفزيون أو أدخل عنوانه") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var pairingKey by remember { mutableStateOf(savedKey) }
    var certificatePin by remember { mutableStateOf(savedFingerprint) }
    var client by remember { mutableStateOf<WebOsClient?>(null) }
    var apps by remember { mutableStateOf<List<TvApp>>(emptyList()) }
    var volume by remember { mutableFloatStateOf(25f) }
    var showApps by remember { mutableStateOf(false) }
    var reconnectJob by remember { mutableStateOf<Job?>(null) }

    fun connect(address: String) {
        ip = address; busy = true; status = "جارٍ الاتصال بـ $address…"
        scope.launch {
            var attempt = 0
            while (attempt < 3 && !connected) {
                val candidate = WebOsClient(address, pairingKey, certificatePin)
                val result = runCatching { candidate.connect() }
                if (result.isSuccess) {
                    client?.disconnect(); client = candidate; connected = true
                    pairingKey = result.getOrThrow()
                    certificatePin = candidate.certificateFingerprint() ?: certificatePin
                    onSave(address, pairingKey!!, certificatePin)
                    status = "متصل بالتلفزيون"; runCatching { apps = candidate.getApps() }
                    break
                } else { candidate.disconnect(); status = result.exceptionOrNull()?.message ?: "فشل الاتصال"; attempt++; if (attempt < 3) delay(1200L * attempt) }
            }
            busy = false
        }
    }
    fun command(block: suspend (WebOsClient) -> Unit) {
        val c = client ?: run { status = "اتصل بالتلفزيون أولاً"; return }
        scope.launch {
            runCatching { block(c) }.onFailure {
                status = it.message ?: "انقطع الاتصال"; connected = false
                // Reconnect with the previously paired key and pinned certificate after Wi-Fi returns.
                if (ip.isNotBlank()) { delay(1800); connect(ip.trim()) }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Accent), contentAlignment = Alignment.Center) { Text("CR", color = Ink, fontWeight = FontWeight.Black) }
            Column(Modifier.weight(1f).padding(start = 9.dp)) { Text("Cobalt Remote", fontSize = 19.sp, fontWeight = FontWeight.Bold); Text(if (connected) "متصل • $ip" else "LG webOS • ريموت ذكي", color = Color.LightGray, fontSize = 11.sp) }
            Surface(color = if (connected) Color(0xFF145747) else Color(0xFF49351D), shape = CircleShape) { Text(if (connected) "ONLINE" else "OFFLINE", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontSize = 10.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ip, { ip = it }, Modifier.weight(1f).height(54.dp), singleLine = true, placeholder = { Text("IP التلفزيون", fontSize = 12.sp) }, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
            Button(enabled = !busy, onClick = { scope.launch { busy = true; status = "جارٍ البحث عن تلفزيون LG…"; val found = discoverLgTv(context); busy = false; if (found != null) connect(found) else status = "لم يُعثر عليه؛ أدخل IP يدويًا" } }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("بحث", fontSize = 12.sp) }
            Button(enabled = !busy && ip.isNotBlank(), onClick = { connect(ip.trim()) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (busy) "…" else "اتصال", fontSize = 12.sp) }
        }
        Text(status, color = if (connected) Accent else Color(0xFFFFC66D), fontSize = 10.sp, maxLines = 1)
        // Touchpad occupies a compact, fixed area; drag moves the webOS pointer.
        Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(18.dp)).background(Panel)
            .pointerInput(client) { detectDragGestures { change, drag -> change.consume(); client?.movePointer((drag.x * 0.75f).toInt(), (drag.y * 0.75f).toInt()) } }
            .pointerInput(client) { detectTapGestures(onTap = { client?.clickPointer() }, onLongPress = { client?.clickPointer() }) }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("لوحة لمس مؤشر التلفزيون", fontWeight = FontWeight.SemiBold, color = Color.White); Text("اسحب لتحريك المؤشر • انقر للاختيار", fontSize = 11.sp, color = Color.LightGray) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyButton("▲", Modifier.weight(1f)) { command { it.button("UP") } }
            KeyButton("▼", Modifier.weight(1f)) { command { it.button("DOWN") } }
            KeyButton("◀", Modifier.weight(1f)) { command { it.button("LEFT") } }
            KeyButton("OK", Modifier.weight(1.2f), true) { command { it.button("ENTER") } }
            KeyButton("▶", Modifier.weight(1f)) { command { it.button("RIGHT") } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Action("رجوع", Modifier.weight(1f)) { command { it.button("BACK") } }
            Action("الرئيسية", Modifier.weight(1f)) { command { it.button("HOME") } }
            Action("خروج", Modifier.weight(1f)) { command { it.button("EXIT") } }
            Action("إيقاف", Modifier.weight(1f)) { command { it.powerOff() } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Action("⏪", Modifier.weight(1f)) { command { it.button("REWIND") } }
            Action("▶", Modifier.weight(1f)) { command { it.button("PLAY") } }
            Action("⏸", Modifier.weight(1f)) { command { it.button("PAUSE") } }
            Action("⏩", Modifier.weight(1f)) { command { it.button("FASTFORWARD") } }
            Action("صوت−", Modifier.weight(1f)) { command { it.button("VOLUMEDOWN") } }
            Action("صوت+", Modifier.weight(1f)) { command { it.button("VOLUMEUP") } }
            Action("كتم", Modifier.weight(1f)) { command { it.button("MUTE") } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("مستوى الصوت", fontSize = 11.sp, color = Color.LightGray); Slider(volume, { volume = it }, Modifier.weight(1f), valueRange = 0f..100f); Text("${volume.toInt()}%", fontSize = 11.sp); TextButton(onClick = { command { it.setVolume(volume.toInt()) } }, contentPadding = PaddingValues(0.dp)) { Text("تطبيق", fontSize = 11.sp) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedTextField(typed, { typed = it }, Modifier.weight(1f).height(54.dp), singleLine = true, placeholder = { Text("اكتب هنا بلوحة مفاتيح الهاتف", fontSize = 11.sp) }, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { command { it.insertText(typed); it.sendEnter() } }))
            Button(onClick = { command { it.insertText(typed) } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("إرسال", fontSize = 11.sp) }
            Button(onClick = { command { it.sendEnter() } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("بحث", fontSize = 11.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("تطبيقات التلفزيون", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            TextButton(onClick = { command { apps = it.getApps(); showApps = true } }) { Text("تحديث / عرض", fontSize = 11.sp) }
            TextButton(onClick = { client?.disconnect(); client = null; connected = false; onClear(); pairingKey = null; certificatePin = null; status = "تم مسح بيانات الاقتران" }) { Text("فصل", fontSize = 11.sp) }
        }
        if (showApps) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                apps.forEach { app ->
                    Column(Modifier.width(68.dp).clip(RoundedCornerShape(12.dp)).background(Panel).clickable { command { it.launchApp(app.id) } }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(model = app.icon, contentDescription = app.title, modifier = Modifier.size(38.dp), placeholder = null, error = null)
                        Text(app.title, fontSize = 9.sp, maxLines = 2, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable private fun KeyButton(label: String, modifier: Modifier = Modifier, strong: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(46.dp), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = if (strong) Accent else Color(0xFF34465F), contentColor = if (strong) Ink else Color.White)) { Text(label, fontWeight = FontWeight.Bold) }
}
@Composable private fun Action(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(38.dp), contentPadding = PaddingValues(horizontal = 3.dp), shape = RoundedCornerShape(10.dp)) { Text(label, fontSize = 10.sp, maxLines = 1) }
}
