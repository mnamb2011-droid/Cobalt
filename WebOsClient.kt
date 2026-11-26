package com.mnasser.cobaltremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

data class TvApp(
    val title: String,
    val id: String,
    val icon: String?,
    val iconColor: String? = null
)

data class TvInput(
    val id: String,
    val label: String,
    val appId: String?,
    val connected: Boolean
)

data class YouTubeSearchResult(
    val videoId: String,
    val title: String,
    val channel: String,
    val duration: String,
    val thumbnailUrl: String
)

class WebOsConnectionException(message: String, cause: Throwable? = null) : IOException(message, cause)
class WebOsCommandException(message: String) : IllegalStateException(message)

/** LG webOS SSAP client with TOFU TLS pinning, TV icon HTTP client, native IME, and Cobalt YouTube launcher. */
class WebOsClient(
    private val host: String,
    private var clientKey: String?,
    private val pinnedFingerprint: String? = null,
    private val onImeFocusChanged: ((Boolean) -> Unit)? = null,
    private val onDisconnected: ((String) -> Unit)? = null
) {
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val registered = CompletableDeferred<String>()
    private val helloMessage = CompletableDeferred<JSONObject>()
    private val errors = CompletableDeferred<String>()
    private val connectedFlag = AtomicBoolean(false)
    private val disconnectedNotified = AtomicBoolean(false)

    @Volatile var imeFocused: Boolean = false
        private set

    private var mainSocket: WebSocket? = null
    private var inputSocket: WebSocket? = null
    private var inputReady = CompletableDeferred<Unit>()
    @Volatile private var observedFingerprint: String? = null
    private val http = buildClient()

    private fun buildClient(): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "لم يرسل التلفزيون شهادة TLS" }
                val fp = MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                observedFingerprint = fp
                if (pinnedFingerprint != null && !fp.equals(pinnedFingerprint, true)) {
                    throw java.security.cert.CertificateException(
                        "تغيرت شهادة التلفزيون؛ تم إلغاء الاتصال للحماية. امسح الاقتران وأعد المحاولة."
                    )
                }
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier(HostnameVerifier { _, _ -> pinnedFingerprint != null || observedFingerprint != null })
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** OkHttpClient configured to trust this TV's self-signed TLS certificate for loading TV app icons. */
    fun imageHttpClient(): OkHttpClient = http

    private fun notifyDisconnect(reason: String) {
        connectedFlag.set(false)
        imeFocused = false
        pending.values.forEach { if (!it.isCompleted) it.completeExceptionally(WebOsConnectionException(reason)) }
        pending.clear()
        if (disconnectedNotified.compareAndSet(false, true)) {
            onDisconnected?.invoke(reason)
        }
    }

    private fun openSocket(url: String, isMain: Boolean, onOpen: (() -> Unit)? = null): Pair<WebSocket, CompletableDeferred<Unit>> {
        val open = CompletableDeferred<Unit>()
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!open.isCompleted) open.complete(Unit)
                onOpen?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val msg = JSONObject(text)
                    val type = msg.optString("type")
                    val id = msg.opt("id")?.toString()
                    val payload = msg.optJSONObject("payload")

                    payload?.optJSONObject("currentWidget")?.let { widget ->
                        val focused = widget.optBoolean("focus", false)
                        if (imeFocused != focused) {
                            imeFocused = focused
                            onImeFocusChanged?.invoke(focused)
                        }
                    }

                    if (type == "hello" && !helloMessage.isCompleted) helloMessage.complete(msg)
                    if (type == "registered") {
                        payload?.optString("client-key")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { if (!registered.isCompleted) registered.complete(it) }
                    }
                    if (type == "error" && !errors.isCompleted) {
                        errors.complete(msg.optString("error", "رفض التلفزيون الطلب"))
                    }
                    if (id != null) pending.remove(id)?.complete(msg)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!open.isCompleted) open.completeExceptionally(t)
                if (!errors.isCompleted) errors.complete(t.message ?: "فشل اتصال WebSocket")
                if (isMain && connectedFlag.get()) {
                    notifyDisconnect(t.message ?: "انقطع الاتصال بالتلفزيون")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!errors.isCompleted) errors.complete("انقطع الاتصال: $reason")
                if (isMain && connectedFlag.get()) {
                    notifyDisconnect("انقطع الاتصال بالتلفزيون")
                }
            }
        })
        return ws to open
    }

    suspend fun connect(): String {
        val (ws, mainOpen) = openSocket("wss://$host:3001", isMain = true)
        mainSocket = ws
        try {
            withTimeout(8000) { mainOpen.await() }
        } catch (e: Throwable) {
            throw WebOsConnectionException(e.message ?: "تعذر الوصول إلى التلفزيون", e)
        }

        mainSocket?.send(
            JSONObject()
                .put("id", "hello")
                .put("type", "hello")
                .put("payload", JSONObject())
                .toString()
        )
        withTimeout(8000) { helloMessage.await() }

        val permissions = JSONArray(
            listOf(
                "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE",
                "CONTROL_AUDIO", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK",
                "CONTROL_POWER", "READ_APP_STATUS", "READ_RUNNING_APPS",
                "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
                "READ_INSTALLED_APPS", "READ_NETWORK_STATE", "READ_POWER_STATE"
            )
        )
        val manifest = JSONObject()
            .put("appId", "com.mnasser.cobaltremote")
            .put("appVersion", "1.1")
            .put("manifestVersion", 1)
            .put("permissions", permissions)
        val payload = JSONObject()
            .put("forcePairing", false)
            .put("manifest", manifest)
            .put("pairingType", "PROMPT")
        if (!clientKey.isNullOrBlank()) payload.put("client-key", clientKey)

        val id = "register_${ids.getAndIncrement()}"
        val wait = CompletableDeferred<JSONObject>()
        pending[id] = wait
        mainSocket?.send(
            JSONObject()
                .put("type", "register")
                .put("id", id)
                .put("payload", payload)
                .toString()
        )
        val reply = withTimeout(12000) { wait.await() }
        if (reply.optString("type") == "error") {
            throw WebOsConnectionException(reply.optString("error", "تم رفض الاقتران على التلفزيون"))
        }
        if (reply.optString("type") == "registered") {
            clientKey = reply.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotBlank() } ?: clientKey
        } else {
            clientKey = withTimeout(90000) { registered.await() }
        }
        if (clientKey.isNullOrBlank()) {
            throw WebOsConnectionException("لم يُرجع التلفزيون مفتاح الاقتران")
        }

        val input = request("com.webos.service.networkinput/getPointerInputSocket")
        val path = input.optJSONObject("payload")?.optString("socketPath")
            ?: throw WebOsConnectionException("لم يوفر التلفزيون قناة المؤشر والأزرار")
        inputReady = CompletableDeferred()
        val (inWs, _) = openSocket(path, isMain = false) {
            if (!inputReady.isCompleted) inputReady.complete(Unit)
        }
        inputSocket = inWs
        withTimeout(8000) { inputReady.await() }

        runCatching { subscribe("com.webos.service.ime/registerRemoteKeyboard") }
        connectedFlag.set(true)
        return clientKey!!
    }

    private fun subscribe(uri: String, payload: JSONObject = JSONObject()) {
        val id = "sub_${ids.getAndIncrement()}"
        mainSocket?.send(
            JSONObject()
                .put("id", id)
                .put("type", "subscribe")
                .put("uri", "ssap://$uri")
                .put("payload", payload)
                .toString()
        )
    }

    suspend fun request(uri: String, payload: JSONObject = JSONObject()): JSONObject {
        val id = "req_${ids.getAndIncrement()}"
        val wait = CompletableDeferred<JSONObject>()
        pending[id] = wait
        val sent = mainSocket?.send(
            JSONObject()
                .put("id", id)
                .put("type", "request")
                .put("uri", "ssap://$uri")
                .put("payload", payload)
                .toString()
        ) == true
        if (!sent) {
            pending.remove(id)
            throw WebOsConnectionException("التلفزيون غير متصل")
        }
        val response = withTimeout(10000) { wait.await() }
        val isError = response.optString("type") == "error" ||
            response.optJSONObject("payload")?.optBoolean("returnValue", true) == false
        if (isError) {
            throw WebOsCommandException(response.optString("error", "تعذر تنفيذ الأمر على التلفزيون"))
        }
        return response
    }

    fun button(name: String) {
        if (inputSocket?.send("type:button\nname:$name\n\n") != true) {
            throw WebOsConnectionException("انقطعت قناة التحكم بالأزرار")
        }
    }

    fun movePointer(dx: Int, dy: Int) {
        if (inputSocket?.send("type:move\ndx:$dx\ndy:$dy\n\n") != true) {
            throw WebOsConnectionException("انقطعت قناة المؤشر")
        }
    }

    fun clickPointer() {
        if (inputSocket?.send("type:click\n\n") != true) {
            throw WebOsConnectionException("انقطعت قناة المؤشر")
        }
    }

    fun scrollPointer(dy: Int) {
        inputSocket?.send("type:scroll\ndy:$dy\n\n")
    }

    suspend fun insertText(text: String, replace: Boolean = false) {
        try {
            request(
                "com.webos.service.ime/insertText",
                JSONObject().put("text", text).put("replace", if (replace) 1 else 0)
            )
        } catch (_: WebOsCommandException) {
            request(
                "com.webos.service.ime/insertText",
                JSONObject().put("text", text).put("replace", replace)
            )
        }
    }

    suspend fun deleteCharacters(count: Int = 1) {
        if (count <= 0) return
        request("com.webos.service.ime/deleteCharacters", JSONObject().put("count", count))
    }

    suspend fun sendEnter() {
        request("com.webos.service.ime/sendEnterKey")
    }

    suspend fun getForegroundAppId(): String? = runCatching {
        request("com.webos.applicationManager/getForegroundAppInfo")
            .optJSONObject("payload")
            ?.optString("appId")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Launches a specific YouTube video directly in Cobalt YouTube via verified contentTarget. */
    suspend fun playYouTubeVideo(appId: String, videoId: String) {
        val contentTarget = "https://www.youtube.com/watch?v=$videoId"
        val params = JSONObject().put("contentTarget", contentTarget)
        val payload = JSONObject().put("id", appId).put("params", params)
        try {
            request("com.webos.applicationManager/launch", payload)
        } catch (_: WebOsCommandException) {
            request("system.launcher/launch", payload)
        }
    }

    suspend fun getVolume(): Int? {
        val payload = request("audio/getVolume").optJSONObject("payload") ?: return null
        val volStatus = payload.optJSONObject("volumeStatus")
        return when {
            payload.has("volume") -> payload.optInt("volume")
            volStatus != null && volStatus.has("volume") -> volStatus.optInt("volume")
            else -> null
        }?.coerceIn(0, 100)
    }

    private fun resolveIconUrl(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            s.startsWith("/resources/", true) -> "https://$host:3001$s"
            else -> null // Local TV filesystem paths (/usr/palm/...) are not HTTP-accessible
        }
    }

    suspend fun getApps(): List<TvApp> {
        val arr = request("com.webos.applicationManager/listLaunchPoints")
            .optJSONObject("payload")
            ?.optJSONArray("launchPoints") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { a ->
                a.optString("id").takeIf(String::isNotBlank)?.let { id ->
                    val iconUrl = resolveIconUrl(a.optString("largeIcon"))
                        ?: resolveIconUrl(a.optString("icon"))
                        ?: resolveIconUrl(a.optString("miniicon"))
                    val color = a.optString("iconColor").takeIf { it.startsWith("#") }
                    TvApp(
                        title = a.optString("title", id),
                        id = id,
                        icon = iconUrl,
                        iconColor = color
                    )
                }
            }
        }.sortedBy { it.title.lowercase() }
    }

    suspend fun getExternalInputs(): List<TvInput> {
        val arr = request("tv/getExternalInputList")
            .optJSONObject("payload")
            ?.optJSONArray("devices") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { d ->
                val id = d.optString("id").takeIf { it.isNotBlank() } ?: return@let null
                TvInput(
                    id = id,
                    label = d.optString("label").ifBlank { id },
                    appId = d.optString("appId").takeIf { it.isNotBlank() },
                    connected = d.optBoolean("connected", false)
                )
            }
        }
    }

    suspend fun switchInput(input: TvInput) {
        try {
            request("tv/switchInput", JSONObject().put("inputId", input.id))
        } catch (_: WebOsCommandException) {
            if (!input.appId.isNullOrBlank()) {
                launchApp(input.appId)
            } else {
                throw WebOsCommandException("تعذر التبديل إلى ${input.label}")
            }
        }
    }

    suspend fun launchApp(appId: String) {
        request("system.launcher/launch", JSONObject().put("id", appId))
    }

    suspend fun setVolume(volume: Int) {
        request("audio/setVolume", JSONObject().put("volume", volume.coerceIn(0, 100)))
    }

    suspend fun powerOff() {
        request("system/turnOff")
    }

    fun isConnected(): Boolean = connectedFlag.get()

    fun disconnect() {
        connectedFlag.set(false)
        imeFocused = false
        disconnectedNotified.set(true)
        inputSocket?.close(1000, "disconnect")
        mainSocket?.close(1000, "disconnect")
        inputSocket = null
        mainSocket = null
        http.dispatcher.executorService.shutdown()
    }

    fun currentClientKey(): String? = clientKey
    fun certificateFingerprint(): String? = observedFingerprint

    companion object {
        private val publicHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        fun extractYouTubeVideoId(input: String): String? {
            val regex = Regex("""(?:youtube\.com/watch\?.*?v=|youtu\.be/|youtube\.com/shorts/|youtube\.com/live/)([A-Za-z0-9_-]{11})""")
            return regex.find(input.trim())?.groupValues?.getOrNull(1)
        }

        /** Searches YouTube directly from the phone and returns clickable video results to cast to Cobalt TV. */
        suspend fun searchYouTube(query: String): List<YouTubeSearchResult> = withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://www.youtube.com/results?search_query=$encoded")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Accept-Language", "ar,en;q=0.9")
                .build()

            val html = publicHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("YouTube HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }

            val jsonStr = extractJsonAfterMarker(html, "var ytInitialData = ")
                ?: extractJsonAfterMarker(html, "ytInitialData = ")
                ?: return@withContext emptyList()

            val root = JSONObject(jsonStr)
            val results = mutableListOf<YouTubeSearchResult>()
            val seen = HashSet<String>()
            collectVideoRenderers(root, results, seen, limit = 20)
            results
        }

        private fun extractJsonAfterMarker(html: String, marker: String): String? {
            val idx = html.indexOf(marker)
            if (idx < 0) return null
            val start = html.indexOf('{', idx + marker.length)
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until html.length) {
                val ch = html[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                } else {
                    when (ch) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) return html.substring(start, i + 1)
                        }
                    }
                }
            }
            return null
        }

        private fun collectVideoRenderers(
            node: Any?,
            out: MutableList<YouTubeSearchResult>,
            seen: MutableSet<String>,
            limit: Int
        ) {
            if (out.size >= limit || node == null) return
            when (node) {
                is JSONObject -> {
                    val vr = node.optJSONObject("videoRenderer")
                    if (vr != null) {
                        val id = vr.optString("videoId")
                        if (id.length == 11 && seen.add(id)) {
                            val title = readText(vr.optJSONObject("title")).ifBlank { "فيديو يوتيوب" }
                            val channel = readText(vr.optJSONObject("ownerText"))
                                .ifBlank { readText(vr.optJSONObject("longBylineText")) }
                            val duration = readText(vr.optJSONObject("lengthText"))
                            out.add(
                                YouTubeSearchResult(
                                    videoId = id,
                                    title = title,
                                    channel = channel,
                                    duration = duration,
                                    thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                                )
                            )
                        }
                        return
                    }
                    val keys = node.keys()
                    while (keys.hasNext() && out.size < limit) {
                        collectVideoRenderers(node.opt(keys.next()), out, seen, limit)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        if (out.size >= limit) break
                        collectVideoRenderers(node.opt(i), out, seen, limit)
                    }
                }
            }
        }

        private fun readText(obj: JSONObject?): String {
            if (obj == null) return ""
            obj.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
            val runs = obj.optJSONArray("runs") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
            return sb.toString().trim()
        }
    }
}
