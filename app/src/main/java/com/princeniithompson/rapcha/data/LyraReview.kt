package com.princeniithompson.rapcha.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One voice-typing session and the optional rating of a Lyra (gemini-3.8-live) result.
 * Smart-only sessions and raw fallbacks are stored here too, without a review.
 */
enum class CaptureMode { VERBATIM, SMART }

enum class SessionPipeline { TWO_STEP, THREE_STEP, SMART_ONLY, DIRECT }

enum class LyraRating { UP, DOWN, UNRATED }

enum class LyraReviewTag(val label: String, val wire: String) {
    REMOVED_WORDS("Removed words", "removed_words"),
    CHANGED_MEANING("Changed meaning", "changed_meaning"),
    ADDED_CONTENT("Added content", "added_content"),
    WRONG_FORMATTING("Wrong formatting", "wrong_formatting"),
    TOO_SHORT("Too short", "too_short"),
    OTHER("Other", "other");

    companion object {
        fun fromWire(value: String): LyraReviewTag? = entries.firstOrNull { it.wire == value }
    }
}

data class TranscriptStage(
    val role: String,
    val label: String,
    val text: String
)

data class VoiceSessionRecord(
    val sessionId: String,
    val timestamp: Long,
    val appName: String,
    val pipeline: SessionPipeline,
    val lyraProduced: Boolean,
    val aiLyra: Boolean,
    val sensitive: Boolean,
    val rawAvailable: Boolean,
    val rawNote: String?,
    val stages: List<TranscriptStage>,
    val finalText: String,
    val rating: LyraRating = LyraRating.UNRATED,
    val tags: List<LyraReviewTag> = emptyList(),
    val comment: String = ""
) {
    val reviewable: Boolean get() = LyraReviewPolicy.reviewable(this)

    fun stage(role: String): String? = stages.firstOrNull { it.role == role }?.text
}

object TextFingerprint {
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun describe(name: String, text: String): String {
        return "$name chars=${text.length} sha256=${sha256(text)}"
    }
}

object LyraHandoff {
    /** The only string Stage-2 may see is the completed Stage-1 final. */
    fun source(stage1Final: String): String = stage1Final.trim()

    fun matches(savedStage1: String, sent: String): Boolean = savedStage1 == sent
}

object LyraReviewPolicy {
    const val AUTO_HIDE_MS = 8_000L
    const val DOWN_PROMPT = "What did Lyra get wrong?"
    const val SMART_INCOMPLETE_NOTE = "Smart finalization did not complete. Lyra was not run."
    const val LYRA_MISSING_NOTE = "Lyra did not produce this text."

    fun reviewable(record: VoiceSessionRecord): Boolean {
        if (record.sensitive) return false
        if (!record.lyraProduced || !record.aiLyra) return false
        return record.pipeline == SessionPipeline.TWO_STEP || record.pipeline == SessionPipeline.THREE_STEP
    }

    fun shouldPrompt(record: VoiceSessionRecord): Boolean {
        return reviewable(record) && record.rating == LyraRating.UNRATED
    }

    /**
     * A down rating may be saved with an empty comment. The prompt asks for one
     * and does not block the rating.
     */
    fun commentRequired(rating: LyraRating): Boolean = false

    fun apply(
        record: VoiceSessionRecord,
        rating: LyraRating,
        tags: List<LyraReviewTag> = record.tags,
        comment: String? = null
    ): VoiceSessionRecord {
        if (!reviewable(record)) return record
        if (commentRequired(rating) && comment.isNullOrBlank() && record.comment.isBlank()) return record
        val nextComment = comment?.trim() ?: record.comment
        val nextTags = if (rating == LyraRating.DOWN) {
            LyraReviewTag.entries.filter { it in tags }
        } else {
            emptyList()
        }
        return record.copy(rating = rating, tags = nextTags, comment = nextComment)
    }
}

object VoiceSessionBuilder {
    /**
     * AI Live session. [rawText] is the frozen voice-container backup.
     * [smartText] is set only after the normal Smart completion gate returns a
     * real final. It is never copied from [rawText].
     */
    fun captured(
        sessionId: String,
        timestamp: Long,
        appName: String,
        threeStep: Boolean,
        rawText: String,
        smartText: String?,
        lyraText: String?,
        smartCompleted: Boolean,
        aiLyra: Boolean,
        sensitive: Boolean
    ): VoiceSessionRecord {
        val stages = ArrayList<TranscriptStage>()
        val raw = rawText.trim()
        if (raw.isNotEmpty()) stages += TranscriptStage("raw", "Raw transcript", raw)
        val smart = smartText?.trim().orEmpty()
        if (threeStep && smartCompleted && smart.isNotEmpty()) {
            stages += TranscriptStage("smart", "3.5 Smart", smart)
        }
        val lyra = lyraText?.trim().orEmpty()
        val lyraOk = aiLyra && lyra.isNotEmpty() && !sensitive && (!threeStep || smartCompleted)
        if (lyra.isNotEmpty() && !sensitive && (!threeStep || smartCompleted)) {
            stages += TranscriptStage("lyra", if (aiLyra) "Lyra 3.8 final" else "3.8 Live final", lyra)
        }
        val note = when {
            threeStep && !smartCompleted -> LyraReviewPolicy.SMART_INCOMPLETE_NOTE
            !lyraOk && smartCompleted -> LyraReviewPolicy.LYRA_MISSING_NOTE
            else -> null
        }
        val finalText = when {
            lyraOk -> lyra
            threeStep && smartCompleted && smart.isNotEmpty() -> smart
            else -> raw
        }
        return VoiceSessionRecord(
            sessionId = sessionId,
            timestamp = timestamp,
            appName = appName.ifBlank { "App" },
            pipeline = if (threeStep) SessionPipeline.THREE_STEP else SessionPipeline.TWO_STEP,
            lyraProduced = lyraOk,
            aiLyra = aiLyra,
            sensitive = sensitive,
            rawAvailable = raw.isNotEmpty(),
            rawNote = note,
            stages = stages,
            finalText = finalText
        )
    }

    fun build(
        sessionId: String,
        timestamp: Long,
        appName: String,
        stage1: CaptureMode,
        threeStep: Boolean,
        usedLiveModel: Boolean,
        aiLyra: Boolean,
        stage1Text: String,
        deliveredText: String,
        lyraText: String?,
        unaryPolishText: String?,
        sensitive: Boolean
    ): VoiceSessionRecord {
        val pipeline = when {
            threeStep -> SessionPipeline.THREE_STEP
            usedLiveModel -> SessionPipeline.TWO_STEP
            unaryPolishText != null || stage1 == CaptureMode.SMART -> SessionPipeline.SMART_ONLY
            else -> SessionPipeline.DIRECT
        }
        val stages = ArrayList<TranscriptStage>()
        val spoken = stage1Text.trim()
        if (stage1 == CaptureMode.VERBATIM && spoken.isNotEmpty()) {
            stages += TranscriptStage("raw", "Raw transcript", spoken)
        } else if (stage1 == CaptureMode.SMART && spoken.isNotEmpty()) {
            stages += TranscriptStage("smart", "3.5 Smart transcript", spoken)
        }
        val unary = unaryPolishText?.trim().orEmpty()
        if (!usedLiveModel && unary.isNotEmpty()) {
            stages += TranscriptStage("smart_polish", "Smart polish", unary)
        }
        val lyra = lyraText?.trim().orEmpty()
        val lyraProduced = usedLiveModel && aiLyra && lyra.isNotEmpty()
        if (usedLiveModel && lyra.isNotEmpty()) {
            val label = if (aiLyra) "Lyra 3.8 final" else "3.8 Live final"
            stages += TranscriptStage("lyra", label, lyra)
        }
        val rawNote = if (usedLiveModel && lyra.isEmpty()) LyraReviewPolicy.LYRA_MISSING_NOTE else null
        val finalText = deliveredText.trim().ifBlank { spoken }
        return VoiceSessionRecord(
            sessionId = sessionId,
            timestamp = timestamp,
            appName = appName.ifBlank { "App" },
            pipeline = pipeline,
            lyraProduced = lyraProduced,
            aiLyra = aiLyra && usedLiveModel,
            sensitive = sensitive,
            rawAvailable = stages.any { it.role == "raw" },
            rawNote = rawNote,
            stages = stages,
            finalText = finalText
        )
    }
}

object LyraReviewExport {
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun section(records: List<VoiceSessionRecord>): String {
        val included = records.filter { LyraReviewPolicy.reviewable(it) }
            .sortedBy { it.timestamp }
        val up = included.count { it.rating == LyraRating.UP }
        val down = included.count { it.rating == LyraRating.DOWN }
        val unrated = included.count { it.rating == LyraRating.UNRATED }
        return buildString {
            append("----------------------------------------------------\n")
            append("                     LYRA_REVIEW                     \n")
            append("----------------------------------------------------\n")
            append("up=$up down=$down unrated=$unrated\n")
            if (included.isEmpty()) {
                append("(No Lyra sessions)\n\n")
                return@buildString
            }
            included.forEach { record ->
                append("\n")
                append(readable(record))
                append("JSON ")
                append(jsonLine(record))
                append('\n')
            }
            append('\n')
        }
    }

    fun readable(record: VoiceSessionRecord): String {
        return buildString {
            append("session_id: ${record.sessionId}\n")
            append("time: ${timeFormat.format(Date(record.timestamp))}\n")
            append("app: ${record.appName}\n")
            append("mode: ${mode(record.pipeline)}\n")
            append("rating: ${record.rating.name.lowercase()}\n")
            append("tags: ${record.tags.joinToString(",") { it.wire }}\n")
            append("comment: ${record.comment}\n")
            append("raw_available: ${if (record.rawAvailable) "yes" else "no"}\n")
            if (!record.rawNote.isNullOrBlank()) append("raw_note: ${record.rawNote}\n")
            record.stages.forEach { stage ->
                append("${stage.label}:\n")
                append(stage.text)
                append("\n")
            }
        }
    }

    fun jsonLine(record: VoiceSessionRecord): String {
        val raw = record.stage("raw")
        val smart = record.stage("smart")
        val lyra = record.stage("lyra")
        val obj = JSONObject()
        obj.put("session_id", record.sessionId)
        obj.put("timestamp_ms", record.timestamp)
        obj.put("app", record.appName)
        obj.put("mode", mode(record.pipeline))
        obj.put("rating", record.rating.name.lowercase())
        obj.put("tags", JSONArray().apply { record.tags.forEach { put(it.wire) } })
        obj.put("comment", record.comment)
        obj.put("raw_available", record.rawAvailable)
        obj.put("raw_note", record.rawNote ?: "")
        if (raw == null) obj.put("raw", JSONObject.NULL) else obj.put("raw", raw)
        if (smart == null) obj.put("smart", JSONObject.NULL) else obj.put("smart", smart)
        obj.put("lyra", lyra ?: "")
        obj.put("compare_from", if (record.rawAvailable) "raw" else "smart")
        return obj.toString()
    }

    fun mode(pipeline: SessionPipeline): String = when (pipeline) {
        SessionPipeline.TWO_STEP -> "two-step"
        SessionPipeline.THREE_STEP -> "three-step"
        SessionPipeline.SMART_ONLY -> "smart"
        SessionPipeline.DIRECT -> "direct"
    }

    fun counts(records: List<VoiceSessionRecord>): Triple<Int, Int, Int> {
        val included = records.filter { LyraReviewPolicy.reviewable(it) }
        return Triple(
            included.count { it.rating == LyraRating.UP },
            included.count { it.rating == LyraRating.DOWN },
            included.count { it.rating == LyraRating.UNRATED }
        )
    }
}

object VoiceSessionJson {
    fun toJson(record: VoiceSessionRecord): JSONObject {
        return JSONObject().apply {
            put("sessionId", record.sessionId)
            put("timestamp", record.timestamp)
            put("appName", record.appName)
            put("pipeline", record.pipeline.name)
            put("lyraProduced", record.lyraProduced)
            put("aiLyra", record.aiLyra)
            put("sensitive", record.sensitive)
            put("rawAvailable", record.rawAvailable)
            put("rawNote", record.rawNote ?: "")
            put("finalText", record.finalText)
            put("rating", record.rating.name)
            put("comment", record.comment)
            put("tags", JSONArray().apply { record.tags.forEach { put(it.wire) } })
            put("stages", JSONArray().apply {
                record.stages.forEach { stage ->
                    put(JSONObject().apply {
                        put("role", stage.role)
                        put("label", stage.label)
                        put("text", stage.text)
                    })
                }
            })
        }
    }

    fun fromJson(obj: JSONObject): VoiceSessionRecord? {
        val id = obj.optString("sessionId")
        if (id.isBlank()) return null
        val stages = ArrayList<TranscriptStage>()
        val stageArray = obj.optJSONArray("stages") ?: JSONArray()
        for (i in 0 until stageArray.length()) {
            val stage = stageArray.optJSONObject(i) ?: continue
            val text = stage.optString("text")
            if (text.isBlank()) continue
            stages += TranscriptStage(
                role = stage.optString("role"),
                label = stage.optString("label"),
                text = text
            )
        }
        val tags = ArrayList<LyraReviewTag>()
        val tagArray = obj.optJSONArray("tags") ?: JSONArray()
        for (i in 0 until tagArray.length()) {
            LyraReviewTag.fromWire(tagArray.optString(i))?.let { tags += it }
        }
        val pipeline = runCatching { SessionPipeline.valueOf(obj.optString("pipeline")) }
            .getOrDefault(SessionPipeline.DIRECT)
        val rating = runCatching { LyraRating.valueOf(obj.optString("rating")) }
            .getOrDefault(LyraRating.UNRATED)
        return VoiceSessionRecord(
            sessionId = id,
            timestamp = obj.optLong("timestamp"),
            appName = obj.optString("appName", "App"),
            pipeline = pipeline,
            lyraProduced = obj.optBoolean("lyraProduced"),
            aiLyra = obj.optBoolean("aiLyra"),
            sensitive = obj.optBoolean("sensitive"),
            rawAvailable = obj.optBoolean("rawAvailable"),
            rawNote = obj.optString("rawNote").ifBlank { null },
            stages = stages,
            finalText = obj.optString("finalText"),
            rating = rating,
            tags = tags,
            comment = obj.optString("comment")
        )
    }
}
