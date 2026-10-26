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
    val state = vm.updateState.value
    if (state is AppViewModel.UpdateUi.Idle) return

    AlertDialog(
        onDismissRequest = { if (state !is AppViewModel.UpdateUi.Downloading) vm.laterUpdate() },
        containerColor = TdColor.Ink2,
        titleContentColor = TdColor.TextHi,
        textContentColor = TdColor.TextMid,
        title = {
            Text(
                when (state) {
                    is AppViewModel.UpdateUi.Available -> "Update available · v${state.release.version}"
                    is AppViewModel.UpdateUi.Downloading -> "Downloading update…"
                    is AppViewModel.UpdateUi.Ready -> "Ready to install · v${state.release.version}"
                    is AppViewModel.UpdateUi.Checking -> "Checking for updates…"
                    is AppViewModel.UpdateUi.Error -> "Update check failed"
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
                            "You have v${vm.currentVersion}. The new build is signed with the same key, " +
                                "so installing it keeps all your settings, tokens and queue.",
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
                                "Download size: ${Fmt.bytes(state.release.sizeBytes)}",
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
                    is AppViewModel.UpdateUi.Ready -> {
                        Text(
                            "Checksum and signature verified. Android's installer will ask you to confirm — " +
                                "your data is preserved because the application id and signing key are unchanged.",
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
                    else -> Text("Contacting GitHub Releases…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppViewModel.UpdateUi.Available -> GradientButton("Update now", icon = null) {
                    vm.downloadUpdate()
                }
                is AppViewModel.UpdateUi.Ready -> GradientButton("Install", icon = null) {
                    vm.installUpdate()
                }
                is AppViewModel.UpdateUi.Error -> GradientButton("Retry", icon = null) {
                    vm.checkForUpdate(manual = true)
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is AppViewModel.UpdateUi.Downloading) {
                GhostButton("Later") { vm.laterUpdate() }
            }
        },
    )
}

/** Compact banner used at the top of the app when an update is pending. */
@Composable
fun UpdateBanner(vm: AppViewModel) {
    val state = vm.updateState.value
    val release = (state as? AppViewModel.UpdateUi.Available)?.release ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GradientButton("Update to v${release.version}", Modifier.weight(1f)) { vm.downloadUpdate() }
        GhostButton("Later") { vm.laterUpdate() }
    }
}
