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
    val originalTitle: String,
    val year: Int,
    val rating: Double,
    val runtimeSec: Int,
    val posterUrl: String?,
    val imdbId: String?,
    val file: String
)

data class KodiShow(
    val tvshowId: Int,
    val title: String,
    val originalTitle: String,
    val year: Int,
    val seasonCount: Int,
    val episodeCount: Int,
    val posterUrl: String?,
    val imdbId: String?
)

data class KodiEpisode(
    val episodeId: Int,
    val tvshowId: Int,
    val season: Int,
    val episode: Int,
    val title: String,
    val runtimeSec: Int,
    val playcount: Int,
    val file: String
)

/**
 * Kodi JSON-RPC client for LG webOS (WebSocket/TCP port 9090, no auth required).
 * Supports Movies, TV Shows, Episodes, direct playback, and Metahub/Cinemeta poster resolution.
 */
class KodiClient(
    private val host: String,
    private val port: Int = 9090
) {
    private val reqIds = AtomicInteger(1)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

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
            val json = withTimeout(9000) { result.await() }
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
            socket.soTimeout = 9000
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
            if (!resp.isSuccessful) throw IOException("تعذر الاتصال بكودي (HTTP ${resp.code})")
            val json = JSONObject(resp.body?.string().orEmpty())
            json.optJSONObject("error")?.let { err ->
                throw IOException(err.optString("message", "خطأ من كودي"))
            }
            json
        }
    }

    private suspend fun call(method: String, params: JSONObject? = null): JSONObject {
        val id = reqIds.getAndIncrement()
        val bodyObj = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) bodyObj.put("params", params)
        val body = bodyObj.toString()

        var lastErr: Throwable? = null
        runCatching { return callWebSocket(port, body, id) }.onFailure { lastErr = it }
        runCatching { return callTcp(port, body) }.onFailure { lastErr = it }
        runCatching { return callHttp(8080, body) }.onFailure { lastErr = it }
        throw IOException(lastErr?.message ?: "تعذر الاتصال بكودي على المنفذ $port")
    }

    suspend fun ping(): Boolean = runCatching {
        call("JSONRPC.Ping").optString("result") == "pong"
    }.getOrDefault(false)

    private fun extractImdbId(obj: JSONObject): String? {
        val unique = obj.optJSONObject("uniqueid")
        val fromUnique = unique?.optString("imdb")?.trim()
            ?: unique?.optString("unknown")?.trim()
        if (!fromUnique.isNullOrBlank() && fromUnique.startsWith("tt")) return fromUnique
        val imdbNum = obj.optString("imdbnumber").trim()
        if (imdbNum.startsWith("tt")) return imdbNum
        return null
    }

    private fun resolvePosterUrl(rawArt: String?, imdbId: String?): String? {
        val raw = rawArt?.trim().orEmpty()
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            return raw.replace("/t/p/original/", "/t/p/w342/")
        }
        if (raw.startsWith("image://", true)) {
            val inner = raw.removePrefix("image://").trimEnd('/')
            val decoded = runCatching { URLDecoder.decode(inner, "UTF-8") }.getOrDefault(inner)
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                return decoded.replace("/t/p/original/", "/t/p/w342/")
            }
        }
        // Instant zero-API-key poster URL from IMDb ID via Metahub/Cinemeta CDN
        if (!imdbId.isNullOrBlank() && imdbId.startsWith("tt")) {
            return "https://images.metahub.space/poster/medium/$imdbId/img"
        }
        return null
    }

    suspend fun getMovies(): List<KodiMovie> {
        val params = JSONObject()
            .put("properties", JSONArray(listOf("title", "originaltitle", "year", "rating", "runtime", "art", "file", "uniqueid", "imdbnumber")))
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
            val originalTitle = m.optString("originaltitle").ifBlank { title }
            val imdbId = extractImdbId(m)
            val art = m.optJSONObject("art")
            val rawPoster = art?.optString("poster")?.takeIf { it.isNotBlank() }
                ?: art?.optString("thumb")?.takeIf { it.isNotBlank() }
            KodiMovie(
                movieId = id,
                title = title,
                originalTitle = originalTitle,
                year = m.optInt("year", 0),
                rating = m.optDouble("rating", 0.0),
                runtimeSec = m.optInt("runtime", 0),
                posterUrl = resolvePosterUrl(rawPoster, imdbId),
                imdbId = imdbId,
                file = m.optString("file", "")
            )
        }
    }

    suspend fun getTvShows(): List<KodiShow> {
        val params = JSONObject()
            .put("properties", JSONArray(listOf("title", "originaltitle", "year", "season", "episode", "art", "uniqueid", "imdbnumber")))
            .put(
                "sort",
                JSONObject()
                    .put("order", "ascending")
                    .put("method", "label")
                    .put("ignorearticle", true)
            )
        val result = call("VideoLibrary.GetTVShows", params).optJSONObject("result") ?: return emptyList()
        val arr = result.optJSONArray("tvshows") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = s.optInt("tvshowid", -1)
            if (id < 0) return@mapNotNull null
            val title = s.optString("title").ifBlank { s.optString("label", "مسلسل #$id") }
            val originalTitle = s.optString("originaltitle").ifBlank { title }
            val imdbId = extractImdbId(s)
            val art = s.optJSONObject("art")
            val rawPoster = art?.optString("poster")?.takeIf { it.isNotBlank() }
                ?: art?.optString("thumb")?.takeIf { it.isNotBlank() }
            KodiShow(
                tvshowId = id,
                title = title,
                originalTitle = originalTitle,
                year = s.optInt("year", 0),
                seasonCount = s.optInt("season", 0),
                episodeCount = s.optInt("episode", 0),
                posterUrl = resolvePosterUrl(rawPoster, imdbId),
                imdbId = imdbId
            )
        }
    }

    suspend fun getEpisodes(tvshowId: Int): List<KodiEpisode> {
        val params = JSONObject()
            .put("tvshowid", tvshowId)
            .put("properties", JSONArray(listOf("title", "season", "episode", "runtime", "playcount", "file")))
            .put(
                "sort",
                JSONObject()
                    .put("order", "ascending")
                    .put("method", "episode")
            )
        val result = call("VideoLibrary.GetEpisodes", params).optJSONObject("result") ?: return emptyList()
        val arr = result.optJSONArray("episodes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optInt("episodeid", -1)
            if (id < 0) return@mapNotNull null
            KodiEpisode(
                episodeId = id,
                tvshowId = tvshowId,
                season = e.optInt("season", 1),
                episode = e.optInt("episode", 0),
                title = e.optString("title").ifBlank { e.optString("label", "حلقة ${e.optInt("episode", 0)}") },
                runtimeSec = e.optInt("runtime", 0),
                playcount = e.optInt("playcount", 0),
                file = e.optString("file", "")
            )
        }.sortedWith(compareBy<KodiEpisode> { it.season }.thenBy { it.episode })
    }

    suspend fun playMovie(movieId: Int, filePath: String? = null) {
        try {
            call("Player.Open", JSONObject().put("item", JSONObject().put("movieid", movieId)))
        } catch (e: Exception) {
            if (!filePath.isNullOrBlank()) {
                call("Player.Open", JSONObject().put("item", JSONObject().put("file", filePath)))
            } else throw e
        }
    }

    suspend fun playEpisode(episodeId: Int, filePath: String? = null) {
        try {
            call("Player.Open", JSONObject().put("item", JSONObject().put("episodeid", episodeId)))
        } catch (e: Exception) {
            if (!filePath.isNullOrBlank()) {
                call("Player.Open", JSONObject().put("item", JSONObject().put("file", filePath)))
            } else throw e
        }
    }

    companion object {
        private val posterHttp = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()

        /** Fallback poster lookup by title via public Cinemeta catalog when IMDb ID is absent. */
        suspend fun searchPosterByTitle(title: String, isSeries: Boolean): String? = withContext(Dispatchers.IO) {
            val clean = title.trim()
            if (clean.isEmpty()) return@withContext null
            val type = if (isSeries) "series" else "movie"
            val encoded = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
            val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
            runCatching {
                val req = Request.Builder().url(url).build()
                posterHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val metas = JSONObject(resp.body?.string().orEmpty()).optJSONArray("metas") ?: return@use null
                    if (metas.length() == 0) return@use null
                    val first = metas.optJSONObject(0) ?: return@use null
                    first.optString("poster").takeIf { it.startsWith("http") }
                        ?: first.optString("id").takeIf { it.startsWith("tt") }?.let {
                            "https://images.metahub.space/poster/medium/$it/img"
                        }
                }
            }.getOrNull()
        }

        fun formatRuntime(seconds: Int): String {
            if (seconds <= 0) return ""
            val totalMins = seconds / 60
            val h = totalMins / 60
            val m = totalMins % 60
            return when {
                h > 0 && m > 0 -> "${h}س ${m}د"
                h > 0 -> "${h}س"
                totalMins > 0 -> "${totalMins}د"
                else -> ""
            }
        }

        fun serializeMovies(movies: List<KodiMovie>): String {
            val arr = JSONArray()
            movies.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("movieId", m.movieId)
                        .put("title", m.title)
                        .put("originalTitle", m.originalTitle)
                        .put("year", m.year)
                        .put("rating", m.rating)
                        .put("runtimeSec", m.runtimeSec)
                        .put("posterUrl", m.posterUrl ?: "")
                        .put("imdbId", m.imdbId ?: "")
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
                        originalTitle = o.optString("originalTitle").ifBlank { o.optString("title", "") },
                        year = o.optInt("year", 0),
                        rating = o.optDouble("rating", 0.0),
                        runtimeSec = o.optInt("runtimeSec", 0),
                        posterUrl = o.optString("posterUrl").takeIf { it.isNotBlank() },
                        imdbId = o.optString("imdbId").takeIf { it.isNotBlank() },
                        file = o.optString("file", "")
                    ).takeIf { it.movieId >= 0 && it.title.isNotBlank() }
                }
            }.getOrDefault(emptyList())
        }

        fun serializeShows(shows: List<KodiShow>): String {
            val arr = JSONArray()
            shows.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("tvshowId", s.tvshowId)
                        .put("title", s.title)
                        .put("originalTitle", s.originalTitle)
                        .put("year", s.year)
                        .put("seasonCount", s.seasonCount)
                        .put("episodeCount", s.episodeCount)
                        .put("posterUrl", s.posterUrl ?: "")
                        .put("imdbId", s.imdbId ?: "")
                )
            }
            return arr.toString()
        }

        fun deserializeShows(raw: String?): List<KodiShow> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    KodiShow(
                        tvshowId = o.optInt("tvshowId", -1),
                        title = o.optString("title", ""),
                        originalTitle = o.optString("originalTitle").ifBlank { o.optString("title", "") },
                        year = o.optInt("year", 0),
                        seasonCount = o.optInt("seasonCount", 0),
                        episodeCount = o.optInt("episodeCount", 0),
                        posterUrl = o.optString("posterUrl").takeIf { it.isNotBlank() },
                        imdbId = o.optString("imdbId").takeIf { it.isNotBlank() }
                    ).takeIf { it.tvshowId >= 0 && it.title.isNotBlank() }
                }
            }.getOrDefault(emptyList())
        }
    }
}
