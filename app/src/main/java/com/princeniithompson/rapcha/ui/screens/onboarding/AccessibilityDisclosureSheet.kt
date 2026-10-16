package com.princeniithompson.rapcha.ui.screens.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.ui.theme.EditorialSerifFontFamily
import com.princeniithompson.rapcha.ui.theme.OnboardingBorderStroke
import com.princeniithompson.rapcha.ui.theme.OnboardingInkBlack

/**
 * Prominent accessibility disclosure. Settings open only after Agree.
 * Back, outside taps, and swipe cannot dismiss this sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilityDisclosureSheet(
    onAgree: () -> Unit,
    onDecline: () -> Unit
) {
    var allowDismiss by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            value != SheetValue.Hidden || allowDismiss
        }
    )

    ModalBottomSheet(
        onDismissRequest = { },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
                .testTag("accessibility_disclosure_sheet")
        ) {
            Text(
                text = "Before you turn on Accessibility",
                fontFamily = EditorialSerifFontFamily,
                fontSize = 30.sp,
                lineHeight = 38.sp,
                color = OnboardingInkBlack,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "Rapcha reads on-screen content to detect the current app and types dictated text into the focused field.",
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = OnboardingInkBlack,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "You have to tap Agree before Android’s Accessibility settings open. This step cannot be skipped.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Color(0xFF333333)
            )

            DisclosureSection(
                title = "What Rapcha reads",
                body = "The name of the app in front, and the text and layout of the screen, so it can tell which app you are in and whether a text field is focused."
            )
            DisclosureSection(
                title = "What Rapcha types",
                body = "The words you dictate, into the focused field. It does not replace that field by copying them to the clipboard when a field is focused."
            )
            DisclosureSection(
                title = "What it does not do",
                body = "It does not read this content to collect passwords or payment details. It stays out of banking apps and password fields. You can turn the service off at any time in Android settings."
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    allowDismiss = true
                    onAgree()
                },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("btn_disclosure_agree")
            ) {
                Text(
                    "Agree",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    allowDismiss = true
                    onDecline()
                },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = OnboardingInkBlack
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("btn_disclosure_not_now")
            ) {
                Text("Not now", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = OnboardingInkBlack)
            }
        }
    }
}

@Composable
private fun DisclosureSection(title: String, body: String) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = OnboardingInkBlack
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = body,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Color(0xFF333333)
        )
    }
}
