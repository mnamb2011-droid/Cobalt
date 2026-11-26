package com.mnasser.cobaltremote

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ScenarioActionType(val labelAr: String) {
    YOUTUBE("يوتيوب (كوبالت)"),
    KODI_MOVIE("فيلم كودي"),
    HDMI("مدخل HDMI / تلفاز"),
    TV_APP("تطبيق تلفزيون"),
    POWER_OFF("إيقاف التلفزيون")
}

data class ScenarioStep(
    val id: String = UUID.randomUUID().toString(),
    val type: ScenarioActionType,
    val title: String,
    val target: String,
    val extra: String = "",
    val durationMinutes: Int = 10,
    val startAtSec: Int = 0,
    val videoDurationSec: Int = 0,
    val playFullVideo: Boolean = false,
    val autoResume: Boolean = true
)

object ScenarioStore {
    fun parseDurationTextToSec(text: String): Int {
        val parts = text.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    fun formatTimestamp(totalSec: Int): String {
        if (totalSec <= 0) return "00:00"
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun serialize(steps: List<ScenarioStep>): String {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("type", s.type.name)
                    .put("title", s.title)
                    .put("target", s.target)
                    .put("extra", s.extra)
                    .put("durationMinutes", s.durationMinutes)
                    .put("startAtSec", s.startAtSec)
                    .put("videoDurationSec", s.videoDurationSec)
                    .put("playFullVideo", s.playFullVideo)
                    .put("autoResume", s.autoResume)
            )
        }
        return arr.toString()
    }

    fun deserialize(raw: String?): List<ScenarioStep> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = runCatching { ScenarioActionType.valueOf(o.optString("type")) }.getOrNull()
                    ?: return@mapNotNull null
                ScenarioStep(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    type = type,
                    title = o.optString("title", type.labelAr),
                    target = o.optString("target", ""),
                    extra = o.optString("extra", ""),
                    durationMinutes = o.optInt("durationMinutes", 10).coerceIn(1, 360),
                    startAtSec = o.optInt("startAtSec", 0).coerceAtLeast(0),
                    videoDurationSec = o.optInt("videoDurationSec", 0).coerceAtLeast(0),
                    playFullVideo = o.optBoolean("playFullVideo", false),
                    autoResume = o.optBoolean("autoResume", true)
                )
            }
        }.getOrDefault(emptyList())
    }
}
