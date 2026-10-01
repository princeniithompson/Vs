package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.VoiceTypingScreen
import com.example.ui.VoiceTypingViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceTypingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.data.AppLogRepository.init(this)
        com.example.data.AudioRecordingRepository.init(this)
        com.example.data.CustomVocabularyRepository.init(this)
        com.example.data.HistoryRepository.init(this)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceTypingScreen(viewModel = viewModel)
                }
            }
        }
    }
}
