package com.princeniithompson.rapcha.ui.screens.onboarding

enum class OnboardingStage(val progressIndex: Int, val totalSegments: Int = 6) {
    INTRO(0),
    HOW_IT_WORKS(1),
    SANDBOX(2),
    APPS_SHOWCASE(3),
    OVERLAY_PERMISSION(4),
    ACCESSIBILITY_GUIDE(5),
    NOTIFICATION_PERMISSION(6),
    ALL_SET(6)
}
