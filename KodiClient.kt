package com.mnasser.cobaltremote

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
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

data class PhoneStreamResult(
    val url: String?,
    val diagnostic: String
)

/**
 * Kodi JSON-RPC client for LG webOS (WebSocket/TCP port 9090, no auth required).
 * Supports Movies, TV Shows, Episodes, seek-to-timestamp, Metahub/Cinemeta posters,
 * and UPnP/DLNA ContentDirectory Browse + Root-walk stream resolution for phone playback.
 */
class KodiClient(
    private val host: String,
    private val port: Int = 9090
) {
    private val reqIds = AtomicInteger(1)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val xmlMediaType = "text/xml; charset=utf-8".toMediaType()
    @Volatile private var cachedContentDirControlUrl: String? = null

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
            .put("sort", JSONObject().put("order", "ascending").put("method", "episode"))
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

    suspend fun playMovie(movieId: Int, filePath: String? = null, startAtSec: Int = 0) {
        try {
            call("Player.Open", JSONObject().put("item", JSONObject().put("movieid", movieId)))
        } catch (e: Exception) {
            if (!filePath.isNullOrBlank()) {
                call("Player.Open", JSONObject().put("item", JSONObject().put("file", filePath)))
            } else throw e
        }
        if (startAtSec > 0) {
            seekToSeconds(startAtSec)
        }
    }

    suspend fun seekToSeconds(seconds: Int) {
        if (seconds <= 0) return
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        repeat(6) {
            delay(900)
            val active = runCatching { call("Player.GetActivePlayers").optJSONArray("result") }.getOrNull()
            val playerId = active?.optJSONObject(0)?.optInt("playerid", -1) ?: -1
            if (playerId >= 0) {
                val timeObj = JSONObject().put("hours", h).put("minutes", m).put("seconds", s)
                val seekParams = JSONObject()
                    .put("playerid", playerId)
                    .put("value", JSONObject().put("time", timeObj))
                runCatching { call("Player.Seek", seekParams) }
                return
            }
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

    /**
     * Resolves an HTTP video stream URL for playing a Kodi movie/episode on the Android phone.
     * Sends both Multicast + direct Unicast SSDP to TV IP, walks UPnP containers from root if needed,
     * and returns a precise Arabic diagnostic if Kodi's UPnP server is unreachable.
     */
    suspend fun resolvePhoneStreamUrl(
        context: Context,
        title: String,
        filePath: String,
        movieId: Int? = null,
        episodeId: Int? = null
    ): PhoneStreamResult = withContext(Dispatchers.IO) {
        val cleanPath = filePath.trim()
        if (cleanPath.startsWith("http://", true) || cleanPath.startsWith("https://", true)) {
            return@withContext PhoneStreamResult(cleanPath, "رابط مباشر")
        }

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching {
            wifi?.createMulticastLock("kodi_upnp")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        try {
            val (controlUrl, foundLocs) = cachedContentDirControlUrl?.let { it to 1 }
                ?: discoverKodiContentDirectoryUrl()
            if (controlUrl != null) {
                cachedContentDirControlUrl = controlUrl

                // 1. Direct BrowseMetadata using Kodi's Platinum UPnP ObjectIDs
                val directIds = buildList {
                    if (movieId != null) add("videodb://movies/titles/$movieId")
                    if (episodeId != null) add("videodb://tvshows/titles/-1/-1/$episodeId")
                }
                for (objId in directIds) {
                    upnpBrowse(controlUrl, objId, "BrowseMetadata", 1)?.let { xml ->
                        extractFirstHttpRes(xml)?.let { return@withContext PhoneStreamResult(it, "UPnP مباشر") }
                    }
                }

                // 2. BrowseDirectChildren on movies container
                if (movieId != null) {
                    upnpBrowse(controlUrl, "videodb://movies/titles/", "BrowseDirectChildren", 500)?.let { xml ->
                        findMatchingItemRes(xml, title, cleanPath)?.let { return@withContext PhoneStreamResult(it, "UPnP قائمة الأفلام") }
                    }
                }

                // 3. Walk from root container "0" -> Video Library -> Movies/Titles
                walkUpnpFromRoot(controlUrl, title, cleanPath, isEpisode = episodeId != null)?.let {
                    return@withContext PhoneStreamResult(it, "UPnP جذر المكتبة")
                }

                // 4. Fallback: UPnP Search
                upnpSearch(controlUrl, title)?.let { xml ->
                    extractFirstHttpRes(xml)?.let { return@withContext PhoneStreamResult(it, "UPnP بحث") }
                }

                return@withContext PhoneStreamResult(
                    null,
                    "تم الاتصال بخادم UPnP لكودي لكن تعذر إيجاد رابط البث لهذا الملف"
                )
            } else {
                // Fallback: HTTP VFS :8080 if enabled
                if (cleanPath.isNotEmpty()) {
                    val vfsUrl = "http://$host:8080/vfs/${URLEncoder.encode(cleanPath, "UTF-8")}"
                    val ok = runCatching {
                        http.newCall(Request.Builder().url(vfsUrl).head().build()).execute().use { it.isSuccessful }
                    }.getOrDefault(false)
                    if (ok) return@withContext PhoneStreamResult(vfsUrl, "HTTP VFS")
                }
                val diag = if (foundLocs == 0) {
                    "لم يرد خادم UPnP من $host — أعد تشغيل كودي بعد تفعيل (تفعيل دعم UPnP + مشاركة المكتبات الخاصة بي)"
                } else {
                    "وُجد $foundLocs جهاز UPnP على $host بدون خدمة مشاركة مكتبة كودي (أغلق كودي وأعد فتحه بعد التفعيل)"
                }
                return@withContext PhoneStreamResult(null, diag)
            }
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun discoverKodiContentDirectoryUrl(): Pair<String?, Int> {
        val locations = LinkedHashSet<String>()
        val queries = listOf(
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:service:ContentDirectory:1\r\n\r\n",
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n",
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n"
        )
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 1600
                val group = InetAddress.getByName("239.255.255.250")
                val tvAddr = runCatching { InetAddress.getByName(host) }.getOrNull()
                queries.forEach { q ->
                    val b = q.toByteArray()
                    socket.send(DatagramPacket(b, b.size, group, 1900))
                    // Direct unicast M-SEARCH to TV IP bypasses router multicast filtering!
                    if (tvAddr != null) {
                        runCatching { socket.send(DatagramPacket(b, b.size, tvAddr, 1900)) }
                    }
                }
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 2600
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        val loc = Regex("""(?im)^LOCATION:\s*(\S+)""").find(msg)?.groupValues?.getOrNull(1)?.trim()
                        if (loc != null && (packet.address?.hostAddress == host || loc.contains(host))) {
                            locations.add(loc)
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
        for (loc in locations) {
            fetchContentDirectoryControlUrl(loc)?.let { return it to locations.size }
        }
        return null to locations.size
    }

    private fun walkUpnpFromRoot(controlUrl: String, title: String, filePath: String, isEpisode: Boolean): String? {
        val rootXml = upnpBrowse(controlUrl, "0", "BrowseDirectChildren", 30) ?: return null
        val rootContainers = parseContainerIds(rootXml)
        // Look for Video Library container (often "library://video/" or similar)
        val videoIds = rootContainers.filter { (id, lbl) ->
            id.contains("video", true) || lbl.contains("video", true) || lbl.contains("فيديو") || lbl.contains("أفلام")
        }.map { it.first }.ifEmpty { rootContainers.map { it.first } }

        for (vidId in videoIds.take(4)) {
            val lvl1 = upnpBrowse(controlUrl, vidId, "BrowseDirectChildren", 30) ?: continue
            findMatchingItemRes(lvl1, title, filePath)?.let { return it }
            val subContainers = parseContainerIds(lvl1).filter { (id, lbl) ->
                if (isEpisode) id.contains("tvshow", true) || lbl.contains("مسلسل") || lbl.contains("tv", true)
                else id.contains("movie", true) || lbl.contains("فيلم") || lbl.contains("أفلام") || lbl.contains("title", true)
            }
            for ((subId, _) in subContainers.take(3)) {
                val lvl2 = upnpBrowse(controlUrl, subId, "BrowseDirectChildren", 500) ?: continue
                findMatchingItemRes(lvl2, title, filePath)?.let { return it }
                val titleContainers = parseContainerIds(lvl2).filter { (id, lbl) ->
                    id.contains("title", true) || lbl.contains("عناوين") || lbl.contains("title", true)
                }
                for ((tId, _) in titleContainers.take(2)) {
                    val lvl3 = upnpBrowse(controlUrl, tId, "BrowseDirectChildren", 500) ?: continue
                    findMatchingItemRes(lvl3, title, filePath)?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseContainerIds(rawXml: String): List<Pair<String, String>> {
        val didl = unescapeDidl(rawXml)
        val regex = Regex("""<container\b[^>]*\bid="([^"]+)"[^>]*>[\s\S]*?<dc:title>([^<]*)</dc:title>""", RegexOption.IGNORE_CASE)
        return regex.findAll(didl).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    private fun fetchContentDirectoryControlUrl(locationUrl: String): String? = runCatching {
        val xml = http.newCall(Request.Builder().url(locationUrl).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        } ?: return null
        val serviceBlock = Regex(
            """<service>[\s\S]*?ContentDirectory:1[\s\S]*?</service>""",
            RegexOption.IGNORE_CASE
        ).find(xml)?.value ?: return null
        val rel = Regex("""<controlURL>([^<]+)</controlURL>""", RegexOption.IGNORE_CASE)
            .find(serviceBlock)?.groupValues?.getOrNull(1)?.trim() ?: return null
        URL(URL(locationUrl), rel).toString()
    }.getOrNull()

    private fun upnpBrowse(controlUrl: String, objectId: String, browseFlag: String, count: Int): String? {
        val escapedId = objectId.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val soap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                  <ObjectID>$escapedId</ObjectID>
                  <BrowseFlag>$browseFlag</BrowseFlag>
                  <Filter>*</Filter>
                  <StartingIndex>0</StartingIndex>
                  <RequestedCount>$count</RequestedCount>
                  <SortCriteria></SortCriteria>
                </u:Browse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        return runCatching {
            val req = Request.Builder()
                .url(controlUrl)
                .header("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
                .post(soap.toRequestBody(xmlMediaType))
                .build()
            http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }

    private fun upnpSearch(controlUrl: String, title: String): String? {
        val escapedTitle = title.replace("\"", "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val soap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Search xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                  <ContainerID>0</ContainerID>
                  <SearchCriteria>dc:title contains "$escapedTitle"</SearchCriteria>
                  <Filter>*</Filter>
                  <StartingIndex>0</StartingIndex>
                  <RequestedCount>5</RequestedCount>
                  <SortCriteria></SortCriteria>
                </u:Search>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        return runCatching {
            val req = Request.Builder()
                .url(controlUrl)
                .header("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Search\"")
                .post(soap.toRequestBody(xmlMediaType))
                .build()
            http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }

    private fun unescapeDidl(xml: String): String = xml
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun extractFirstHttpRes(rawXml: String): String? {
        val didl = unescapeDidl(rawXml)
        val resRegex = Regex("""<res[^>]*>(https?://[^<]+)</res>""", RegexOption.IGNORE_CASE)
        return resRegex.findAll(didl)
            .map { it.groupValues[1].trim() }
            .firstOrNull { !it.endsWith(".jpg", true) && !it.endsWith(".png", true) && !it.endsWith(".srt", true) }
            ?: resRegex.find(didl)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun findMatchingItemRes(rawXml: String, title: String, filePath: String): String? {
        val didl = unescapeDidl(rawXml)
        val itemRegex = Regex("""<item\b[\s\S]*?</item>""", RegexOption.IGNORE_CASE)
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val targetTitle = title.trim().lowercase()
        for (m in itemRegex.findAll(didl)) {
            val block = m.value
            val itemTitle = Regex("""<dc:title>([^<]+)</dc:title>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.getOrNull(1)?.trim()?.lowercase().orEmpty()
            if (itemTitle == targetTitle || (targetTitle.isNotEmpty() && itemTitle.contains(targetTitle)) ||
                (fileName.length > 3 && block.contains(fileName, true))
            ) {
                extractFirstHttpRes(block)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private val posterHttp = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()

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
