package com.princeniithompson.rapcha.service

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Packages of the IMEs the device has actually enabled.
 * A name check misses keyboards whose package does not contain "inputmethod".
 */
object EnabledImePackages {

    fun parse(enabledIds: Collection<String>?, defaultIme: String? = null): Set<String> {
        val raw = ArrayList<String>()
        if (enabledIds != null) raw.addAll(enabledIds)
        if (!defaultIme.isNullOrBlank()) raw.add(defaultIme)
        return raw.mapNotNull { id ->
            val pkg = id.trim().substringBefore('/').substringBefore(':').lowercase()
            if (pkg.contains('.')) pkg else null
        }.toSet()
    }

    fun load(context: Context): Set<String> {
        val fromManager = try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.mapNotNull { it.packageName }.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        val secure = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
        } catch (_: Throwable) {
            null
        }
        val defaultIme = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (_: Throwable) {
            null
        }
        val pieces = secure?.split(':')?.filter { it.isNotBlank() }.orEmpty()
        return parse(fromManager + pieces, defaultIme)
    }
}
