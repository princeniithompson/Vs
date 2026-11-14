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
     * Resolves the active Gemini API key using strict precedence:
     * 1. Encrypted personal user key from ApiKeyVault (provisioned via Google onboarding)
     * 2. SharedPreferences manual custom_api_key (if non-placeholder)
     * 3. Otherwise returns empty string "" (user API key is strictly required)
     */
    fun resolveApiKey(context: Context? = null): String {
        if (context != null) {
            ApiKeyVault.init(context)
        }

        // 1. User's personal key from ApiKeyVault
        val vaultKey = ApiKeyVault.currentUserApiKey.trim()
        if (vaultKey.isNotEmpty() && !isPlaceholder(vaultKey)) {
            return vaultKey
        }

        // 2. SharedPreferences manual override key
        if (context != null) {
            val prefs = context.getSharedPreferences(ApiKeyVault.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            val customKey = prefs.getString(ApiKeyVault.KEY_CUSTOM_API_KEY, null)?.trim().orEmpty()
            if (customKey.isNotEmpty() && !isPlaceholder(customKey)) {
                return customKey
            }
        }

        // 3. No user API key present
        return ""
    }

    /**
     * Backward-compatible resolution method for callers passing a manual custom key.
     * Prefers an explicit manual key or the user's ApiKeyVault key; returns "" if neither exists.
     */
    fun getEffectiveKey(customKey: String? = null): String {
        val trimmedCustom = customKey?.trim().orEmpty()
        if (trimmedCustom.isNotEmpty() && !isPlaceholder(trimmedCustom)) {
            return trimmedCustom
        }
        val vaultKey = ApiKeyVault.currentUserApiKey.trim()
        if (vaultKey.isNotEmpty() && !isPlaceholder(vaultKey)) {
            return vaultKey
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
