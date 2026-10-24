package com.princeniithompson.rapcha.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.text.InputType
import android.util.LruCache
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Smart Safe Mode Classifier:
 * Automatically identifies sensitive applications (Banking, Crypto Wallets, Password Managers, 2FA)
 * as well as sensitive screens and fields (Sign-up, Sign-in, Password entries, Credential forms)
 * to immediately auto-hide the floating bubble, prevent audio capture, and protect user privacy.
 */
object SafeModeClassifier {

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

        // Betting and mobile money that showed up as correct Safe Mode triggers
        "com.sportybet.android",
        "com.mtn.mymtn",
        "com.mtngh.mymtn",
        "com.mtn.mymtngh",
        "gh.com.mtn.mymtnapp",
        "com.gcb.mobile",
        "com.gcb.gcbmobile",
        "com.gcb.gcbmobilebanking",
        "com.gcbbank.mobile",
        "com.gcbbank.gcbmobile",
        "gh.com.gcbbank.app",

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
        "sportybet", "mymtn",

        // Crypto & Web3 tokens
        "metamask", "trustwallet", "crypto", "bitcoin", "ethereum",
        "blockchain", "web3", "tokenpocket", "ledger.live",
        "safepal", "argent.wallet", "phantom.wallet",

        // Password & Auth tokens
        "password", "passcode", "authenticator", "authy",
        "bitwarden", "onepassword", "1password", "lastpass",
        "dashlane", "keepass", "nordpass", "enpass", "2fas"
    )

    private val SENSITIVE_NAME_TOKENS = listOf(
        "bank", "banking", "credit union", "crypto", "wallet",
        "authenticator", "password manager", "bitwarden", "1password",
        "lastpass", "dashlane", "keepass", "authy", "binance", "coinbase",
        "fintech", "mutual fund", "brokerage", "securities",
        "sportybet", "mymtn", "my mtn", "gcb bank"
    )

    private val WHOLE_TOKEN = Regex("""(^|[^a-z0-9])gcb([^a-z0-9]|$)""")
    private val PIN_TOKEN = Regex("""(^|[^a-z0-9])pin([^a-z0-9]|$)""")

    enum class Reason {
        PACKAGE_LIST,
        PASSWORD_FIELD,
        KEYWORD,
        NONE
    }

    data class Verdict(
        val sensitive: Boolean,
        val reason: Reason,
        val detail: String,
        val path: String
    ) {
        fun describe(pkg: String?): String {
            val reasonText = when (reason) {
                Reason.PACKAGE_LIST -> "package_list"
                Reason.PASSWORD_FIELD -> "password_field"
                Reason.KEYWORD -> "keyword"
                Reason.NONE -> "none"
            }
            val shown = pkg?.takeIf { it.isNotBlank() } ?: "unknown"
            return "pkg=$shown reason=$reasonText detail=$detail path=$path"
        }

        companion object {
            fun notSensitive() = Verdict(false, Reason.NONE, "clear", "clear")
        }
    }

    data class FieldSignal(
        val isPassword: Boolean = false,
        val editable: Boolean = false,
        val viewId: String = "",
        val hint: String = "",
        val contentDescription: String = "",
        val packageName: String? = null
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
        val pkgLower = packageName.trim().lowercase()
        if (AppContextResolver.isIgnoredPackage(context, pkgLower)) return false
        if (isFocusedPasswordOnly(pkgLower, resolvedAppName, null)) return false
        val cached = decisionCache.get(pkgLower)
        if (cached != null) return cached
        val sensitive = matchPackage(pkgLower, resolvedAppName, context) != null
        decisionCache.put(pkgLower, sensitive)
        return sensitive
    }

    /**
     * The only Safe Mode decision. Screen and title words are not a trigger.
     * Everyday apps hide only when a password or OTP field is focused.
     * Other apps also hide when a password field is visible in their own window,
     * or when the package or app name is banking, betting, or mobile money.
     */
    fun classify(
        context: Context? = null,
        packageName: String? = null,
        resolvedAppName: String? = null,
        windowTitle: String? = null,
        focused: FieldSignal? = null,
        visiblePassword: Boolean = false,
        visiblePackage: String? = null
    ): Verdict {
        if (packageName.isNullOrBlank()) return Verdict.notSensitive()
        val pkgLower = packageName.trim().lowercase()
        val name = resolvedAppName?.lowercase().orEmpty()
        val title = windowTitle?.lowercase().orEmpty()
        if (AppContextResolver.isIgnoredPackage(context, pkgLower) && focusedPassword(focused, pkgLower) == null) {
            return Verdict.notSensitive()
        }

        val focusedToken = focusedPassword(focused, pkgLower)
        if (isFocusedPasswordOnly(pkgLower, name, title)) {
            return if (focusedToken != null) {
                Verdict(true, Reason.PASSWORD_FIELD, "focused $focusedToken", "focused_field")
            } else {
                Verdict.notSensitive()
            }
        }

        matchPackage(pkgLower, resolvedAppName, context)?.let { return it }

        if (focusedToken != null) {
            return Verdict(true, Reason.PASSWORD_FIELD, "focused $focusedToken", "focused_field")
        }

        val sameWindow = visiblePassword &&
            (visiblePackage.isNullOrBlank() || visiblePackage.equals(packageName, ignoreCase = true) ||
                visiblePackage.equals(pkgLower, ignoreCase = true))
        if (sameWindow) {
            return Verdict(true, Reason.PASSWORD_FIELD, "visible password", "visible_field")
        }
        return Verdict.notSensitive()
    }

    fun classifyFromNodes(
        context: Context?,
        packageName: String?,
        focusedNode: AccessibilityNodeInfo? = null,
        rootNode: AccessibilityNodeInfo? = null,
        windowTitle: String? = null,
        resolvedAppName: String? = null
    ): Verdict {
        val pkgLower = packageName?.trim()?.lowercase().orEmpty()
        val everyday = isFocusedPasswordOnly(pkgLower, resolvedAppName, windowTitle)
        val rootPkg = try {
            rootNode?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
        val sameWindow = rootPkg != null && rootPkg.equals(packageName, ignoreCase = true)
        val visible = !everyday && sameWindow && hasPasswordOrSensitiveField(rootNode)
        return classify(
            context = context,
            packageName = packageName,
            resolvedAppName = resolvedAppName,
            windowTitle = windowTitle,
            focused = fieldSignal(focusedNode),
            visiblePassword = visible,
            visiblePackage = if (sameWindow) rootPkg else null
        )
    }

    fun credentialToken(field: FieldSignal): String? {
        if (field.isPassword) return "password"
        if (!field.editable) return null
        val viewId = field.viewId.lowercase()
        val hint = field.hint.lowercase()
        val desc = field.contentDescription.lowercase()
        val parts = listOf(viewId, hint, desc)
        for (token in listOf("password", "passcode", "pwd", "otp", "cvv", "security_code")) {
            if (parts.any { it.contains(token) }) return token
        }
        val pinInText = PIN_TOKEN.containsMatchIn(hint) || PIN_TOKEN.containsMatchIn(desc)
        val pinInId = PIN_TOKEN.containsMatchIn(viewId) && !viewId.contains("pinterest")
        if (pinInText || pinInId) return "pin"
        return null
    }

    private fun focusedPassword(field: FieldSignal?, foregroundPackage: String): String? {
        if (field == null) return null
        val token = credentialToken(field) ?: return null
        val fieldPkg = field.packageName?.trim()?.lowercase()
        if (!fieldPkg.isNullOrBlank() && fieldPkg != foregroundPackage) return null
        return token
    }

    private fun isFocusedPasswordOnly(pkg: String, resolvedAppName: String?, windowTitle: String?): Boolean {
        if (pkg.isBlank()) return false
        if (NORMAL_ALLOWLIST.contains(pkg)) return true
        if (pkg == "com.android.settings" || pkg.startsWith("com.android.settings.")) return true
        if (pkg == "com.samsung.android.settings" || pkg.startsWith("com.samsung.android.settings.")) return true
        if (pkg.contains("launcher") || pkg == "com.miui.home" || pkg == "com.teslacoilsw.launcher") return true
        if (pkg == "com.omkar.voiceflow" || pkg.contains("voiceflow")) return true
        if (pkg == "com.einnovation.temu" || pkg.startsWith("com.einnovation.temu.") || pkg.contains(".temu")) return true
        if (AppClassifier.isFileManager(pkg, resolvedAppName)) return true
        val blob = "${resolvedAppName.orEmpty()} ${windowTitle.orEmpty()}".lowercase()
        if (blob.contains("ai studio") || blob.contains("aistudio.google") || blob.contains("google ai studio")) return true
        return false
    }

    private fun matchPackage(pkgLower: String, resolvedAppName: String?, context: Context?): Verdict? {
        if (KNOWN_SENSITIVE_PACKAGES.contains(pkgLower)) {
            return Verdict(true, Reason.PACKAGE_LIST, "known_package", "package_list")
        }
        for (token in SENSITIVE_PACKAGE_SUBSTRINGS) {
            if (pkgLower.contains(token)) {
                return Verdict(true, Reason.KEYWORD, token, "package_keyword")
            }
        }
        if (WHOLE_TOKEN.containsMatchIn(pkgLower)) {
            return Verdict(true, Reason.KEYWORD, "gcb", "package_keyword")
        }
        val nameLower = resolvedName(pkgLower, resolvedAppName, context)
        if (nameLower.isNotBlank()) {
            for (token in SENSITIVE_NAME_TOKENS) {
                if (nameLower.contains(token)) {
                    return Verdict(true, Reason.KEYWORD, token, "name_keyword")
                }
            }
            if (WHOLE_TOKEN.containsMatchIn(nameLower)) {
                return Verdict(true, Reason.KEYWORD, "gcb", "name_keyword")
            }
        }
        return null
    }

    private fun resolvedName(pkgLower: String, resolvedAppName: String?, context: Context?): String {
        val given = resolvedAppName?.trim()?.lowercase().orEmpty()
        if (given.isNotBlank()) return given
        if (context == null) return ""
        return try {
            val pm = context.packageManager
            val appInfo: ApplicationInfo = pm.getApplicationInfo(pkgLower, 0)
            pm.getApplicationLabel(appInfo).toString().lowercase()
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Screen and title words are not a Safe Mode trigger. Kept so older call sites compile.
     * [classify] ignores this result.
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
    fun fieldSignal(node: AccessibilityNodeInfo?): FieldSignal? {
        if (node == null) return null
        val viewId = try {
            node.viewIdResourceName?.lowercase().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                node.hintText?.toString()?.lowercase().orEmpty()
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }
        val desc = try {
            node.contentDescription?.toString()?.lowercase().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val pkg = try {
            node.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
        return FieldSignal(
            isPassword = nodeLooksLikePassword(node),
            editable = try { node.isEditable } catch (_: Throwable) { false },
            viewId = viewId,
            hint = hint,
            contentDescription = desc,
            packageName = pkg
        )
    }

    private fun nodeLooksLikePassword(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val variation = try {
                node.inputType and InputType.TYPE_MASK_VARIATION
            } catch (_: Throwable) {
                return false
            }
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        return false
    }

    fun isPasswordOrSensitiveNode(node: AccessibilityNodeInfo?): Boolean {
        val signal = fieldSignal(node) ?: return false
        return credentialToken(signal) != null
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
     * Safe Mode comes from [classifyFromNodes] only. [className] is accepted so existing
     * callers still compile, and it is not used as a trigger.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isSensitiveContext(
        context: Context?,
        packageName: String?,
        focusedNode: AccessibilityNodeInfo? = null,
        rootNode: AccessibilityNodeInfo? = null,
        className: String? = null,
        windowTitle: String? = null,
        resolvedAppName: String? = null
    ): Boolean {
        return classifyFromNodes(
            context = context,
            packageName = packageName,
            focusedNode = focusedNode,
            rootNode = rootNode,
            windowTitle = windowTitle,
            resolvedAppName = resolvedAppName
        ).sensitive
    }
}
