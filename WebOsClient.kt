package com.mnasser.cobaltremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

data class TvApp(val title: String, val id: String, val icon: String?)

/** SSAP client. HTTPS uses trust-on-first-use certificate pinning. */
class WebOsClient(private val host: String, private var clientKey: String?, private val pinnedFingerprint: String? = null) {
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val registered = CompletableDeferred<String>()
    private val helloMessage = CompletableDeferred<JSONObject>()
    private val errors = CompletableDeferred<String>()
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
                require(chain.isNotEmpty()) { "TV supplied no TLS certificate" }
                val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                observedFingerprint = fp
                if (pinnedFingerprint != null && !fp.equals(pinnedFingerprint, true))
                    throw java.security.cert.CertificateException("تغيرت شهادة التلفزيون؛ ألغيت الاتصال لحمايتك. امسح الاقتران بعد التأكد من هوية التلفزيون.")
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
            // LG TVs commonly use self-signed certificates without an IP SAN; the certificate is pinned above.
            .hostnameVerifier(HostnameVerifier { _, _ -> pinnedFingerprint != null || observedFingerprint != null })
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    private var lastOpen = CompletableDeferred<Unit>()
    private fun socket(url: String, onOpen: (() -> Unit)? = null): WebSocket {
        val open = CompletableDeferred<Unit>()
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { open.complete(Unit); onOpen?.invoke() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val msg = JSONObject(text); val type = msg.optString("type"); val id = msg.opt("id")?.toString()
                    if (type == "hello" && !helloMessage.isCompleted) helloMessage.complete(msg)
                    if (type == "registered") msg.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotBlank() }?.let { if (!registered.isCompleted) registered.complete(it) }
                    if (type == "error" && !errors.isCompleted) errors.complete(msg.optString("error", "TV returned an error"))
                    if (id != null) pending.remove(id)?.complete(msg)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (!open.isCompleted) open.completeExceptionally(t); if (!errors.isCompleted) errors.complete(t.message ?: "WebSocket connection failed") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (!errors.isCompleted) errors.complete("TV disconnected: $reason") }
        })
        lastOpen = open
        return ws
    }

    suspend fun connect(): String {
        mainSocket = socket("wss://$host:3001")
        // Never downgrade to plaintext: a failed TLS handshake must not bypass the stored pin.
        withTimeout(10000) { lastOpen.await() }
        mainSocket?.send(JSONObject().put("id", "hello").put("type", "hello").put("payload", JSONObject()).toString())
        withTimeout(10000) { helloMessage.await() }
        val permissions = JSONArray(listOf("LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_POWER", "READ_APP_STATUS", "READ_RUNNING_APPS", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS", "READ_NETWORK_STATE", "READ_POWER_STATE"))
        val manifest = JSONObject().put("appVersion", "1.1").put("manifestVersion", 1).put("permissions", permissions)
        val payload = JSONObject().put("forcePairing", false).put("manifest", manifest).put("pairingType", "PROMPT")
        if (!clientKey.isNullOrBlank()) payload.put("client-key", clientKey)
        val id = "register_${ids.getAndIncrement()}"; val wait = CompletableDeferred<JSONObject>(); pending[id] = wait
        mainSocket?.send(JSONObject().put("type", "register").put("id", id).put("payload", payload).toString())
        val reply = withTimeout(12000) { wait.await() }
        if (reply.optString("type") == "error") throw IllegalStateException(reply.optString("error", "Pairing rejected"))
        if (reply.optString("type") == "registered") clientKey = reply.optJSONObject("payload")?.optString("client-key") ?: clientKey
        else if (clientKey.isNullOrBlank()) clientKey = withTimeout(90000) { registered.await() }
        if (clientKey.isNullOrBlank()) throw IllegalStateException("TV did not return a pairing key")
        val input = request("com.webos.service.networkinput/getPointerInputSocket")
        val path = input.optJSONObject("payload")?.optString("socketPath") ?: throw IllegalStateException("TV did not provide remote-input socket")
        inputReady = CompletableDeferred(); inputSocket = socket(path) { inputReady.complete(Unit) }; withTimeout(10000) { inputReady.await() }
        runCatching { request("com.webos.service.ime/registerRemote") }
        return clientKey!!
    }

    suspend fun request(uri: String, payload: JSONObject = JSONObject()): JSONObject {
        val id = "req_${ids.getAndIncrement()}"; val wait = CompletableDeferred<JSONObject>(); pending[id] = wait
        if (mainSocket?.send(JSONObject().put("id", id).put("type", "request").put("uri", "ssap://$uri").put("payload", payload).toString()) != true) { pending.remove(id); throw IllegalStateException("TV is not connected") }
        val response = withTimeout(12000) { wait.await() }
        if (response.optString("type") == "error" || response.optJSONObject("payload")?.optBoolean("returnValue", true) == false) throw IllegalStateException(response.optString("error", "TV command failed"))
        return response
    }
    fun button(name: String) { if (inputSocket?.send("type:button\nname:$name\n\n") != true) throw IllegalStateException("Remote input channel is not connected") }
    fun movePointer(dx: Int, dy: Int) { if (inputSocket?.send("type:move\ndx:$dx\ndy:$dy\n\n") != true) throw IllegalStateException("Pointer channel is not connected") }
    fun clickPointer() { if (inputSocket?.send("type:click\n\n") != true) throw IllegalStateException("Pointer channel is not connected") }
    fun scrollPointer(dy: Int) { inputSocket?.send("type:scroll\ndy:$dy\n\n") }
    suspend fun insertText(text: String) { request("com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", false)) }
    suspend fun sendEnter() { request("com.webos.service.ime/sendEnterKey") }
    suspend fun getApps(): List<TvApp> {
        val arr = request("com.webos.applicationManager/listLaunchPoints").optJSONObject("payload")?.optJSONArray("launchPoints") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { a -> a.optString("id").takeIf(String::isNotBlank)?.let { TvApp(a.optString("title", it), it, a.optString("icon").takeIf(String::isNotBlank)) } } }.sortedBy { it.title.lowercase() }
    }
    suspend fun launchApp(appId: String) { request("system.launcher/launch", JSONObject().put("id", appId)) }
    suspend fun setVolume(volume: Int) { request("audio/setVolume", JSONObject().put("volume", volume.coerceIn(0, 100))) }
    suspend fun powerOff() { request("system/turnOff") }
    fun disconnect() { inputSocket?.close(1000, "disconnect"); mainSocket?.close(1000, "disconnect"); inputSocket = null; mainSocket = null }
    fun currentClientKey(): String? = clientKey
    fun certificateFingerprint(): String? = observedFingerprint
}
