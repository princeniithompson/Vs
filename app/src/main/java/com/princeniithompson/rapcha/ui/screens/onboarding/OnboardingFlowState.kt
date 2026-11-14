package com.princeniithompson.rapcha.ui.screens.onboarding

enum class OnboardingStage(val progressIndex: Int, val showsProgressBar: Boolean) {
    WELCOME_HERO(progressIndex = -1, showsProgressBar = false),
    GOOGLE_SIGN_IN(progressIndex = -1, showsProgressBar = false),
    GOOGLE_AUTH_HANDOFF(progressIndex = -1, showsProgressBar = false),
    INTRO(progressIndex = 0, showsProgressBar = true),
    HOW_IT_WORKS(progressIndex = 1, showsProgressBar = true),
    SANDBOX(progressIndex = 2, showsProgressBar = true),
    APPS_SHOWCASE(progressIndex = 3, showsProgressBar = true),
    OVERLAY_PERMISSION(progressIndex = 4, showsProgressBar = true),
    ACCESSIBILITY_GUIDE(progressIndex = 5, showsProgressBar = true),
    NOTIFICATION_PERMISSION(progressIndex = 6, showsProgressBar = true),
    ALL_SET(progressIndex = 6, showsProgressBar = true)
}
