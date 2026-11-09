package com.princeniithompson.rapcha.service.lens

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.princeniithompson.rapcha.service.RapchaAccessibilityService
import com.princeniithompson.rapcha.service.SafeModeClassifier

object LensAccessibility {
    fun read(service: RapchaAccessibilityService): List<LensCandidate> {
        val windows = try {
            service.windows ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val out = ArrayList<LensCandidate>()
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            } ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            val sensitive = SafeModeClassifier.isSensitiveApp(service, pkg)
            val own = pkg == service.packageName
            walk(root, sensitive, own, out, 0)
        }
        return out
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        sensitive: Boolean,
        own: Boolean,
        out: MutableList<LensCandidate>,
        depth: Int
    ) {
        if (depth > 24) return
        try {
            val text = node.text?.toString().orEmpty().ifBlank {
                node.contentDescription?.toString().orEmpty()
            }
            if (text.isNotBlank()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                out.add(
                    LensCandidate(
                        text = text,
                        bounds = ScreenRect(rect.left, rect.top, rect.right, rect.bottom),
                        source = LensSource.ACCESSIBILITY,
                        editable = node.isEditable,
                        password = node.isPassword,
                        sensitivePackage = sensitive,
                        secureWindow = false,
                        ownOverlay = own
                    )
                )
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                walk(child, sensitive, own, out, depth + 1)
            }
        } catch (_: Exception) {
        }
    }
}
