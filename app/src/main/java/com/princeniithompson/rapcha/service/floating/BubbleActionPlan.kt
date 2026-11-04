package com.princeniithompson.rapcha.service.floating

/**
 * Which bubble actions are on screen.
 * Live two-step hides Complete. Polish is the only forward action, and its label stays Polish.
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
        fun resolve(
            liveTwoStep: Boolean,
            polishing: Boolean,
            isAiApp: Boolean,
            optimizePrompt: Boolean
        ): BubbleActionPlan {
            if (liveTwoStep) {
                return BubbleActionPlan(
                    actions = listOf(BubbleAction.CANCEL, BubbleAction.POLISH),
                    polishLabel = if (polishing) "Polishing..." else "Polish",
                    polishIsPrimary = true
                )
            }
            val label = when {
                polishing -> "Polishing..."
                !isAiApp -> "Polish"
                optimizePrompt -> "Optimize"
                else -> "Clean"
            }
            return BubbleActionPlan(
                actions = listOf(BubbleAction.CANCEL, BubbleAction.POLISH, BubbleAction.COMPLETE),
                polishLabel = label,
                polishIsPrimary = false
            )
        }
    }
}
