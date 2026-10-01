package com.example.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AudioRecording(
    val file: File,
    val fileName: String,
    val timestamp: Long,
    val source: DiagnosticSource,
    val durationMs: Long,
    val sizeBytes: Long,
    val transcriptSnippet: String?
)

object AudioRecordingRepository {
    private const val TAG = "AudioRecordingRepo"
    private const val PREFS_NAME = "voxstream_settings"
    private const val KEY_SAVE_RECORDINGS = "save_audio_recordings"
    private const val RECORDINGS_DIR_NAME = "recordings"

    private const val MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000L // 3 days
    private const val MAX_TOTAL_SIZE_BYTES = 500 * 1024 * 1024L // 500 MB

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val _isSaveRecordingsEnabled = MutableStateFlow(true)
    val isSaveRecordingsEnabled: StateFlow<Boolean> = _isSaveRecordingsEnabled.asStateFlow()

    private val _recordings = MutableStateFlow<List<AudioRecording>>(emptyList())
    val recordings: StateFlow<List<AudioRecording>> = _recordings.asStateFlow()

    // Active recording session state
    @Volatile
    private var currentFile: File? = null
    @Volatile
    private var currentOutputStream: FileOutputStream? = null
    @Volatile
    private var currentDataBytesWritten: Long = 0L

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _isSaveRecordingsEnabled.value = sp.getBoolean(KEY_SAVE_RECORDINGS, true)

        scope.launch {
            val dir = getRecordingsDir(appCtx)
            repairIncompleteWavFiles(dir)
            enforceCleanupRules(appCtx)
            refreshRecordingsList()
        }
    }

    fun setSaveRecordingsEnabled(enabled: Boolean) {
        _isSaveRecordingsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_SAVE_RECORDINGS, enabled)?.apply()
    }

    fun startRecordingSession(source: DiagnosticSource) {
        if (!_isSaveRecordingsEnabled.value) return
        val context = appContext ?: return

        scope.launch {
            try {
                // Ensure previous session is finalized
                finalizeActiveSessionInternal()

                val dir = getRecordingsDir(context)
                val now = System.currentTimeMillis()
                val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
                val fileName = "${dateStr}_${source.name}.wav"
                val file = File(dir, fileName)

                val fos = FileOutputStream(file)
                // Write initial 44-byte WAV header with zero sizes
                val initialHeader = createWavHeader(dataLength = 0)
                fos.write(initialHeader)
                fos.flush()

                currentFile = file
                currentOutputStream = fos
                currentDataBytesWritten = 0L

                Log.d(TAG, "Started WAV recording session: ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WAV recording session", e)
                currentFile = null
                currentOutputStream = null
            }
        }
    }

    fun appendAudioChunk(chunk: ByteArray) {
        if (!_isSaveRecordingsEnabled.value) return
        val fos = currentOutputStream ?: return

        scope.launch {
            try {
                fos.write(chunk)
                currentDataBytesWritten += chunk.size
            } catch (e: Exception) {
                Log.e(TAG, "Error writing audio chunk to WAV file", e)
            }
        }
    }

    fun stopRecordingSession() {
        scope.launch {
            finalizeActiveSessionInternal()
            val context = appContext
            if (context != null) {
                enforceCleanupRules(context)
            }
            refreshRecordingsList()
        }
    }

    private fun finalizeActiveSessionInternal() {
        val file = currentFile ?: return
        val fos = currentOutputStream
        val dataBytes = currentDataBytesWritten

        try {
            fos?.flush()
            fos?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WAV output stream", e)
        } finally {
            currentOutputStream = null
            currentFile = null
        }

        // Update WAV header sizes
        try {
            if (file.exists() && file.length() >= 44) {
                RandomAccessFile(file, "rw").use { raf ->
                    // ChunkSize = 36 + dataBytes
                    val chunkSize = (36 + dataBytes).toInt()
                    raf.seek(4)
                    raf.write(intToLittleEndian(chunkSize))

                    // Subchunk2Size = dataBytes
                    raf.seek(40)
                    raf.write(intToLittleEndian(dataBytes.toInt()))
                }
                Log.d(TAG, "Finalized WAV header for ${file.name}: $dataBytes data bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing WAV header for ${file.name}", e)
        }
    }

    private fun repairIncompleteWavFiles(dir: File) {
        try {
            if (!dir.exists()) return
            val wavFiles = dir.listFiles { f -> f.extension.lowercase() == "wav" } ?: return
            for (file in wavFiles) {
                val totalLen = file.length()
                if (totalLen < 44) continue
                val actualDataBytes = totalLen - 44
                try {
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(40)
                        val b0 = raf.read()
                        val b1 = raf.read()
                        val b2 = raf.read()
                        val b3 = raf.read()
                        val subchunk2Size = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)

                        if (subchunk2Size.toLong() != actualDataBytes) {
                            Log.w(TAG, "Repairing WAV header for ${file.name}: recorded header size=$subchunk2Size, actual data bytes=$actualDataBytes")
                            raf.seek(4)
                            raf.write(intToLittleEndian((36 + actualDataBytes).toInt()))
                            raf.seek(40)
                            raf.write(intToLittleEndian(actualDataBytes.toInt()))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to repair WAV ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning/repairing WAV files", e)
        }
    }

    private fun enforceCleanupRules(context: Context) {
        try {
            val dir = getRecordingsDir(context)
            if (!dir.exists()) return
            val wavFiles = dir.listFiles { f -> f.extension.lowercase() == "wav" }?.sortedBy { it.lastModified() } ?: return

            val now = System.currentTimeMillis()
            var currentTotalSize = 0L

            for (file in wavFiles) {
                val ageMs = now - file.lastModified()
                if (ageMs > MAX_AGE_MS) {
                    Log.d(TAG, "Auto-deleting expired recording (>3 days old): ${file.name}")
                    file.delete()
                } else {
                    currentTotalSize += file.length()
                }
            }

            // Enforce total size <= 500 MB by deleting oldest files
            val remainingFiles = dir.listFiles { f -> f.extension.lowercase() == "wav" }?.sortedBy { it.lastModified() } ?: return
            var totalSize = remainingFiles.sumOf { it.length() }

            for (file in remainingFiles) {
                if (totalSize <= MAX_TOTAL_SIZE_BYTES) break
                val size = file.length()
                Log.d(TAG, "Auto-deleting oldest recording to maintain <500MB cap: ${file.name} (${size / 1024} KB)")
                if (file.delete()) {
                    totalSize -= size
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing recording cleanup rules", e)
        }
    }

    fun refreshRecordingsList() {
        val context = appContext ?: return
        scope.launch {
            try {
                val dir = getRecordingsDir(context)
                if (!dir.exists()) {
                    _recordings.value = emptyList()
                    return@launch
                }

                val wavFiles = dir.listFiles { f -> f.extension.lowercase() == "wav" } ?: emptyArray()
                val diagnosticLogs = AppLogRepository.diagnosticEntries.value

                val list = wavFiles.map { file ->
                    val fileName = file.name
                    val parts = fileName.removeSuffix(".wav").split("_")
                    val sourceStr = parts.lastOrNull() ?: "APP"
                    val source = try {
                        DiagnosticSource.valueOf(sourceStr.uppercase())
                    } catch (e: Exception) {
                        DiagnosticSource.APP
                    }

                    val lastMod = file.lastModified()
                    val dataSize = (file.length() - 44).coerceAtLeast(0L)
                    // 16kHz 16-bit mono = 32,000 bytes per second -> 32 bytes per millisecond
                    val durationMs = (dataSize / 32)

                    // Find matching transcript snippet from AppLogRepository
                    // Search for TRANSCRIPT_FINAL log from same source within +/- 45s of file timestamp
                    val matchingLog = diagnosticLogs.firstOrNull { log ->
                        log.source == source &&
                        log.type == DiagnosticType.TRANSCRIPT_FINAL &&
                        kotlin.math.abs(log.timestamp - lastMod) < 45_000L
                    } ?: diagnosticLogs.firstOrNull { log ->
                        log.source == source &&
                        log.type == DiagnosticType.SESSION_START &&
                        kotlin.math.abs(log.timestamp - lastMod) < 45_000L
                    }

                    val snippet = matchingLog?.message?.lineSequence()?.firstOrNull()?.take(100)

                    AudioRecording(
                        file = file,
                        fileName = fileName,
                        timestamp = lastMod,
                        source = source,
                        durationMs = durationMs,
                        sizeBytes = file.length(),
                        transcriptSnippet = snippet
                    )
                }.sortedByDescending { it.timestamp }

                _recordings.value = list
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing recordings list", e)
            }
        }
    }

    fun deleteRecording(recording: AudioRecording) {
        scope.launch {
            try {
                if (recording.file.exists()) {
                    recording.file.delete()
                }
                refreshRecordingsList()
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting recording ${recording.fileName}", e)
            }
        }
    }

    fun clearAllRecordings() {
        val context = appContext ?: return
        scope.launch {
            try {
                val dir = getRecordingsDir(context)
                if (dir.exists()) {
                    dir.listFiles()?.forEach { file ->
                        file.delete()
                    }
                }
                refreshRecordingsList()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing all recordings", e)
            }
        }
    }

    fun shareRecording(context: Context, recording: AudioRecording) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                recording.file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Recording WAV"))
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing recording ${recording.fileName}", e)
        }
    }

    fun saveToDownloads(context: Context, recording: AudioRecording): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, recording.fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        recording.file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    true
                } else false
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, recording.fileName)
                recording.file.copyTo(targetFile, overwrite = true)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving recording to Downloads", e)
            false
        }
    }

    private fun getRecordingsDir(context: Context): File {
        val dir = File(context.filesDir, RECORDINGS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun createWavHeader(dataLength: Int): ByteArray {
        val totalDataLen = dataLength
        val totalSize = totalDataLen + 36
        val sampleRate = 16000
        val channels = 1
        val byteRate = sampleRate * channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        header[4] = (totalSize and 0xff).toByte()
        header[5] = ((totalSize shr 8) and 0xff).toByte()
        header[6] = ((totalSize shr 16) and 0xff).toByte()
        header[7] = ((totalSize shr 24) and 0xff).toByte()

        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16 // 16 for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0

        header[20] = 1 // Format = 1 (PCM)
        header[21] = 0

        header[22] = channels.toByte() // 1 channel
        header[23] = 0

        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()

        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()

        header[32] = (channels * 2).toByte() // Block align
        header[33] = 0

        header[34] = 16 // Bits per sample
        header[35] = 0

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        header[40] = (totalDataLen and 0xff).toByte()
        header[41] = ((totalDataLen shr 8) and 0xff).toByte()
        header[42] = ((totalDataLen shr 16) and 0xff).toByte()
        header[43] = ((totalDataLen shr 24) and 0xff).toByte()

        return header
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }
}
