package com.princeniithompson.rapcha.ui.components.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.service.floating.LivePolishOutcome
import com.princeniithompson.rapcha.service.floating.LivePolishProtocol
import com.princeniithompson.rapcha.service.floating.LiveTextPolishClient
import com.princeniithompson.rapcha.service.floating.Stage2Category
import com.princeniithompson.rapcha.service.floating.Stage2LivePolish
import com.princeniithompson.rapcha.service.floating.Stage2Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Talks to the same gemini-3.8-live client Polish uses when two-step is on.
 */
@Composable
fun DiagnosticsLiveModelSection(
    apiKey: String,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("Ping checks setup only. Send runs a Stage-2 polish turn.") }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        if (ApiConfig.isPlaceholder(apiKey)) {
            output = "Add a Gemini API key in Voice Activity first."
            return
        }
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumSurface()
                .padding(16.dp)
        ) {
            Text(
                text = "Gemini 3.8 Live",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = DiagnosticsPalette.ink
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = LivePolishProtocol.MODEL,
                style = MaterialTheme.typography.bodySmall,
                color = DiagnosticsPalette.teal
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Same socket and setup as two-step Polish. Audio in, transcript out.",
                style = MaterialTheme.typography.bodySmall,
                color = DiagnosticsPalette.muted
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Prompt") },
            minLines = 2,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                run { output = sendTurn(apiKey, prompt) }
            })
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { run { output = pingLive(apiKey) } },
                enabled = !busy
            ) {
                Text(if (busy) "Working…" else "Ping Live")
            }
            Button(
                onClick = { run { output = sendTurn(apiKey, prompt) } },
                enabled = !busy && prompt.isNotBlank()
            ) {
                Text("Send")
            }
        }

        Text(
            text = output,
            modifier = Modifier
                .fillMaxWidth()
                .premiumSurface()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = DiagnosticsPalette.ink
        )
    }
}

private suspend fun pingLive(apiKey: String): String = withContext(Dispatchers.IO) {
    AppLogRepository.logEvent(
        DiagnosticSource.APP,
        DiagnosticType.STAGE2_LIVE_START,
        "source=ping model=${LivePolishProtocol.MODEL}"
    )
    val response = LiveTextPolishClient().ping(apiKey)
    val line = if (response.text == "setup" && response.error == null) {
        AppLogRepository.logEvent(
            DiagnosticSource.APP,
            DiagnosticType.STAGE2_LIVE_SUCCESS,
            "source=ping model=${LivePolishProtocol.MODEL} ${response.trace}"
        )
        "Setup complete in ${response.firstTokenMs} ms.\n${response.trace}"
    } else {
        AppLogRepository.logEvent(
            DiagnosticSource.APP,
            DiagnosticType.STAGE2_LIVE_FAILED,
            "source=ping model=${LivePolishProtocol.MODEL} reason=${response.error} ${response.trace}"
        )
        LivePolishOutcome.userLine(response.error).removeSuffix(" Your words were inserted.") +
            "\n${response.trace}"
    }
    line
}

private suspend fun sendTurn(apiKey: String, prompt: String): String {
    val text = prompt.trim()
    if (text.isEmpty()) return "Type a short prompt first."
    return withContext(Dispatchers.IO) {
        val paste = Stage2LivePolish.execute(
            rawTranscript = text,
            category = Stage2Category.OTHER,
            protectedTerms = emptyList(),
            stage1Dropped = false,
            apiKey = apiKey,
            appName = "Diagnostics",
            model = LiveTextPolishClient(),
            log = { type, message ->
                AppLogRepository.logEvent(DiagnosticSource.APP, type, "source=console $message")
            }
        )
        if (paste.outcome == Stage2Outcome.SUCCESS) {
            paste.text
        } else {
            LivePolishOutcome.userLine(paste.error).removeSuffix(" Your words were inserted.")
        }
    }
}
