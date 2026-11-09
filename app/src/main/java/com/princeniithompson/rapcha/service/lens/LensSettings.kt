package com.princeniithompson.rapcha.service.lens

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TonePreset(
    val name: String,
    val emoji: String,
    val instruction: String
)

object LensSettings {
    const val ENABLED_KEY = "lens_enabled"
    const val TONES_KEY = "lens_tones"

    val DEFAULT_TONES = listOf(
        TonePreset("Friendly", "😊", "Warm, kind, and easy to read."),
        TonePreset("Professional", "💼", "Clear and professional, without stiffness."),
        TonePreset("Casual", "✌️", "Relaxed everyday wording.")
    )

    fun enabled(prefs: SharedPreferences): Boolean {
        if (!prefs.contains(ENABLED_KEY)) return true
        return prefs.getBoolean(ENABLED_KEY, true)
    }

    fun writeEnabled(prefs: SharedPreferences, value: Boolean) {
        prefs.edit().putBoolean(ENABLED_KEY, value).apply()
    }

    fun tones(prefs: SharedPreferences): List<TonePreset> {
        val raw = prefs.getString(TONES_KEY, null) ?: return DEFAULT_TONES
        return parse(raw).ifEmpty { DEFAULT_TONES }
    }

    fun writeTones(prefs: SharedPreferences, tones: List<TonePreset>) {
        val array = JSONArray()
        tones.forEach { tone ->
            array.put(JSONObject().apply {
                put("name", tone.name)
                put("emoji", tone.emoji)
                put("instruction", tone.instruction)
            })
        }
        prefs.edit().putString(TONES_KEY, array.toString()).apply()
    }

    fun parse(raw: String): List<TonePreset> {
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    if (name.isEmpty()) continue
                    add(
                        TonePreset(
                            name = name,
                            emoji = obj.optString("emoji"),
                            instruction = obj.optString("instruction")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
