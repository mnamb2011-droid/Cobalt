package com.mnasser.cobaltremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

data class TvApp(val title: String, val id: String, val icon: String?)

class WebOsConnectionException(message: String, cause: Throwable? = null) : IOException(message, cause)
class WebOsCommandException(message: String) : IllegalStateException(message)

/** LG webOS SSAP client with TOFU TLS certificate pinning, pointer socket, and live IME keyboard support. */
class WebOsClient(
    private val host: String,
    private var clientKey: String?,
    private val pinnedFingerprint: String? = null,
    private val onDisconnected: ((String) -> Unit)? = null
) {
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val registered = CompletableDeferred<String>()
    private val helloMessage = CompletableDeferred<JSONObject>()
    private val errors = CompletableDeferred<String>()
    private val connectedFlag = AtomicBoolean(false)
    private val disconnectedNotified = AtomicBoolean(false)

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
            // LG webOS TVs use self-signed certificates without IP SANs; pinned above via SHA-256 TOFU.
            .hostnameVerifier(HostnameVerifier { _, _ -> pinnedFingerprint != null || observedFingerprint != null })
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private fun notifyDisconnect(reason: String) {
        connectedFlag.set(false)
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
                    if (type == "hello" && !helloMessage.isCompleted) helloMessage.complete(msg)
                    if (type == "registered") {
                        msg.optJSONObject("payload")?.optString("client-key")
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
            .put("appVersion", "1.2")
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
            clientKey = reply.optJSONObject("payload")?.optString("client-key") ?: clientKey
        } else if (clientKey.isNullOrBlank()) {
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

        // Subscribe to webOS remote keyboard service (correct SSAP URI).
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

    /** LG webOS IME insertText requires integer replace flag: 0 = append, 1 = replace. */
    suspend fun insertText(text: String, replace: Boolean = false) {
        request(
            "com.webos.service.ime/insertText",
            JSONObject().put("text", text).put("replace", if (replace) 1 else 0)
        )
    }

    suspend fun deleteCharacters(count: Int = 1) {
        if (count <= 0) return
        request("com.webos.service.ime/deleteCharacters", JSONObject().put("count", count))
    }

    suspend fun sendEnter() {
        request("com.webos.service.ime/sendEnterKey")
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

    suspend fun getApps(): List<TvApp> {
        val arr = request("com.webos.applicationManager/listLaunchPoints")
            .optJSONObject("payload")
            ?.optJSONArray("launchPoints") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { a ->
                a.optString("id").takeIf(String::isNotBlank)?.let {
                    TvApp(
                        title = a.optString("title", it),
                        id = it,
                        icon = a.optString("icon").takeIf(String::isNotBlank)
                    )
                }
            }
        }.sortedBy { it.title.lowercase() }
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
        disconnectedNotified.set(true)
        inputSocket?.close(1000, "disconnect")
        mainSocket?.close(1000, "disconnect")
        inputSocket = null
        mainSocket = null
        http.dispatcher.executorService.shutdown()
    }

    fun currentClientKey(): String? = clientKey
    fun certificateFingerprint(): String? = observedFingerprint
}
