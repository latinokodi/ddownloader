package com.ddownloader.android.ui.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.i18n.LocalStrings
import com.ddownloader.android.ui.components.GradientButton
import com.ddownloader.android.ui.theme.TdColor

/**
 * "Link your account" dialog for the URL + code (device authorization) flow.
 *
 * The dialog shows the verification URL and the code the user must type in the browser,
 * and polls in the background. It never asks for an API token and never displays the
 * credential that comes back.
 */
@Composable
fun LinkAccountDialog(vm: AppViewModel) {
    val flowState = vm.linkState.collectAsStateWithLifecycle()
    val state = flowState.value
    if (state is AppViewModel.LinkUi.Idle) return
    val t = LocalStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Dialog(
        onDismissRequest = { if (state !is AppViewModel.LinkUi.Waiting) vm.cancelLink() },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(TdColor.Ink2)
                .padding(24.dp)
                .fillMaxWidth(),
        ) {
            when (state) {
                is AppViewModel.LinkUi.Starting -> {
                    Text(
                        "Requesting a code…",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TdColor.TextHi,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(color = TdColor.Cyan, strokeWidth = 2.dp)
                }

                is AppViewModel.LinkUi.Waiting -> {
                    val prompt = state.prompt
                    Text(
                        "${t.linkTitle} ${prompt.provider.label}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TdColor.TextHi,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t.linkHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = TdColor.TextMid,
                    )
                    Spacer(Modifier.height(18.dp))

                    Text(
                        t.linkCode,
                        style = MaterialTheme.typography.labelSmall,
                        color = TdColor.TextLow,
                        letterSpacing = 1.5.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        prompt.userCode,
                        style = MaterialTheme.typography.displaySmall,
                        color = TdColor.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        textAlign = TextAlign.Start,
                    )
                    Spacer(Modifier.height(16.dp))

                    Text(
                        prompt.verificationUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TdColor.TextHi,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(14.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GradientButton(
                            t.linkOpen,
                            Modifier.weight(1f),
                        ) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(prompt.verificationUrl)),
                                )
                            }
                        }
                        GhostButton(t.linkCopy) {
                            clipboard.setText(AnnotatedString(prompt.userCode))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = TdColor.Cyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.width(16.dp).height(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${t.linkWaiting} (${state.secondsLeft}s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TdColor.TextMid,
                        )
                    }
                }

                is AppViewModel.LinkUi.Linked -> {
                    Text(
                        "${state.provider.label} linked",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TdColor.Mint,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Credential received and stored encrypted on this device. It is never displayed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TdColor.TextMid,
                    )
                    Spacer(Modifier.height(18.dp))
                    GradientButton("Done", Modifier.fillMaxWidth()) { vm.cancelLink() }
                }

                is AppViewModel.LinkUi.Failed -> {
                    Text(
                        "${t.linkFailed} ${state.provider.label}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TdColor.Amber,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = TdColor.TextMid,
                    )
                    Spacer(Modifier.height(18.dp))
                    GradientButton(t.close, Modifier.fillMaxWidth()) { vm.cancelLink() }
                }

                AppViewModel.LinkUi.Idle -> Unit
            }
        }
    }
}
