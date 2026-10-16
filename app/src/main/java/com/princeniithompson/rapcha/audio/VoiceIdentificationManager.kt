package com.princeniithompson.rapcha.audio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

sealed interface ModelDownloadStatus {
    object NotDownloaded : ModelDownloadStatus
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadStatus
    object Ready : ModelDownloadStatus
    data class Error(val message: String) : ModelDownloadStatus
}

data class SpeakerMatchResult(
    val isMatch: Boolean,
    val similarity: Float,
    val isOwnerLocked: Boolean,
    val message: String
)

/**
 * Manages on-device AI Speaker Identification (WeSpeaker LiteRT FP16).
 *
 * Implements Prince's Per-Session Voice Lock:
 * 1. The model is optional and downloaded on-demand (not in APK).
 * 2. When voice typing starts, the first clean utterance locks the speaker's 256-D embedding.
 * 3. While the session is active, any new speech after pauses is verified against the locked owner.
 * 4. Voices that do not match are blocked (gate closed).
 * 5. When the session ends, the locked voice is completely cleared so the next user starts fresh.
 */
object VoiceIdentificationManager {
    private const val TAG = "VoiceIdManager"
    private const val PREFS_NAME = "voxstream_settings"
    private const val KEY_VOICE_ID_ENABLED = "voice_id_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val fbankExtractor = KaldiFbankExtractor()
    private val tfliteInputBuffer = ByteBuffer.allocateDirect(1 * 500 * 80 * 4).order(ByteOrder.nativeOrder())
    private val tfliteOutputArray = Array(1) { FloatArray(256) }

    private var interpreter: Interpreter? = null

    // Session-based owner voice lock (cleared at end of session)
    private var sessionOwnerEmbedding: FloatArray? = null
    private val sessionEmbeddingLock = Any()

    private val _isVoiceIdEnabled = MutableStateFlow(false)
    val isVoiceIdEnabled: StateFlow<Boolean> = _isVoiceIdEnabled.asStateFlow()

    private val _modelStatus = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.NotDownloaded)
    val modelStatus: StateFlow<ModelDownloadStatus> = _modelStatus.asStateFlow()

    private val _isSessionOwnerLocked = MutableStateFlow(false)
    val isSessionOwnerLocked: StateFlow<Boolean> = _isSessionOwnerLocked.asStateFlow()

    private val _lastSimilarityScore = MutableStateFlow(0f)
    val lastSimilarityScore: StateFlow<Float> = _lastSimilarityScore.asStateFlow()

    private val _lastVerificationStatus = MutableStateFlow("Idle")
    val lastVerificationStatus: StateFlow<String> = _lastVerificationStatus.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedEnabled = prefs?.getBoolean(KEY_VOICE_ID_ENABLED, false) ?: false
        _isVoiceIdEnabled.value = savedEnabled

        checkModelFile()

        if (savedEnabled && _modelStatus.value is ModelDownloadStatus.NotDownloaded) {
            downloadModel()
        }
    }

    private fun getModelFile(): File? {
        val ctx = appContext ?: return null
        val modelsDir = File(ctx.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        return File(modelsDir, ApiConfig.VOICE_ID_MODEL_FILENAME)
    }

    fun isModelReady(): Boolean = _modelStatus.value is ModelDownloadStatus.Ready && interpreter != null

    @Synchronized
    fun checkModelFile() {
        val file = getModelFile() ?: return
        if (file.exists() && file.length() > 5_000_000L) {
            _modelStatus.value = ModelDownloadStatus.Ready
            loadInterpreter(file)
        } else {
            _modelStatus.value = ModelDownloadStatus.NotDownloaded
            closeInterpreter()
        }
    }

    @Synchronized
    private fun loadInterpreter(file: File): Boolean {
        if (interpreter != null) return true
        return try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(file, options)
            Log.i(TAG, "WeSpeaker LiteRT interpreter initialized successfully (${file.length()} bytes)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize TFLite interpreter", t)
            _modelStatus.value = ModelDownloadStatus.Error("Failed to load model: ${t.message}")
            false
        }
    }

    @Synchronized
    private fun closeInterpreter() {
        try {
            interpreter?.close()
        } catch (_: Throwable) {}
        interpreter = null
    }

    fun setVoiceIdEnabled(enabled: Boolean) {
        _isVoiceIdEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_VOICE_ID_ENABLED, enabled)?.apply()

        AppLogRepository.logEvent(
            DiagnosticSource.APP,
            DiagnosticType.VOICE_ID_EVENT,
            "Voice Identification switch set to: ${if (enabled) "ENABLED" else "DISABLED"}"
        )

        if (enabled) {
            val file = getModelFile()
            if (file == null || !file.exists() || file.length() < 5_000_000L) {
                downloadModel()
            } else if (interpreter == null) {
                loadInterpreter(file)
            }
        }
    }

    fun downloadModel() {
        if (_modelStatus.value is ModelDownloadStatus.Downloading) return
        downloadJob?.cancel()

        downloadJob = scope.launch {
            val file = getModelFile() ?: return@launch
            val tempFile = File(file.parentFile, "${file.name}.tmp")

            _modelStatus.value = ModelDownloadStatus.Downloading(0f, 0L, 13_352_192L)
            Log.i(TAG, "Starting WeSpeaker model download from ${ApiConfig.VOICE_ID_MODEL_URL}")

            try {
                var currentUrl = ApiConfig.VOICE_ID_MODEL_URL
                var connection: HttpURLConnection? = null
                var redirects = 0
                val maxRedirects = 5

                while (redirects < maxRedirects) {
                    val url = URL(currentUrl)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30000
                        readTimeout = 60000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Rapcha-Android")
                    }

                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == 307 || code == 308
                    ) {
                        val newLocation = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (newLocation != null) {
                            currentUrl = newLocation
                            redirects++
                            continue
                        }
                    }
                    break
                }

                val conn = connection ?: throw IllegalStateException("Could not open HTTP connection")
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Server returned HTTP $responseCode")
                }

                val totalBytes = conn.contentLengthLong.let { if (it > 0) it else 13_352_192L }
                var downloadedBytes = 0L

                val input: InputStream = conn.inputStream
                val output = FileOutputStream(tempFile)

                val buffer = ByteArray(32768)
                var bytesRead: Int
                var lastProgressUpdate = System.currentTimeMillis()

                output.use { out ->
                    input.use { inp ->
                        while (inp.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 100L || downloadedBytes == totalBytes) {
                                lastProgressUpdate = now
                                val progress = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                _modelStatus.value = ModelDownloadStatus.Downloading(progress, downloadedBytes, totalBytes)
                            }
                        }
                    }
                }

                if (tempFile.length() < 5_000_000L) {
                    throw IllegalStateException("Downloaded file incomplete (${tempFile.length()} bytes)")
                }

                if (file.exists()) file.delete()
                val renamed = tempFile.renameTo(file)
                if (!renamed) {
                    tempFile.copyTo(file, overwrite = true)
                    tempFile.delete()
                }

                _modelStatus.value = ModelDownloadStatus.Ready
                loadInterpreter(file)

                Log.i(TAG, "WeSpeaker model downloaded successfully (${file.length()} bytes)")
                AppLogRepository.logEvent(
                    DiagnosticSource.APP,
                    DiagnosticType.VOICE_ID_EVENT,
                    "AI Voice ID Model ready: WeSpeaker LiteRT FP16 (13.4 MB)"
                )

            } catch (t: Throwable) {
                if (tempFile.exists()) tempFile.delete()
                val err = t.message ?: "Unknown download error"
                Log.e(TAG, "WeSpeaker model download failed: $err", t)
                _modelStatus.value = ModelDownloadStatus.Error(err)
                AppLogRepository.logEvent(
                    DiagnosticSource.APP,
                    DiagnosticType.ERROR,
                    "Voice ID model download failed: $err"
                )
            }
        }
    }

    private var consecutiveRejections = 0

    private fun softUpdateOwnerEmbedding(newEmb: FloatArray, similarity: Float) {
        if (similarity < 0.76f) return
        synchronized(sessionEmbeddingLock) {
            val current = sessionOwnerEmbedding ?: return
            val updated = FloatArray(256)
            var sumSq = 0f
            for (i in 0 until 256) {
                updated[i] = 0.15f * newEmb[i] + 0.85f * current[i]
                sumSq += updated[i] * updated[i]
            }
            val norm = kotlin.math.sqrt(sumSq).coerceAtLeast(1e-12f)
            for (i in 0 until 256) {
                updated[i] /= norm
            }
            sessionOwnerEmbedding = updated
        }
    }

    /**
     * Called when a voice typing session begins.
     * Clears any previous session lock so the first speaker of THIS session is captured.
     */
    fun startSession() {
        synchronized(sessionEmbeddingLock) {
            sessionOwnerEmbedding = null
        }
        consecutiveRejections = 0
        _isSessionOwnerLocked.value = false
        _lastSimilarityScore.value = 0f
        _lastVerificationStatus.value = if (_isVoiceIdEnabled.value && isModelReady()) {
            "Listening for session owner..."
        } else {
            "Off"
        }
    }

    /**
     * Called when a voice typing session finishes.
     * Ensures NO voice data is permanently kept so another user can freely use the app next time.
     */
    fun endSession() {
        synchronized(sessionEmbeddingLock) {
            sessionOwnerEmbedding = null
        }
        consecutiveRejections = 0
        _isSessionOwnerLocked.value = false
        _lastSimilarityScore.value = 0f
        _lastVerificationStatus.value = "Idle"
    }

    /**
     * Extracts 256-D normalized WeSpeaker embedding from audio snippet.
     */
    @Synchronized
    private fun extractEmbedding(pcmSamples: ShortArray): FloatArray? {
        val inter = interpreter ?: return null
        if (pcmSamples.isEmpty()) return null

        return try {
            fbankExtractor.extractFbankCmnToBuffer(pcmSamples, tfliteInputBuffer)
            inter.run(tfliteInputBuffer, tfliteOutputArray)

            val raw = tfliteOutputArray[0]
            val normArray = FloatArray(256)
            var sumSq = 0f
            for (i in 0 until 256) {
                sumSq += raw[i] * raw[i]
            }
            val norm = sqrt(sumSq).coerceAtLeast(1e-12f)
            for (i in 0 until 256) {
                normArray[i] = raw[i] / norm
            }
            normArray
        } catch (t: Throwable) {
            Log.e(TAG, "Error executing WeSpeaker embedding inference", t)
            null
        }
    }

    /**
     * Computes cosine similarity between two unit-norm 256-D vectors.
     */
    private fun computeCosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in 0 until 256) {
            dot += a[i] * b[i]
        }
        return dot.coerceIn(-1.0f, 1.0f)
    }

    /**
     * Locks the speaker's voice for the current active session from their first clean utterance.
     */
    fun lockSessionOwner(pcmSamples: ShortArray): Boolean {
        if (!_isVoiceIdEnabled.value || !isModelReady()) return false

        synchronized(sessionEmbeddingLock) {
            if (sessionOwnerEmbedding != null) return true // Already locked
            val emb = extractEmbedding(pcmSamples) ?: return false
            sessionOwnerEmbedding = emb
            _isSessionOwnerLocked.value = true
            _lastVerificationStatus.value = "Owner voice locked 🔒"

            Log.i(TAG, "Session owner voice locked successfully (256-D embedding extracted)")
            AppLogRepository.logEvent(
                DiagnosticSource.APP,
                DiagnosticType.VOICE_ID_EVENT,
                "AI Voice ID: Locked to session owner's voice. Background talkers will be rejected."
            )
            return true
        }
    }

    /**
     * Verifies if candidate audio matches the current session owner.
     * Returns true if speaker matches (or if Voice ID is disabled / no owner locked yet).
     */
    fun verifySpeaker(pcmSamples: ShortArray): SpeakerMatchResult {
        if (!_isVoiceIdEnabled.value || !isModelReady()) {
            return SpeakerMatchResult(
                isMatch = true,
                similarity = 1.0f,
                isOwnerLocked = false,
                message = "Voice ID disabled"
            )
        }

        val ownerEmb: FloatArray?
        synchronized(sessionEmbeddingLock) {
            ownerEmb = sessionOwnerEmbedding
        }

        if (ownerEmb == null) {
            // First utterance: lock as owner
            val locked = lockSessionOwner(pcmSamples)
            return SpeakerMatchResult(
                isMatch = true,
                similarity = 1.0f,
                isOwnerLocked = locked,
                message = if (locked) "Initial owner locked" else "Awaiting clean owner audio"
            )
        }

        val candEmb = extractEmbedding(pcmSamples)
        if (candEmb == null) {
            // Fail open if model transiently fails, but keep locked state
            return SpeakerMatchResult(
                isMatch = true,
                similarity = 0.70f,
                isOwnerLocked = true,
                message = "Inference skipped"
            )
        }

        val similarity = computeCosineSimilarity(ownerEmb, candEmb)
        _lastSimilarityScore.value = similarity
        var isMatch = similarity >= ApiConfig.VOICE_ID_SIMILARITY_THRESHOLD

        if (!isMatch) {
            consecutiveRejections++
            if (consecutiveRejections >= 2 && similarity >= 0.58f) {
                isMatch = true
                consecutiveRejections = 0
                Log.i(TAG, "Grace fallback triggered for owner voice (sim: $similarity)")
            }
        } else {
            consecutiveRejections = 0
            softUpdateOwnerEmbedding(candEmb, similarity)
        }

        if (isMatch) {
            _lastVerificationStatus.value = "Owner verified (${(similarity * 100).toInt()}%)"
        } else {
            _lastVerificationStatus.value = "Bystander blocked (${(similarity * 100).toInt()}%)"
            AppLogRepository.logEvent(
                DiagnosticSource.APP,
                DiagnosticType.NOISY_ENVIRONMENT,
                "AI Voice ID: Bystander voice blocked (sim: ${(similarity * 100).toInt()}%, threshold: ${(ApiConfig.VOICE_ID_SIMILARITY_THRESHOLD * 100).toInt()}%)"
            )
        }

        return SpeakerMatchResult(
            isMatch = isMatch,
            similarity = similarity,
            isOwnerLocked = true,
            message = if (isMatch) "Match verified" else "Bystander rejected"
        )
    }
}
