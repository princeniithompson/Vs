package com.example.ui.components.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.example.data.AppLogRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility functions for exporting diagnostic event logs and notes to clipboard or text files.
 */
object DiagnosticsExportUtils {

    /**
     * Copies the full formatted diagnostics log and user notes to the Android system clipboard.
     */
    fun copyToClipboard(context: Context) {
        val export = AppLogRepository.getFullExportFormatted()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText("VoxStream Diagnostics", export)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied full history & notes to clipboard!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Failed to access clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Generates a timestamped default file name for .txt log exports.
     */
    fun generateExportFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "voxstream_diagnostics_$timestamp.txt"
    }

    /**
     * Writes the formatted diagnostics export directly into the user-selected document URI.
     */
    fun writeExportToUri(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(AppLogRepository.getFullExportFormatted().toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(context, "Diagnostics exported successfully!", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }
}
