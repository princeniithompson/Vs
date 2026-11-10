package com.princeniithompson.rapcha.service.floating

/**
 * The recording chrome shows Cancel and Complete.
 * Complete runs the locked section's existing finish. There is no manual Polish button.
 */
enum class BubbleAction {
    CANCEL,
    POLISH,
    COMPLETE
}

data class BubbleActionPlan(
    val actions: List<BubbleAction>,
    val polishLabel: String,
    /** Filled accent button. Live two-step uses this so Polish is not a leftover ghost control. */
    val polishIsPrimary: Boolean
) {
    val showsComplete: Boolean
        get() = BubbleAction.COMPLETE in actions

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun resolve(
            liveTwoStep: Boolean,
            polishing: Boolean,
            isAiApp: Boolean,
            optimizePrompt: Boolean
        ): BubbleActionPlan {
            return BubbleActionPlan(
                actions = listOf(BubbleAction.CANCEL, BubbleAction.COMPLETE),
                polishLabel = "Complete",
                polishIsPrimary = false
            )
        }
    }
}
