package com.princeniithompson.rapcha.ui.screens.onboarding.provisioning

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Pure JVM-safe URL, coordinate, and intent parsing helpers for AI Studio onboarding automation.
 */
object ProvisioningWebMath {

    const val AI_STUDIO_API_KEYS_URL = "https://aistudio.google.com/api-keys"

    fun buildGoogleSignInUrl(emailHint: String? = null): String {
        val base = "https://accounts.google.com/ServiceLogin?continue=${Uri.encode(AI_STUDIO_API_KEYS_URL)}"
        val cleanHint = emailHint?.trim().orEmpty()
        return if (cleanHint.isNotEmpty()) {
            "$base&Email=${Uri.encode(cleanHint)}"
        } else {
            base
        }
    }

    fun stripWebViewTokenFromUserAgent(defaultUserAgent: String): String {
        return defaultUserAgent
            .replace("; wv)", ")")
            .replace("; wv", "")
    }

    fun isGoogleAuthUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("accounts.google.com") ||
            lower.contains("accounts.youtube.com") ||
            lower.contains("/signin/") ||
            lower.contains("servicelogin") ||
            lower.contains("challenge/")
    }

    fun isAiStudioUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.startsWith("https://aistudio.google.com") ||
            lower.startsWith("http://aistudio.google.com")
    }

    fun isApiKeysRoute(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("api-keys") || lower.contains("apikey")
    }

    fun extractIntentFallbackUrl(intentUrl: String?): String? {
        if (intentUrl.isNullOrBlank() || !intentUrl.startsWith("intent:", ignoreCase = true)) {
            return null
        }
        val marker = "S.browser_fallback_url="
        val markerIdx = intentUrl.indexOf(marker)
        if (markerIdx >= 0) {
            val start = markerIdx + marker.length
            val end = intentUrl.indexOf(';', start).let { if (it < 0) intentUrl.length else it }
            val encoded = intentUrl.substring(start, end)
            val decoded = Uri.decode(encoded).trim()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded
            }
        }
        return try {
            val parsed = Intent.parseUri(intentUrl, Intent.URI_INTENT_SCHEME)
            val fallback = parsed.getStringExtra("browser_fallback_url")?.trim().orEmpty()
            fallback.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } catch (_: Throwable) {
            null
        }
    }

    fun normalizeToScale1000(position: Float, dimension: Float): Int {
        return if (dimension <= 0f) {
            0
        } else {
            ((position / dimension) * 1000f).roundToInt().coerceIn(0, 1000)
        }
    }

    fun denormalizeFromScale1000(normalized: Int, liveDimensionPx: Int): Float {
        if (liveDimensionPx <= 0) return 0f
        return (normalized.coerceIn(0, 1000) / 1000f) * liveDimensionPx.toFloat()
    }
}

data class FindInPageMatch(
    val index: Int,
    val label: String,
    val nx: Int,
    val ny: Int,
    val inViewport: Boolean,
    val disabled: Boolean,
    val tagName: String
)

data class CheckboxMatch(
    val index: Int,
    val label: String,
    val nx: Int,
    val ny: Int,
    val inViewport: Boolean,
    val checked: Boolean,
    val required: Boolean
)

data class PageReadinessProbe(
    val ready: Boolean,
    val url: String,
    val readyState: String,
    val textLength: Int,
    val hasLandmarks: Boolean,
    val hasBlockingSpinner: Boolean
)

/**
 * Screen-agnostic local DOM + Shadow DOM coordinate scanner and Android MotionEvent tap dispatcher.
 * Uses zero external AI or screenshot inspection.
 */
class FindInPageController(private val webView: WebView) {

    suspend fun evaluateJavascript(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            try {
                webView.evaluateJavascript(script) { result ->
                    if (cont.isActive) {
                        cont.resume(result ?: "null")
                    }
                }
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume("null")
            }
        }
    }

    suspend fun probePageReadiness(): PageReadinessProbe {
        val js = """
            (function() {
                var readyState = document.readyState || '';
                var text = (document.body && document.body.innerText) ? document.body.innerText.trim() : '';
                var lower = text.toLowerCase();
                var landmarkPhrases = [
                    'api keys', 'create api key', 'create a new key',
                    'welcome to google ai studio', 'continue', 'sign in', 'get api key'
                ];
                var hasPhrase = landmarkPhrases.some(function(p) { return lower.indexOf(p) !== -1; });
                var interactive = document.querySelectorAll('button, [role="button"], mat-checkbox, [role="checkbox"], input');
                var visibleInteractive = false;
                for (var i = 0; i < interactive.length; i++) {
                    var r = interactive[i].getBoundingClientRect();
                    var style = window.getComputedStyle(interactive[i]);
                    if (r.width >= 8 && r.height >= 8 && style.visibility !== 'hidden' && style.display !== 'none' && parseFloat(style.opacity || '1') > 0.05) {
                        visibleInteractive = true;
                        break;
                    }
                }
                var spinners = document.querySelectorAll('mat-progress-spinner, mat-spinner, [role="progressbar"]');
                var hasBlockingSpinner = false;
                for (var j = 0; j < spinners.length; j++) {
                    var sr = spinners[j].getBoundingClientRect();
                    var ss = window.getComputedStyle(spinners[j]);
                    if (sr.width >= 20 && sr.height >= 20 && ss.visibility !== 'hidden' && ss.display !== 'none' && parseFloat(ss.opacity || '1') > 0.05) {
                        hasBlockingSpinner = true;
                        break;
                    }
                }
                var hasLandmarks = hasPhrase || visibleInteractive;
                var ready = (readyState === 'complete') && (text.length >= 35) && hasLandmarks && !hasBlockingSpinner;
                return JSON.stringify({
                    ready: ready,
                    url: location.href || '',
                    readyState: readyState,
                    textLength: text.length,
                    hasLandmarks: hasLandmarks,
                    hasBlockingSpinner: hasBlockingSpinner
                });
            })();
        """.trimIndent()

        val raw = unquoteJsJson(evaluateJavascript(js))
        return try {
            val obj = JSONObject(raw)
            PageReadinessProbe(
                ready = obj.optBoolean("ready", false),
                url = obj.optString("url", ""),
                readyState = obj.optString("readyState", ""),
                textLength = obj.optInt("textLength", 0),
                hasLandmarks = obj.optBoolean("hasLandmarks", false),
                hasBlockingSpinner = obj.optBoolean("hasBlockingSpinner", false)
            )
        } catch (_: Throwable) {
            PageReadinessProbe(
                ready = false,
                url = "",
                readyState = "",
                textLength = 0,
                hasLandmarks = false,
                hasBlockingSpinner = false
            )
        }
    }

    suspend fun findInteractiveMatches(
        query: String,
        excludePhrases: List<String> = emptyList(),
        highlightInDebug: Boolean = false
    ): List<FindInPageMatch> {
        if (highlightInDebug) {
            withContext(Dispatchers.Main) {
                try {
                    webView.findAllAsync(query)
                } catch (_: Throwable) {
                }
            }
        }
        val escapedQuery = JSONObject.quote(query.lowercase())
        val escapedExcludes = JSONArray(excludePhrases.map { it.lowercase() }).toString()

        val js = """
            (function() {
                var query = $escapedQuery;
                var excludes = $escapedExcludes;
                var vw = window.innerWidth || document.documentElement.clientWidth || 1;
                var vh = window.innerHeight || document.documentElement.clientHeight || 1;

                function isVisible(el, rect) {
                    if (!el || !rect) return false;
                    if (rect.width < 4 || rect.height < 4) return false;
                    var style = window.getComputedStyle(el);
                    if (!style || style.display === 'none' || style.visibility === 'hidden') return false;
                    if (parseFloat(style.opacity || '1') <= 0.02) return false;
                    return true;
                }

                function isDisabled(el) {
                    if (!el) return true;
                    if (el.disabled === true) return true;
                    if (el.getAttribute && el.getAttribute('aria-disabled') === 'true') return true;
                    if (el.classList && (el.classList.contains('mat-mdc-button-disabled') || el.classList.contains('mdc-button--disabled'))) return true;
                    return false;
                }

                function climbClickable(el) {
                    var cur = el;
                    var selector = 'button, a, [role="button"], [role="menuitem"], [role="option"], [role="tab"], [role="combobox"], mat-option, input[type="button"], input[type="submit"]';
                    while (cur && cur !== document.body && cur !== document.documentElement) {
                        if (cur.matches && cur.matches(selector)) return cur;
                        cur = cur.parentElement || (cur.getRootNode && cur.getRootNode().host) || null;
                    }
                    return null;
                }

                function textExcluded(txt) {
                    var low = (txt || '').toLowerCase();
                    for (var i = 0; i < excludes.length; i++) {
                        if (low.indexOf(excludes[i]) !== -1) return true;
                    }
                    return false;
                }

                var candidates = [];

                function scanRoot(root) {
                    if (!root) return;
                    try {
                        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
                        var node;
                        while ((node = walker.nextNode())) {
                            var val = (node.nodeValue || '').trim();
                            if (!val) continue;
                            var low = val.toLowerCase();
                            if (low.indexOf(query) !== -1 && !textExcluded(low)) {
                                var range = document.createRange();
                                range.selectNodeContents(node);
                                var rect = range.getBoundingClientRect();
                                var parent = node.parentElement;
                                var clickable = climbClickable(parent) || parent;
                                var targetRect = clickable ? clickable.getBoundingClientRect() : rect;
                                var useRect = (targetRect && targetRect.width >= 4 && targetRect.height >= 4) ? targetRect : rect;
                                if (isVisible(clickable, useRect)) {
                                    var cx = useRect.left + (useRect.width / 2.0);
                                    var cy = useRect.top + (useRect.height / 2.0);
                                    candidates.push({
                                        el: clickable,
                                        label: val,
                                        cx: cx,
                                        cy: cy,
                                        inViewport: (cx >= 0 && cx <= vw && cy >= 0 && cy <= vh),
                                        disabled: isDisabled(clickable),
                                        tagName: (clickable && clickable.tagName) ? clickable.tagName : 'TEXT'
                                    });
                                }
                            }
                        }
                    } catch (e) {}

                    try {
                        var elems = root.querySelectorAll('button, [role="button"], a, [aria-label], [title], input[type="button"], input[type="submit"], [role="option"], mat-option');
                        for (var k = 0; k < elems.length; k++) {
                            var el = elems[k];
                            var label = (el.getAttribute('aria-label') || el.getAttribute('title') || el.innerText || el.value || '').trim();
                            var lowLabel = label.toLowerCase();
                            if (lowLabel.indexOf(query) !== -1 && !textExcluded(lowLabel)) {
                                var clickableEl = climbClickable(el) || el;
                                var r = clickableEl.getBoundingClientRect();
                                if (isVisible(clickableEl, r)) {
                                    var cx2 = r.left + (r.width / 2.0);
                                    var cy2 = r.top + (r.height / 2.0);
                                    candidates.push({
                                        el: clickableEl,
                                        label: label,
                                        cx: cx2,
                                        cy: cy2,
                                        inViewport: (cx2 >= 0 && cx2 <= vw && cy2 >= 0 && cy2 <= vh),
                                        disabled: isDisabled(clickableEl),
                                        tagName: clickableEl.tagName || 'BTN'
                                    });
                                }
                            }
                        }
                    } catch (e2) {}

                    try {
                        var all = root.querySelectorAll('*');
                        for (var s = 0; s < all.length; s++) {
                            if (all[s].shadowRoot) {
                                scanRoot(all[s].shadowRoot);
                            }
                        }
                    } catch (e3) {}
                }

                scanRoot(document.body);

                // Deduplicate nearby boxes (|cx1 - cx2| < 10 && |cy1 - cy2| < 10)
                var deduped = [];
                for (var d = 0; d < candidates.length; d++) {
                    var c = candidates[d];
                    var dup = false;
                    for (var u = 0; u < deduped.length; u++) {
                        if (Math.abs(c.cx - deduped[u].cx) < 10 && Math.abs(c.cy - deduped[u].cy) < 10) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        deduped.push(c);
                    }
                }

                window.__rapchaLastMatchedEls = deduped.map(function(item) { return item.el; });

                var out = deduped.map(function(item, idx) {
                    var nx = Math.max(0, Math.min(1000, Math.round((item.cx / vw) * 1000)));
                    var ny = Math.max(0, Math.min(1000, Math.round((item.cy / vh) * 1000)));
                    return {
                        index: idx,
                        label: item.label.substring(0, 120),
                        nx: nx,
                        ny: ny,
                        inViewport: item.inViewport,
                        disabled: item.disabled,
                        tagName: item.tagName
                    };
                });

                return JSON.stringify(out);
            })();
        """.trimIndent()

        val raw = unquoteJsJson(evaluateJavascript(js))
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        FindInPageMatch(
                            index = obj.optInt("index", i),
                            label = obj.optString("label", ""),
                            nx = obj.optInt("nx", 0),
                            ny = obj.optInt("ny", 0),
                            inViewport = obj.optBoolean("inViewport", false),
                            disabled = obj.optBoolean("disabled", true),
                            tagName = obj.optString("tagName", "")
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun scrollMatchIntoViewAndRemeasure(
        query: String,
        matchIndex: Int,
        excludePhrases: List<String> = emptyList()
    ): FindInPageMatch? {
        evaluateJavascript(
            """
            (function() {
                var arr = window.__rapchaLastMatchedEls || [];
                var el = arr[$matchIndex];
                if (el && el.scrollIntoView) {
                    el.scrollIntoView({ behavior: 'auto', block: 'center', inline: 'center' });
                    return true;
                }
                return false;
            })();
            """.trimIndent()
        )
        delay(290L)
        val updated = findInteractiveMatches(query, excludePhrases)
        return updated.getOrNull(matchIndex) ?: updated.firstOrNull { !it.disabled && it.inViewport }
    }

    suspend fun scanCheckboxes(): List<CheckboxMatch> {
        val js = """
            (function() {
                var vw = window.innerWidth || document.documentElement.clientWidth || 1;
                var vh = window.innerHeight || document.documentElement.clientHeight || 1;
                var nodes = document.querySelectorAll('mat-checkbox, [role="checkbox"], input[type="checkbox"]');
                var results = [];
                window.__rapchaCheckboxEls = [];
                for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    var inner = el.querySelector('.mdc-checkbox__background, .mdc-checkbox, input[type="checkbox"]') || el;
                    var r = inner.getBoundingClientRect();
                    if (r.width < 4 || r.height < 4) r = el.getBoundingClientRect();
                    var style = window.getComputedStyle(el);
                    if (r.width < 4 || r.height < 4 || style.display === 'none' || style.visibility === 'hidden') continue;

                    var input = (el.tagName === 'INPUT') ? el : el.querySelector('input[type="checkbox"]');
                    var checked = false;
                    if (input && input.checked) checked = true;
                    if (el.getAttribute('aria-checked') === 'true') checked = true;
                    if (el.classList && el.classList.contains('mat-mdc-checkbox-checked')) checked = true;

                    var containerText = (el.innerText || el.getAttribute('aria-label') || (el.parentElement ? el.parentElement.innerText : '') || '').trim();
                    var lower = containerText.toLowerCase();
                    var required = (input && input.required === true) ||
                        el.hasAttribute('required') ||
                        el.getAttribute('aria-required') === 'true' ||
                        lower.indexOf('*') !== -1 ||
                        lower.indexOf('18 years') !== -1 ||
                        lower.indexOf('acknowledge') !== -1 ||
                        lower.indexOf('agree') !== -1 ||
                        lower.indexOf('terms') !== -1;

                    var cx = r.left + (r.width / 2.0);
                    var cy = r.top + (r.height / 2.0);
                    window.__rapchaCheckboxEls.push(inner);
                    results.push({
                        index: window.__rapchaCheckboxEls.length - 1,
                        label: containerText.substring(0, 120),
                        nx: Math.max(0, Math.min(1000, Math.round((cx / vw) * 1000))),
                        ny: Math.max(0, Math.min(1000, Math.round((cy / vh) * 1000))),
                        inViewport: (cx >= 0 && cx <= vw && cy >= 0 && cy <= vh),
                        checked: checked,
                        required: required
                    });
                }
                return JSON.stringify(results);
            })();
        """.trimIndent()

        val raw = unquoteJsJson(evaluateJavascript(js))
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        CheckboxMatch(
                            index = obj.optInt("index", i),
                            label = obj.optString("label", ""),
                            nx = obj.optInt("nx", 0),
                            ny = obj.optInt("ny", 0),
                            inViewport = obj.optBoolean("inViewport", false),
                            checked = obj.optBoolean("checked", false),
                            required = obj.optBoolean("required", false)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun scrollCheckboxIntoViewAndRemeasure(checkboxIndex: Int): CheckboxMatch? {
        evaluateJavascript(
            """
            (function() {
                var arr = window.__rapchaCheckboxEls || [];
                var el = arr[$checkboxIndex];
                if (el && el.scrollIntoView) {
                    el.scrollIntoView({ behavior: 'auto', block: 'center', inline: 'center' });
                    return true;
                }
                return false;
            })();
            """.trimIndent()
        )
        delay(290L)
        return scanCheckboxes().getOrNull(checkboxIndex)
    }

    suspend fun fillEmptyKeyNameInput(defaultKeyName: String = "Rapcha"): Boolean {
        val escapedName = JSONObject.quote(defaultKeyName)
        val js = """
            (function() {
                var keyName = $escapedName;
                var inputs = document.querySelectorAll('input, textarea');
                for (var i = 0; i < inputs.length; i++) {
                    var el = inputs[i];
                    var type = (el.getAttribute('type') || 'text').toLowerCase();
                    if (el.tagName === 'INPUT' && (type === 'checkbox' || type === 'radio' || type === 'hidden' || type === 'submit' || type === 'button')) {
                        continue;
                    }
                    var r = el.getBoundingClientRect();
                    var style = window.getComputedStyle(el);
                    if (r.width < 30 || r.height < 16 || style.display === 'none' || style.visibility === 'hidden') {
                        continue;
                    }
                    var hint = ((el.getAttribute('placeholder') || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.name || '')).toLowerCase();
                    if (hint.indexOf('search') !== -1 || hint.indexOf('filter') !== -1 || hint.indexOf('project') !== -1) {
                        continue;
                    }
                    if ((el.value || '').trim().length === 0) {
                        var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                        var setter = Object.getOwnPropertyDescriptor(proto, 'value');
                        if (setter && setter.set) setter.set.call(el, keyName); else el.value = keyName;
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        return true;
                    }
                }
                return false;
            })();
        """.trimIndent()
        return evaluateJavascript(js) == "true"
    }

    suspend fun readBodyInnerText(): String {
        val js = """
            (function() {
                var chunks = [];
                function collect(root) {
                    if (!root) return;
                    try {
                        if (root.innerText) chunks.push(root.innerText);
                        else if (root.textContent) chunks.push(root.textContent);
                    } catch (e) {}
                    try {
                        var inputs = root.querySelectorAll('input, textarea, code, pre');
                        for (var i = 0; i < inputs.length; i++) {
                            var el = inputs[i];
                            if (el.value) chunks.push(el.value);
                            if (el.innerText) chunks.push(el.innerText);
                        }
                    } catch (e2) {}
                    try {
                        var all = root.querySelectorAll('*');
                        for (var s = 0; s < all.length; s++) {
                            if (all[s].shadowRoot) collect(all[s].shadowRoot);
                        }
                    } catch (e3) {}
                }
                collect(document.body);
                return chunks.join('\n');
            })();
        """.trimIndent()
        val raw = evaluateJavascript(js)
        return unquoteJsJson(raw)
    }

    suspend fun extractSignedInEmailFromDom(): String? {
        val js = """
            (function() {
                var emailRe = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;
                var nodes = document.querySelectorAll('[aria-label*="@"], [data-email], a[href*="SignOutOptions"], [title*="@"]');
                for (var i = 0; i < nodes.length; i++) {
                    var n = nodes[i];
                    var text = (n.getAttribute('data-email') || n.getAttribute('aria-label') || n.getAttribute('title') || n.innerText || '');
                    var m = text.match(emailRe);
                    if (m && m[0]) return m[0];
                }
                var body = (document.body && document.body.innerText) ? document.body.innerText : '';
                var bm = body.match(emailRe);
                return (bm && bm[0]) ? bm[0] : '';
            })();
        """.trimIndent()
        val res = unquoteJsJson(evaluateJavascript(js)).trim()
        return res.takeIf { it.contains("@") }
    }

    suspend fun dispatchRealTap(nx: Int, ny: Int) = withContext(Dispatchers.Main) {
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return@withContext
        val px = ProvisioningWebMath.denormalizeFromScale1000(nx, w)
        val py = ProvisioningWebMath.denormalizeFromScale1000(ny, h)
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, px, py, 0)
        try {
            webView.dispatchTouchEvent(downEvent)
        } finally {
            downEvent.recycle()
        }
        delay(45L)
        val upTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, px, py, 0)
        try {
            webView.dispatchTouchEvent(upEvent)
        } finally {
            upEvent.recycle()
        }
    }

    suspend fun dispatchVerticalScrollSwipeDown() = withContext(Dispatchers.Main) {
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return@withContext
        val x = w * 0.5f
        val startY = h * 0.72f
        val endY = h * 0.28f
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0)
        try {
            webView.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }
        val steps = 8
        for (s in 1..steps) {
            delay(16L)
            val fraction = s.toFloat() / steps.toFloat()
            val curY = startY + (endY - startY) * fraction
            val move = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, curY, 0)
            try {
                webView.dispatchTouchEvent(move)
            } finally {
                move.recycle()
            }
        }
        delay(16L)
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, endY, 0)
        try {
            webView.dispatchTouchEvent(up)
        } finally {
            up.recycle()
        }
    }

    private fun unquoteJsJson(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            org.json.JSONTokener(raw).nextValue()?.toString() ?: raw
        } catch (_: Throwable) {
            raw.trim().removeSurrounding("\"")
        }
    }
}
