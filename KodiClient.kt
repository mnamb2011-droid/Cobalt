package com.mnasser.cobaltremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class KodiMovie(
    val movieId: Int,
    val title: String,
    val year: Int,
    val rating: Double,
    val runtimeSec: Int,
    val posterUrl: String?,
    val file: String
)

/**
 * Kodi JSON-RPC client supporting LG webOS Kodi's WebSocket/TCP port 9090 (no auth required)
 * as well as standard HTTP port 8080 fallback, plus automatic TMDB image:// poster unwrapping.
 */
class KodiClient(
    private val host: String,
    private val port: Int = 9090,
    private val username: String = "kodi",
    private val password: String = ""
) {
    private val reqIds = AtomicInteger(1)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val authHeader: String? = if (username.isNotBlank() || password.isNotBlank()) {
        Credentials.basic(username, password)
    } else null

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = if (authHeader != null && chain.request().url.host == host) {
                chain.request().newBuilder().header("Authorization", authHeader).build()
            } else {
                chain.request()
            }
            chain.proceed(req)
        }
        .build()

    fun imageHttpClient(): OkHttpClient = http

    private suspend fun callWebSocket(wsPort: Int, body: String, expectedId: Int): JSONObject = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<JSONObject>()
        val ws = http.newWebSocket(
            Request.Builder().url("ws://$host:$wsPort/jsonrpc").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(body)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val json = JSONObject(text)
                        if (json.optInt("id", -1) == expectedId) {
                            if (!result.isCompleted) result.complete(json)
                            webSocket.close(1000, "done")
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!result.isCompleted) result.completeExceptionally(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!result.isCompleted) result.completeExceptionally(IOException("أغلق كودي الاتصال: $reason"))
                }
            }
        )
        try {
            val json = withTimeout(7000) { result.await() }
            json.optJSONObject("error")?.let { err ->
                throw IOException(err.optString("message", "خطأ من كودي"))
            }
            json
        } finally {
            runCatching { ws.cancel() }
        }
    }

    private suspend fun callTcp(tcpPort: Int, body: String): JSONObject = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, tcpPort), 3500)
            socket.soTimeout = 7000
            val out = socket.getOutputStream()
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
            val input = socket.getInputStream()
            val sb = StringBuilder()
            val buf = ByteArray(8192)
            var depth = 0
            var inString = false
            var escaped = false
            var started = false
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val chunk = String(buf, 0, n, Charsets.UTF_8)
                sb.append(chunk)
                for (ch in chunk) {
                    if (inString) {
                        if (escaped) escaped = false
                        else if (ch == '\\') escaped = true
                        else if (ch == '"') inString = false
                    } else {
                        when (ch) {
                            '"' -> inString = true
                            '{' -> { depth++; started = true }
                            '}' -> depth--
                        }
                    }
                }
                if (started && depth == 0) break
            }
            val json = JSONObject(sb.toString().trim())
            json.optJSONObject("error")?.let { err ->
                throw IOException(err.optString("message", "خطأ من كودي"))
            }
            json
        }
    }

    private suspend fun callHttp(httpPort: Int, body: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("http://$host:$httpPort/jsonrpc")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("كلمة مرور أو اسم مستخدم كودي غير صحيح")
            if (!resp.isSuccessful) throw IOException("تعذر الاتصال بكودي (HTTP ${resp.code})")
            val json = JSONObject(resp.body?.string().orEmpty())
            json.optJSONObject("error")?.let { err ->
                throw IOException(err.optString("message", "خطأ من كودي"))
            }
            json
        }
    }

    /** Automatically tries WebSocket 9090 (webOS default), TCP 9090, and HTTP fallback. */
    private suspend fun call(method: String, params: JSONObject? = null): JSONObject {
        val id = reqIds.getAndIncrement()
        val bodyObj = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) bodyObj.put("params", params)
        val body = bodyObj.toString()

        val wsPort = if (port == 8080) 9090 else port
        var lastErr: Throwable? = null

        // 1. Try WebSocket JSON-RPC (standard on LG webOS Kodi, port 9090, no password needed)
        runCatching { return callWebSocket(wsPort, body, id) }.onFailure { lastErr = it }
        // 2. Try raw TCP JSON-RPC on the same port
        runCatching { return callTcp(wsPort, body) }.onFailure { lastErr = it }
        // 3. Fallback to HTTP JSON-RPC (port 8080 or configured port)
        val httpPort = if (port == 9090) 8080 else port
        runCatching { return callHttp(httpPort, body) }.onFailure { lastErr = it }

        throw IOException(lastErr?.message ?: "تعذر الاتصال بكودي على المنفذ $wsPort")
    }

    suspend fun ping(): Boolean = runCatching {
        call("JSONRPC.Ping").optString("result") == "pong"
    }.getOrDefault(false)

    private fun resolvePosterUrl(rawArt: String?): String? {
        val raw = rawArt?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            return raw.replace("/t/p/original/", "/t/p/w342/")
        }
        if (raw.startsWith("image://", true)) {
            val inner = raw.removePrefix("image://").trimEnd('/')
            val decoded = runCatching { URLDecoder.decode(inner, "UTF-8") }.getOrDefault(inner)
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                return decoded.replace("/t/p/original/", "/t/p/w342/")
            }
            // Local Kodi image path (only works if HTTP 8080 is also active)
            if (port == 8080) {
                return "http://$host:8080/image/${URLEncoder.encode(raw, "UTF-8")}"
            }
        }
        return null
    }

    suspend fun getMovies(): List<KodiMovie> {
        val params = JSONObject()
            .put("properties", JSONArray(listOf("title", "year", "rating", "runtime", "art", "file")))
            .put(
                "sort",
                JSONObject()
                    .put("order", "ascending")
                    .put("method", "label")
                    .put("ignorearticle", true)
            )
        val result = call("VideoLibrary.GetMovies", params).optJSONObject("result") ?: return emptyList()
        val arr = result.optJSONArray("movies") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optInt("movieid", -1)
            if (id < 0) return@mapNotNull null
            val title = m.optString("title").ifBlank { m.optString("label", "فيلم #$id") }
            val art = m.optJSONObject("art")
            val rawPoster = art?.optString("poster")?.takeIf { it.isNotBlank() }
                ?: art?.optString("thumb")?.takeIf { it.isNotBlank() }
            KodiMovie(
                movieId = id,
                title = title,
                year = m.optInt("year", 0),
                rating = m.optDouble("rating", 0.0),
                runtimeSec = m.optInt("runtime", 0),
                posterUrl = resolvePosterUrl(rawPoster),
                file = m.optString("file", "")
            )
        }
    }

    suspend fun playMovie(movieId: Int, filePath: String? = null) {
        try {
            call("Player.Open", JSONObject().put("item", JSONObject().put("movieid", movieId)))
        } catch (e: Exception) {
            if (!filePath.isNullOrBlank()) {
                call("Player.Open", JSONObject().put("item", JSONObject().put("file", filePath)))
            } else {
                throw e
            }
        }
    }

    companion object {
        fun serializeMovies(movies: List<KodiMovie>): String {
            val arr = JSONArray()
            movies.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("movieId", m.movieId)
                        .put("title", m.title)
                        .put("year", m.year)
                        .put("rating", m.rating)
                        .put("runtimeSec", m.runtimeSec)
                        .put("posterUrl", m.posterUrl ?: "")
                        .put("file", m.file)
                )
            }
            return arr.toString()
        }

        fun deserializeMovies(raw: String?): List<KodiMovie> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    KodiMovie(
                        movieId = o.optInt("movieId", -1),
                        title = o.optString("title", ""),
                        year = o.optInt("year", 0),
                        rating = o.optDouble("rating", 0.0),
                        runtimeSec = o.optInt("runtimeSec", 0),
                        posterUrl = o.optString("posterUrl").takeIf { it.isNotBlank() },
                        file = o.optString("file", "")
                    ).takeIf { it.movieId >= 0 && it.title.isNotBlank() }
                }
            }.getOrDefault(emptyList())
        }
    }
}
