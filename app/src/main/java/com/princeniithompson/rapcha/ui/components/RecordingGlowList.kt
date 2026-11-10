package com.princeniithompson.rapcha.ui.components

/**
 * The five glows shown on the Recording glows screen.
 * The other catalogue styles stay available to the live overlay.
 */
enum class GlowBrushKind {
    VERTICAL,
    LINEAR,
    RADIAL
}

data class RecordingGlow(
    val id: String,
    val name: String,
    val kind: GlowBrushKind,
    val angleDegrees: Float,
    val luminous: List<Pair<Float, Int>>,
    val deep: List<Pair<Float, Int>>
) {
    fun stops(deepMode: Boolean): List<Pair<Float, Int>> = if (deepMode) deep else luminous
}

object RecordingGlowList {
    private val retired = setOf(
        "bioluminescent_deep",
        "liquid_glass",
        "velvet_aura"
    )

    val glows: List<RecordingGlow> = listOf(
        RecordingGlow(
            id = "breathing_horizon",
            name = "Breathing Horizon",
            kind = GlowBrushKind.VERTICAL,
            angleDegrees = 90f,
            luminous = listOf(0f to 0xFFBFE8FF.toInt(), 0.5f to 0xFF7BE0B0.toInt(), 1f to 0xFF2FBF6A.toInt()),
            deep = listOf(0f to 0xFF07161F.toInt(), 0.5f to 0xFF0F4A3A.toInt(), 1f to 0xFF1A6A3A.toInt())
        ),
        RecordingGlow(
            id = "electric_sunburst",
            name = "Electric Sunburst",
            kind = GlowBrushKind.LINEAR,
            angleDegrees = 135f,
            luminous = listOf(0f to 0xFFFFD23F.toInt(), 0.5f to 0xFFFF7A2F.toInt(), 1f to 0xFFFF1F8E.toInt()),
            deep = listOf(0f to 0xFF3A1A05.toInt(), 0.5f to 0xFF8A2A0A.toInt(), 1f to 0xFF5A0A3A.toInt())
        ),
        RecordingGlow(
            id = "sunset_mirage",
            name = "Sunset Mirage",
            kind = GlowBrushKind.VERTICAL,
            angleDegrees = 90f,
            luminous = listOf(
                0f to 0xFF7B2FD0.toInt(),
                0.31f to 0xFFD04A9A.toInt(),
                0.62f to 0xFFFFB347.toInt(),
                0.621f to 0xFF4A1B6B.toInt(),
                1f to 0xFF4A1B6B.toInt()
            ),
            deep = listOf(
                0f to 0xFF1A0F3A.toInt(),
                0.31f to 0xFF5A1F5A.toInt(),
                0.62f to 0xFF8A4A1A.toInt(),
                0.621f to 0xFF120A24.toInt(),
                1f to 0xFF120A24.toInt()
            )
        ),
        RecordingGlow(
            id = "gentle_slice",
            name = "Gentle Wave Slice",
            kind = GlowBrushKind.LINEAR,
            angleDegrees = 160f,
            luminous = listOf(0f to 0xFFD8F3FF.toInt(), 0.5f to 0xFF4AA3F0.toInt(), 1f to 0xFF5B6CF0.toInt()),
            deep = listOf(0f to 0xFF07182A.toInt(), 0.5f to 0xFF0E3D66.toInt(), 1f to 0xFF1A2A6A.toInt())
        ),
        RecordingGlow(
            id = "pulsing_spotlight",
            name = "Flashlight Core",
            kind = GlowBrushKind.RADIAL,
            angleDegrees = 90f,
            luminous = listOf(
                0f to 0xFFFFFFFF.toInt(),
                0.35f to 0xFF7CC4FF.toInt(),
                0.7f to 0xFF0A4FD6.toInt(),
                1f to 0xFF08204F.toInt()
            ),
            deep = listOf(0f to 0xFF2A5FA8.toInt(), 0.5f to 0xFF0A2A66.toInt(), 1f to 0xFF040D24.toInt())
        )
    )

    val names: List<String> = glows.map { it.name }

    fun byId(id: String): RecordingGlow = glows.first { it.id == resolve(id) }

    /** Old glows that are not on this screen become Breathing Horizon. */
    fun resolve(savedId: String?): String {
        if (savedId != null && glows.any { it.id == savedId }) return savedId
        return glows.first().id
    }

    fun isRetired(savedId: String?): Boolean = savedId != null && savedId in retired
}

object RecordingGlowColors {
    const val PAGE = 0xFFE2EFCF.toInt()
    const val TEXT = 0xFF2A3320.toInt()
    const val HEADER_FILL = 0xFFF4F8EA.toInt()
    const val DOT = 0xFFB3C29A.toInt()
    const val ACCENT = 0xFF6F8F12.toInt()
    const val MODE_TRACK = 0xFFCFDCB4.toInt()
    const val MODE_OFF = 0xFF3F4F1A.toInt()
    const val ON_SELECTED = 0xFFFFFFFF.toInt()
    const val ROW = 0xFFF7F9F1.toInt()
    const val ROW_ON = 0xFFEAF3CF.toInt()
    const val ROW_RING = 0xFF8FAE1E.toInt()
    const val HOLLOW = 0xFF9AA58A.toInt()
    const val BUBBLE = 0xFF17211B.toInt()
    const val POLISH = 0xFFA3C21C.toInt()
    const val POLISH_TEXT = 0xFF1F2D00.toInt()
    const val CANCEL_TEXT = 0xFFFFB4A8.toInt()

    fun isOpaque(argb: Int): Boolean = argb ushr 24 == 0xFF

    val solidControls: List<Int> = listOf(
        PAGE, TEXT, HEADER_FILL, DOT, ACCENT, MODE_TRACK, MODE_OFF, ON_SELECTED,
        ROW, ROW_ON, ROW_RING, HOLLOW, BUBBLE, POLISH, POLISH_TEXT, CANCEL_TEXT
    )
}
