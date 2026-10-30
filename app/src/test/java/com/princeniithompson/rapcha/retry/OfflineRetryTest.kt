package com.princeniithompson.rapcha.retry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.config.RapchaConfig
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.AudioRecordingRepository
import com.princeniithompson.rapcha.data.RetryClipStore
import com.princeniithompson.rapcha.data.RetryQuota
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.floating.GeminiHttpRequest
import com.princeniithompson.rapcha.service.floating.GeminiHttpResponse
import com.princeniithompson.rapcha.service.floating.GeminiHttpTransport
import com.princeniithompson.rapcha.service.floating.OfflineRetryCoordinator
import com.princeniithompson.rapcha.service.floating.OfflineRetryRules
import com.princeniithompson.rapcha.service.floating.RetryClipLength
import com.princeniithompson.rapcha.service.floating.RetryTapGate
import com.princeniithompson.rapcha.service.floating.TranscribeFallbackClient
import com.princeniithompson.rapcha.service.floating.TranscribeResult
import com.princeniithompson.rapcha.service.floating.UrlConnectionGeminiHttp
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineRetryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        RetryClipStore.init(context)
        RetryClipStore.resetForTest()
        RetryQuota.resetForTest(context)
        FloatingBubbleManager.setOfflineRetry(null)
        AudioRecordingRepository.init(context)
        AudioRecordingRepository.setSaveRecordingsEnabled(false)
    }

    @After
    fun tearDown() {
        RetryClipStore.resetForTest()
        RetryQuota.resetForTest(context)
        FloatingBubbleManager.setOfflineRetry(null)
    }

    @Test
    fun retryClipIsPrivateWavAndIgnoresSaveRecordingsPreference() {
        assertFalse(AudioRecordingRepository.isSaveRecordingsEnabled.value)
        val id = RetryClipStore.begin(context)
        val pcm = ByteArray(3200) { 0x11 }
        RetryClipStore.append(pcm)
        val clip = RetryClipStore.finalizeKeepSession()
        assertNotNull(clip)
        assertEquals(id, clip!!.id)
        assertEquals("retry", clip.wavFile.parentFile?.name)
        assertFalse(clip.wavFile.path.contains("/recordings/"))
        assertEquals(44L + pcm.size, clip.wavFile.length())
        assertEquals(pcm.size.toLong(), clip.dataBytes)
        assertEquals(100L, clip.durationMs)

        clip.wavFile.inputStream().use { input ->
            val header = ByteArray(44)
            assertEquals(44, input.read(header))
            assertEquals("RIFF", String(header, 0, 4))
            assertEquals("WAVE", String(header, 8, 4))
            assertEquals(1, header[22].toInt() and 0xFF)
            assertEquals(16_000, le32(header, 24))
            assertEquals(16, header[34].toInt() and 0xFF)
            assertEquals(pcm.size, le32(header, 40))
            val data = ByteArray(8)
            assertEquals(8, input.read(data))
            assertTrue(data.all { it == 0x11.toByte() })
        }

        val recordings = File(context.filesDir, "recordings")
        val leaked = recordings.listFiles()?.any { it.name.contains(id) } == true
        assertFalse(leaked)
    }

    @Test
    fun newSessionDropsPreviousClipAndDiscardRemovesOnlyThatId() {
        val first = RetryClipStore.begin(context)
        RetryClipStore.append(ByteArray(640))
        RetryClipStore.finalizeKeepSession()
        val second = RetryClipStore.begin(context)
        assertFalse(File(File(context.filesDir, "retry"), "$first.wav").exists())
        RetryClipStore.append(ByteArray(640))
        val kept = RetryClipStore.finalizeKeepSession()
        assertEquals(second, kept!!.id)

        val other = File(File(context.filesDir, "retry"), "other.wav")
        other.writeBytes(ByteArray(80))
        File(File(context.filesDir, "retry"), "other.json").writeText(
            """{"id":"other","createdAtMs":${System.currentTimeMillis()},"dataBytes":36,"state":"pending"}"""
        )
        RetryClipStore.discard(second)
        assertFalse(kept.wavFile.exists())
        assertTrue(other.exists())
    }

    @Test
    fun expiredRetryClipsArePurgedAfter24Hours() {
        val dir = File(context.filesDir, "retry")
        dir.mkdirs()
        val oldId = "old-clip"
        File(dir, "$oldId.wav").writeBytes(ByteArray(44 + 3200))
        val created = System.currentTimeMillis() - RetryClipStore.TTL_MS - 60_000L
        File(dir, "$oldId.json").writeText(
            """{"id":"$oldId","createdAtMs":$created,"dataBytes":3200,"state":"pending"}"""
        )
        RetryClipStore.purgeExpired(System.currentTimeMillis())
        assertFalse(File(dir, "$oldId.wav").exists())
        assertNull(RetryClipStore.latestPending())
    }

    @Test
    fun lengthPolicyWarnsAt4MinutesAndBlocksAt5() {
        assertEquals(RetryClipLength.OK, OfflineRetryRules.lengthFor(4 * 60 * 1000L - 1))
        assertEquals(RetryClipLength.WARN, OfflineRetryRules.lengthFor(OfflineRetryRules.WARN_AFTER_MS))
        assertEquals(RetryClipLength.WARN, OfflineRetryRules.lengthFor(OfflineRetryRules.BLOCK_AT_MS - 1))
        assertEquals(RetryClipLength.BLOCKED, OfflineRetryRules.lengthFor(OfflineRetryRules.BLOCK_AT_MS))
        assertEquals(
            "Long clip · 12 left today",
            OfflineRetryRules.statusLine(12, RetryClipLength.WARN, busy = false, message = null)
        )
        assertEquals(
            "Too long to retry",
            OfflineRetryRules.statusLine(12, RetryClipLength.BLOCKED, busy = false, message = null)
        )
        assertEquals("7 left today", OfflineRetryRules.statusLine(7, RetryClipLength.OK, busy = false, message = null))
        assertFalse(OfflineRetryRules.retryEnabled(0, RetryClipLength.OK, busy = false, durationMs = 1000))
        assertFalse(OfflineRetryRules.retryEnabled(3, RetryClipLength.BLOCKED, busy = false, durationMs = 1000))
        assertTrue(OfflineRetryRules.retryEnabled(3, RetryClipLength.WARN, busy = false, durationMs = 1000))
    }

    @Test
    fun retryUiAppearsForLiveFailureAndNotForHealthyRecording() {
        assertFalse(
            OfflineRetryRules.shouldOffer(
                recording = true,
                networkProblem = false,
                holdingFailure = false,
                busy = false,
                dataBytes = 3200
            )
        )
        assertTrue(
            OfflineRetryRules.shouldOffer(
                recording = true,
                networkProblem = true,
                holdingFailure = false,
                busy = false,
                dataBytes = 3200
            )
        )
        assertTrue(
            OfflineRetryRules.shouldOffer(
                recording = false,
                networkProblem = false,
                holdingFailure = true,
                busy = false,
                dataBytes = 3200
            )
        )
        assertFalse(
            OfflineRetryRules.shouldOffer(
                recording = false,
                networkProblem = true,
                holdingFailure = true,
                busy = false,
                dataBytes = 0
            )
        )
    }

    @Test
    fun pacificQuotaResetsAtMidnightAndCapsAt25() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val calendar = Calendar.getInstance(zone, Locale.US)
        calendar.set(2026, Calendar.OCTOBER, 30, 23, 59, 30)
        calendar.set(Calendar.MILLISECOND, 0)
        val beforeMidnight = calendar.timeInMillis
        calendar.add(Calendar.MINUTE, 2)
        val afterMidnight = calendar.timeInMillis
        assertEquals("2026-10-30", RetryQuota.pacificDayKey(beforeMidnight))
        assertEquals("2026-10-31", RetryQuota.pacificDayKey(afterMidnight))

        assertEquals(25, RetryQuota.remaining(context, beforeMidnight))
        assertEquals(24, RetryQuota.recordAttempt(context, beforeMidnight))
        assertEquals(24, RetryQuota.remaining(context, beforeMidnight))
        assertEquals(25, RetryQuota.remaining(context, afterMidnight))
        RetryQuota.markDailyExhausted(context, afterMidnight)
        assertEquals(0, RetryQuota.remaining(context, afterMidnight))
        assertEquals(25, RetryQuota.remaining(context, beforeMidnight))
    }

    @Test
    fun tapGateDebouncesAndRejectsOverlappingRetries() {
        var now = 5_000L
        val gate = RetryTapGate(debounceMs = RapchaConfig.RETRY_DEBOUNCE_MS, now = { now })
        assertEquals(RetryTapGate.Decision.START, gate.tryStart())
        assertEquals(RetryTapGate.Decision.BUSY, gate.tryStart())
        gate.finish()
        now += 100
        assertEquals(RetryTapGate.Decision.DEBOUNCED, gate.tryStart())
        now += RapchaConfig.RETRY_DEBOUNCE_MS
        assertEquals(RetryTapGate.Decision.START, gate.tryStart())
    }

    @Test
    fun smartRequestUsesVocabAndOmitsTimestampsAndDiarization() {
        val terms = mutableListOf("  Rapcha ", "rapcha", " ", "Gemini")
        repeat(98) { terms.add("term$it") }
        terms.add("overflow")
        val vocab = TranscribeFallbackClient.prepareCustomVocabulary(terms)
        assertEquals(100, vocab.size)
        assertEquals("Rapcha", vocab[0])
        assertEquals("Gemini", vocab[1])
        assertFalse(vocab.contains("overflow"))

        val json = TranscribeFallbackClient.buildGenerateContentJson(
            fileUri = "https://generativelanguage.googleapis.com/v1beta/files/abc",
            mimeType = "audio/wav",
            vocabulary = vocab
        )
        val config = json.getJSONObject("generationConfig").getJSONObject("audioTranscriptionConfig")
        assertEquals(setOf("mode", "customVocabulary"), config.keys().asSequence().toSet())
        assertEquals("SMART", config.getString("mode"))
        assertEquals(100, config.getJSONArray("customVocabulary").length())
        val flat = json.toString()
        assertFalse(flat.contains("wordTimestamp"))
        assertFalse(flat.contains("word_timestamp"))
        assertFalse(flat.contains("diarization"))
        assertFalse(ApiConfig.geminiGenerateContentUrl(RapchaConfig.TRANSCRIBE_MODEL).contains("key="))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/files/abc?upload_id=1",
            ApiConfig.withoutApiKeyQuery(
                "https://generativelanguage.googleapis.com/v1beta/files/abc?key=secret&upload_id=1"
            )
        )
    }

    @Test
    fun transcribeUploadsThenGeneratesAndDeletesRemoteFile() {
        val transport = ScriptedTransport()
        val client = TranscribeFallbackClient(transport) { }
        val wav = tempWav(3200)
        val result = client.transcribe("test-key", wav, listOf("Rapcha", " rapcha ", "Gemini"))
        assertTrue(result is TranscribeResult.Success)
        assertEquals("Hello from retry.", (result as TranscribeResult.Success).text)
        assertTrue(result.quotaCharge)
        assertTrue(wav.exists())

        val urls = transport.calls.map { it.url }
        assertTrue(urls.any { it == ApiConfig.geminiFilesUploadUrl() })
        assertTrue(urls.any { it.endsWith("/models/gemini-3.5-transcribe:generateContent") })
        assertTrue(transport.calls.any { it.method == "DELETE" && it.url.endsWith("/v1beta/files/abc") })
        transport.calls.forEach { call ->
            assertFalse(call.url.contains("key="))
            assertEquals("test-key", call.header("x-goog-api-key"))
        }
        val generate = transport.calls.first { it.url.contains(":generateContent") }
        val body = JSONObject(String(generate.body!!))
        val config = body.getJSONObject("generationConfig").getJSONObject("audioTranscriptionConfig")
        assertEquals("SMART", config.getString("mode"))
        assertEquals("Rapcha", config.getJSONArray("customVocabulary").getString(0))
        assertEquals("Gemini", config.getJSONArray("customVocabulary").getString(1))
        assertFalse(body.toString().contains("wordTimestamp"))
        assertFalse(body.toString().contains("diarization"))
        val upload = transport.calls.first { it.header("X-Goog-Upload-Command") == "upload, finalize" }
        assertEquals(wav.length().toInt(), upload.body?.size)
    }

    @Test
    fun transcribeKeepsGoingUntilFileIsActive() {
        val transport = ScriptedTransport(startState = "PROCESSING")
        val client = TranscribeFallbackClient(transport) { }
        val result = client.transcribe("test-key", tempWav(100), emptyList())
        assertTrue(result is TranscribeResult.Success)
        assertTrue(transport.calls.any { it.method == "GET" && it.url.endsWith("/v1beta/files/abc") })
        val config = JSONObject(String(transport.calls.first { it.url.contains("generateContent") }.body!!))
            .getJSONObject("generationConfig")
            .getJSONObject("audioTranscriptionConfig")
        assertEquals(setOf("mode"), config.keys().asSequence().toSet())
    }

    @Test
    fun httpFailuresDoNotDeleteLocalAudio() {
        val wav = tempWav(800)
        val badRequest = ScriptedTransport(generateMode = "400")
        val denied = TranscribeFallbackClient(badRequest) { }.transcribe("test-key", wav, emptyList())
        assertTrue(denied is TranscribeResult.Failure)
        denied as TranscribeResult.Failure
        assertEquals(TranscribeResult.Kind.BAD_REQUEST, denied.kind)
        assertEquals(400, denied.httpCode)
        assertTrue(denied.quotaCharge)
        assertEquals("INVALID_ARGUMENT", denied.status)
        assertTrue(wav.exists())
        assertTrue(badRequest.calls.any { it.method == "DELETE" })

        val daily = ScriptedTransport(generateMode = "429d")
        val limited = TranscribeFallbackClient(daily) { }.transcribe("test-key", wav, emptyList()) as TranscribeResult.Failure
        assertEquals(TranscribeResult.Kind.QUOTA_DAILY, limited.kind)
        assertTrue(wav.exists())

        val rate = ScriptedTransport(generateMode = "429m")
        val rpm = TranscribeFallbackClient(rate) { }.transcribe("test-key", wav, emptyList()) as TranscribeResult.Failure
        assertEquals(TranscribeResult.Kind.RATE_LIMIT, rpm.kind)

        val server = ScriptedTransport(generateMode = "500")
        val failed = TranscribeFallbackClient(server) { }.transcribe("test-key", wav, emptyList()) as TranscribeResult.Failure
        assertEquals(TranscribeResult.Kind.SERVER, failed.kind)
        assertTrue(wav.exists())

        val broken = ScriptedTransport(generateMode = "io")
        val io = TranscribeFallbackClient(broken) { }.transcribe("test-key", wav, emptyList()) as TranscribeResult.Failure
        assertEquals(TranscribeResult.Kind.IO, io.kind)
        assertFalse(io.quotaCharge)
        assertTrue(wav.exists())
    }

    @Test
    fun coordinatorInjectsSmartTextAndKeepsClipOnFailure() = runBlocking {
        var now = 20_000L
        val transport = ScriptedTransport()
        val client = TranscribeFallbackClient(transport) { }
        val coordinator = OfflineRetryCoordinator(
            client = client,
            nowElapsed = { now },
            nowWall = { 1_700_000_000_000L }
        )
        val wav = tempWav(1600)
        var injected: String? = null
        coordinator.runRetry(
            context = context,
            apiKey = "test-key",
            vocabulary = listOf("Rapcha"),
            prepareClip = { wav },
            onAcceptedText = { injected = it }
        )
        assertEquals("Hello from retry.", injected)
        assertTrue(wav.exists())
        assertEquals(24, RetryQuota.remaining(context, 1_700_000_000_000L))
        assertNull(FloatingBubbleManager.offlineRetry.value)

        injected = null
        val kept = storedClip(1600)
        val failing = OfflineRetryCoordinator(
            client = TranscribeFallbackClient(ScriptedTransport(generateMode = "500")) { },
            nowElapsed = { now + 5_000L },
            nowWall = { 1_700_000_000_000L }
        )
        failing.runRetry(
            context = context,
            apiKey = "test-key",
            vocabulary = emptyList(),
            prepareClip = { kept },
            onAcceptedText = { injected = it }
        )
        assertNull(injected)
        assertTrue(kept.exists())
        val offer = FloatingBubbleManager.offlineRetry.value
        assertNotNull(offer)
        assertEquals("Gemini error. Clip kept.", offer!!.message)
        assertTrue(offer.retriesLeft < 25)
    }

    @Test
    fun coordinatorBlocksLongClipsQuotaAndDebouncedTaps() = runBlocking {
        var now = 50_000L
        val calls = AtomicInteger(0)
        val client = TranscribeFallbackClient(object : GeminiHttpTransport {
            override fun execute(request: GeminiHttpRequest): GeminiHttpResponse {
                calls.incrementAndGet()
                throw IOException("should not upload")
            }
        }) { }
        val coordinator = OfflineRetryCoordinator(client, nowElapsed = { now }, nowWall = { 1_700_000_000_000L })
        val longClip = storedClip(9_600_000)
        coordinator.runRetry(context, "test-key", emptyList(), { longClip }) { }
        assertEquals(0, calls.get())
        assertEquals("Too long to retry", FloatingBubbleManager.offlineRetry.value?.message)

        RetryQuota.markDailyExhausted(context, 1_700_000_000_000L)
        val short = storedClip(400)
        now += 5_000L
        coordinator.runRetry(context, "test-key", emptyList(), { short }) { }
        assertEquals(0, calls.get())
        assertEquals("0 left today", FloatingBubbleManager.offlineRetry.value?.message)

        RetryQuota.resetForTest(context)
        now += 5_000L
        val ok = ScriptedTransport()
        val debounced = OfflineRetryCoordinator(
            TranscribeFallbackClient(ok) { },
            nowElapsed = { now },
            nowWall = { 1_700_000_000_000L }
        )
        debounced.runRetry(context, "test-key", emptyList(), { tempWav(400) }) { }
        val afterFirst = ok.calls.count { it.url.contains("generateContent") }
        assertEquals(1, afterFirst)
        now += 200
        debounced.runRetry(context, "test-key", emptyList(), { tempWav(400) }) { }
        assertEquals(afterFirst, ok.calls.count { it.url.contains("generateContent") })
    }

    @Test
    fun urlConnectionSendsApiKeyHeaderAndBodyWithoutQueryKey() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var seenKey: String? = null
        var seenUri = ""
        var seenBody = ""
        server.createContext("/v1beta/models/gemini-3.5-transcribe:generateContent") { exchange ->
            seenKey = exchange.requestHeaders.getFirst("x-goog-api-key")
            seenUri = exchange.requestURI.toString()
            seenBody = exchange.requestBody.readBytes().decodeToString()
            val response = """{"candidates":[{"content":{"parts":[{"text":"wired"}]}}]}"""
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val port = server.address.port
            val response = UrlConnectionGeminiHttp().execute(
                GeminiHttpRequest(
                    method = "POST",
                    url = "http://127.0.0.1:$port/v1beta/models/gemini-3.5-transcribe:generateContent",
                    headers = mapOf("x-goog-api-key" to "socket-key"),
                    body = """{"ping":true}""".toByteArray(),
                    contentType = "application/json",
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 5_000
                )
            )
            assertEquals(200, response.code)
            assertEquals("wired", TranscribeFallbackClient.parseTranscript(response.body))
            assertEquals("socket-key", seenKey)
            assertFalse(seenUri.contains("key="))
            assertTrue(seenBody.contains("\"ping\":true"))
        } finally {
            server.stop(0)
        }
    }

    private fun storedClip(pcmBytes: Int): File {
        RetryClipStore.begin(context)
        RetryClipStore.append(ByteArray(minOf(pcmBytes, 64).coerceAtLeast(1)))
        val clip = RetryClipStore.finalizeKeepSession()
        checkNotNull(clip)
        if (clip.wavFile.length() != 44L + pcmBytes) {
            java.io.RandomAccessFile(clip.wavFile, "rw").use { it.setLength(44L + pcmBytes.toLong()) }
            RetryClipStore.latestPending()
        }
        return checkNotNull(RetryClipStore.sessionWavFile())
    }

    private fun tempWav(pcmBytes: Int): File {
        val file = File.createTempFile("retry", ".wav")
        file.deleteOnExit()
        file.writeBytes(RetryClipStore.pcmWavHeader(pcmBytes) + ByteArray(pcmBytes))
        return file
    }

    private fun le32(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private class ScriptedTransport(
        private val generateMode: String = "success",
        private val startState: String = "ACTIVE"
    ) : GeminiHttpTransport {
        val calls = mutableListOf<GeminiHttpRequest>()
        private var polls = 0

        override fun execute(request: GeminiHttpRequest): GeminiHttpResponse {
            calls.add(request)
            if (generateMode == "io" && request.url.contains("generateContent")) {
                throw IOException("offline")
            }
            if (request.url == ApiConfig.geminiFilesUploadUrl()) {
                return GeminiHttpResponse(
                    code = 200,
                    body = "",
                    headers = mapOf(
                        "x-goog-upload-url" to "https://generativelanguage.googleapis.com/upload/v1beta/files?upload_id=abc&key=secret"
                    )
                )
            }
            if (request.url.contains("upload_id")) {
                return GeminiHttpResponse(
                    code = 200,
                    body = fileJson(startState),
                    headers = emptyMap()
                )
            }
            if (request.method == "GET") {
                polls += 1
                return GeminiHttpResponse(200, fileJson("ACTIVE"), emptyMap())
            }
            if (request.method == "DELETE") {
                return GeminiHttpResponse(200, "", emptyMap())
            }
            if (request.url.contains("generateContent")) {
                val response = when (generateMode) {
                    "400" -> GeminiHttpResponse(
                        400,
                        """{"error":{"status":"INVALID_ARGUMENT","message":"bad audio"}}""",
                        emptyMap()
                    )
                    "500" -> GeminiHttpResponse(500, """{"error":{"status":"INTERNAL"}}""", emptyMap())
                    "429d" -> GeminiHttpResponse(
                        429,
                        """{"error":{"status":"RESOURCE_EXHAUSTED","details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel"}]}}""",
                        emptyMap()
                    )
                    "429m" -> GeminiHttpResponse(
                        429,
                        """{"error":{"status":"RESOURCE_EXHAUSTED","details":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel"}]}}""",
                        emptyMap()
                    )
                    else -> GeminiHttpResponse(
                        200,
                        """{"candidates":[{"content":{"parts":[{"text":"Hello from retry."}]}}]}""",
                        emptyMap()
                    )
                }
                return response
            }
            return GeminiHttpResponse(404, "", emptyMap())
        }

        private fun fileJson(state: String): String {
            return """{"file":{"name":"files/abc","uri":"https://generativelanguage.googleapis.com/v1beta/files/abc?key=secret","state":"$state"}}"""
        }
    }
}
