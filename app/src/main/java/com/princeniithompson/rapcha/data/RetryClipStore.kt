package com.princeniithompson.rapcha.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Always-on per-session WAV for offline Retry.
 *
 * Stored under app-private filesDir/retry, separate from the optional
 * "save recordings" library in [AudioRecordingRepository].
 */
object RetryClipStore {
    private const val TAG = "RetryClipStore"
    const val DIR_NAME = "retry"
    const val TTL_MS = 24L * 60L * 60L * 1000L
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SECOND = 32_000L // 16 kHz * 16-bit * mono
    private const val HEADER_BYTES = 44

    data class Clip(
        val id: String,
        val wavFile: File,
        val createdAtMs: Long,
        val dataBytes: Long,
        val durationMs: Long
    )

    private val writerThread = AtomicReference<Thread?>(null)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RetryClipWriter").apply {
            isDaemon = true
            writerThread.set(this)
        }
    }

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var activeId: String? = null
    @Volatile
    private var activeFile: File? = null
    @Volatile
    private var activeStream: FileOutputStream? = null
    @Volatile
    private var activeBytes: Long = 0L
    @Volatile
    private var activeCreatedAt: Long = 0L
    @Volatile
    private var sessionClip: Clip? = null
    @Volatile
    private var peekBytes: Long = 0L
    @Volatile
    private var writeFailureLogged = false
    @Volatile
    private var uploadFrozen = false

    fun durationMsForBytes(dataBytes: Long): Long {
        if (dataBytes <= 0L) return 0L
        return dataBytes * 1000L / BYTES_PER_SECOND
    }

    fun peekDataBytes(): Long = peekBytes

    fun sessionWavFile(): File? = sessionClip?.wavFile ?: activeFile

    fun init(context: Context) {
        appContext = context.applicationContext
        runWriter {
            repairAndPurgeLocked(System.currentTimeMillis())
        }
    }

    fun begin(context: Context): String {
        appContext = context.applicationContext
        var created = ""
        runWriter {
            repairAndPurgeLocked(System.currentTimeMillis())
            finalizeOpenLocked(keepOthers = true)
            // A new dictation replaces any clip the user did not keep for Retry.
            retryDir().listFiles()?.forEach { file -> file.delete() }
            sessionClip = null
            peekBytes = 0L
            val id = UUID.randomUUID().toString()
            val dir = retryDir()
            val wav = File(dir, "$id.wav")
            val stream = FileOutputStream(wav)
            stream.write(pcmWavHeader(0))
            stream.flush()
            activeId = id
            activeFile = wav
            activeStream = stream
            activeBytes = 0L
            activeCreatedAt = System.currentTimeMillis()
            peekBytes = 0L
            sessionClip = null
            writeFailureLogged = false
            uploadFrozen = false
            writeMetaLocked(id, activeCreatedAt, 0L, "open")
            created = id
        }
        return created
    }

    fun currentId(): String? = activeId ?: sessionClip?.id

    /** WAV for a pending or in-progress clip, if it already has audio samples. */
    fun clipFile(id: String?): File? {
        if (id.isNullOrBlank()) return null
        val context = appContext ?: return null
        val file = File(File(context.filesDir, DIR_NAME), "$id.wav")
        return if (file.exists() && file.length() > 44L) file else null
    }

    fun isUploadFrozen(): Boolean = uploadFrozen

    /**
     * Keep only the audio captured up to the Live drop, with a short silence pad.
     * Later microphone chunks stay off this file so Retry does not upload dead air.
     */
    fun freezeForUpload(): Long {
        var frozen = 0L
        runWriter {
            if (uploadFrozen) {
                frozen = peekBytes
                return@runWriter
            }
            try {
                activeStream?.flush()
            } catch (_: Exception) {
            }
            val pcm = readActivePcmLocked()
            val trimmed = PcmSilenceTrim.trim(pcm)
            uploadFrozen = true
            closeActiveStreamLocked()
            val file = activeFile
            if (file != null) {
                file.writeBytes(pcmWavHeader(trimmed.size) + trimmed)
            }
            activeBytes = trimmed.size.toLong()
            peekBytes = activeBytes
            frozen = activeBytes
            val id = activeId
            if (id != null) writeMetaLocked(id, activeCreatedAt, activeBytes, "frozen")
        }
        return frozen
    }

    fun append(chunk: ByteArray) {
        if (chunk.isEmpty() || uploadFrozen) return
        val id = activeId ?: return
        val copy = chunk.copyOf()
        writer.execute {
            if (uploadFrozen || activeId != id) return@execute
            val stream = activeStream ?: return@execute
            try {
                stream.write(copy)
                activeBytes += copy.size
                peekBytes = activeBytes
            } catch (e: Exception) {
                if (!writeFailureLogged) {
                    writeFailureLogged = true
                    Log.w(TAG, "Retry clip write failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Close the open WAV, keep it as the only pending retry clip, drop older ones. */
    fun finalizeKeepSession(): Clip? {
        var clip: Clip? = null
        runWriter {
            clip = finalizeOpenLocked(keepOthers = false)
        }
        return clip
    }

    /**
     * Finalize only if [id] is still the open clip. A newer session is left alone.
     */
    fun finalizeIfCurrent(id: String?) {
        if (id.isNullOrBlank()) return
        runWriter {
            if (activeId == id) {
                finalizeOpenLocked(keepOthers = false)
            }
        }
    }

    /** Delete one clip. An open clip with a different id keeps recording. */
    fun discard(id: String?) {
        if (id.isNullOrBlank()) return
        runWriter { discardIdLocked(id) }
    }

    fun latestPending(nowMs: Long = System.currentTimeMillis()): Clip? {
        var found: Clip? = null
        runWriter {
            repairAndPurgeLocked(nowMs)
            found = pendingClipsLocked(nowMs).maxByOrNull { it.createdAtMs }
            val clip = found
            if (clip != null) {
                sessionClip = clip
                peekBytes = clip.dataBytes
            }
        }
        return found
    }

    fun purgeExpired(nowMs: Long = System.currentTimeMillis()) {
        runWriter { repairAndPurgeLocked(nowMs) }
    }

    internal fun resetForTest() {
        runWriter {
            closeActiveStreamLocked()
            retryDirOrNull()?.listFiles()?.forEach { it.delete() }
            activeId = null
            activeFile = null
            activeBytes = 0L
            sessionClip = null
            peekBytes = 0L
            writeFailureLogged = false
            uploadFrozen = false
        }
    }

    private fun finalizeOpenLocked(keepOthers: Boolean): Clip? {
        val id = activeId
        val file = activeFile
        if (id == null || file == null) {
            return sessionClip
        }
        val bytes = activeBytes
        val created = activeCreatedAt
        closeActiveStreamLocked()
        patchHeader(file, bytes)
        writeMetaLocked(id, created, bytes, "pending")
        activeId = null
        activeFile = null
        activeBytes = 0L
        val clip = Clip(
            id = id,
            wavFile = file,
            createdAtMs = created,
            dataBytes = bytes,
            durationMs = durationMsForBytes(bytes)
        )
        sessionClip = clip
        peekBytes = bytes
        if (!keepOthers) {
            deleteAllExceptLocked(id)
        }
        return clip
    }

    private fun discardIdLocked(id: String) {
        if (activeId == id) {
            closeActiveStreamLocked()
            activeId = null
            activeFile = null
            activeBytes = 0L
        }
        val dir = retryDirOrNull()
        if (dir != null) {
            File(dir, "$id.wav").delete()
            File(dir, "$id.json").delete()
        }
        if (sessionClip?.id == id) sessionClip = null
        if (activeId == null) peekBytes = 0L
    }

    private fun readActivePcmLocked(): ByteArray {
        val file = activeFile ?: return ByteArray(0)
        val count = activeBytes.toInt().coerceAtLeast(0)
        if (!file.exists() || count <= 0) return ByteArray(0)
        return try {
            val all = file.readBytes()
            val start = 44
            val end = (start + count).coerceAtMost(all.size)
            if (end <= start) ByteArray(0) else all.copyOfRange(start, end)
        } catch (e: Exception) {
            Log.w(TAG, "Retry clip read failed: ${e.javaClass.simpleName}")
            ByteArray(0)
        }
    }

    private fun closeActiveStreamLocked() {
        try {
            activeStream?.flush()
            activeStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Retry clip close failed: ${e.javaClass.simpleName}")
        } finally {
            activeStream = null
        }
    }

    private fun deleteAllExceptLocked(keepId: String) {
        val dir = retryDirOrNull() ?: return
        dir.listFiles()?.forEach { file ->
            val name = file.nameWithoutExtension
            if (name != keepId) file.delete()
        }
    }

    private fun repairAndPurgeLocked(nowMs: Long) {
        val dir = retryDirOrNull() ?: return
        val wavs = dir.listFiles { file -> file.extension.equals("wav", ignoreCase = true) } ?: return
        for (wav in wavs) {
            if (wav.nameWithoutExtension == activeId) continue
            val total = wav.length()
            if (total < HEADER_BYTES) {
                wav.delete()
                File(dir, "${wav.nameWithoutExtension}.json").delete()
                continue
            }
            val dataBytes = total - HEADER_BYTES
            patchHeader(wav, dataBytes)
            val meta = readMeta(File(dir, "${wav.nameWithoutExtension}.json"))
            val created = meta?.createdAtMs ?: wav.lastModified()
            val age = nowMs - created
            if (age > TTL_MS || dataBytes <= 0L) {
                wav.delete()
                File(dir, "${wav.nameWithoutExtension}.json").delete()
            } else {
                writeMetaLocked(wav.nameWithoutExtension, created, dataBytes, "pending")
            }
        }
        dir.listFiles { file -> file.extension.equals("json", ignoreCase = true) }?.forEach { json ->
            val wav = File(dir, "${json.nameWithoutExtension}.wav")
            if (!wav.exists()) json.delete()
        }
    }

    private fun pendingClipsLocked(nowMs: Long): List<Clip> {
        val dir = retryDirOrNull() ?: return emptyList()
        val wavs = dir.listFiles { file -> file.extension.equals("wav", ignoreCase = true) } ?: return emptyList()
        val clips = ArrayList<Clip>()
        for (wav in wavs) {
            if (wav.nameWithoutExtension == activeId) continue
            val meta = readMeta(File(dir, "${wav.nameWithoutExtension}.json")) ?: continue
            if (meta.state == "open") continue
            val dataBytes = (wav.length() - HEADER_BYTES).coerceAtLeast(0L)
            if (dataBytes <= 0L) continue
            if (nowMs - meta.createdAtMs > TTL_MS) continue
            clips.add(
                Clip(
                    id = wav.nameWithoutExtension,
                    wavFile = wav,
                    createdAtMs = meta.createdAtMs,
                    dataBytes = dataBytes,
                    durationMs = durationMsForBytes(dataBytes)
                )
            )
        }
        return clips
    }

    private data class Meta(val createdAtMs: Long, val state: String)

    private fun readMeta(file: File): Meta? {
        if (!file.exists()) return null
        return try {
            val json = org.json.JSONObject(file.readText())
            Meta(
                createdAtMs = json.optLong("createdAtMs", file.lastModified()),
                state = json.optString("state", "pending")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Retry clip metadata unreadable")
            null
        }
    }

    private fun writeMetaLocked(id: String, createdAtMs: Long, dataBytes: Long, state: String) {
        val dir = retryDir()
        val json = org.json.JSONObject()
            .put("id", id)
            .put("createdAtMs", createdAtMs)
            .put("dataBytes", dataBytes)
            .put("state", state)
        File(dir, "$id.json").writeText(json.toString())
    }

    private fun retryDir(): File {
        val context = appContext ?: error("RetryClipStore is not initialized")
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun retryDirOrNull(): File? {
        val context = appContext ?: return null
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return null
        return dir
    }

    private fun runWriter(block: () -> Unit) {
        if (Thread.currentThread() == writerThread.get()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        writer.execute {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw IllegalStateException("Retry clip writer timed out")
        }
        error?.let { throw it }
    }

    internal fun pcmWavHeader(dataLength: Int): ByteArray {
        val totalSize = dataLength + 36
        val channels = 1
        val sampleRate = SAMPLE_RATE
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(HEADER_BYTES)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeLe32(header, 4, totalSize)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeLe32(header, 16, 16)
        header[20] = 1
        header[22] = channels.toByte()
        writeLe32(header, 24, sampleRate)
        writeLe32(header, 28, byteRate)
        header[32] = (channels * 2).toByte()
        header[34] = 16
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeLe32(header, 40, dataLength)
        return header
    }

    private fun patchHeader(file: File, dataBytes: Long) {
        if (!file.exists() || file.length() < HEADER_BYTES) return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val chunkSize = (36 + dataBytes).toInt()
                raf.seek(4)
                raf.write(le32(chunkSize))
                raf.seek(40)
                raf.write(le32(dataBytes.toInt()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Retry clip header patch failed: ${e.javaClass.simpleName}")
        }
    }

    private fun writeLe32(target: ByteArray, offset: Int, value: Int) {
        val bytes = le32(value)
        System.arraycopy(bytes, 0, target, offset, 4)
    }

    private fun le32(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }
}
