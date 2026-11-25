package com.mnasser.cobaltremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val Ink = Color(0xFF101522)
private val Panel = Color(0xFF1B2435)
private val Accent = Color(0xFF53D6C4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cobalt_remote", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) {
                RemoteScreen(
                    savedIp = prefs.getString("tv_ip", "") ?: "",
                    savedKey = prefs.getString("client_key", null),
                    onSave = { ip, key -> prefs.edit().putString("tv_ip", ip).putString("client_key", key).apply() },
                    onClear = { prefs.edit().remove("client_key").apply() }
                )
            }
        }
    }
}

@Composable
private fun RemoteScreen(savedIp: String, savedKey: String?, onSave: (String, String) -> Unit, onClear: () -> Unit) {
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(savedIp) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("أدخل عنوان IP للتلفزيون ثم اضغط اتصال") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var client by remember { mutableStateOf<WebOsClient?>(null) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showApps by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(20f) }

    fun runCommand(block: suspend (WebOsClient) -> Unit) {
        val c = client ?: run { status = "اتصل بالتلفزيون أولاً"; return }
        scope.launch {
            runCatching { block(c) }
                .onFailure { status = it.message ?: "تعذر تنفيذ الأمر" }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(46.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                Text("CR", color = Ink, fontWeight = FontWeight.Black)
            }
            Column(Modifier.weight(1f)) {
                Text("Cobalt Remote", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("ريموت شامل لتلفزيون LG webOS", color = Color.LightGray)
            }
            Surface(color = if (connected) Color(0xFF164D43) else Color(0xFF49351D), shape = RoundedCornerShape(30.dp)) {
                Text(if (connected) "متصل" else "غير متصل", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Color.White)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("الاتصال بالتلفزيون", fontWeight = FontWeight.Bold)
                OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("عنوان IP مثل 192.168.1.20") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && ip.isNotBlank(), onClick = {
                        busy = true; status = "جارٍ الاتصال…"
                        scope.launch {
                            val newClient = WebOsClient(ip.trim(), savedKey)
                            runCatching { newClient.connect() }
                                .onSuccess { key ->
                                    client?.disconnect(); client = newClient; connected = true
                                    onSave(ip.trim(), key); status = "تم الاقتران والاتصال بنجاح"
                                    runCatching { apps = newClient.getApps() }
                                }
                                .onFailure { newClient.disconnect(); connected = false; status = it.message ?: "فشل الاتصال" }
                            busy = false
                        }
                    }, modifier = Modifier.weight(1f)) { Text(if (busy) "انتظر…" else if (connected) "إعادة الاتصال" else "اتصال / اقتران") }
                    OutlinedButton(onClick = { client?.disconnect(); client = null; connected = false; onClear(); status = "تم مسح مفتاح الاقتران؛ أعد الاقتران من جديد" }) { Text("فصل") }
                }
                Text(status, color = if (connected) Accent else Color(0xFFFFC66D), style = MaterialTheme.typography.bodySmall)
                Text("عند أول اتصال وافق على طلب الاقتران الذي يظهر على شاشة التلفزيون.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("لوحة التحكم", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    RemoteKey("▲") { runCatching { client?.button("UP") } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteKey("◀", Modifier.weight(1f)) { runCatching { client?.button("LEFT") } }
                    RemoteKey("OK", Modifier.weight(1f), strong = true) { runCatching { client?.button("ENTER") } }
                    RemoteKey("▶", Modifier.weight(1f)) { runCatching { client?.button("RIGHT") } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    RemoteKey("▼") { runCatching { client?.button("DOWN") } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction("رجوع", Modifier.weight(1f)) { runCatching { client?.button("BACK") } }
                    SmallAction("الرئيسية", Modifier.weight(1f)) { runCatching { client?.button("HOME") } }
                    SmallAction("خروج", Modifier.weight(1f)) { runCatching { client?.button("EXIT") } }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("تشغيل الفيديو والصوت", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction("⏪", Modifier.weight(1f)) { runCatching { client?.button("REWIND") } }
                    SmallAction("▶ تشغيل", Modifier.weight(1f)) { runCatching { client?.button("PLAY") } }
                    SmallAction("⏸ إيقاف مؤقت", Modifier.weight(1f)) { runCatching { client?.button("PAUSE") } }
                    SmallAction("⏩", Modifier.weight(1f)) { runCatching { client?.button("FASTFORWARD") } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction("صوت −", Modifier.weight(1f)) { runCatching { client?.button("VOLUMEDOWN") } }
                    SmallAction("كتم", Modifier.weight(1f)) { runCatching { client?.button("MUTE") } }
                    SmallAction("صوت +", Modifier.weight(1f)) { runCatching { client?.button("VOLUMEUP") } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("مستوى الصوت", color = Color.LightGray)
                    Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f, modifier = Modifier.weight(1f))
                    Text(volume.toInt().toString())
                }
                Button(onClick = { runCommand { it.setVolume(volume.toInt()) } }, modifier = Modifier.fillMaxWidth()) { Text("تطبيق مستوى الصوت") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("لوحة الكتابة والبحث", fontWeight = FontWeight.Bold)
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("اكتب للبحث على التلفزيون") }, modifier = Modifier.fillMaxWidth(), minLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runCommand { it.insertText(query) } }, modifier = Modifier.weight(1f)) { Text("إرسال النص") }
                    OutlinedButton(onClick = { runCommand { it.sendEnter() } }, modifier = Modifier.weight(1f)) { Text("Enter / بحث") }
                }
                Text("ملاحظة: افتح أو ركّز أولاً على خانة البحث في Cobalt أو التطبيق الحالي، ثم أرسل النص.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("تطبيقات التلفزيون", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { runCommand { apps = it.getApps(); showApps = true } }) { Text("تحديث القائمة") }
                }
                Button(onClick = { showApps = !showApps }, modifier = Modifier.fillMaxWidth()) { Text(if (showApps) "إخفاء التطبيقات" else "عرض وفتح التطبيقات") }
                if (showApps) {
                    if (apps.isEmpty()) Text("لا توجد قائمة بعد؛ اضغط تحديث القائمة.", color = Color.LightGray)
                    apps.take(80).forEach { (title, id) ->
                        OutlinedButton(onClick = { runCommand { it.launchApp(id); status = "تم إرسال أمر فتح $title" } }, modifier = Modifier.fillMaxWidth()) {
                            Text("$title  ·  $id", maxLines = 1)
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("إدارة التلفزيون", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { runCommand { it.powerOff() } }, modifier = Modifier.fillMaxWidth()) { Text("إيقاف تشغيل التلفزيون") }
                Text("بعض الأوامر قد تختلف حسب إصدار webOS وصلاحيات الاقتران. تشغيل التلفزيون من وضع الإيقاف قد يحتاج Wake-on-LAN مفعلاً.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RemoteKey(label: String, modifier: Modifier = Modifier.width(82.dp), strong: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(58.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = if (strong) Accent else Color(0xFF344258), contentColor = if (strong) Ink else Color.White), contentPadding = PaddingValues(0.dp)) {
        Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SmallAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 46.dp), shape = RoundedCornerShape(14.dp)) { Text(label, maxLines = 1) }
}
