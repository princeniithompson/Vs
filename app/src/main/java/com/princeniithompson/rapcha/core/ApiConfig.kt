package com.princeniithompson.rapcha.core

import android.content.Context
import com.princeniithompson.rapcha.BuildConfig
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

object ApiConfig {
    private const val PLACEHOLDER_KEY = "MY_GEMINI_API_KEY"

    const val VOICE_ID_MODEL_FILENAME = "wespeaker_emb_fp16.tflite"
    const val VOICE_ID_MODEL_URL = "https://huggingface.co/litert-community/Speaker-Diarization-LiteRT/resolve/main/wespeaker_emb_fp16.tflite"
    const val VOICE_ID_SIMILARITY_THRESHOLD = 0.68f

    /**
     * Checks if the given API key is null, blank, or matches standard placeholder tokens.
     */
    fun isPlaceholder(apiKey: String?): Boolean {
        if (apiKey.isNullOrBlank()) return true
        val trimmedUpper = apiKey.trim().uppercase(Locale.US)
        return trimmedUpper == PLACEHOLDER_KEY ||
               trimmedUpper.contains("PLACEHOLDER") ||
               trimmedUpper.contains("YOUR_") ||
               trimmedUpper.contains("MY_")
    }

    /**
     * Resolves the active Gemini API key using the strict precedence order:
     * 1. SharedPreferences custom key (if non-placeholder)
     * 2. BuildConfig.GEMINI_API_KEY from environment (if non-placeholder)
     * 3. Otherwise returns empty string ""
     */
    fun resolveApiKey(context: Context? = null): String {
        // 1. SharedPreferences custom key
        if (context != null) {
            val prefs = context.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
            val customKey = prefs.getString("custom_api_key", null)?.trim().orEmpty()
            if (customKey.isNotEmpty() && !isPlaceholder(customKey)) {
                return customKey
            }
        }

        // 2. BuildConfig.GEMINI_API_KEY
        val buildConfigKey = try {
            BuildConfig.GEMINI_API_KEY.trim()
        } catch (_: Throwable) {
            ""
        }
        if (buildConfigKey.isNotEmpty() && !isPlaceholder(buildConfigKey)) {
            return buildConfigKey
        }

        // 3. Safe fallback: empty string only
        return ""
    }

    /**
     * Backward-compatible resolution method for callers passing a custom key directly.
     */
    fun getEffectiveKey(customKey: String? = null): String {
        val trimmedCustom = customKey?.trim().orEmpty()
        if (trimmedCustom.isNotEmpty() && !isPlaceholder(trimmedCustom)) {
            return trimmedCustom
        }
        val buildConfigKey = try {
            BuildConfig.GEMINI_API_KEY.trim()
        } catch (_: Throwable) {
            ""
        }
        if (buildConfigKey.isNotEmpty() && !isPlaceholder(buildConfigKey)) {
            return buildConfigKey
        }
        return ""
    }

    /** REST generateContent URL. The API key is sent in the x-goog-api-key header, never in the query. */
    fun geminiGenerateContentUrl(modelName: String): String {
        val model = modelName.trim()
        return "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    }

    /** Files API resumable upload start. The API key stays in the header. */
    fun geminiFilesUploadUrl(): String {
        return "https://generativelanguage.googleapis.com/upload/v1beta/files"
    }

    /** v1beta resource URL, for example files/{id}. */
    fun geminiV1betaUrl(resource: String): String {
        val path = resource.trim().trimStart('/')
        return "https://generativelanguage.googleapis.com/v1beta/$path"
    }

    /** Drops a key query parameter if a Files API upload URL includes one. */
    fun withoutApiKeyQuery(url: String): String {
        val trimmed = url.trim()
        val queryStart = trimmed.indexOf('?')
        if (queryStart < 0) return trimmed
        val base = trimmed.substring(0, queryStart)
        val kept = trimmed.substring(queryStart + 1)
            .split('&')
            .filter { part ->
                val name = part.substringBefore('=').lowercase()
                name != "key" && name != "api_key"
            }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
