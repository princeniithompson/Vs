package com.example.core

import android.content.Context
import com.example.BuildConfig
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

object ApiConfig {
    private const val PLACEHOLDER_KEY = "MY_GEMINI_API_KEY"

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

    fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
