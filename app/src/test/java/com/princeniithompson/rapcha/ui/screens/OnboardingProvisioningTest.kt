package com.princeniithompson.rapcha.ui.screens

import android.app.Application
import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.core.ApiKeyFormat
import com.princeniithompson.rapcha.core.ApiKeyVault
import com.princeniithompson.rapcha.ui.screens.onboarding.OnboardingGoogleHandoffScreen
import com.princeniithompson.rapcha.ui.screens.onboarding.OnboardingGoogleSignInScreen
import com.princeniithompson.rapcha.ui.screens.onboarding.OnboardingHeroWelcomeScreen
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.ProvisioningWebMath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingProvisioningTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application

    private val testCipherAdapter = object : ApiKeyVault.CipherAdapter {
        override fun encrypt(plainText: String): String {
            return Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }

        override fun decrypt(cipherBlobBase64: String): String {
            return String(Base64.decode(cipherBlobBase64, Base64.NO_WRAP), Charsets.UTF_8)
        }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ApiKeyVault.resetForTesting()
        ApiKeyVault.setCipherAdapterForTesting(testCipherAdapter)
    }

    @After
    fun tearDown() = runBlocking {
        ApiKeyVault.clearUserCredentials(app)
        ApiKeyVault.resetForTesting()
    }

    @Test
    fun `dual regex validates both classic AIzaSy and newer AQ keys`() {
        val classic = "AIzaSyD-1234567890_abcdefghijklmnopQRSTUV"
        val aqKey = "AQ.Ab8RN6K_1234567890.abcdef-GHIJKLMN_OPQRST"

        assertTrue(ApiKeyFormat.isValidGeminiKey(classic))
        assertTrue(ApiKeyFormat.isValidGeminiKey(aqKey))
        assertFalse(ApiKeyFormat.isValidGeminiKey("MY_GEMINI_API_KEY"))
        assertFalse(ApiKeyFormat.isValidGeminiKey("short"))

        assertEquals(
            aqKey,
            ApiKeyFormat.extractFirstGeminiKey("Your new key is $aqKey ready")
        )
        assertEquals(
            "princeniithompson@gmail.com",
            ApiKeyFormat.extractFirstEmail("Google Account: Prince (princeniithompson@gmail.com)")
        )
    }

    @Test
    fun `web math normalizes coordinates and strips wv from user agent`() {
        assertEquals(500, ProvisioningWebMath.normalizeToScale1000(540f, 1080f))
        assertEquals(0, ProvisioningWebMath.normalizeToScale1000(-10f, 1080f))
        assertEquals(1000, ProvisioningWebMath.normalizeToScale1000(1500f, 1080f))
        assertEquals(0, ProvisioningWebMath.normalizeToScale1000(100f, 0f))
        assertEquals(540f, ProvisioningWebMath.denormalizeFromScale1000(500, 1080), 0.5f)

        val cleanedUa = ProvisioningWebMath.stripWebViewTokenFromUserAgent(
            "Mozilla/5.0 (Linux; Android 15; Pixel 8; wv) AppleWebKit/537.36 Chrome/129.0.0.0 Mobile Safari/537.36"
        )
        assertFalse(cleanedUa.contains("; wv"))

        assertTrue(ProvisioningWebMath.isGoogleAuthUrl("https://accounts.google.com/v3/signin/identifier"))
        assertTrue(ProvisioningWebMath.isAiStudioUrl("https://aistudio.google.com/api-keys"))
        assertTrue(ProvisioningWebMath.isApiKeysRoute("https://aistudio.google.com/app/apikey"))
        assertFalse(ProvisioningWebMath.isApiKeysRoute("https://aistudio.google.com/prompts/new_chat"))

        assertEquals(
            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Faistudio.google.com%2Fapi-keys",
            ProvisioningWebMath.buildGoogleSignInUrl()
        )
        assertEquals(
            "https://aistudio.google.com/api-keys",
            ProvisioningWebMath.extractIntentFallbackUrl(
                "intent://aistudio.google.com#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Faistudio.google.com%2Fapi-keys;end"
            )
        )
        assertNull(ProvisioningWebMath.extractIntentFallbackUrl("https://aistudio.google.com"))
    }

    @Test
    fun `vault encrypts saves and restores user key and overrides bundled key`() = runBlocking {
        val personalKey = "AIzaSyPersonalUserKey1234567890ABCDEFGHIJ"
        val saved = ApiKeyVault.saveUserApiKey(app, personalKey, "user@gmail.com")
        assertTrue(saved)
        assertEquals(personalKey, ApiConfig.resolveApiKey(app))
        assertEquals("user@gmail.com", ApiKeyVault.googleEmailFlow.value)

        // Simulate reload from DataStore
        ApiKeyVault.resetForTesting()
        ApiKeyVault.setCipherAdapterForTesting(testCipherAdapter)
        ApiKeyVault.loadFromDataStore(app)
        assertEquals(personalKey, ApiKeyVault.currentUserApiKey)
        assertEquals(personalKey, ApiConfig.resolveApiKey(app))
    }

    @Test
    fun `hero welcome and google-only sign-in and handoff render cleanly`() {
        var started = false
        composeRule.setContent {
            OnboardingHeroWelcomeScreen(onGetStarted = { started = true })
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_hero_welcome_screen").assertIsDisplayed()
        composeRule.onNodeWithText("4x faster than typing").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_hero_get_started").performClick()
        assertTrue(started)
    }

    @Test
    fun `google sign in page omits other providers and terms of service`() {
        var googleClicked = false
        composeRule.setContent {
            OnboardingGoogleSignInScreen(onContinueWithGoogle = { googleClicked = true })
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue with Google").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with Apple").assertDoesNotExist()
        composeRule.onNodeWithText("Continue with Microsoft").assertDoesNotExist()
        composeRule.onNodeWithText("Terms of Service", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("btn_continue_with_google").performClick()
        assertTrue(googleClicked)
    }

    @Test
    fun `google handoff screen shows email and open rapcha action`() {
        var opened = false
        composeRule.setContent {
            OnboardingGoogleHandoffScreen(
                email = "princeniithompson@gmail.com",
                onOpenRapcha = { opened = true },
                onUseAnotherAccount = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Log in as").assertIsDisplayed()
        composeRule.onNodeWithText("princeniithompson@gmail.com").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_open_rapcha").performClick()
        assertTrue(opened)
    }

    @Test
    fun `hero screen caps button width cleanly on tablet viewports`() {
        composeRule.setContent {
            Box(modifier = Modifier.requiredSize(width = 900.dp, height = 1280.dp)) {
                OnboardingHeroWelcomeScreen(onGetStarted = {})
            }
        }
        composeRule.waitForIdle()
        val unclipped = composeRule.onNodeWithTag("btn_hero_get_started")
            .getUnclippedBoundsInRoot()
        val widthDp = unclipped.right - unclipped.left
        assertTrue("Expected width in 200.dp..460.dp, got $widthDp", widthDp in 200.dp..460.dp)
    }

    @Test
    fun `api config returns empty when no user key exists even if build config has key`() = runBlocking {
        ApiKeyVault.clearUserCredentials(app)
        ApiKeyVault.resetForTesting()

        assertEquals("", ApiConfig.resolveApiKey(app))
        assertEquals("", ApiConfig.getEffectiveKey(""))
        assertEquals("", ApiConfig.getEffectiveKey(null))
        assertFalse(ApiKeyVault.hasUserApiKey())
    }

    @Test
    fun `saving provisioned key automatically populates custom_api_key setting and clearing removes it`() = runBlocking {
        val aqKey = "AQ.FakeUnitTestKeyNotReal000000000000000000000"
        val saved = ApiKeyVault.saveUserApiKey(app, aqKey, "user@gmail.com")
        assertTrue(saved)

        val prefs = app.getSharedPreferences(ApiKeyVault.SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        assertEquals(aqKey, prefs.getString(ApiKeyVault.KEY_CUSTOM_API_KEY, ""))
        assertEquals(aqKey, ApiConfig.resolveApiKey(app))

        ApiKeyVault.clearUserKeyOnly(app)
        assertEquals("", prefs.getString(ApiKeyVault.KEY_CUSTOM_API_KEY, ""))
        assertEquals("", ApiConfig.resolveApiKey(app))
    }

    @Test
    fun `view model blocks recording without key and auto injects copied key from clipboard`() = runBlocking {
        ApiKeyVault.clearUserCredentials(app)
        val vm = com.princeniithompson.rapcha.ui.VoiceTypingViewModel(app)

        assertFalse(vm.isApiKeyConfigured())
        vm.startRecording()
        assertFalse(vm.isRecording.value)

        val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val copiedAqKey = "AQ.FakeUnitTestKeyNotReal000000000000000000000"
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("API Key", copiedAqKey))

        assertTrue(vm.tryAutoInjectFromClipboard())
        assertTrue(vm.isApiKeyConfigured())
        assertEquals(copiedAqKey, vm.customApiKey.value)
        assertEquals(copiedAqKey, vm.effectiveApiKey)
        assertEquals(copiedAqKey, ApiConfig.resolveApiKey(app))

        val afterClipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
        assertFalse(afterClipText.contains("AQ."))
        assertTrue(afterClipText.isNotEmpty() && afterClipText.all { it == '\u2022' })

        // Clearing key de-authorizes the app again
        vm.setCustomApiKey("")
        assertFalse(vm.isApiKeyConfigured())
        assertEquals("", vm.customApiKey.value)
        assertEquals("", vm.effectiveApiKey)
    }
}
