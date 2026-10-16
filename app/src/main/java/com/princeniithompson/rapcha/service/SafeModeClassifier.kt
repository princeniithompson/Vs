package com.princeniithompson.rapcha.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Smart Safe Mode Classifier:
 * Automatically identifies sensitive applications (Banking, Crypto Wallets, Password Managers, 2FA)
 * as well as sensitive screens and fields (Sign-up, Sign-in, Password entries, Credential forms)
 * to immediately auto-hide the floating bubble, prevent audio capture, and protect user privacy.
 */
object SafeModeClassifier {

    private const val TAG = "SafeModeClassifier"
    private val decisionCache = object : LruCache<String, Boolean>(300) {}

    // 1. Explicit Normal Allowlist (Common apps that are not sensitive by default,
    // UNLESS a password field or sign-up credential form is actively focused)
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
        "com.ecobank.mobile",
        "com.stanbic.mobile",
        "com.fidelitybank.fidelityonline",
        "com.uba.mobile",
        "com.ubagroup.uba_mobile",

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
        "bank", "banking", "creditunion", "fintech", "payment",
        "transferwise", "remitly", "westernunion", "moneygram",
        "cashapp", "zelle", "mobilebank",

        // Crypto & Web3 tokens
        "metamask", "trustwallet", "crypto", "bitcoin", "ethereum",
        "blockchain", "web3", "tokenpocket", "ledger.live",
        "safepal", "argent.wallet", "phantom.wallet",

        // Password & Auth tokens
        "password", "passcode", "authenticator", "authy",
        "bitwarden", "onepassword", "1password", "lastpass",
        "dashlane", "keepass", "nordpass", "enpass", "2fas"
    )

    /**
     * Determines whether the given package or app represents a sensitive application
     * (Banking, Finance, Crypto, Password Manager, Authenticator).
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
                "lastpass", "dashlane", "keepass", "authy", "binance", "coinbase",
                "fintech", "mutual fund", "brokerage", "securities"
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
                    "lastpass", "dashlane", "keepass", "authy", "binance", "coinbase",
                    "fintech", "mutual fund", "brokerage", "securities"
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

    /**
     * Determines whether the current activity, screen, or window title indicates
     * an authentication, sign-up, sign-in, password, or security flow.
     */
    fun isSensitiveScreen(className: String?, windowTitle: String?): Boolean {
        val cls = (className ?: "").lowercase()
        val title = (windowTitle ?: "").lowercase()

        val screenTokens = listOf(
            "signin", "sign_in", "sign-in",
            "signup", "sign_up", "sign-up",
            "login", "log_in", "log-in",
            "register", "registration",
            "password", "passcode", "credential",
            "authactivity", "authenticationactivity",
            "lockscreen", "keyguard",
            "biometric", "verifyidentity", "pinentry", "pincode",
            "securitycode", "twofactor", "2fa", "otpactivity"
        )
        for (token in screenTokens) {
            if (cls.contains(token)) return true
        }

        val titleTokens = listOf(
            "sign in", "sign up", "create account", "create your account",
            "log in", "enter password", "choose a password", "set password",
            "confirm password", "verify your identity", "security code",
            "verification code", "reset password", "pin entry", "enter pin"
        )
        for (token in titleTokens) {
            if (title.contains(token)) return true
        }

        return false
    }

    /**
     * Inspects whether a specific accessibility node is a password or credential input.
     */
    fun isPasswordOrSensitiveNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isPassword) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val inputType = node.inputType
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val isPassVariation = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            if (isPassVariation) return true
        }

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.lowercase() ?: ""
        } else ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        val sensitiveIdTokens = listOf(
            "password", "passcode", "pwd", "pin", "cvv",
            "security_code", "secret", "otp"
        )
        for (token in sensitiveIdTokens) {
            if (viewId.contains(token) || hint.contains(token) || desc.contains(token)) {
                return true
            }
        }

        return false
    }

    /**
     * Recursively inspects the active window tree (bounded depth) to detect password fields.
     */
    fun hasPasswordOrSensitiveField(root: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (root == null || depth > 5) return false
        if (isPasswordOrSensitiveNode(root)) return true

        val count = root.childCount.coerceAtMost(25)
        for (i in 0 until count) {
            val child = root.getChild(i) ?: continue
            try {
                if (hasPasswordOrSensitiveField(child, depth + 1)) {
                    return true
                }
            } catch (_: Throwable) {
                // Ignore transient node query exceptions
            }
        }
        return false
    }

    /**
     * Unified Safe Mode evaluation: combines app category, activity screen name,
     * focused field inspection, and window hierarchy to determine privacy safety.
     */
    fun isSensitiveContext(
        context: Context?,
        packageName: String?,
        focusedNode: AccessibilityNodeInfo? = null,
        rootNode: AccessibilityNodeInfo? = null,
        className: String? = null,
        windowTitle: String? = null,
        resolvedAppName: String? = null
    ): Boolean {
        // 1. Is the application itself a banking/crypto/sensitive app?
        if (isSensitiveApp(context, packageName, resolvedAppName)) {
            return true
        }

        // 2. Is the current screen a sign-up, sign-in, or credential form?
        if (isSensitiveScreen(className, windowTitle)) {
            return true
        }

        // 3. Is the currently focused input a password or credential field?
        if (focusedNode != null && isPasswordOrSensitiveNode(focusedNode)) {
            return true
        }

        // 4. Does the active window tree contain any password fields?
        if (rootNode != null && hasPasswordOrSensitiveField(rootNode)) {
            return true
        }

        return false
    }
}
