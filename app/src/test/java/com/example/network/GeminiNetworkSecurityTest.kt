package com.example.network

import com.example.service.floating.FloatingPolishClient
import com.example.websocket.GeminiLiveWebSocketClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiNetworkSecurityTest {

    private val testValidKey = "AIzaSyD-TestValidKey1234567890"

    @Test
    fun `websocket request does not contain key in URL query parameter`() {
        val request = GeminiLiveWebSocketClient.buildWebSocketRequest(testValidKey)
        val url = request.url

        assertNull("Query parameter 'key' must not be present in URL", url.queryParameter("key"))
        assertFalse("URL string must not contain key=", url.toString().contains("key="))
        assertEquals(GeminiLiveWebSocketClient.WS_BASE_URL.replace("wss://", "https://"), url.toString())
    }

    @Test
    fun `websocket request authenticates via x-goog-api-key header`() {
        val request = GeminiLiveWebSocketClient.buildWebSocketRequest(testValidKey)
        val authHeader = request.header("x-goog-api-key")

        assertNotNull("x-goog-api-key header must be present", authHeader)
        assertEquals(testValidKey, authHeader)
    }

    @Test
    fun `websocket request builder throws IllegalArgumentException for missing or placeholder key`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveWebSocketClient.buildWebSocketRequest("")
        }

        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveWebSocketClient.buildWebSocketRequest("   ")
        }

        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveWebSocketClient.buildWebSocketRequest("MY_GEMINI_API_KEY")
        }

        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveWebSocketClient.buildWebSocketRequest("  my_gemini_api_key  ")
        }
    }

    @Test
    fun `floating polish client throws IllegalArgumentException for missing or placeholder key`() {
        assertThrows(IllegalArgumentException::class.java) {
            FloatingPolishClient.polishTranscript(
                apiKey = "",
                rawTranscript = "Hello world"
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            FloatingPolishClient.polishTranscript(
                apiKey = "   ",
                rawTranscript = "Hello world"
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            FloatingPolishClient.polishTranscript(
                apiKey = "MY_GEMINI_API_KEY",
                rawTranscript = "Hello world"
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            FloatingPolishClient.polishTranscript(
                apiKey = "  my_gemini_api_key  ",
                rawTranscript = "Hello world"
            )
        }
    }

    @Test
    fun `mock okhttp client asserts request url contains no key query parameter and header is set`() {
        var interceptedUrl = ""
        var interceptedKeyParam: String? = null
        var interceptedHeader: String? = null

        val testInterceptor = Interceptor { chain ->
            val req = chain.request()
            interceptedUrl = req.url.toString()
            interceptedKeyParam = req.url.queryParameter("key")
            interceptedHeader = req.header("x-goog-api-key")

            // Return a dummy 200 response
            Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(okhttp3.ResponseBody.create(null, "{}"))
                .build()
        }

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(testInterceptor)
            .build()

        val request = GeminiLiveWebSocketClient.buildWebSocketRequest(testValidKey)

        // Execute request through interceptor chain
        val response = mockClient.newCall(request).execute()
        response.close()

        assertNull("Intercepted URL query parameter 'key' must be null", interceptedKeyParam)
        assertFalse("Intercepted URL must not contain 'key='", interceptedUrl.contains("key="))
        assertEquals("Intercepted header must match API key", testValidKey, interceptedHeader)
    }
}
