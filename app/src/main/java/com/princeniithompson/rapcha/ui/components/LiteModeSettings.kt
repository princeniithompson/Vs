package com.princeniithompson.rapcha.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.core.LiteMode
import com.princeniithompson.rapcha.core.LitePreference

/** v24: Lite mode status and the Auto / On / Off override. Applies on the next app start. */
@Composable
fun LiteModeSettings(context: Context) {
    var selected by remember { mutableStateOf(LiteMode.preference(context)) }
    val pendingChange = LiteMode.resolve(selected, LiteMode.weakDevice) != LiteMode.enabled
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Lite mode",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = buildString {
                append(if (LiteMode.enabled) "On: talk, Smart polish, type. Nothing else runs." else "Off: all features")
                if (LiteMode.weakDevice) append(" (weak phone detected)")
                if (pendingChange) append("\nRestart Rapcha to apply your change.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("lite_mode_status")
        )
        if (pendingChange) {
            androidx.compose.material3.FilledTonalButton(
                onClick = { restartApp(context) },
                modifier = Modifier.testTag("lite_mode_restart")
            ) { Text("Restart now", style = MaterialTheme.typography.labelSmall) }
        }
        LitePreference.entries.forEach { pref ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable {
                        selected = pref
                        LiteMode.setPreference(context, pref)
                    }
                    .testTag("lite_mode_${pref.stored}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == pref,
                    onClick = {
                        selected = pref
                        LiteMode.setPreference(context, pref)
                    }
                )
                Text(text = pref.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Lite is decided once per process, so a change needs a fresh process. Accessibility rebinds it. */
private fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
    if (launch != null) context.startActivity(launch)
    android.os.Process.killProcess(android.os.Process.myPid())
}
