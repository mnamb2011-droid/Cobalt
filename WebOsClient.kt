package com.mnasser.cobaltremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import java.util.concurrent.atomic.AtomicInteger

/** Minimal SSAP client for LG webOS TVs. Pairing is approved on the TV itself. */
class WebOsClient(private val host: String, private var clientKey: String?) {
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val registered = CompletableDeferred<String>()
    private val helloMessage = CompletableDeferred<JSONObject>()
    private val errors = CompletableDeferred<String>()
    private var mainSocket: WebSocket? = null
    private var inputSocket: WebSocket? = null
    private var inputReady = CompletableDeferred<Unit>()
    private val http = buildClient()

    private fun buildClient(): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun socket(url: String, onOpen: (() -> Unit)? = null): WebSocket {
        val open = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url).build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.complete(Unit)
                onOpen?.invoke()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val type = msg.optString("type")
                    val id = msg.opt("id")?.toString()
                    if (type == "hello" && !helloMessage.isCompleted) helloMessage.complete(msg)
                    if (type == "registered") {
                        val key = msg.optJSONObject("payload")?.optString("client-key").orEmpty()
                        if (key.isNotBlank()) registered.complete(key)
                    }
                    if (type == "error") {
                        val err = msg.optString("error", "TV returned an error")
                        if (!errors.isCompleted) errors.complete(err)
                    }
                    if (id != null) pending.remove(id)?.complete(msg)
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!open.isCompleted) open.completeExceptionally(t)
                if (!errors.isCompleted) errors.complete(t.message ?: "WebSocket connection failed")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!errors.isCompleted) errors.complete("TV disconnected: $reason")
            }
        })
        // The deferred is awaited by callers after assigning the socket.
        lastOpen = open
        return ws
    }

    private var lastOpen = CompletableDeferred<Unit>()

    suspend fun connect(): String {
        val mainUrl = "wss://$host:3001"
        mainSocket = socket(mainUrl)
        try {
            withTimeout(6500) { lastOpen.await() }
        } catch (_: Exception) {
            mainSocket?.cancel()
            mainSocket = socket("ws://$host:3000")
            withTimeout(8000) { lastOpen.await() }
        }

        val hello = JSONObject().put("id", "hello").put("type", "hello").put("payload", JSONObject())
        // The TV hello response may omit its id, so listen by message type.
        mainSocket?.send(hello.toString())
        val helloReply = withTimeout(10000) { helloMessage.await() }
        if (helloReply.optString("type") != "hello") throw IllegalStateException("Invalid TV handshake")

        val manifestPermissions = JSONArray(listOf(
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "CONTROL_AUDIO",
            "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_POWER",
            "READ_APP_STATUS", "READ_RUNNING_APPS", "WRITE_NOTIFICATION_TOAST",
            "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
            "READ_NETWORK_STATE", "READ_SETTINGS", "READ_POWER_STATE"
        ))
        // Use an unsigned public manifest. The TV still displays its normal pairing prompt.
        val manifest = JSONObject()
            .put("appVersion", "1.0")
            .put("manifestVersion", 1)
            .put("permissions", manifestPermissions)
        val payload = JSONObject().put("forcePairing", false).put("manifest", manifest).put("pairingType", "PROMPT")
        if (!clientKey.isNullOrBlank()) payload.put("client-key", clientKey)
        val registerId = "register_${ids.getAndIncrement()}"
        val registerWait = CompletableDeferred<JSONObject>()
        pending[registerId] = registerWait
        mainSocket?.send(JSONObject().put("type", "register").put("id", registerId).put("payload", payload).toString())
        val registerReply = withTimeout(12000) { registerWait.await() }
        if (registerReply.optString("type") == "error") throw IllegalStateException(registerReply.optString("error", "Pairing rejected"))

        if (registerReply.optString("type") == "registered") {
            clientKey = registerReply.optJSONObject("payload")?.optString("client-key") ?: clientKey
        } else if (registerReply.optJSONObject("payload")?.optString("pairingType") == "PROMPT" || clientKey.isNullOrBlank()) {
            // The TV displays an approval dialog. Wait for its registered event.
            clientKey = withTimeout(90000) { registered.await() }
        }
        if (clientKey.isNullOrBlank()) throw IllegalStateException("TV did not return a pairing key")

        val input = request("com.webos.service.networkinput/getPointerInputSocket")
        val inputPath = input.optJSONObject("payload")?.optString("socketPath")
            ?: throw IllegalStateException("TV did not provide the remote-input socket")
        inputReady = CompletableDeferred()
        inputSocket = socket(inputPath) { inputReady.complete(Unit) }
        withTimeout(10000) { inputReady.await() }
        runCatching { request("com.webos.service.ime/registerRemote") }
        return clientKey!!
    }

    suspend fun request(uri: String, payload: JSONObject = JSONObject()): JSONObject {
        val id = "req_${ids.getAndIncrement()}"
        val wait = CompletableDeferred<JSONObject>()
        pending[id] = wait
        val sent = mainSocket?.send(JSONObject().put("id", id).put("type", "request").put("uri", "ssap://$uri").put("payload", payload).toString()) ?: false
        if (!sent) { pending.remove(id); throw IllegalStateException("TV is not connected") }
        val response = withTimeout(12000) { wait.await() }
        if (response.optString("type") == "error" || response.optJSONObject("payload")?.optBoolean("returnValue", true) == false) {
            throw IllegalStateException(response.optString("error", "TV command failed"))
        }
        return response
    }

    fun button(name: String) {
        val ok = inputSocket?.send("type:button\nname:$name\n\n") ?: false
        if (!ok) throw IllegalStateException("Remote input channel is not connected")
    }

    suspend fun insertText(text: String) {
        request("com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", false))
    }

    suspend fun deleteCharacters(count: Int = 1) {
        request("com.webos.service.ime/deleteCharacters", JSONObject().put("count", count))
    }

    suspend fun sendEnter() { request("com.webos.service.ime/sendEnterKey") }
    suspend fun getApps(): List<Pair<String, String>> {
        val payload = request("com.webos.applicationManager/listLaunchPoints").optJSONObject("payload")
        val arr = payload?.optJSONArray("launchPoints") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val app = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = app.optString("id")
            val title = app.optString("title", id)
            if (id.isBlank()) null else title to id
        }.sortedBy { it.first.lowercase() }
    }

    suspend fun launchApp(appId: String) { request("system.launcher/launch", JSONObject().put("id", appId)) }
    suspend fun setVolume(volume: Int) { request("audio/setVolume", JSONObject().put("volume", volume.coerceIn(0, 100))) }
    suspend fun setMute(mute: Boolean) { request("audio/setMute", JSONObject().put("mute", mute)) }
    suspend fun powerOff() { request("system/turnOff") }

    fun disconnect() {
        inputSocket?.close(1000, "disconnect")
        mainSocket?.close(1000, "disconnect")
        inputSocket = null
        mainSocket = null
    }

    fun currentClientKey(): String? = clientKey
}
