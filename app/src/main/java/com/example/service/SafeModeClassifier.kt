package com.example.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.LruCache

/**
 * Smart Safe Mode Classifier:
 * Automatically identifies sensitive applications (Banking, Crypto Wallets, Password Managers, 2FA)
 * to immediately lock the floating bubble, prevent audio capture, and block all text scraping/injection.
 */
object SafeModeClassifier {

    private val decisionCache = object : LruCache<String, Boolean>(200) {}

    // 1. Explicit Normal Allowlist (Guarantees these common apps are NEVER blocked)
    private val NORMAL_ALLOWLIST = setOf(
        // Social & Messaging
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "com.instagram.android",
        "com.facebook.orca",
        "com.facebook.katana",
        "com.zhiliaoapp.musically",
        "com.twitter.android",
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.discord",
        "com.pinterest",
        "org.thoughtcrime.securesms",
        "com.linkedin.android",
        "com.instagram.barcelona",
        "com.viber.voip",
        "jp.naver.line.android",

        // AI Chat Applications
        "com.openai.chatgpt",
        "com.anthropic.claude",
        "com.google.android.apps.bard",
        "ai.perplexity.app.android",
        "com.microsoft.copilot",
        "ai.x.grok",
        "com.deepseek.chat",
        "com.poe.android",
        "ai.character.app",
        "com.alibaba.tongyi.intl",
        "com.alibaba.qwen.intl",
        "com.aliyun.tongyi.intl",
        "com.alibaba.tongyi",
        "com.alibaba.qwen",
        "com.qwen.ai",
        "ai.qwen.chat",

        // Work & Productivity
        "com.github.android",
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.Slack",
        "com.slack",
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.google.android.keep",
        "com.microsoft.teams",
        "notion.id",
        "com.trello",
        "com.asana.app",
        "us.zoom.videomeetings",
        "com.linear.android",
        "md.obsidian",
        "com.evernote",
        "com.microsoft.office.onenote",
        "com.samsung.android.app.notes",

        // Browsers & Media
        "com.android.chrome",
        "com.chrome.beta",
        "com.google.android.apps.chrome",
        "org.mozilla.firefox",
        "com.sec.android.app.sbrowser",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.google.android.youtube",
        "com.spotify.music",
        "com.google.android.apps.maps"
    )

    // 2. High-Profile Sensitive Packages (Global Banking, Crypto, Passwords)
    private val KNOWN_SENSITIVE_PACKAGES = setOf(
        // US & Global Banks
        "com.chase.sig.android",
        "com.infonow.bofa",
        "com.wf.wellsfargomobile",
        "com.citi.citimobile",
        "com.usaa.mobile.android.usaa",
        "com.capitalone.mobile",
        "com.tdbank",
        "com.pnc.ecommerce.mobile",
        "com.discoverfinancial.mobile",
        "com.chime.chimeaccount",
        "com.sofi.mobile",
        "com.ally.mobile.android",
        "com.schwab.mobile",
        "com.fidelity.android",
        "com.vanguard",
        "com.robinhood.android",
        "com.wealthfront",
        "com.acorns.android",

        // UK & European Banks
        "com.barclays.android.barclaysmobilebanking",
        "com.rbs.mobile.android.natwest",
        "com.rbs.mobile.android.rbs",
        "com.htsu.hsbcpersonalbanking",
        "com.santander.app",
        "com.grppl.android.shell.halifax",
        "com.grppl.android.shell.BBD", // Lloyds
        "com.revolut.revolut",
        "co.uk.monzo",
        "de.number26.android",
        "com.starlingbank",
        "com.ing.mobile",
        "com.bnpparibas.banking",
        "com.bbva.bbvacontigo",
        "com.caixabank.mobil.caixabank",

        // African & Emerging Market Banks / FinTech
        "com.kuda.android",
        "com.zenithBank.mPlus",
        "com.gtbank.gtworldv1",
        "com.accessbank.diamondmobile",
        "com.interswitchng.quickteller",
        "team.opay.pay",
        "com.transsnet.palmpay",
        "com.standardbank.international",
        "com.standardbank.banking",
        "com.safaricom.mpesa.lifestyle",

        // Payment Platforms & Transfers
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.westernunion.android.mtapp",
        "com.remitly.android.app",
        "com.transferwise.android",
        "com.klarna.mobile",

        // Crypto & Web3 Wallets & Exchanges
        "io.metamask",
        "com.wallet.crypto.trustapp",
        "com.binance.dev",
        "com.coinbase.android",
        "com.coinbase.wallet",
        "org.phantom",
        "com.kraken.invest.app",
        "com.ledger.live",
        "com.exodus",
        "com.okx.mobile",
        "com.bybit.app",
        "io.safepal.wallet",
        "io.argent.wallet",
        "me.rainbow",
        "com.solflare.mobile",
        "com.zerion.android",
        "com.kucoin.android",
        "com.gemini.android.app",
        "vip.mytokenpocket",
        "im.token.app",
        "com.bitget.android",
        "com.mexc.crypto",
        "com.crypto.exchange",

        // Password Managers & 2FA / Authenticators
        "com.agilebits.onepassword",
        "com.x8bit.bitwarden",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet",
        "com.kunzisoft.keepass.free",
        "ch.proton.pass",
        "com.nordpass.android",
        "com.roboform",
        "com.keepersecurity.passwordmanager",
        "com.msecure.msecure5",
        "com.enpass.app",
        "com.authy.authy",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.duosecurity.duomobile",
        "com.yubico.yubioath",
        "org.fedorahosted.freeotp",
        "com.beemdevelopment.aegis",
        "org.twofas.twofasapp"
    )

    // 3. Sensitive Keywords / Substrings (Tokenized to avoid false matches)
    private val SENSITIVE_PACKAGE_SUBSTRINGS = listOf(
        // Banking & Financial tokens
        ".bank.", ".banking.", "mobilebank", "creditunion",
        "fintech", "payment", "transferwise", "remitly",
        "westernunion", "moneygram", "cashapp", "zelle",

        // Crypto & Web3 tokens
        "metamask", "trustwallet", "crypto.wallet", ".crypto.",
        "bitcoin", "ethereum", "blockchain", "web3", "tokenpocket",
        "ledger.live", "phantom.wallet", "safepal", "argent.wallet",

        // Password & Auth tokens
        "password", "passcode", "authenticator", "authy",
        "bitwarden", "onepassword", "1password", "lastpass",
        "dashlane", "keepass", "nordpass", "enpass", "2fas"
    )

    /**
     * Determines whether the given package or app represents a sensitive application.
     */
    fun isSensitiveApp(
        context: Context?,
        packageName: String?,
        resolvedAppName: String? = null
    ): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.trim()
        val pkgLower = pkg.lowercase()

        // System UI, launchers, keyboard or self should never be considered sensitive
        if (AppContextResolver.isIgnoredPackage(context, pkgLower)) return false

        // Fast path 1: Check Allowlist
        if (NORMAL_ALLOWLIST.contains(pkgLower)) return false

        // Fast path 2: Check LRU Cache
        val cached = decisionCache.get(pkgLower)
        if (cached != null) return cached

        // Check Known Sensitive Set
        if (KNOWN_SENSITIVE_PACKAGES.contains(pkgLower)) {
            decisionCache.put(pkgLower, true)
            return true
        }

        // Check Package Substring Tokens
        for (token in SENSITIVE_PACKAGE_SUBSTRINGS) {
            if (pkgLower.contains(token)) {
                decisionCache.put(pkgLower, true)
                return true
            }
        }

        // Check Resolved App Name Tokens
        val nameLower = (resolvedAppName ?: "").lowercase()
        if (nameLower.isNotBlank()) {
            val sensitiveNameTokens = listOf(
                "bank", "banking", "credit union", "crypto", "wallet",
                "authenticator", "password manager", "bitwarden", "1password",
                "lastpass", "dashlane", "keepass", "authy", "binance", "coinbase"
            )
            for (token in sensitiveNameTokens) {
                if (nameLower.contains(token)) {
                    decisionCache.put(pkgLower, true)
                    return true
                }
            }
        }

        // Inspect package label from PackageManager if resolvedAppName was not provided
        if (context != null && nameLower.isBlank()) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                val sensitiveTokens = listOf(
                    "bank", "banking", "credit union", "crypto", "wallet",
                    "authenticator", "password", "bitwarden", "1password",
                    "lastpass", "dashlane", "keepass", "authy", "binance", "coinbase"
                )
                for (token in sensitiveTokens) {
                    if (label.contains(token)) {
                        decisionCache.put(pkgLower, true)
                        return true
                    }
                }
            } catch (_: Throwable) {}
        }

        decisionCache.put(pkgLower, false)
        return false
    }
}
