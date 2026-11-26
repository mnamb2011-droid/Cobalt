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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
        // Stream URL input bar
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

        // 16:9 Live Stream Viewer Box
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
                                    // Works for MJPEG streams (piccap/hyperion-webos) and web streams
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
                        "نظام LG webOS الرسمي لا يبث صورة الشاشة لاسلكياً للتطبيقات بدون خدمة التقاط على التلفزيون (مثل piccap/Hyperion على الأجهزة المروّتة). يمكنك لصق أي رابط بث MJPEG أو HLS أو فيديو هنا لمشاهدته مباشرة.",
                        color = TabMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Quick stream action buttons (Stop / Reload / Open in VLC)
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

        // Quick Mini Remote Controls while watching stream
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
    kodiMovies: List<KodiMovie>,
    tvInputs: List<TvInput>,
    tvApps: List<TvApp>,
    onAddStep: (ScenarioStep) -> Unit,
    onRemoveStep: (String) -> Unit,
    onMoveStep: (Int, Int) -> Unit,
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
            val totalSec = (active.durationMinutes * 60).coerceAtLeast(1)
            val progress = 1f - (remainingSec.toFloat() / totalSec.toFloat()).coerceIn(0f, 1f)
            val mins = remainingSec / 60
            val secs = remainingSec % 60
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
                        text = "%02d:%02d".format(mins, secs),
                        color = TabAccent,
                        fontSize = 22.sp,
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
                        Text("إيقاف السيناريو", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // Top Action Row: Start Scenario + Add Step
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
                    Text("بدء السيناريو (${steps.sumOf { it.durationMinutes }} د)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                        "رتّب خطواتك بمدد زمنية محددة: مثلاً 30 دقيقة فيديو يوتيوب ← 15 دقيقة فيلم من كودي ← 20 دقيقة مخرج HDMI ← إيقاف التلفزيون.",
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
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(step.title, color = TabText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(step.type.labelAr, color = typeColor, fontSize = 11.sp)
                                if (step.type != ScenarioActionType.POWER_OFF) {
                                    Text("• ${step.durationMinutes} دقيقة", color = TabMuted, fontSize = 11.sp)
                                }
                            }
                        }
                        if (!running) {
                            if (idx > 0) {
                                IconButton(onClick = { onMoveStep(idx, idx - 1) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "لأعلى", tint = TabMuted, modifier = Modifier.size(18.dp))
                                }
                            }
                            if (idx < steps.lastIndex) {
                                IconButton(onClick = { onMoveStep(idx, idx + 1) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "لأسفل", tint = TabMuted, modifier = Modifier.size(18.dp))
                                }
                            }
                            IconButton(onClick = { onRemoveStep(step.id) }, modifier = Modifier.size(30.dp)) {
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
    var selectedType by remember { mutableStateOf(ScenarioActionType.YOUTUBE) }
    var durationText by remember { mutableStateOf("15") }
    var ytInput by remember { mutableStateOf("") }
    var ytLabel by remember { mutableStateOf("") }
    var movieFilter by remember { mutableStateOf("") }
    var chosenMovie by remember { mutableStateOf<KodiMovie?>(kodiMovies.firstOrNull()) }
    var chosenInput by remember { mutableStateOf<TvInput?>(tvInputs.firstOrNull()) }
    var chosenApp by remember { mutableStateOf<TvApp?>(tvApps.firstOrNull()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
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

                // Type-specific target selector
                when (selectedType) {
                    ScenarioActionType.YOUTUBE -> {
                        OutlinedTextField(
                            value = ytInput,
                            onValueChange = { ytInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("رابط فيديو يوتيوب أو معرّفه (11 حرفاً)", fontSize = 11.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TabText),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = ytLabel,
                            onValueChange = { ytLabel = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("اسم توضيحي (اختياري)", fontSize = 11.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TabText),
                            shape = RoundedCornerShape(12.dp)
                        )
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
                            (if (q.isEmpty()) kodiMovies else kodiMovies.filter { it.title.contains(q, true) }).take(8)
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
                                        if (m.year > 0) Text("${m.year}", color = TabMuted, fontSize = 11.sp)
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
                                tvApps.take(10).forEach { app ->
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

                // Duration picker (minutes)
                if (selectedType != ScenarioActionType.POWER_OFF) {
                    Text("المدة (بالدقائق):", color = TabMuted, fontSize = 12.sp)
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

                Button(
                    onClick = {
                        val mins = durationText.toIntOrNull()?.coerceIn(1, 360) ?: 15
                        val step = when (selectedType) {
                            ScenarioActionType.YOUTUBE -> {
                                val raw = ytInput.trim()
                                val vid = WebOsClient.extractYouTubeVideoId(raw) ?: raw
                                if (vid.isBlank()) return@Button
                                ScenarioStep(
                                    type = selectedType,
                                    title = ytLabel.trim().ifBlank { "يوتيوب: $vid" },
                                    target = vid,
                                    durationMinutes = mins
                                )
                            }
                            ScenarioActionType.KODI_MOVIE -> {
                                val m = chosenMovie ?: return@Button
                                ScenarioStep(
                                    type = selectedType,
                                    title = m.title,
                                    target = m.movieId.toString(),
                                    extra = m.file,
                                    durationMinutes = mins
                                )
                            }
                            ScenarioActionType.HDMI -> {
                                val inp = chosenInput ?: return@Button
                                ScenarioStep(
                                    type = selectedType,
                                    title = inp.label,
                                    target = inp.id,
                                    extra = inp.appId ?: "",
                                    durationMinutes = mins
                                )
                            }
                            ScenarioActionType.TV_APP -> {
                                val app = chosenApp ?: return@Button
                                ScenarioStep(
                                    type = selectedType,
                                    title = app.title,
                                    target = app.id,
                                    durationMinutes = mins
                                )
                            }
                            ScenarioActionType.POWER_OFF -> {
                                ScenarioStep(
                                    type = selectedType,
                                    title = "إيقاف التلفزيون",
                                    target = "power_off",
                                    durationMinutes = 1
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
