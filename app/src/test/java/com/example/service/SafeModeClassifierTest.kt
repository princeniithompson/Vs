package com.example.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeModeClassifierTest {

    @Test
    fun `normal daily apps are not classified as sensitive`() {
        val normalApps = listOf(
            "com.whatsapp",
            "com.google.android.apps.messaging",
            "org.telegram.messenger",
            "com.instagram.android",
            "com.twitter.android",
            "com.openai.chatgpt",
            "com.anthropic.claude",
            "com.google.android.apps.bard",
            "ai.perplexity.app.android",
            "com.microsoft.copilot",
            "com.google.android.gm",
            "com.google.android.keep",
            "notion.id",
            "com.slack",
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.google.android.youtube"
        )

        for (pkg in normalApps) {
            assertFalse(
                "App $pkg should NOT be sensitive",
                SafeModeClassifier.isSensitiveApp(null, pkg)
            )
        }
    }

    @Test
    fun `banking and financial apps are classified as sensitive`() {
        val bankingApps = listOf(
            "com.chase.sig.android",
            "com.infonow.bofa",
            "com.wf.wellsfargomobile",
            "com.citi.citimobile",
            "com.revolut.revolut",
            "co.uk.monzo",
            "com.barclays.android.barclaysmobilebanking",
            "com.paypal.android.p2pmobile",
            "com.squareup.cash",
            "com.venmo",
            "com.kuda.android"
        )

        for (pkg in bankingApps) {
            assertTrue(
                "Banking app $pkg should be detected as sensitive",
                SafeModeClassifier.isSensitiveApp(null, pkg)
            )
        }
    }

    @Test
    fun `crypto and web3 wallets are classified as sensitive`() {
        val cryptoApps = listOf(
            "io.metamask",
            "com.wallet.crypto.trustapp",
            "com.binance.dev",
            "com.coinbase.android",
            "org.phantom",
            "com.ledger.live",
            "com.exodus"
        )

        for (pkg in cryptoApps) {
            assertTrue(
                "Crypto wallet $pkg should be detected as sensitive",
                SafeModeClassifier.isSensitiveApp(null, pkg)
            )
        }
    }

    @Test
    fun `password managers and authenticators are classified as sensitive`() {
        val passwordApps = listOf(
            "com.agilebits.onepassword",
            "com.x8bit.bitwarden",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "keepass2android.keepass2android",
            "com.authy.authy",
            "com.google.android.apps.authenticator2",
            "org.twofas.twofasapp"
        )

        for (pkg in passwordApps) {
            assertTrue(
                "Password manager $pkg should be detected as sensitive",
                SafeModeClassifier.isSensitiveApp(null, pkg)
            )
        }
    }
}
