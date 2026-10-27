package com.ddownloader.android.ui.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ddownloader.android.core.Fmt
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.components.GradientButton
import com.ddownloader.android.ui.theme.TdColor

/**
 * Update prompt. Shows once per new version (a dismissed version is remembered),
 * then again in-app while downloading, and a verification summary before handing
 * the APK to the system installer.
 */
@Composable
fun UpdateDialog(vm: AppViewModel) {
    val str = com.ddownloader.android.ui.i18n.LocalStrings.current
    val stateFlow = vm.updateState.collectAsStateWithLifecycle()
    val state = stateFlow.value
    if (state is AppViewModel.UpdateUi.Idle) return

    AlertDialog(
        onDismissRequest = { if (state !is AppViewModel.UpdateUi.Downloading) vm.laterUpdate() },
        containerColor = TdColor.Ink2,
        titleContentColor = TdColor.TextHi,
        textContentColor = TdColor.TextMid,
        title = {
            Text(
                when (state) {
                    is AppViewModel.UpdateUi.Available -> str.updateAvailable.format(state.release.version)
                    is AppViewModel.UpdateUi.Downloading -> str.downloadingUpdate
                    is AppViewModel.UpdateUi.Ready -> str.readyToInstall.format(state.release.version)
                    is AppViewModel.UpdateUi.Saved -> str.updateSavedTitle
                    is AppViewModel.UpdateUi.Checking -> "Checking for updates…"
                    is AppViewModel.UpdateUi.Error -> str.updateCheckFailed.format("")
                    AppViewModel.UpdateUi.Idle -> ""
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                when (state) {
                    is AppViewModel.UpdateUi.Available -> {
                        Text(
                            str.updateYouHave.format(vm.currentVersion),
                            style = MaterialTheme.typography.bodySmall,
                            color = TdColor.TextMid,
                        )
                        if (state.release.notes.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                state.release.notes.take(1200),
                                style = MaterialTheme.typography.bodySmall,
                                color = TdColor.TextLow,
                                modifier = Modifier
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        if (state.release.sizeBytes > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                str.downloadSize.format(Fmt.bytes(state.release.sizeBytes)),
                                style = MaterialTheme.typography.labelSmall,
                                color = TdColor.TextLow,
                            )
                        }
                    }
                    is AppViewModel.UpdateUi.Downloading -> {
                        val pct = if (state.total > 0) ((state.bytes * 100) / state.total).toInt() else 0
                        Text(
                            "${Fmt.bytes(state.bytes)}" + if (state.total > 0) " / ${Fmt.bytes(state.total)} ($pct%)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TdColor.TextHi,
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if (state.total > 0) state.bytes.toFloat() / state.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = TdColor.Cyan,
                            trackColor = TdColor.Ink3,
                        )
                    }
                    is AppViewModel.UpdateUi.Saved -> {
                        Text(
                            str.updateSavedBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = TdColor.TextMid,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = TdColor.Primary,
                        )
                    }
                    is AppViewModel.UpdateUi.Ready -> {
                        Text(
                            str.updateManualNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = TdColor.TextMid,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            str.verifiedNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = TdColor.TextMid,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "sha256 ${state.sha256.take(16)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = TdColor.TextLow,
                        )
                    }
                    is AppViewModel.UpdateUi.Error -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = TdColor.Amber,
                    )
                    else -> Text(str.contactingGithub, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppViewModel.UpdateUi.Available -> GradientButton(str.updateNow, icon = null) {
                    vm.downloadUpdate()
                }
                is AppViewModel.UpdateUi.Ready -> GradientButton(str.saveToDownloads, icon = null) {
                    vm.saveUpdateToDownloads()
                }
                is AppViewModel.UpdateUi.Saved -> GradientButton(str.doneBtn, icon = null) {
                    vm.laterUpdate()
                }
                is AppViewModel.UpdateUi.Error -> GradientButton(str.retryLabel, icon = null) {
                    vm.checkForUpdate(manual = true)
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is AppViewModel.UpdateUi.Downloading) {
                GhostButton(
                    if (state is AppViewModel.UpdateUi.Ready) str.saveToDownloads else str.later,
                ) {
                    if (state is AppViewModel.UpdateUi.Ready) vm.saveUpdateToDownloads() else vm.laterUpdate()
                }
            }
        },
    )
}

/** Compact banner used at the top of the app when an update is pending. */
@Composable
fun UpdateBanner(vm: AppViewModel) {
    val str = com.ddownloader.android.ui.i18n.LocalStrings.current
    val stateFlow = vm.updateState.collectAsStateWithLifecycle()
    val state = stateFlow.value
    val release = (state as? AppViewModel.UpdateUi.Available)?.release ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GradientButton(str.updateAvailable.format(release.version), Modifier.weight(1f)) { vm.downloadUpdate() }
        GhostButton(str.later) { vm.laterUpdate() }
    }
}
