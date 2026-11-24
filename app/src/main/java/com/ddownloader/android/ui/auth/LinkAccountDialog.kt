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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.ddownloader.android.data.DeviceAuth
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
                .fillMaxWidth()
                // The failure copy is deliberately explicit (what went wrong AND what to do), so
                // on a small screen it needs to scroll rather than push the retry button offscreen.
                .verticalScroll(rememberScrollState()),
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
                    if (state.retrying) {
                        // The code is still valid; only the phone's link to the provider is
                        // wobbling. Say so instead of silently stalling the counter.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = TdColor.Amber,
                                strokeWidth = 2.dp,
                                modifier = Modifier.width(16.dp).height(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                t.linkRetrying,
                                style = MaterialTheme.typography.bodySmall,
                                color = TdColor.Amber,
                            )
                        }
                    } else {
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
                    GradientButton(t.doneBtn, Modifier.fillMaxWidth()) { vm.cancelLink() }
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
                        linkFailureCopy(state, t),
                        style = MaterialTheme.typography.bodySmall,
                        color = TdColor.TextMid,
                    )
                    Spacer(Modifier.height(18.dp))
                    // A dropped connection or an expired code is retryable; closing the dialog
                    // would force the user to hunt for the Link button again.
                    GradientButton(t.retryLabel, Modifier.fillMaxWidth()) {
                        vm.linkAccount(state.provider)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        GhostButton(t.close) { vm.cancelLink() }
                    }
                }

                AppViewModel.LinkUi.Idle -> Unit
            }
        }
    }
}

/**
 * Turns a pairing failure into instructions.
 *
 * The old dialog printed the exception verbatim, so a resolver failure looked like
 * "Unable to resolve host \"api.torbox.app\": No address associated with hostname" — accurate,
 * alarming and unactionable. Each [DeviceAuth.Failure] now gets copy that says what to do.
 */
private fun linkFailureCopy(
    state: AppViewModel.LinkUi.Failed,
    t: com.ddownloader.android.ui.i18n.Strings,
): String = when (state.kind) {
    // Most often the phone is on a network that blocks or cannot resolve the provider, or
    // Private DNS is pointed at a resolver that is itself unreachable.
    DeviceAuth.Failure.NETWORK -> t.linkFailedNetwork
    DeviceAuth.Failure.EXPIRED -> t.linkFailedExpired
    DeviceAuth.Failure.ALREADY_USED -> t.linkFailedUsed
    // The provider answered but not with what we expected: its message is the only useful part.
    DeviceAuth.Failure.SERVER,
    DeviceAuth.Failure.UNKNOWN,
    -> state.message.ifBlank { t.linkFailedServer }
}
