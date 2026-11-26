package com.mnasser.cobaltremote

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val TabInk = Color(0xFF0A101D)
private val TabCard = Color(0xFF131D30)
private val TabElevated = Color(0xFF1C2A42)
private val TabBorder = Color(0xFF283A57)
private val TabAccent = Color(0xFF2EE6CE)
private val TabAccentDark = Color(0xFF062925)
private val TabYoutube = Color(0xFFFF3D57)
private val TabKodi = Color(0xFF29B6F6)
private val TabDanger = Color(0xFFFF5C72)
private val TabWarning = Color(0xFFFFC861)
private val TabText = Color(0xFFF3F6FB)
private val TabMuted = Color(0xFF94A3B8)

@Composable
internal fun StreamTabContent(
    tvIp: String,
    streamUrl: String,
    onStreamUrlChange: (String) -> Unit,
    onRemoteCommand: (suspend (WebOsClient) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeUrl by remember { mutableStateOf(streamUrl) }
    var playing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = streamUrl,
                onValueChange = onStreamUrlChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        if (tvIp.isNotBlank()) "http://$tvIp:8090/stream.mjpg" else "رابط البث (MJPEG / HLS / HTTP)",
                        fontSize = 11.sp,
                        color = TabMuted
                    )
                },
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TabText),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TabAccent,
                    unfocusedBorderColor = TabBorder,
                    focusedContainerColor = TabCard,
                    unfocusedContainerColor = TabCard
                )
            )
            Button(
                onClick = {
                    val target = streamUrl.trim().ifBlank {
                        if (tvIp.isNotBlank()) "http://$tvIp:8090/stream.mjpg" else ""
                    }
                    if (target.isNotBlank()) {
                        onStreamUrlChange(target)
                        activeUrl = target
                        playing = true
                        reloadKey++
                    }
                },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TabAccent, contentColor = TabAccentDark)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("عرض", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
                .border(1.dp, TabBorder, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (playing && activeUrl.isNotBlank()) {
                key(reloadKey, activeUrl) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                setBackgroundColor(android.graphics.Color.BLACK)
                                webViewClient = WebViewClient()
                                val lower = activeUrl.lowercase()
                                if (lower.endsWith(".mp4") || lower.endsWith(".m3u8") || lower.endsWith(".mkv")) {
                                    val html = """
                                        <html><body style="margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh;">
                                        <video src="$activeUrl" controls autoplay playsinline style="width:100%;height:100%;object-fit:contain;"></video>
                                        </body></html>
                                    """.trimIndent()
                                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                } else {
                                    val html = """
                                        <html><body style="margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh;">
                                        <img src="$activeUrl" style="width:100%;height:100%;object-fit:contain;" onerror="document.body.innerHTML='<div style=color:#ffc861;font-family:sans-serif;text-align:center;padding:16px;direction:rtl>تعذر فتح البث على هذا الرابط.<br/>تأكد من تشغيل خدمة البث على التلفزيون (مثل piccap) أو افتح الرابط بمشغل خارجي.</div>'"/>
                                        </body></html>
                                    """.trimIndent()
                                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(Icons.Rounded.Cast, contentDescription = null, tint = TabAccent.copy(alpha = 0.7f), modifier = Modifier.size(38.dp))
                    Text("بث شاشة التلفزيون أو الفيديو على الهاتف", color = TabText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text(
                        "نظام LG webOS الرسمي لا يبث صورة الشاشة لاسلكياً للتطبيقات بدون خدمة التقاط على التلفزيون (مثل piccap/Hyperion). يمكنك لصق أي رابط بث MJPEG أو HLS أو فيديو هنا لمشاهدته مباشرة.",
                        color = TabMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { playing = false },
                enabled = playing,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = TabCard, contentColor = TabText)
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null, tint = TabDanger, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("إيقاف", fontSize = 12.sp)
            }
            FilledTonalButton(
                onClick = { if (activeUrl.isNotBlank()) { playing = true; reloadKey++ } },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = TabCard, contentColor = TabText)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = TabAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("إعادة تحميل", fontSize = 12.sp)
            }
            FilledTonalButton(
                onClick = {
                    val u = streamUrl.trim().ifBlank { activeUrl }
                    if (u.isNotBlank()) {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(u), "video/*")
                            }
                            context.startActivity(Intent.createChooser(intent, "فتح البث في مشغّل فيديو"))
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = TabCard, contentColor = TabText)
            ) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = TabKodi, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("مشغّل VLC", fontSize = 12.sp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(TabCard)
                .border(1.dp, TabBorder, RoundedCornerShape(14.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onRemoteCommand { it.button("BACK") } }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع", tint = TabText)
            }
            IconButton(onClick = { onRemoteCommand { it.button("HOME") } }) {
                Icon(Icons.Rounded.Home, contentDescription = "الرئيسية", tint = TabText)
            }
            IconButton(onClick = { onRemoteCommand { it.button("PAUSE") } }) {
                Icon(Icons.Rounded.Pause, contentDescription = "إيقاف مؤقت", tint = TabAccent)
            }
            IconButton(onClick = { onRemoteCommand { it.button("VOLUMEDOWN") } }) {
                Icon(Icons.Rounded.VolumeDown, contentDescription = "خفض الصوت", tint = TabText)
            }
            IconButton(onClick = { onRemoteCommand { it.button("VOLUMEUP") } }) {
                Icon(Icons.Rounded.VolumeUp, contentDescription = "رفع الصوت", tint = TabText)
            }
        }
    }
}

@Composable
internal fun ScenarioTabContent(
    steps: List<ScenarioStep>,
    running: Boolean,
    currentStepIndex: Int,
    remainingSec: Int,
    currentTotalSec: Int,
    kodiMovies: List<KodiMovie>,
    tvInputs: List<TvInput>,
    tvApps: List<TvApp>,
    onAddStep: (ScenarioStep) -> Unit,
    onRemoveStep: (String) -> Unit,
    onMoveStep: (Int, Int) -> Unit,
    onResetStepProgress: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Live Running Countdown Banner
        if (running && currentStepIndex in steps.indices) {
            val active = steps[currentStepIndex]
            val total = currentTotalSec.coerceAtLeast(1)
            val progress = 1f - (remainingSec.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(TabCard)
                    .border(1.5.dp, TabAccent, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "قيد التشغيل (${currentStepIndex + 1}/${steps.size})",
                            color = TabAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = active.title,
                            color = TabText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = ScenarioStore.formatTimestamp(remainingSec),
                        color = TabAccent,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = TabAccent,
                    trackColor = TabBorder
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onSkipNext,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = TabElevated, contentColor = TabText)
                    ) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("تخطي للتالي", fontSize = 12.sp)
                    }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TabDanger, contentColor = Color.White)
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("إيقاف وحفظ الموضع", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStart,
                    enabled = steps.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TabAccent, contentColor = TabAccentDark)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("بدء السيناريو", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = TabElevated, contentColor = TabText)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = TabAccent, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("إضافة خطوة", fontSize = 12.sp)
                }
            }
        }

        // Steps List
        if (steps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(TabCard)
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, tint = TabAccent.copy(alpha = 0.7f), modifier = Modifier.size(40.dp))
                    Text("منسّق التشغيل المتتابع (السيناريو)", color = TabText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "ابحث واختر فيديوهات يوتيوب أو أفلام كودي أو مداخل HDMI مع تحديد المدة أو تشغيل الفيديو كاملاً، والاستكمال التلقائي من حيث توقف في المرة السابقة.",
                        color = TabMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TabAccent, contentColor = TabAccentDark)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("إضافة أول خطوة", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(steps, key = { _, s -> s.id }) { idx, step ->
                    val isCurrent = running && idx == currentStepIndex
                    val typeColor = when (step.type) {
                        ScenarioActionType.YOUTUBE -> TabYoutube
                        ScenarioActionType.KODI_MOVIE -> TabKodi
                        ScenarioActionType.HDMI -> TabAccent
                        ScenarioActionType.TV_APP -> TabWarning
                        ScenarioActionType.POWER_OFF -> TabDanger
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isCurrent) TabElevated else TabCard)
                            .border(1.dp, if (isCurrent) TabAccent else TabBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(typeColor.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${idx + 1}", color = typeColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(step.title, color = TabText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(step.type.labelAr, color = typeColor, fontSize = 10.sp)
                                if (step.type != ScenarioActionType.POWER_OFF) {
                                    val durLabel = if (step.playFullVideo) "الفيديو كامل" else "${step.durationMinutes} دقيقة"
                                    Text("• $durLabel", color = TabMuted, fontSize = 10.sp)
                                }
                                if ((step.type == ScenarioActionType.YOUTUBE || step.type == ScenarioActionType.KODI_MOVIE) && step.startAtSec > 0) {
                                    Text("• يبدأ ${ScenarioStore.formatTimestamp(step.startAtSec)}", color = TabAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (!running) {
                            if (step.startAtSec > 0 && (step.type == ScenarioActionType.YOUTUBE || step.type == ScenarioActionType.KODI_MOVIE)) {
                                IconButton(onClick = { onResetStepProgress(step.id) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Rounded.RestartAlt, contentDescription = "تصفير وقت البداية", tint = TabAccent, modifier = Modifier.size(18.dp))
                                }
                            }
                            if (idx > 0) {
                                IconButton(onClick = { onMoveStep(idx, idx - 1) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "لأعلى", tint = TabMuted, modifier = Modifier.size(18.dp))
                                }
                            }
                            if (idx < steps.lastIndex) {
                                IconButton(onClick = { onMoveStep(idx, idx + 1) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "لأسفل", tint = TabMuted, modifier = Modifier.size(18.dp))
                                }
                            }
                            IconButton(onClick = { onRemoveStep(step.id) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "حذف", tint = TabDanger, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddScenarioStepDialog(
            kodiMovies = kodiMovies,
            tvInputs = tvInputs,
            tvApps = tvApps,
            onDismiss = { showAddDialog = false },
            onAdd = { step ->
                onAddStep(step)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddScenarioStepDialog(
    kodiMovies: List<KodiMovie>,
    tvInputs: List<TvInput>,
    tvApps: List<TvApp>,
    onDismiss: () -> Unit,
    onAdd: (ScenarioStep) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedType by remember { mutableStateOf(ScenarioActionType.YOUTUBE) }
    var durationText by remember { mutableStateOf("10") }
    var startMinText by remember { mutableStateOf("0") }
    var playFullVideo by remember { mutableStateOf(false) }
    var autoResume by remember { mutableStateOf(true) }

    // YouTube Search + Direct URL state
    var ytQuery by remember { mutableStateOf("") }
    var ytSearching by remember { mutableStateOf(false) }
    var ytResults by remember { mutableStateOf<List<YouTubeSearchResult>>(emptyList()) }
    var ytSelectedVideo by remember { mutableStateOf<YouTubeSearchResult?>(null) }
    var ytManualUrl by remember { mutableStateOf("") }

    var movieFilter by remember { mutableStateOf("") }
    var chosenMovie by remember { mutableStateOf<KodiMovie?>(kodiMovies.firstOrNull()) }
    var chosenInput by remember { mutableStateOf<TvInput?>(tvInputs.firstOrNull()) }
    var chosenApp by remember { mutableStateOf<TvApp?>(tvApps.firstOrNull()) }

    fun runYtSearch() {
        val q = ytQuery.trim()
        if (q.isEmpty()) return
        ytSearching = true
        scope.launch {
            ytResults = runCatching { WebOsClient.searchYouTube(q) }.getOrDefault(emptyList())
            ytSearching = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(TabCard)
                    .border(1.dp, TabBorder, RoundedCornerShape(22.dp))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("إضافة خطوة للسيناريو", color = TabText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "إغلاق", tint = TabMuted)
                    }
                }

                // Action Type Chips
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ScenarioActionType.entries) { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = { Text(t.labelAr, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TabAccent,
                                selectedLabelColor = TabAccentDark
                            )
                        )
                    }
                }

                // Type-specific selector
                when (selectedType) {
                    ScenarioActionType.YOUTUBE -> {
                        // In-dialog YouTube Search!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = ytQuery,
                                onValueChange = { ytQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("ابحث في يوتيوب لاختيار فيديو…", fontSize = 12.sp, color = TabMuted) },
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TabText),
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { runYtSearch() })
                            )
                            FilledIconButton(
                                onClick = { runYtSearch() },
                                modifier = Modifier.size(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = TabYoutube, contentColor = Color.White)
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = "بحث", modifier = Modifier.size(20.dp))
                            }
                        }

                        if (ytSearching) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = TabYoutube, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                Text("جارٍ البحث في يوتيوب…", color = TabMuted, fontSize = 12.sp)
                            }
                        }

                        if (ytSelectedVideo != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(TabYoutube.copy(alpha = 0.16f))
                                    .border(1.dp, TabYoutube, RoundedCornerShape(12.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AsyncImage(
                                    model = ytSelectedVideo!!.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(72.dp).height(42.dp).clip(RoundedCornerShape(6.dp))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(ytSelectedVideo!!.title, color = TabText, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (ytSelectedVideo!!.duration.isNotBlank()) {
                                        Text("مدة الفيديو: ${ytSelectedVideo!!.duration}", color = TabAccent, fontSize = 10.sp)
                                    }
                                }
                                IconButton(onClick = { ytSelectedVideo = null }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Rounded.Close, contentDescription = "إزالة", tint = TabMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        if (ytResults.isNotEmpty() && ytSelectedVideo == null) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ytResults.take(6).forEach { vid ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(TabInk)
                                            .border(1.dp, TabBorder, RoundedCornerShape(10.dp))
                                            .clickable { ytSelectedVideo = vid }
                                            .padding(6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AsyncImage(
                                            model = vid.thumbnailUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.width(76.dp).height(44.dp).clip(RoundedCornerShape(6.dp))
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(vid.title, color = TabText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text("${vid.channel} ${if (vid.duration.isNotBlank()) "• ${vid.duration}" else ""}", color = TabMuted, fontSize = 10.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }

                        if (ytSelectedVideo == null) {
                            OutlinedTextField(
                                value = ytManualUrl,
                                onValueChange = { ytManualUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("أو الصق رابط فيديو يوتيوب مباشرة (اختياري)", fontSize = 11.sp) },
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TabText),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    ScenarioActionType.KODI_MOVIE -> {
                        OutlinedTextField(
                            value = movieFilter,
                            onValueChange = { movieFilter = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("ابحث لاختيار فيلم من كودي…", fontSize = 12.sp, color = TabMuted) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TabText),
                            shape = RoundedCornerShape(12.dp)
                        )
                        val matches = remember(kodiMovies, movieFilter) {
                            val q = movieFilter.trim()
                            (if (q.isEmpty()) kodiMovies else kodiMovies.filter { it.title.contains(q, true) }).take(6)
                        }
                        if (matches.isEmpty()) {
                            Text("حدّث مكتبة أفلام كودي أولاً من تبويب كودي.", color = TabWarning, fontSize = 12.sp)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                matches.forEach { m ->
                                    val sel = chosenMovie?.movieId == m.movieId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (sel) TabKodi.copy(alpha = 0.22f) else TabInk)
                                            .border(1.dp, if (sel) TabKodi else TabBorder, RoundedCornerShape(10.dp))
                                            .clickable { chosenMovie = m }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(m.title, color = TabText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        val dur = KodiClient.formatRuntime(m.runtimeSec)
                                        Text(listOfNotNull(m.year.takeIf { it > 0 }?.toString(), dur.takeIf { it.isNotEmpty() }).joinToString(" • "), color = TabMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }

                    ScenarioActionType.HDMI -> {
                        val allInputs = tvInputs + TvInput("LIVE_TV", "التلفاز المباشر", "com.webos.app.livetv", true)
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            allInputs.forEach { inp ->
                                val sel = chosenInput?.id == inp.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (sel) TabAccent.copy(alpha = 0.20f) else TabInk)
                                        .border(1.dp, if (sel) TabAccent else TabBorder, RoundedCornerShape(10.dp))
                                        .clickable { chosenInput = inp }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(inp.label, color = TabText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(inp.id.replace("_", " "), color = TabMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    ScenarioActionType.TV_APP -> {
                        if (tvApps.isEmpty()) {
                            Text("اتصل بالتلفزيون أولاً لتحميل التطبيقات.", color = TabWarning, fontSize = 12.sp)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                tvApps.take(8).forEach { app ->
                                    val sel = chosenApp?.id == app.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (sel) TabWarning.copy(alpha = 0.20f) else TabInk)
                                            .border(1.dp, if (sel) TabWarning else TabBorder, RoundedCornerShape(10.dp))
                                            .clickable { chosenApp = app }
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text(app.title, color = TabText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }

                    ScenarioActionType.POWER_OFF -> {
                        Text("سيتم إيقاف تشغيل التلفزيون تلقائياً عند الوصول لهذه الخطوة.", color = TabMuted, fontSize = 12.sp)
                    }
                }

                // Video timing options: Full video vs Fixed minutes + Start Minute + Auto-Resume!
                val isVideoStep = selectedType == ScenarioActionType.YOUTUBE || selectedType == ScenarioActionType.KODI_MOVIE
                if (isVideoStep) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(TabInk)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("تشغيل الفيديو بالكامل ثم الانتقال للتالي", color = TabText, fontSize = 12.sp)
                        Switch(checked = playFullVideo, onCheckedChange = { playFullVideo = it })
                    }
                }

                if (selectedType != ScenarioActionType.POWER_OFF && !playFullVideo) {
                    Text("مدة المشاهدة (بالدقائق):", color = TabMuted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf(5, 10, 15, 20, 30, 45, 60)) { mins ->
                            FilterChip(
                                selected = durationText == mins.toString(),
                                onClick = { durationText = mins.toString() },
                                label = { Text("$mins د", fontSize = 11.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("مدة مخصصة بالدقائق", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = TabText),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                if (isVideoStep) {
                    OutlinedTextField(
                        value = startMinText,
                        onValueChange = { startMinText = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("ابدأ الفيديو من الدقيقة (0 = من البداية)", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = TabText),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(TabInk)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("استكمال تلقائي في المرة القادمة", color = TabText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("يحفظ الدقيقة التي توقف عندها ليبدأ منها المرة التالية", color = TabMuted, fontSize = 10.sp)
                        }
                        Switch(checked = autoResume, onCheckedChange = { autoResume = it })
                    }
                }

                Button(
                    onClick = {
                        val mins = durationText.toIntOrNull()?.coerceIn(1, 360) ?: 10
                        val startSec = (startMinText.toIntOrNull()?.coerceAtLeast(0) ?: 0) * 60
                        val step = when (selectedType) {
                            ScenarioActionType.YOUTUBE -> {
                                val sel = ytSelectedVideo
                                val vid = sel?.videoId ?: run {
                                    val raw = ytManualUrl.trim()
                                    WebOsClient.extractYouTubeVideoId(raw) ?: raw
                                }
                                if (vid.isBlank()) return@Button
                                val totalVidSec = sel?.duration?.let { ScenarioStore.parseDurationTextToSec(it) } ?: 0
                                ScenarioStep(
                                    type = selectedType,
                                    title = sel?.title ?: "يوتيوب: $vid",
                                    target = vid,
                                    durationMinutes = mins,
                                    startAtSec = startSec,
                                    videoDurationSec = totalVidSec,
                                    playFullVideo = playFullVideo,
                                    autoResume = autoResume
                                )
                            }
                            ScenarioActionType.KODI_MOVIE -> {
                                val m = chosenMovie ?: return@Button
                                ScenarioStep(
                                    type = selectedType,
                                    title = m.title,
                                    target = m.movieId.toString(),
                                    extra = m.file,
                                    durationMinutes = mins,
                                    startAtSec = startSec,
                                    videoDurationSec = m.runtimeSec,
                                    playFullVideo = playFullVideo,
                                    autoResume = autoResume
                                )
                            }
                            ScenarioActionType.HDMI -> {
                                val inp = chosenInput ?: return@Button
                                ScenarioStep(
                                    type = selectedType,
                                    title = inp.label,
                                    target = inp.id,
                                    extra = inp.appId ?: "",
                                    durationMinutes = mins,
                                    autoResume = false
                                )
                            }
                            ScenarioActionType.TV_APP -> {
                                val app = chosenApp ?: return@Button
                                ScenarioStep(
                                    type = selectedType,
                                    title = app.title,
                                    target = app.id,
                                    durationMinutes = mins,
                                    autoResume = false
                                )
                            }
                            ScenarioActionType.POWER_OFF -> {
                                ScenarioStep(
                                    type = selectedType,
                                    title = "إيقاف التلفزيون",
                                    target = "power_off",
                                    durationMinutes = 1,
                                    autoResume = false
                                )
                            }
                        }
                        onAdd(step)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TabAccent, contentColor = TabAccentDark)
                ) {
                    Text("إضافة للسيناريو", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}
