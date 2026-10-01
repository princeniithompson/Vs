package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified, persistent diagnostics logger covering both the in-app recorder and the floating bubble.
 * Durable across app restarts and phone reboots by appending to a JSONL file in app's private filesDir.
 */
object AppLogRepository {
    private const val TAG = "AppLogRepository"
    private const val LOGS_FILE_NAME = "diagnostics_history.jsonl"
    private const val NOTES_FILE_NAME = "diagnostics_notes.jsonl"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null
    private var isInitialized = false

    // Multi-day persistent log entries (newest first for display)
    private val _diagnosticEntries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val diagnosticEntries: StateFlow<List<DiagnosticLogEntry>> = _diagnosticEntries.asStateFlow()

    // Notes list (newest first)
    private val _notes = MutableStateFlow<List<DiagnosticNote>>(emptyList())
    val notes: StateFlow<List<DiagnosticNote>> = _notes.asStateFlow()

    // Real-time protocol frames for "Live" tab inspector
    private const val MAX_LIVE_FRAMES = 150
    private val _liveFrames = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _liveFrames.asStateFlow()

    fun init(context: Context) {
        if (isInitialized && appContext != null) return
        appContext = context.applicationContext
        isInitialized = true
        loadFromDisk()
    }

    private fun loadFromDisk() {
        scope.launch {
            val context = appContext ?: return@launch
            try {
                // 1. Load Diagnostic logs from JSONL
                val logFile = File(context.filesDir, LOGS_FILE_NAME)
                val loadedLogs = mutableListOf<DiagnosticLogEntry>()
                if (logFile.exists()) {
                    logFile.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) {
                                try {
                                    val obj = JSONObject(trimmed)
                                    val entry = DiagnosticLogEntry(
                                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                                        source = DiagnosticSource.valueOf(obj.optString("source", "APP")),
                                        type = DiagnosticType.valueOf(obj.optString("type", "SESSION_START")),
                                        message = obj.optString("message", "")
                                    )
                                    loadedLogs.add(entry)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error parsing log JSONL line", e)
                                }
                            }
                        }
                    }
                }
                // Sort newest first
                _diagnosticEntries.value = loadedLogs.sortedByDescending { it.timestamp }

                // 2. Load Notes from JSONL
                val noteFile = File(context.filesDir, NOTES_FILE_NAME)
                val loadedNotes = mutableListOf<DiagnosticNote>()
                if (noteFile.exists()) {
                    noteFile.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) {
                                try {
                                    val obj = JSONObject(trimmed)
                                    val note = DiagnosticNote(
                                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                                        text = obj.optString("text", "")
                                    )
                                    loadedNotes.add(note)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error parsing note JSONL line", e)
                                }
                            }
                        }
                    }
                }
                _notes.value = loadedNotes.sortedByDescending { it.timestamp }

                Log.d(TAG, "Loaded ${loadedLogs.size} diagnostic logs and ${loadedNotes.size} notes from disk")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load diagnostics from disk", e)
            }
        }
    }

    /**
     * Unified event-level logging entry point for both APP and BUBBLE.
     * Appends to persistent JSONL on private storage off the main thread.
     */
    fun logEvent(source: DiagnosticSource, type: DiagnosticType, message: String) {
        val entry = DiagnosticLogEntry(
            source = source,
            type = type,
            message = message
        )

        // Update in-memory state (prepend newest)
        _diagnosticEntries.update { current ->
            listOf(entry) + current
        }

        // Off-thread durable append
        scope.launch {
            val context = appContext ?: return@launch
            try {
                val file = File(context.filesDir, LOGS_FILE_NAME)
                val json = JSONObject().apply {
                    put("id", entry.id)
                    put("timestamp", entry.timestamp)
                    put("source", entry.source.name)
                    put("type", entry.type.name)
                    put("message", entry.message)
                }
                file.appendText(json.toString() + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed appending diagnostic event to disk", e)
            }
        }
    }

    /**
     * Adds an auto-timestamped note to the persistent store.
     */
    fun addNote(text: String) {
        if (text.isBlank()) return
        val note = DiagnosticNote(text = text.trim())

        _notes.update { current ->
            listOf(note) + current
        }

        scope.launch {
            val context = appContext ?: return@launch
            try {
                val file = File(context.filesDir, NOTES_FILE_NAME)
                val json = JSONObject().apply {
                    put("id", note.id)
                    put("timestamp", note.timestamp)
                    put("text", note.text)
                }
                file.appendText(json.toString() + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed appending note to disk", e)
            }
        }
    }

    /**
     * Real-time socket frame logger for Live tab stream view
     */
    fun addLog(level: LogLevel, tag: String, message: String, payload: String? = null) {
        val entry = LogEntry(level = level, tag = tag, message = message, payload = payload)
        _liveFrames.update { current ->
            (current + entry).takeLast(MAX_LIVE_FRAMES)
        }
    }

    fun clearLiveFrames() {
        _liveFrames.value = emptyList()
    }

    /**
     * Wipes all persisted diagnostics history and notes from disk and memory.
     */
    fun clearAllDiagnostics() {
        _diagnosticEntries.value = emptyList()
        _notes.value = emptyList()
        _liveFrames.value = emptyList()

        scope.launch {
            val context = appContext ?: return@launch
            try {
                File(context.filesDir, LOGS_FILE_NAME).delete()
                File(context.filesDir, NOTES_FILE_NAME).delete()
                Log.d(TAG, "All persisted diagnostics and notes cleared from disk.")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting diagnostics files", e)
            }
        }
    }

    /**
     * Builds one combined, chronological .txt covering full persisted history plus all notes.
     */
    fun getFullExportFormatted(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateHeaderFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())

        val entries = _diagnosticEntries.value.sortedBy { it.timestamp }
        val allNotes = _notes.value.sortedBy { it.timestamp }

        return buildString {
            append("====================================================\n")
            append("      VOXSTREAM DIAGNOSTICS & PROTOCOL MONITOR      \n")
            append("      Unified Persistent Multi-Day Export           \n")
            append("====================================================\n")
            append("Export Date: ${sdf.format(Date())}\n")
            append("Total Events: ${entries.size}\n")
            append("Total Notes: ${allNotes.size}\n\n")

            // Section 1: Notes
            append("----------------------------------------------------\n")
            append("                DIAGNOSTIC USER NOTES               \n")
            append("----------------------------------------------------\n")
            if (allNotes.isEmpty()) {
                append("(No user notes recorded)\n\n")
            } else {
                allNotes.forEachIndexed { idx, note ->
                    append("[#${idx + 1}] [${sdf.format(Date(note.timestamp))}]\n")
                    append("     ${note.text}\n\n")
                }
            }

            // Section 2: Combined Timeline
            append("----------------------------------------------------\n")
            append("                 DIAGNOSTIC EVENTS                  \n")
            append("----------------------------------------------------\n")
            if (entries.isEmpty()) {
                append("(No diagnostic events recorded yet)\n\n")
            } else {
                var lastDayStr = ""
                entries.forEach { entry ->
                    val dayStr = dateHeaderFormat.format(Date(entry.timestamp))
                    if (dayStr != lastDayStr) {
                        append("\n>>> DATE: $dayStr <<<\n")
                        lastDayStr = dayStr
                    }
                    val timeStr = sdf.format(Date(entry.timestamp))
                    append("[$timeStr] [${entry.source.name}] [${entry.type.name}] ${entry.message}\n")
                }
            }

            append("\n==================== END OF EXPORT ====================\n")
        }
    }

    /**
     * Legacy formatted string for backward compatibility
     */
    fun getAllLogsFormatted(): String {
        return getFullExportFormatted()
    }
}
