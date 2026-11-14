package com.princeniithompson.rapcha.ui.screens.onboarding

import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex

/**
 * Hosts the live WebView at full viewport size at all times during onboarding so Angular SPAs
 * and getBoundingClientRect() compute accurate coordinates even when hidden (alpha = 0f).
 *
 * When surfaced (showBrowserToUser == true), renders clean standard mobile browser chrome
 * resembling Android Custom Tabs so Google Sign-In feels completely natural.
 */
@Composable
fun EmbeddedGoogleAuthBrowserContainer(
    webView: WebView?,
    visible: Boolean,
    pageTitle: String,
    currentUrl: String,
    isLoading: Boolean,
    progress: Int,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (webView == null) return

    val hostLabel = remember(currentUrl) {
        try {
            val host = Uri.parse(currentUrl).host.orEmpty()
            host.ifBlank { "accounts.google.com" }
        } catch (_: Throwable) {
            "accounts.google.com"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .zIndex(if (visible) 20f else -1f)
            .alpha(if (visible) 1f else 0f)
            .then(
                if (visible) {
                    Modifier
                        .background(Color.White)
                        .safeDrawingPadding()
                } else {
                    Modifier
                }
            )
            .testTag(if (visible) "embedded_browser_visible" else "embedded_browser_hidden")
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (visible) {
                Surface(
                    color = Color(0xFFF8F9FA),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("btn_browser_close")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close browser",
                                    tint = Color(0xFF1F1F1F)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = pageTitle.ifBlank { "Sign in - Google Accounts" },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1F1F1F),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "HTTPS",
                                        tint = Color(0xFF5F6368),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = hostLabel,
                                        fontSize = 12.sp,
                                        color = Color(0xFF5F6368),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            IconButton(
                                onClick = onRefresh,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("btn_browser_refresh")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reload page",
                                    tint = Color(0xFF444746)
                                )
                            }
                        }

                        if (isLoading) {
                            LinearProgressIndicator(
                                progress = { (progress.coerceIn(5, 100)) / 100f },
                                color = Color(0xFF1A73E8),
                                trackColor = Color(0xFFE0E3E7),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.5.dp)
                            )
                        } else {
                            HorizontalDivider(color = Color(0xFFE0E3E7), thickness = 1.dp)
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            androidx.compose.runtime.key(webView) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
