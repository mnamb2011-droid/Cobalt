package com.mnasser.cobaltremote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class KodiMovie(
    val movieId: Int,
    val title: String,
    val year: Int,
    val rating: Double,
    val runtimeSec: Int,
    val posterUrl: String?,
    val file: String
)

/** Kodi JSON-RPC HTTP client for listing movies, loading posters, and one-tap movie playback. */
class KodiClient(
    private val host: String,
    private val port: Int = 8080,
    private val username: String = "kodi",
    private val password: String = ""
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "http://$host:$port"
    private val authHeader: String? = if (username.isNotBlank() || password.isNotBlank()) {
        Credentials.basic(username, password)
    } else null

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = if (authHeader != null) {
                chain.request().newBuilder().header("Authorization", authHeader).build()
            } else {
                chain.request()
            }
            chain.proceed(req)
        }
        .build()

    fun imageHttpClient(): OkHttpClient = http

    private suspend fun call(method: String, params: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val bodyObj = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
        if (params != null) bodyObj.put("params", params)

        val req = Request.Builder()
            .url("$baseUrl/jsonrpc")
            .post(bodyObj.toString().toRequestBody(jsonMediaType))
            .build()

        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("كلمة مرور أو اسم مستخدم كودي غير صحيح")
            if (!resp.isSuccessful) throw IOException("تعذر الاتصال بكودي (HTTP ${resp.code})")
            val raw = resp.body?.string().orEmpty()
            val json = JSONObject(raw)
            json.optJSONObject("error")?.let { err ->
                throw IOException(err.optString("message", "خطأ من كودي"))
            }
            json
        }
    }

    suspend fun ping(): Boolean = runCatching {
        call("JSONRPC.Ping").optString("result") == "pong"
    }.getOrDefault(false)

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
            val posterUrl = rawPoster?.let {
                "$baseUrl/image/${URLEncoder.encode(it, "UTF-8")}"
            }
            KodiMovie(
                movieId = id,
                title = title,
                year = m.optInt("year", 0),
                rating = m.optDouble("rating", 0.0),
                runtimeSec = m.optInt("runtime", 0),
                posterUrl = posterUrl,
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
