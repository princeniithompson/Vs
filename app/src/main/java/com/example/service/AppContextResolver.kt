package com.example.service

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.util.AppResolutionEngine

/**
 * AppContextResolver:
 * Lightweight façade routing all app context queries to AppResolutionEngine in com.example.util.
 */
object AppContextResolver {

    data class AppContext(
        val group: String,
        val appName: String,
        val formatted: String
    )

    private val engine = AppResolutionEngine.defaultInstance

    fun isIgnoredPackage(context: Context?, packageName: String?): Boolean {
        return engine.isSystemOrIme(context, packageName)
    }

    fun resolve(
        context: Context,
        packageName: String?,
        windowInfo: AccessibilityWindowInfo? = null,
        rootNode: AccessibilityNodeInfo? = null,
        className: String? = null
    ): AppContext? {
        val resolved = engine.resolve(context, packageName, windowInfo, rootNode, className) ?: return null
        return AppContext(
            group = resolved.category,
            appName = resolved.name,
            formatted = resolved.formatted
        )
    }

    fun resolveAppName(context: Context, packageName: String?): String {
        val resolved = engine.resolve(context, packageName)
        return resolved?.name ?: "App"
    }
}
