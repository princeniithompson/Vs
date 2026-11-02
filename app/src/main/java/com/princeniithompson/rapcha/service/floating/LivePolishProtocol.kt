package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.config.RapchaConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * gemini-3.8-live only emits audio. Text polish reads outputAudioTranscription,
 * which arrives as serverContent.outputTranscription while the model speaks.
 * Asking for responseModalities TEXT is accepted at setup and then stays silent.
 */
object LivePolishProtocol {
    const val MODEL: String = RapchaConfig.STAGE2_LIVE_MODEL

    fun setupMessage(systemInstruction: String, model: String = MODEL): String {
        return JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("responseModalities", JSONArray().put("AUDIO"))
                put("outputAudioTranscription", JSONObject())
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                })
            })
        }.toString()
    }

    fun clientContentMessage(userText: String): String {
        return JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userText)))
                }))
                put("turnComplete", true)
            })
        }.toString()
    }
}

enum class LivePolishSignal {
    SETUP_COMPLETE,
    TEXT,
    TURN_COMPLETE,
    ERROR,
    IGNORE
}

class LivePolishTranscriptAssembler {
    private val text = StringBuilder()
    private val frames = ArrayList<String>()
    var setupComplete: Boolean = false
        private set
    var turnComplete: Boolean = false
        private set
    var error: String? = null
        private set

    fun text(): String = PolishOutputCleaner.clean(text.toString())

    fun trace(): String = frames.joinToString("|").take(420)

    fun absorb(rawJson: String): LivePolishSignal {
        if (turnComplete) return LivePolishSignal.IGNORE
        val json = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            error = "Unreadable Live frame: ${e.message}"
            note("bad_json")
            turnComplete = true
            return LivePolishSignal.ERROR
        }
        if (json.has("setupComplete")) {
            setupComplete = true
            note("setup")
            return LivePolishSignal.SETUP_COMPLETE
        }
        val errorObj = json.optJSONObject("error")
        if (errorObj != null || json.has("error")) {
            val message = errorObj?.optString("message").orEmpty().ifBlank { json.optString("error") }
            error = message.ifBlank { "Gemini Live rejected the text turn" }
            note("error")
            turnComplete = true
            return LivePolishSignal.ERROR
        }
        val serverContent = json.optJSONObject("serverContent") ?: run {
            note("keys:${json.keys().asSequence().take(4).joinToString("+")}")
            return LivePolishSignal.IGNORE
        }
        if (containsAudio(serverContent)) note("audio")
        appendModelText(serverContent)
        val output = transcriptionText(serverContent)
        if (output.isNotEmpty()) {
            text.append(output)
            note("out:${output.length}")
        }
        val done = serverContent.optBoolean("turnComplete", false) ||
            serverContent.optBoolean("generationComplete", false) ||
            serverContent.optBoolean("interrupted", false)
        return if (done) {
            turnComplete = true
            note("turn")
            LivePolishSignal.TURN_COMPLETE
        } else if (text.isNotEmpty()) {
            LivePolishSignal.TEXT
        } else {
            LivePolishSignal.IGNORE
        }
    }

    private fun note(kind: String) {
        if (frames.size < 16) frames.add(kind)
    }

    private fun transcriptionText(serverContent: JSONObject): String {
        val obj = serverContent.optJSONObject("outputTranscription")
            ?: serverContent.optJSONObject("output_transcription")
        return obj?.optString("text").orEmpty()
    }

    private fun containsAudio(serverContent: JSONObject): Boolean {
        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return false
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.has("inlineData") || part.has("inline_data")) return true
        }
        return false
    }

    private fun appendModelText(serverContent: JSONObject) {
        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val chunk = part.optString("text")
            if (chunk.isNotEmpty()) text.append(chunk)
        }
    }
}
