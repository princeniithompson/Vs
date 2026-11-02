package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.config.RapchaConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini Live text turn on BidiGenerateContent.
 *
 * The transcription socket sends audio on realtimeInput and reads inputTranscription.
 * Stage 2 is text-only, so setup asks for TEXT, omits inputAudioTranscription, and the
 * user turn is clientContent. Model text is read from serverContent.modelTurn.parts[].text
 * until turnComplete. outputTranscription.text is also accepted if a Live model emits
 * the reply there instead of modelTurn.
 */
object LivePolishProtocol {
    const val MODEL: String = RapchaConfig.STAGE2_LIVE_MODEL

    fun setupMessage(systemInstruction: String, model: String = MODEL): String {
        return JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("TEXT"))
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
    var setupComplete: Boolean = false
        private set
    var turnComplete: Boolean = false
        private set
    var error: String? = null
        private set

    fun text(): String = PolishOutputCleaner.clean(text.toString())

    fun absorb(rawJson: String): LivePolishSignal {
        if (turnComplete) return LivePolishSignal.IGNORE
        val json = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            error = "Unreadable Live frame: ${e.message}"
            turnComplete = true
            return LivePolishSignal.ERROR
        }
        if (json.has("setupComplete")) {
            setupComplete = true
            return LivePolishSignal.SETUP_COMPLETE
        }
        val errorObj = json.optJSONObject("error")
        if (errorObj != null || json.has("error")) {
            val message = errorObj?.optString("message").orEmpty().ifBlank { json.optString("error") }
            error = message.ifBlank { "Gemini Live rejected the text turn" }
            turnComplete = true
            return LivePolishSignal.ERROR
        }
        val serverContent = json.optJSONObject("serverContent") ?: return LivePolishSignal.IGNORE
        appendModelText(serverContent)
        val output = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty()
        if (output.isNotEmpty()) {
            text.append(output)
        }
        val done = serverContent.optBoolean("turnComplete", false) ||
            serverContent.optBoolean("generationComplete", false) ||
            serverContent.optBoolean("interrupted", false)
        return if (done) {
            turnComplete = true
            LivePolishSignal.TURN_COMPLETE
        } else if (text.isNotEmpty()) {
            LivePolishSignal.TEXT
        } else {
            LivePolishSignal.IGNORE
        }
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
