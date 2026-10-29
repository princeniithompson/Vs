package com.princeniithompson.rapcha

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.floating.VocabSuggestions
import com.princeniithompson.rapcha.ui.VoiceTypingScreen
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceTypingViewModel by viewModels()
    private var dictionaryDraft by mutableStateOf<String?>(null)

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
        consumeDictionaryDraft(intent)
        enableEdgeToEdge()
        setContent {
            val isAppDynamicColor by FloatingBubbleManager.isAppDynamicColorEnabled.collectAsState()
            val selectedTone by FloatingBubbleManager.selectedColorTone.collectAsState()

            MyApplicationTheme(
                dynamicColor = isAppDynamicColor,
                toneId = selectedTone
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceTypingScreen(
                        viewModel = viewModel,
                        dictionaryDraft = dictionaryDraft,
                        onDictionaryDraftConsumed = {
                            dictionaryDraft = null
                            intent.removeExtra(EXTRA_DICTIONARY_DRAFT)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDictionaryDraft(intent)
    }

    override fun onResume() {
        super.onResume()
        FloatingBubbleManager.checkPermissions(this)
        FloatingBubbleManager.ensureBubbleRunning(this)
        viewModel.refreshPermissions()
    }

    private fun consumeDictionaryDraft(intent: Intent?) {
        val key = intent?.getStringExtra(VocabSuggestions.EXTRA_KEY)?.trim().orEmpty()
        if (key.isNotEmpty()) {
            VocabSuggestions.onEditOpened(this, key)
            intent?.removeExtra(VocabSuggestions.EXTRA_KEY)
        }
        val draft = intent?.getStringExtra(EXTRA_DICTIONARY_DRAFT)?.trim().orEmpty()
        if (draft.isNotEmpty()) {
            dictionaryDraft = draft
        }
    }

    companion object {
        const val EXTRA_DICTIONARY_DRAFT = "com.princeniithompson.rapcha.extra.DICTIONARY_DRAFT"
    }
}
