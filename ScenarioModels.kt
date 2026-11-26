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
    val target: String, // videoId/URL, movieId, HDMI inputId/appId, or appId
    val extra: String = "", // optional movie file path or HDMI appId fallback
    val durationMinutes: Int = 15
)

object ScenarioStore {
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
                    durationMinutes = o.optInt("durationMinutes", 15).coerceIn(1, 360)
                )
            }
        }.getOrDefault(emptyList())
    }
}
