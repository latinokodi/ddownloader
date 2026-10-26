package com.ddownloader.android.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.ddownloader.android.BuildConfig
import com.ddownloader.android.storage.MediaLayout
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.DividerLine
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.components.GlassCard
import com.ddownloader.android.ui.components.MetricRow
import com.ddownloader.android.ui.components.SectionLabel
import com.ddownloader.android.ui.components.StatusPill
import com.ddownloader.android.ui.components.TdTextField
import com.ddownloader.android.ui.components.ToggleRow
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.data.AccountAuth
import com.ddownloader.android.ui.theme.TdColor

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val torboxState by vm.torboxAccount.collectAsStateWithLifecycle()
    val rdState by vm.realDebridAccount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var torbox by remember { mutableStateOf("") }
    var realdebrid by remember { mutableStateOf("") }
    var tmdb by remember { mutableStateOf("") }
    var layoutInput by remember { mutableStateOf("1923.S02E01.2025.1080p.WEB-DL.x264-GROUP") }
    var initialized by remember { mutableStateOf(false) }

    // Secrets are never echoed back from storage; the fields are write-only and
    // show a masked placeholder when a value already exists.
    LaunchedEffect(settings) {
        if (!initialized) {
            torbox = if (settings.hasTorBox) "••••••••••••" else ""
            realdebrid = if (settings.hasRealDebrid) "••••••••••••" else ""
            tmdb = if (settings.hasTmdb) "••••••••••••" else ""
            initialized = true
        }
    }

    val backup = remember { com.ddownloader.android.data.SettingsBackup(context, com.ddownloader.android.data.ServiceLocator.settings) }
    val backupScope = androidx.compose.runtime.rememberCoroutineScope()
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupScope.launch {
            backup.exportTo(uri)
                .onSuccess { vm.toast("Settings exported (tokens excluded)") }
                .onFailure { vm.toast("Export failed: ${it.message}") }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupScope.launch {
            backup.importFrom(uri)
                .onSuccess { r ->
                    vm.toast(
                        "Restored: ${r.applied.joinToString().ifBlank { "nothing" }}" +
                            if (r.ignoredSecrets) " — re-enter your tokens" else "",
                    )
                }
                .onFailure { vm.toast("Import failed: ${it.message}") }
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.setOutputTree(uri.toString())
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Column {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, color = TdColor.TextHi)
                Text(
                    "Accounts, storage and privacy",
                    style = MaterialTheme.typography.labelSmall,
                    color = TdColor.TextLow,
                )
            }
        }

        // ── Privacy & security ─────────────────────────────────────────────
        item {
            GlassCard(borderColor = TdColor.Mint.copy(alpha = 0.28f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Privacy & security")
                    Spacer(Modifier.weight(1f))
                    StatusPill("HARDENED", TdColor.Mint, AppIcons.VerifiedUser)
                }
                Spacer(Modifier.height(12.dp))
                MetricRow("Transport", "HTTPS only • cleartext blocked", TdColor.Cyan)
                MetricRow("Tokens", "AES-256-GCM • Android Keystore", TdColor.Mint)
                MetricRow("Backups", "Excluded (cloud + D2D)", TdColor.Mint)
                MetricRow("Logs", "Credentials auto-redacted", TdColor.Mint)
                MetricRow("Redirects", "Every hop re-validated", TdColor.Mint)
                Spacer(Modifier.height(10.dp))
                Text(
                    "This app talks to TorBox, Real-Debrid and TMDB over HTTPS only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextLow,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Keystore self-test", icon = AppIcons.Key) { vm.runKeystoreSelfTest() }
                    GhostButton("Open logs", icon = AppIcons.Terminal) { vm.toast("Use the Logs button in the top bar") }
                }
            }
        }

        // ── Accounts ───────────────────────────────────────────────────────
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Debrid accounts")
                    Spacer(Modifier.weight(1f))
                    GhostButton("Check tokens", icon = AppIcons.Refresh) { vm.checkAccounts() }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tokens are encrypted with a hardware-backed Android Keystore key and excluded from backups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextLow,
                )
                Spacer(Modifier.height(12.dp))
                TdTextField(
                    value = torbox,
                    onValueChange = { torbox = it },
                    label = "TorBox API token",
                    secret = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Save", icon = AppIcons.CheckCircle) {
                        vm.saveTorboxToken(torbox.filterNot { it == '•' })
                    }
                    GhostButton("Clear", icon = AppIcons.Delete) {
                        torbox = ""
                        vm.saveTorboxToken("")
                    }
                }
                Spacer(Modifier.height(8.dp))
                TokenStatusRow(torboxState)
                Spacer(Modifier.height(16.dp))
                DividerLine()
                Spacer(Modifier.height(16.dp))
                TdTextField(
                    value = realdebrid,
                    onValueChange = { realdebrid = it },
                    label = "Real-Debrid API token",
                    secret = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Save", icon = AppIcons.CheckCircle) {
                        vm.saveRealDebridToken(realdebrid.filterNot { it == '•' })
                    }
                    GhostButton("Clear", icon = AppIcons.Delete) {
                        realdebrid = ""
                        vm.saveRealDebridToken("")
                    }
                }
                Spacer(Modifier.height(8.dp))
                TokenStatusRow(rdState)
            }
        }

        // ── Metadata ───────────────────────────────────────────────────────
        item {
            GlassCard {
                SectionLabel("Metadata")
                Spacer(Modifier.height(10.dp))
                TdTextField(
                    value = tmdb,
                    onValueChange = { tmdb = it },
                    label = "TMDB API key",
                    secret = true,
                )
                Spacer(Modifier.height(8.dp))
                GhostButton("Validate & save", icon = AppIcons.Key) {
                    vm.saveTmdbKey(tmdb.filterNot { it == '•' })
                }
            }
        }

        // ── Storage ────────────────────────────────────────────────────────
        item {
            GlassCard {
                SectionLabel("Storage")
                Spacer(Modifier.height(10.dp))
                MetricRow(
                    "Output",
                    if (settings.outputTreeUri.isBlank()) "App storage /Download" else "Custom folder",
                    TdColor.TextHi,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Choose folder", icon = AppIcons.FolderOpen) {
                        treePicker.launch(null)
                    }
                    GhostButton("Use app storage", icon = AppIcons.Delete) { vm.setOutputTree("") }
                }
                Spacer(Modifier.height(14.dp))
                DividerLine()
                Spacer(Modifier.height(14.dp))
                Text(
                    "Simultaneous downloads: ${settings.maxConcurrentDownloads}",
                    style = MaterialTheme.typography.titleSmall,
                    color = TdColor.TextHi,
                )
                Slider(
                    value = settings.maxConcurrentDownloads.toFloat(),
                    onValueChange = { vm.setMaxConcurrent(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                    colors = SliderDefaults.colors(
                        thumbColor = TdColor.Cyan,
                        activeTrackColor = TdColor.Violet,
                        inactiveTrackColor = TdColor.Ink3,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = "Auto-remove completed from debrid",
                    subtitle = "Deletes the cloud torrent once the local file is verified",
                    checked = settings.autoRemoveCompleted,
                    onCheckedChange = { vm.setAutoRemove(it) },
                )
            }
        }

        // ── Backup & restore ───────────────────────────────────────────────
        item {
            GlassCard {
                SectionLabel("Backup & restore")
                Spacer(Modifier.height(8.dp))
                Text(
                    "An in-place update keeps your data. For a reinstall or a new device, export the " +
                        "configuration (tokens are never included) and import it later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextLow,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Export", icon = AppIcons.Link) { exportPicker.launch("ddownloader-settings.json") }
                    GhostButton("Import", icon = AppIcons.FolderOpen) { importPicker.launch(arrayOf("application/json")) }
                }
            }
        }

        // ── Updates ────────────────────────────────────────────────────────
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Updates")
                    Spacer(Modifier.weight(1f))
                    StatusPill("v${BuildConfig.VERSION_NAME}", TdColor.TextMid, AppIcons.History)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Checked against github.com/${BuildConfig.UPDATE_REPO}/releases. The APK is " +
                        "checksum- and signer-verified before Android installs it; settings and tokens are preserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextLow,
                )
                Spacer(Modifier.height(10.dp))
                val upd = vm.updateState.collectAsStateWithLifecycle().value
                val status = when (upd) {
                    is AppViewModel.UpdateUi.Available -> "v${upd.release.version} available"
                    is AppViewModel.UpdateUi.Downloading -> "downloading…"
                    is AppViewModel.UpdateUi.Ready -> "v${upd.release.version} ready to install"
                    is AppViewModel.UpdateUi.Checking -> "checking…"
                    is AppViewModel.UpdateUi.Error -> upd.message
                    AppViewModel.UpdateUi.Idle -> "up to date"
                }
                MetricRow("Status", status, if (upd is AppViewModel.UpdateUi.Error) TdColor.Amber else TdColor.TextHi)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Check now", icon = AppIcons.Refresh) { vm.checkForUpdate(manual = true) }
                    when (upd) {
                        is AppViewModel.UpdateUi.Available ->
                            GhostButton("Update", icon = AppIcons.CloudDownload) { vm.downloadUpdate() }
                        is AppViewModel.UpdateUi.Ready ->
                            GhostButton("Install", icon = AppIcons.Download) { vm.installUpdate() }
                        else -> Unit
                    }
                }
            }
        }

        // ── Diagnostics ────────────────────────────────────────────────────
        item {
            GlassCard {
                SectionLabel("Diagnostics")
                Spacer(Modifier.height(10.dp))
                TdTextField(
                    value = layoutInput,
                    onValueChange = { layoutInput = it },
                    label = "Release-name → folder layout preview",
                )
                Spacer(Modifier.height(8.dp))
                val preview = remember(layoutInput) {
                    runCatching {
                        val parsed = MediaLayout.parseReleaseName(layoutInput)
                        val dest = MediaLayout.computeDestination(MediaLayout.Kind.SERIES, layoutInput, null, parsed.season)
                        "${parsed.title} • ${parsed.year ?: "?"} → ${dest.relativePath}"
                    }.getOrElse { "—" }
                }
                Text(preview, style = MaterialTheme.typography.labelSmall, color = TdColor.Cyan)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Keystore self-test", icon = AppIcons.VerifiedUser) { vm.runKeystoreSelfTest() }
                    GhostButton("Wipe partials", icon = AppIcons.Delete) {
                        com.ddownloader.android.storage.Storage.wipePartials(context)
                        vm.toast("Partial files removed")
                    }
                }
            }
        }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "DDownloader ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TdColor.TextLow,
                )
                Text(
                    "Embedded tor 0.4.9.14 • material symbols icons • OFL fonts",
                    style = MaterialTheme.typography.labelSmall,
                    color = TdColor.TextLow,
                )
            }
        }
    }
}


/**
 * Shows whether the pasted token is actually authorized. A token is the only
 * credential this app accepts (no OAuth device flow), so this row is the proof
 * that the credential works: AUTHORIZED / TOKEN REJECTED / SAVED-UNVERIFIED.
 */
@Composable
private fun TokenStatusRow(state: AccountAuth.AccountState) {
    val tint = when (state) {
        is AccountAuth.AccountState.Authorized -> TdColor.Mint
        is AccountAuth.AccountState.Rejected -> TdColor.Rose
        is AccountAuth.AccountState.Unverified -> TdColor.Amber
        AccountAuth.AccountState.Checking -> TdColor.Cyan
        AccountAuth.AccountState.NotConfigured -> TdColor.TextLow
    }
    val label = when (state) {
        is AccountAuth.AccountState.Authorized -> "AUTHORIZED"
        is AccountAuth.AccountState.Rejected -> "TOKEN REJECTED"
        is AccountAuth.AccountState.Unverified -> "SAVED • UNVERIFIED"
        AccountAuth.AccountState.Checking -> "CHECKING…"
        AccountAuth.AccountState.NotConfigured -> "NO TOKEN"
    }
    val detail = when (state) {
        is AccountAuth.AccountState.Authorized -> "${state.account} • ${state.detail}"
        is AccountAuth.AccountState.Rejected -> state.reason.take(90)
        is AccountAuth.AccountState.Unverified -> state.reason.take(90)
        else -> "Paste the API token from the provider's website"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(label, tint, AppIcons.Key)
        Spacer(Modifier.width(8.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (state is AccountAuth.AccountState.Rejected) TdColor.Rose else TdColor.TextMid,
        )
    }
}
