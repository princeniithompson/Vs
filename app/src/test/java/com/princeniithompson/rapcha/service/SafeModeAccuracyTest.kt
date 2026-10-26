package com.princeniithompson.rapcha.service

import com.princeniithompson.rapcha.util.AppResolutionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeModeAccuracyTest {

    @Test
    fun `everyday apps stay clear unless a password field is focused`() {
        val everyday = listOf(
            "com.android.settings" to "Settings",
            "com.android.launcher3" to "Launcher",
            "com.omkar.voiceflow" to "Voiceflow",
            "ai.x.grok" to "Grok",
            "com.pinterest" to "Pinterest",
            "com.android.chrome" to "Chrome",
            "com.whatsapp" to "WhatsApp",
            "com.google.android.keep" to "Keep",
            "com.einnovation.temu" to "Temu",
            "com.alphainventor.filemanager" to "File Manager",
            "com.android.documentsui" to "Files",
            "org.chromium.webapk.aistudio" to "Google AI Studio"
        )
        for ((pkg, name) in everyday) {
            val verdict = SafeModeClassifier.classify(
                packageName = pkg,
                resolvedAppName = name,
                windowTitle = "Enter password"
            )
            assertFalse("$pkg hid on a screen title", verdict.sensitive)
            assertFalse(
                "$pkg hid on an activity name",
                SafeModeClassifier.isSensitiveContext(
                    context = null,
                    packageName = pkg,
                    className = "com.example.PasswordActivity",
                    windowTitle = "Security",
                    resolvedAppName = name
                )
            )
            val focused = SafeModeClassifier.classify(
                packageName = pkg,
                resolvedAppName = name,
                focused = SafeModeClassifier.FieldSignal(isPassword = true, editable = true, packageName = pkg)
            )
            assertTrue("$pkg should hide when a password field is focused", focused.sensitive)
            assertEquals(SafeModeClassifier.Reason.PASSWORD_FIELD, focused.reason)
            assertEquals("focused_field", focused.path)
        }
    }

    @Test
    fun `a pin image and a stray password word are not a password field`() {
        val pinImage = SafeModeClassifier.credentialToken(
            SafeModeClassifier.FieldSignal(
                editable = false,
                viewId = "com.pinterest:id/pin_image",
                contentDescription = "shopping pin"
            )
        )
        assertNull(pinImage)
        val spinner = SafeModeClassifier.credentialToken(
            SafeModeClassifier.FieldSignal(editable = true, viewId = "com.example:id/spinner")
        )
        assertNull(spinner)
        val stray = SafeModeClassifier.credentialToken(
            SafeModeClassifier.FieldSignal(editable = false, contentDescription = "password")
        )
        assertNull(stray)
        val otp = SafeModeClassifier.credentialToken(
            SafeModeClassifier.FieldSignal(editable = true, hint = "Enter OTP")
        )
        assertEquals("otp", otp)
    }

    @Test
    fun `banking betting and mobile money still engage`() {
        val apps = listOf(
            "com.sportybet.android",
            "com.sportybet.android.gp.gh",
            "com.mtn.mymtn",
            "com.mtngh.mymtn",
            "com.gcb.mobile",
            "com.modefin.gcbui",
            "com.chase.sig.android"
        )
        for (pkg in apps) {
            val verdict = SafeModeClassifier.classify(packageName = pkg, windowTitle = "Home")
            assertTrue(pkg, verdict.sensitive)
            assertTrue(
                verdict.reason == SafeModeClassifier.Reason.PACKAGE_LIST ||
                    verdict.reason == SafeModeClassifier.Reason.KEYWORD
            )
            assertTrue(verdict.describe(pkg).contains("pkg=$pkg"))
            assertTrue(verdict.describe(pkg).contains("path="))
        }
        val named = SafeModeClassifier.classify(
            packageName = "com.example.unknown",
            resolvedAppName = "GCB Mobile"
        )
        assertTrue(named.sensitive)
        assertEquals("gcb", named.detail)
        assertEquals("name_keyword", named.path)
    }

    @Test
    fun `a visible password counts only inside the foreground app window`() {
        val otherWindow = SafeModeClassifier.classify(
            packageName = "com.example.notes",
            visiblePassword = true,
            visiblePackage = "com.other.app"
        )
        assertFalse(otherWindow.sensitive)
        val sameWindow = SafeModeClassifier.classify(
            packageName = "com.example.notes",
            visiblePassword = true,
            visiblePackage = "com.example.notes"
        )
        assertTrue(sameWindow.sensitive)
        assertEquals("visible_field", sameWindow.path)
        val settings = SafeModeClassifier.classify(
            packageName = "com.android.settings",
            resolvedAppName = "Settings",
            visiblePassword = true,
            visiblePackage = "com.android.settings"
        )
        assertFalse(settings.sensitive)
    }

    @Test
    fun `file managers are not ai apps and ai studio still is`() {
        assertEquals(AppCategory.OTHER, AppClassifier.classify("com.alphainventor.filemanager", "File Manager"))
        assertEquals(AppCategory.OTHER, AppClassifier.classify("com.android.documentsui", "Files"))
        assertEquals(AppCategory.OTHER, AppClassifier.classify("com.google.android.apps.nbu.files", "Files by Google"))
        assertEquals(AppCategory.OTHER, AppClassifier.classify("com.omkar.voiceflow", "Voiceflow"))
        assertEquals(AppCategory.AI_CHAT, AppClassifier.classify("ai.x.grok", "Grok"))
        assertEquals(AppCategory.AI_CHAT, AppClassifier.classify(null, "Google AI Studio"))
        assertEquals(AppCategory.AI_CHAT, AppClassifier.classify("com.openai.chatgpt", "ChatGPT"))
        assertEquals(AppCategory.EMAIL, AppCategory.fromString("Email"))
        assertEquals(AppCategory.EMAIL, AppCategory.fromString("Gmail"))
        assertEquals(AppCategory.OTHER, AppCategory.fromString("DAILY"))
        assertEquals(AppCategory.OTHER, AppCategory.fromString("DETAIL"))
        assertEquals(AppCategory.AI_CHAT, AppCategory.fromString("AI"))
        assertEquals(AppCategory.AI_CHAT, AppCategory.fromString("AI Chat"))

        val engine = AppResolutionEngine()
        val files = engine.resolveWithEvidence(
            AppResolutionEngine.AppEvidence(
                packageName = "com.alphainventor.filemanager",
                appLabel = "File Manager",
                localDisplayName = "File Manager"
            )
        )
        assertFalse(files!!.isAiApp)
        assertNotEquals("AI", files.category)
        val detail = engine.resolveWithEvidence(
            AppResolutionEngine.AppEvidence(
                packageName = "com.example.detail",
                appLabel = "Detail",
                localDisplayName = "Available detail"
            )
        )
        assertFalse(detail!!.isAiApp)
        val studio = engine.resolveWithEvidence(
            AppResolutionEngine.AppEvidence(
                packageName = "com.example.studio",
                localDisplayName = "Google AI Studio"
            )
        )
        assertTrue(studio!!.isAiApp)
    }
}
