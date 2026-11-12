package com.princeniithompson.rapcha.service.floating

import androidx.compose.runtime.Composable
import com.princeniithompson.rapcha.ui.components.FloatingDictationPopup
import com.princeniithompson.rapcha.ui.components.overlay.CompactRecordingBar
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette

/**
 * Single source of truth for available floating bubble types.
 *
 * Registered initial bubbles:
 * - "words" (Original Words bubble)
 * - "mini" (Compact Mini bubble)
 *
 * Adding a third bubble type requires only adding a new entry to this registry.
 *
 * NOTE: Per-bubble settings / customization storage will later be associated with each entry here.
 * (e.g., val perBubbleSettings: Map<String, Any> = emptyMap())
 */
data class BubbleType(
    val id: String,
    val displayName: String,
    val previewComposable: @Composable (glowStyleId: String, tone: String, voiceLevel: Float) -> Unit,
    val liveOverlayType: LiveOverlayType
)

enum class LiveOverlayType {
    WORDS,
    MINI
}

object BubbleTypeRegistry {
    const val WORDS_ID = "words"
    const val MINI_ID = "mini"

    private val registry = LinkedHashMap<String, BubbleType>()

    init {
        registerDefaults()
    }

    private fun registerDefaults() {
        register(
            BubbleType(
                id = WORDS_ID,
                displayName = "Words",
                previewComposable = { glowId, _, voice ->
                    FloatingDictationPopup(
                        transcriptText = "",
                        isRecording = false,
                        isPendingFinalizing = false,
                        isPolishing = false,
                        audioAmplitude = voice,
                        glowStyleId = glowId,
                        onCancelClick = {},
                        onPolishClick = {},
                        onCompleteClick = {},
                        onLifebuoyClick = {},
                        blankPreview = true,
                        mimicTalkingWhenSilent = true
                    )
                },
                liveOverlayType = LiveOverlayType.WORDS
            )
        )

        register(
            BubbleType(
                id = MINI_ID,
                displayName = "Mini",
                previewComposable = { glowId, _, voice ->
                    val palette = rememberDynamicAuroraPalette()
                    CompactRecordingBar(
                        elapsedSeconds = 0,
                        appName = "WhatsApp",
                        glowStyleId = glowId,
                        palette = palette,
                        audioAmplitude = voice,
                        processing = false,
                        mimicTalkingWhenSilent = true,
                        showAppName = true,
                        onCancel = {},
                        onComplete = {}
                    )
                },
                liveOverlayType = LiveOverlayType.MINI
            )
        )
    }

    fun register(type: BubbleType) {
        registry[type.id] = type
    }

    fun unregister(id: String) {
        if (id != WORDS_ID && id != MINI_ID) {
            registry.remove(id)
        }
    }

    fun get(id: String?): BubbleType {
        val normalized = normalizeId(id)
        return registry[normalized] ?: registry[WORDS_ID]!!
    }

    fun getAll(): List<BubbleType> = registry.values.toList()

    fun normalizeId(id: String?): String {
        return when (id?.lowercase()) {
            MINI_ID, "compact" -> MINI_ID
            WORDS_ID, "original" -> WORDS_ID
            else -> {
                if (id != null && registry.containsKey(id)) id else WORDS_ID
            }
        }
    }
}
