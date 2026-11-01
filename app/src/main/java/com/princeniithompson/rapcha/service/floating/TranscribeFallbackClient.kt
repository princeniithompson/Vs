package com.princeniithompson.rapcha.service.floating

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.princeniithompson.rapcha.config.RapchaConfig
import com.princeniithompson.rapcha.core.ApiConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

data class GeminiHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val contentType: String? = null,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 30_000
) {
    fun header(name: String): String? {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}

data class GeminiHttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String>
) {
    fun header(name: String): String? {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()
    }
}

interface GeminiHttpTransport {
    fun execute(request: GeminiHttpRequest): GeminiHttpResponse
}

object SharedGeminiHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val transport: GeminiHttpTransport = OkHttpGeminiHttp(client)
}

class OkHttpGeminiHttp(
    private val client: OkHttpClient = SharedGeminiHttp.client
) : GeminiHttpTransport {
    override fun execute(request: GeminiHttpRequest): GeminiHttpResponse {
        val call = client.newBuilder()
            .connectTimeout(request.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(request.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val body = request.body?.toRequestBody(request.contentType?.toMediaType())
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        builder.method(request.method, if (request.method == "GET" || request.method == "DELETE") null else body)
        call.newCall(builder.build()).execute().use { response ->
            val headers = LinkedHashMap<String, String>()
            for (i in 0 until response.headers.size) {
                headers[response.headers.name(i).lowercase(Locale.US)] = response.headers.value(i)
            }
            return GeminiHttpResponse(response.code, response.body?.string().orEmpty(), headers)
        }
    }
}

class UrlConnectionGeminiHttp : GeminiHttpTransport {
    override fun execute(request: GeminiHttpRequest): GeminiHttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = request.connectTimeoutMs
            readTimeout = request.readTimeoutMs
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (request.body != null) {
                doOutput = true
                if (!request.contentType.isNullOrBlank()) {
                    setRequestProperty("Content-Type", request.contentType)
                }
                setFixedLengthStreamingMode(request.body.size)
            }
        }
        try {
            if (request.body != null) {
                connection.outputStream.use { it.write(request.body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = LinkedHashMap<String, String>()
            var index = 0
            while (true) {
                val key = connection.getHeaderFieldKey(index)
                if (key == null) {
                    if (index == 0) {
                        index++
                        continue
                    }
                    break
                }
                val value = connection.getHeaderField(index)
                if (value != null) headers[key.lowercase(Locale.US)] = value
                index++
            }
            return GeminiHttpResponse(code, responseBody, headers)
        } finally {
            connection.disconnect()
        }
    }
}

data class TranscribeTiming(
    val path: String,
    val format: String,
    val rawBytes: Long,
    val encodedBytes: Long,
    val durationMs: Long,
    val encodeMs: Long,
    val uploadMs: Long,
    val pollMs: Long,
    val generateMs: Long,
    val totalMs: Long,
    val inlineRejected: Boolean = false
) {
    fun toLog(): String {
        val rejected = if (inlineRejected) " inline_rejected=1" else ""
        return "path=$path format=$format raw_bytes=$rawBytes encoded_bytes=$encodedBytes duration_ms=$durationMs encode_ms=$encodeMs upload_ms=$uploadMs poll_ms=$pollMs generate_ms=$generateMs total_ms=$totalMs$rejected"
    }
}

sealed class TranscribeResult {
    data class Success(
        val text: String,
        val quotaCharge: Boolean,
        val timing: TranscribeTiming? = null
    ) : TranscribeResult()
    data class Failure(
        val kind: Kind,
        val httpCode: Int?,
        val quotaCharge: Boolean,
        val status: String?,
        val timing: TranscribeTiming? = null
    ) : TranscribeResult()

    enum class Kind {
        BAD_KEY,
        EMPTY_AUDIO,
        BAD_REQUEST,
        RATE_LIMIT,
        QUOTA_DAILY,
        SERVER,
        IO,
        EMPTY
    }
}

/**
 * Offline Retry for gemini-3.5-transcribe. SMART + vocabulary only.
 * Short clips go inline in one generateContent call. The Files API is the fallback.
 */
class TranscribeFallbackClient(
    private val http: GeminiHttpTransport = SharedGeminiHttp.transport,
    private val encoder: RetryAudioEncoder = AndroidRetryAudioEncoder(),
    private val inlineBase64Limit: Int = RetryTransport.INLINE_BASE64_LIMIT,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    fun warm(apiKey: String) {
        if (ApiConfig.isPlaceholder(apiKey)) return
        try {
            http.execute(
                GeminiHttpRequest(
                    method = "GET",
                    url = ApiConfig.geminiV1betaUrl("models/${RapchaConfig.TRANSCRIBE_MODEL}"),
                    headers = authHeaders(apiKey),
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 5_000
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Transcribe warm-up failed: ${e.javaClass.simpleName}")
        }
    }

    fun transcribe(apiKey: String, wavFile: File, vocabulary: List<String>): TranscribeResult {
        if (ApiConfig.isPlaceholder(apiKey)) {
            return failure(TranscribeResult.Kind.BAD_KEY, null, quotaCharge = false, status = null)
        }
        if (!wavFile.exists() || wavFile.length() <= WAV_HEADER_BYTES) {
            return failure(TranscribeResult.Kind.EMPTY_AUDIO, null, quotaCharge = false, status = null)
        }
        val started = nowMs()
        val wavBytes = wavFile.readBytes()
        val encodeStarted = nowMs()
        val encoded = try {
            encoder.encode(wavBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Encoder failed: ${e.javaClass.simpleName}")
            WavIdentityEncoder.encode(wavBytes)
        }
        val encodeMs = (nowMs() - encodeStarted).coerceAtLeast(0L)
        val durationMs = com.princeniithompson.rapcha.data.RetryClipStore.durationMsForBytes(
            (wavBytes.size - WAV_HEADER_BYTES).coerceAtLeast(0).toLong()
        )
        val key = apiKey.trim()
        val vocab = prepareCustomVocabulary(vocabulary)
        val inline = RetryTransport.preferInline(encoded.bytes.size, inlineBase64Limit)
        var uploadMs = 0L
        var pollMs = 0L
        var generateMs = 0L
        var path = if (inline) "inline" else "files"
        var inlineRejected = false
        val outcome = try {
            if (inline) {
                val generateStarted = nowMs()
                val inlineResult = try {
                    generateInline(key, encoded, vocab)
                } catch (e: IOException) {
                    Log.w(TAG, "Inline transcribe failed: ${e.javaClass.simpleName}")
                    null
                }
                generateMs = (nowMs() - generateStarted).coerceAtLeast(0L)
                if (inlineResult is TranscribeResult.Success) {
                    inlineResult
                } else if (inlineResult is TranscribeResult.Failure && !shouldFallback(inlineResult)) {
                    inlineResult
                } else {
                    inlineRejected = true
                    path = "files"
                    val files = transcribeWithFiles(key, encoded, vocab)
                    uploadMs = files.uploadMs
                    pollMs = files.pollMs
                    generateMs = files.generateMs
                    files.result
                }
            } else {
                val files = transcribeWithFiles(key, encoded, vocab)
                uploadMs = files.uploadMs
                pollMs = files.pollMs
                generateMs = files.generateMs
                files.result
            }
        } catch (e: TranscribeHttpException) {
            val failed = e.result
            if (failed is TranscribeResult.Failure && failed.quotaCharge) {
                failed.copy(quotaCharge = false)
            } else {
                failed
            }
        } catch (e: IOException) {
            Log.w(TAG, "Transcribe I/O failure: ${e.javaClass.simpleName}")
            failure(TranscribeResult.Kind.IO, null, quotaCharge = false, status = null)
        }
        val timing = TranscribeTiming(
            path = path,
            format = encoded.format,
            rawBytes = wavBytes.size.toLong(),
            encodedBytes = encoded.bytes.size.toLong(),
            durationMs = durationMs,
            encodeMs = encodeMs,
            uploadMs = uploadMs,
            pollMs = pollMs,
            generateMs = generateMs,
            totalMs = (nowMs() - started).coerceAtLeast(0L),
            inlineRejected = inlineRejected
        )
        return attachTiming(outcome, timing)
    }

    private fun generateInline(
        apiKey: String,
        audio: EncodedAudio,
        vocabulary: List<String>
    ): TranscribeResult {
        val payload = buildInlineGenerateContentJson(
            base64 = Base64.encodeToString(audio.bytes, Base64.NO_WRAP),
            mimeType = audio.mimeType,
            vocabulary = vocabulary
        )
        val response = http.execute(
            GeminiHttpRequest(
                method = "POST",
                url = ApiConfig.geminiGenerateContentUrl(RapchaConfig.TRANSCRIBE_MODEL),
                headers = authHeaders(apiKey),
                body = payload.toString().toByteArray(Charsets.UTF_8),
                contentType = "application/json; charset=UTF-8",
                connectTimeoutMs = 15_000,
                readTimeoutMs = 120_000
            )
        )
        if (isPayloadTooLarge(response)) {
            return failure(TranscribeResult.Kind.BAD_REQUEST, response.code, quotaCharge = false, status = "PAYLOAD_TOO_LARGE")
        }
        return interpretGenerate(response)
    }

    private data class FilesAttempt(
        val result: TranscribeResult,
        val uploadMs: Long,
        val pollMs: Long,
        val generateMs: Long
    )

    private fun transcribeWithFiles(
        apiKey: String,
        audio: EncodedAudio,
        vocabulary: List<String>
    ): FilesAttempt {
        var remoteName: String? = null
        var uploadMs = 0L
        var pollMs = 0L
        var generateMs = 0L
        try {
            val uploadStarted = nowMs()
            val uploaded = uploadAudio(apiKey, audio.bytes, audio.mimeType)
            uploadMs = (nowMs() - uploadStarted).coerceAtLeast(0L)
            remoteName = uploaded.name
            val ready = if (uploaded.state.equals("ACTIVE", ignoreCase = true)) {
                uploaded
            } else {
                val pollStarted = nowMs()
                val active = waitUntilActive(apiKey, uploaded)
                pollMs = (nowMs() - pollStarted).coerceAtLeast(0L)
                active ?: return FilesAttempt(
                    failure(TranscribeResult.Kind.SERVER, null, quotaCharge = false, status = "FILE_NOT_READY"),
                    uploadMs,
                    pollMs,
                    0L
                )
            }
            val generateStarted = nowMs()
            val payload = buildGenerateContentJson(ready.uri, audio.mimeType, vocabulary)
            val response = http.execute(
                GeminiHttpRequest(
                    method = "POST",
                    url = ApiConfig.geminiGenerateContentUrl(RapchaConfig.TRANSCRIBE_MODEL),
                    headers = authHeaders(apiKey),
                    body = payload.toString().toByteArray(Charsets.UTF_8),
                    contentType = "application/json; charset=UTF-8",
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 120_000
                )
            )
            generateMs = (nowMs() - generateStarted).coerceAtLeast(0L)
            return FilesAttempt(interpretGenerate(response), uploadMs, pollMs, generateMs)
        } catch (e: TranscribeHttpException) {
            val failed = e.result
            val result = if (failed is TranscribeResult.Failure && failed.quotaCharge) {
                failed.copy(quotaCharge = false)
            } else {
                failed
            }
            return FilesAttempt(result, uploadMs, pollMs, generateMs)
        } finally {
            val name = remoteName
            if (!name.isNullOrBlank()) bestEffortDelete(apiKey, name)
        }
    }

    private fun uploadAudio(apiKey: String, bytes: ByteArray, mimeType: String): RemoteFile {
        val startBody = JSONObject().put("file", JSONObject().put("display_name", "rapcha-retry")).toString()
        val start = http.execute(
            GeminiHttpRequest(
                method = "POST",
                url = ApiConfig.geminiFilesUploadUrl(),
                headers = authHeaders(apiKey) + mapOf(
                    "X-Goog-Upload-Protocol" to "resumable",
                    "X-Goog-Upload-Command" to "start",
                    "X-Goog-Upload-Header-Content-Length" to bytes.size.toString(),
                    "X-Goog-Upload-Header-Content-Type" to mimeType
                ),
                body = startBody.toByteArray(Charsets.UTF_8),
                contentType = "application/json; charset=UTF-8",
                connectTimeoutMs = 15_000,
                readTimeoutMs = 30_000
            )
        )
        if (start.code !in 200..299) {
            throw TranscribeHttpException(interpretGenerate(start))
        }
        val inline = parseRemoteFile(start.body)
        if (inline != null && inline.uri.isNotBlank()) {
            return inline
        }
        val uploadUrl = ApiConfig.withoutApiKeyQuery(start.header("x-goog-upload-url").orEmpty())
        if (uploadUrl.isBlank()) {
            throw IOException("Files API upload URL missing")
        }
        val uploaded = http.execute(
            GeminiHttpRequest(
                method = "POST",
                url = uploadUrl,
                headers = authHeaders(apiKey) + mapOf(
                    "X-Goog-Upload-Offset" to "0",
                    "X-Goog-Upload-Command" to "upload, finalize"
                ),
                body = bytes,
                contentType = mimeType,
                connectTimeoutMs = 20_000,
                readTimeoutMs = 120_000
            )
        )
        if (uploaded.code !in 200..299) {
            throw TranscribeHttpException(interpretGenerate(uploaded))
        }
        return parseRemoteFile(uploaded.body) ?: throw IOException("Files API response missing file uri")
    }

    private fun waitUntilActive(apiKey: String, remote: RemoteFile): RemoteFile? {
        var current = remote
        repeat(POLL_ATTEMPTS) {
            sleeper(POLL_DELAY_MS)
            val response = http.execute(
                GeminiHttpRequest(
                    method = "GET",
                    url = ApiConfig.geminiV1betaUrl(current.name),
                    headers = authHeaders(apiKey),
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 20_000
                )
            )
            if (response.code !in 200..299) return null
            val parsed = parseRemoteFile(response.body) ?: return null
            if (parsed.state.equals("FAILED", ignoreCase = true)) return null
            if (parsed.state.equals("ACTIVE", ignoreCase = true)) return parsed
            current = parsed
        }
        return null
    }

    private fun interpretGenerate(response: GeminiHttpResponse): TranscribeResult {
        val status = safeErrorStatus(response.body)
        if (response.code in 200..299) {
            val text = parseTranscript(response.body)
            if (text.isBlank()) {
                val hint = responseShapeHint(response.body)
                Log.w(TAG, "Transcribe HTTP 200 empty $hint")
                return failure(TranscribeResult.Kind.EMPTY, response.code, quotaCharge = true, status = hint)
            }
            return TranscribeResult.Success(text, quotaCharge = true)
        }
        val kind = when (response.code) {
            400 -> TranscribeResult.Kind.BAD_REQUEST
            401, 403 -> TranscribeResult.Kind.BAD_KEY
            429 -> if (isDailyQuotaBody(response.body)) {
                TranscribeResult.Kind.QUOTA_DAILY
            } else {
                TranscribeResult.Kind.RATE_LIMIT
            }
            in 500..599 -> TranscribeResult.Kind.SERVER
            else -> TranscribeResult.Kind.SERVER
        }
        Log.w(TAG, "Transcribe HTTP ${response.code} kind=$kind status=${status.orEmpty()}")
        return failure(kind, response.code, quotaCharge = true, status = status)
    }

    private fun bestEffortDelete(apiKey: String, fileName: String) {
        try {
            val response = http.execute(
                GeminiHttpRequest(
                    method = "DELETE",
                    url = ApiConfig.geminiV1betaUrl(fileName),
                    headers = authHeaders(apiKey),
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 20_000
                )
            )
            if (response.code !in 200..299 && response.code != 404) {
                Log.w(TAG, "Remote retry file delete HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote retry file delete failed: ${e.javaClass.simpleName}")
        }
    }

    private fun authHeaders(apiKey: String): Map<String, String> {
        return mapOf("x-goog-api-key" to apiKey.trim())
    }

    private fun shouldFallback(result: TranscribeResult.Failure): Boolean {
        if (result.kind == TranscribeResult.Kind.IO) return true
        return isPayloadStatus(result.httpCode, result.status)
    }

    private fun isPayloadTooLarge(response: GeminiHttpResponse): Boolean {
        if (response.code == 413) return true
        if (response.code != 400) return false
        val compact = response.body.lowercase(Locale.US)
        return compact.contains("payload") || compact.contains("too large") ||
            compact.contains("request size") || compact.contains("exceeds")
    }

    private fun isPayloadStatus(code: Int?, status: String?): Boolean {
        if (code == 413) return true
        val compact = status.orEmpty().lowercase(Locale.US)
        return code == 400 && (compact.contains("payload") || compact.contains("too_large") || compact.contains("request_size"))
    }

    private fun attachTiming(result: TranscribeResult, timing: TranscribeTiming): TranscribeResult {
        return when (result) {
            is TranscribeResult.Success -> result.copy(timing = timing)
            is TranscribeResult.Failure -> result.copy(timing = timing)
        }
    }

    private fun failure(
        kind: TranscribeResult.Kind,
        httpCode: Int?,
        quotaCharge: Boolean,
        status: String?
    ): TranscribeResult.Failure {
        return TranscribeResult.Failure(kind, httpCode, quotaCharge, status)
    }

    private data class RemoteFile(val name: String, val uri: String, val state: String)

    private class TranscribeHttpException(val result: TranscribeResult) : Exception()

    companion object {
        private const val TAG = "TranscribeFallback"
        private const val MIME_WAV = "audio/wav"
        private const val WAV_HEADER_BYTES = 44
        private const val POLL_ATTEMPTS = 8
        private const val POLL_DELAY_MS = 400L
        const val MAX_VOCAB = 100

        fun prepareCustomVocabulary(terms: List<String>, limit: Int = MAX_VOCAB): List<String> {
            val seen = HashSet<String>()
            val prepared = ArrayList<String>(minOf(limit, terms.size))
            for (raw in terms) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) continue
                val key = trimmed.lowercase(Locale.US)
                if (!seen.add(key)) continue
                prepared.add(trimmed.take(100))
                if (prepared.size >= limit) break
            }
            return prepared
        }

        fun buildGenerateContentJson(fileUri: String, mimeType: String, vocabulary: List<String>): JSONObject {
            val fileData = JSONObject()
                .put("mimeType", mimeType)
                .put("fileUri", fileUri)
            val part = JSONObject().put("fileData", fileData)
            return requestBody(JSONObject().put("parts", JSONArray().put(part)), vocabulary)
        }

        fun buildInlineGenerateContentJson(
            base64: String,
            mimeType: String,
            vocabulary: List<String>
        ): JSONObject {
            val inline = JSONObject()
                .put("mimeType", mimeType)
                .put("data", base64)
            val part = JSONObject().put("inlineData", inline)
            return requestBody(JSONObject().put("parts", JSONArray().put(part)), vocabulary)
        }

        private fun requestBody(content: JSONObject, vocabulary: List<String>): JSONObject {
            return JSONObject()
                .put("contents", JSONArray().put(content))
                .put("generationConfig", JSONObject().put("audioTranscriptionConfig", audioConfig(vocabulary)))
        }

        private fun audioConfig(vocabulary: List<String>): JSONObject {
            val config = JSONObject().put("mode", "SMART")
            if (vocabulary.isNotEmpty()) {
                val vocab = JSONArray()
                vocabulary.forEach { vocab.put(it) }
                config.put("customVocabulary", vocab)
            }
            return config
        }

        fun parseTranscript(body: String): String {
            if (body.isBlank()) return ""
            return try {
                val root = JSONObject(body)
                val candidates = root.optJSONArray("candidates") ?: return ""
                val pieces = ArrayList<String>()
                for (i in 0 until candidates.length()) {
                    val parts = candidates.optJSONObject(i)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts") ?: continue
                    for (p in 0 until parts.length()) {
                        val part = parts.optJSONObject(p) ?: continue
                        val piece = transcriptFromPart(part)
                        if (piece.isNotBlank()) pieces.add(piece.trim())
                    }
                }
                cleanTranscript(pieces.joinToString("\n"))
            } catch (e: Exception) {
                Log.w(TAG, "Transcript parse failed: ${e.javaClass.simpleName}")
                ""
            }
        }

        /**
         * Counts response fields only. Never includes transcript text.
         */
        fun responseShapeHint(body: String): String {
            if (body.isBlank()) {
                return "candidates=0 audio_transcription=0 text=0 words=0 finish=none"
            }
            return try {
                val root = JSONObject(body)
                val candidates = root.optJSONArray("candidates")
                var audio = false
                var text = false
                var words = false
                var finish = "none"
                if (candidates != null) {
                    for (i in 0 until candidates.length()) {
                        val candidate = candidates.optJSONObject(i) ?: continue
                        val reason = candidate.optString("finishReason", "")
                            .filter { it.isLetterOrDigit() || it == '_' }
                            .take(24)
                        if (reason.isNotBlank()) finish = reason
                        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: continue
                        for (p in 0 until parts.length()) {
                            val part = parts.optJSONObject(p) ?: continue
                            if (part.optString("text", "").isNotBlank()) text = true
                            val transcription = audioObject(part) ?: continue
                            audio = true
                            if (transcription.optString("text", "").isNotBlank()) text = true
                            val wordList = transcription.optJSONArray("words")
                            if (wordList != null && wordList.length() > 0) words = true
                        }
                    }
                }
                val count = candidates?.length() ?: 0
                "candidates=$count audio_transcription=${flag(audio)} text=${flag(text)} words=${flag(words)} finish=$finish"
            } catch (_: Exception) {
                "candidates=0 audio_transcription=0 text=0 words=0 finish=unparsed"
            }
        }

        private fun transcriptFromPart(part: JSONObject): String {
            val audio = audioObject(part)
            val audioText = audio?.optString("text", "").orEmpty().trim()
            if (audioText.isNotBlank()) return audioText
            val direct = part.optString("text", "").trim()
            if (direct.isNotBlank()) return direct
            val wordList = audio?.optJSONArray("words") ?: return ""
            val joined = StringBuilder()
            for (index in 0 until wordList.length()) {
                val word = wordList.optJSONObject(index)?.optString("word", "").orEmpty().trim()
                if (word.isEmpty()) continue
                if (joined.isNotEmpty()) joined.append(' ')
                joined.append(word)
            }
            return joined.toString()
        }

        private fun audioObject(part: JSONObject): JSONObject? {
            return part.optJSONObject("audioTranscription") ?: part.optJSONObject("audio_transcription")
        }

        private fun cleanTranscript(raw: String): String {
            var result = raw.trim()
            if (result.startsWith("```") && result.endsWith("```")) {
                result = result.removeSurrounding("```").trim()
                if (result.startsWith("text") || result.startsWith("markdown")) {
                    result = result.substringAfter("\n").trim()
                }
            }
            return result
        }

        private fun flag(present: Boolean): Int = if (present) 1 else 0

        fun isDailyQuotaBody(body: String): Boolean {
            val compact = body.lowercase(Locale.US).replace("_", "").replace("-", "")
            return compact.contains("perday") || compact.contains("per day") || compact.contains("requestsperday")
        }

        fun safeErrorStatus(body: String): String? {
            val status = try {
                JSONObject(body).optJSONObject("error")?.optString("status").orEmpty()
            } catch (_: Exception) {
                return null
            }
            val cleaned = status.filter { it.isLetterOrDigit() || it == '_' }.take(40)
            return cleaned.ifBlank { null }
        }

        private fun parseRemoteFile(body: String): RemoteFile? {
            if (body.isBlank()) return null
            return try {
                val root = JSONObject(body)
                val file = root.optJSONObject("file") ?: root
                val name = file.optString("name", "").trim()
                var uri = file.optString("uri", "").trim()
                if (uri.isBlank() && name.isNotBlank()) {
                    uri = ApiConfig.geminiV1betaUrl(name)
                }
                if (name.isBlank() || uri.isBlank()) return null
                val state = file.optString("state", "ACTIVE").ifBlank { "ACTIVE" }
                RemoteFile(name = name, uri = ApiConfig.withoutApiKeyQuery(uri), state = state)
            } catch (_: Exception) {
                null
            }
        }
    }
}
