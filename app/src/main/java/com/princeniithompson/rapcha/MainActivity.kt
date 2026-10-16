package com.princeniithompson.rapcha

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.ui.VoiceTypingScreen
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceTypingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.princeniithompson.rapcha.data.AppLogRepository.init(this)
        com.princeniithompson.rapcha.data.AudioRecordingRepository.init(this)
        com.princeniithompson.rapcha.data.CustomVocabularyRepository.init(this)
        com.princeniithompson.rapcha.data.HistoryRepository.init(this)
        com.princeniithompson.rapcha.data.InjectionLogRepository.init(this)
        com.princeniithompson.rapcha.data.AppDetectionLogRepository.init(this)
        com.princeniithompson.rapcha.data.OnboardingRepository.init(this)
        FloatingBubbleManager.init(this)
        enableEdgeToEdge()
        setContent {
            val isAppDynamicColor by FloatingBubbleManager.isAppDynamicColorEnabled.collectAsState()
            val selectedTone by FloatingBubbleManager.selectedColorTone.collectAsState()

            MyApplicationTheme(
                dynamicColor = isAppDynamicColor,
                toneId = selectedTone
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceTypingScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FloatingBubbleManager.checkPermissions(this)
        viewModel.refreshPermissions()
    }
}
