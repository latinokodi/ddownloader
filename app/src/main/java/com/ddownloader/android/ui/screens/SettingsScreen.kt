package com.ddownloader.android.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddownloader.android.BuildConfig
import com.ddownloader.android.data.AccountAuth
import com.ddownloader.android.data.DeviceAuth
import com.ddownloader.android.data.ServiceLocator
import com.ddownloader.android.data.SettingsBackup
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.DividerLine
import com.ddownloader.android.ui.components.CardAction
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.components.AuroraCard
import com.ddownloader.android.ui.components.MetricRow
import com.ddownloader.android.ui.components.SettingsSection
import com.ddownloader.android.ui.components.ValidationLine
import com.ddownloader.android.ui.components.StatusPill
import com.ddownloader.android.ui.components.TdTextField
import com.ddownloader.android.ui.components.ToggleRow
import com.ddownloader.android.ui.i18n.Language
import com.ddownloader.android.ui.i18n.LocalStrings
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.TdColor
import kotlinx.coroutines.launch

/**
 * Settings — accounts (URL + code linking), metadata, storage, backup, updates, language.
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val t = LocalStrings.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var catalogUrl by remember { mutableStateOf(settings.catalogAddonUrl) }
    val tmdbState by vm.tmdbKeyState.collectAsStateWithLifecycle()
    val torboxState by vm.torboxAccount.collectAsStateWithLifecycle()
    val rdState by vm.realDebridAccount.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tmdb by remember { mutableStateOf("") }
    var movies by remember(settings.moviesSubfolder) { mutableStateOf(settings.moviesSubfolder) }
    var series by remember(settings.seriesSubfolder) { mutableStateOf(settings.seriesSubfolder) }

    val backup = remember { SettingsBackup(context, ServiceLocator.settings) }
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
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            backup.exportTo(uri)
                .onSuccess { vm.toast(t.exportOk) }
                .onFailure { vm.toast(t.exportFailed.format(it.message ?: "")) }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            backup.importFrom(uri)
                .onSuccess { r ->
                    vm.toast(
                        "Restored: ${r.applied.joinToString().ifBlank { "nothing" }}" +
                            if (r.ignoredSecrets) " — re-link your accounts" else "",
                    )
                }
                .onFailure { vm.toast(t.importFailed.format(it.message ?: "")) }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Language ───────────────────────────────────────────────────────
        item {
            AuroraCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsSection(AppIcons.Bolt, t.language, TdColor.Cyan)
                    Spacer(Modifier.weight(1f))
                    StatusPill(if (settings.language == "es") "ES" else "EN", TdColor.Cyan, AppIcons.History)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Language.entries.forEach { lang ->
                        val active = settings.language == lang.tag
                        Text(
                            lang.nativeLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (active) TdColor.Ink0 else TdColor.TextHi,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) TdColor.Cyan else TdColor.Ink3)
                                .clickable { vm.setLanguage(lang.tag) }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }

        // ── Debrid accounts ────────────────────────────────────────────────
        item {
            AuroraCard {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsSection(AppIcons.Shield, t.accounts, TdColor.Violet)
                    Spacer(Modifier.weight(1f))
                    CardAction(t.recheck, AppIcons.Refresh) { vm.checkAccounts() }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    t.accountsHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextLow,
                )
                Spacer(Modifier.height(14.dp))
                LinkRow(
                    provider = DeviceAuth.Provider.TORBOX,
                    state = torboxState,
                    linkLabel = t.link,
                    unlinkLabel = t.unlink,
                    onLink = { vm.linkAccount(DeviceAuth.Provider.TORBOX) },
                    onUnlink = { vm.unlink(DeviceAuth.Provider.TORBOX) },
                )
                Spacer(Modifier.height(12.dp))
                DividerLine()
                Spacer(Modifier.height(12.dp))
                LinkRow(
                    provider = DeviceAuth.Provider.REALDEBRID,
                    state = rdState,
                    linkLabel = t.link,
                    unlinkLabel = t.unlink,
                    onLink = { vm.linkAccount(DeviceAuth.Provider.REALDEBRID) },
                    onUnlink = { vm.unlink(DeviceAuth.Provider.REALDEBRID) },
                )
                Spacer(Modifier.height(10.dp))
                Text(t.accountsNote, style = MaterialTheme.typography.bodySmall, color = TdColor.TextLow)
            }
        }

        // ── Privacy & security ─────────────────────────────────────────────
        item {
            AuroraCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsSection(AppIcons.Key, t.privacy, TdColor.Mint)
                    Spacer(Modifier.weight(1f))
                    StatusPill(t.hardenedPill, TdColor.Mint, AppIcons.VerifiedUser)
                }
                Spacer(Modifier.height(10.dp))
                MetricRow(t.destination, t.metricHttps)
                MetricRow(t.accounts, t.metricKeystore)
                MetricRow(t.backup, t.metricBackup)
                MetricRow(t.logs, t.metricRedacted)
            }
        }

        // ── Metadata ───────────────────────────────────────────────────────
        item {
            AuroraCard {
                SettingsSection(AppIcons.Search, t.metadata, TdColor.Cyan)
                Spacer(Modifier.height(6.dp))
                Text(t.tmdbHint, style = MaterialTheme.typography.bodySmall, color = TdColor.TextLow)
                Spacer(Modifier.height(10.dp))
                TdTextField(
                    value = tmdb,
                    onValueChange = { tmdb = it },
                    label = t.tmdbLabel,
                    secret = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(t.save, icon = AppIcons.CheckCircle) { vm.saveTmdbKey(tmdb.trim()) }
                    if (settings.hasTmdb) {
                        GhostButton(t.clear, icon = AppIcons.Delete) {
                            tmdb = ""
                            vm.saveTmdbKey("")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Catalogue add-on: same idea as the Windows app's CATALOG_BASE, but configurable.
                TdTextField(
                    value = catalogUrl,
                    onValueChange = { catalogUrl = it },
                    label = t.catalogueAddonLabel,
                    placeholder = "https://v3-cinemeta.strem.io",
                )
                Text(t.catalogueAddonHint, style = MaterialTheme.typography.labelSmall, color = TdColor.TextLow)
                Spacer(Modifier.height(6.dp))
                GhostButton(t.save, icon = AppIcons.CheckCircle) { vm.setCatalogueAddon(catalogUrl) }
                Spacer(Modifier.height(10.dp))
                // Field-level result: green when accepted, red with the reason when not.
                when (val st = tmdbState) {
                    is AppViewModel.TmdbUi.Checking -> ValidationLine(t.checking, TdColor.Cyan)
                    is AppViewModel.TmdbUi.Valid -> ValidationLine(t.tmdbValid, TdColor.Mint)
                    is AppViewModel.TmdbUi.Invalid -> ValidationLine(st.reason, TdColor.Rose)
                    AppViewModel.TmdbUi.Idle -> ValidationLine(
                        if (settings.hasTmdb) t.tmdbValid else t.tmdbHint,
                        if (settings.hasTmdb) TdColor.Mint else TdColor.TextLow,
                    )
                }
            }
        }

        // ── Storage ────────────────────────────────────────────────────────
        item {
            AuroraCard {
                SettingsSection(AppIcons.Download, t.storage, TdColor.Mint)
                Spacer(Modifier.height(10.dp))
                MetricRow(
                    t.destination,
                    if (settings.outputTreeUri.isBlank()) t.destinationApp else t.destinationCustom,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(t.chooseFolder, icon = AppIcons.FolderOpen) { treePicker.launch(null) }
                    if (settings.outputTreeUri.isNotBlank()) {
                        GhostButton(t.useAppStorage, icon = AppIcons.Delete) { vm.setOutputTree("") }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        TdTextField(
                            value = movies,
                            onValueChange = { movies = it },
                            label = t.moviesSubfolder,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        TdTextField(
                            value = series,
                            onValueChange = { series = it },
                            label = t.seriesSubfolder,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                GhostButton(t.saveFolders, icon = AppIcons.FolderOpen) {
                    vm.setSubfolders(movies.trim().ifBlank { "Movies" }, series.trim().ifBlank { "Series" })
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Series go to <series> (year)/Season NN, movies to <title> (year).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextLow,
                )
                Spacer(Modifier.height(14.dp))
                MetricRow(t.parallelDownloads, "${settings.maxConcurrentDownloads}")
                Slider(
                    value = settings.maxConcurrentDownloads.toFloat(),
                    onValueChange = { vm.setMaxConcurrent(it.toInt().coerceIn(1, 5)) },
                    valueRange = 1f..5f,
                    steps = 3,
                    colors = SliderDefaults.colors(
                        thumbColor = TdColor.Cyan,
                        activeTrackColor = TdColor.Cyan,
                        inactiveTrackColor = TdColor.Ink3,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = t.autoRemove,
                    subtitle = t.autoRemoveSub,
                    checked = settings.autoRemoveCompleted,
                    onCheckedChange = { vm.setAutoRemove(it) },
                )
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = t.wifiOnly,
                    subtitle = t.wifiOnlySub,
                    checked = settings.unmeteredOnly,
                    onCheckedChange = { vm.setUnmeteredOnly(it) },
                )
            }
        }

        // ── Backup & restore ───────────────────────────────────────────────
        item {
            AuroraCard {
                SettingsSection(AppIcons.CloudDownload, t.backup, TdColor.Violet)
                Spacer(Modifier.height(6.dp))
                Text(t.backupHint, style = MaterialTheme.typography.bodySmall, color = TdColor.TextLow)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(t.exportLabel, icon = AppIcons.Link) {
                        exportPicker.launch("ddownloader-settings.json")
                    }
                    GhostButton(t.importLabel, icon = AppIcons.FolderOpen) {
                        importPicker.launch(arrayOf("application/json"))
                    }
                }
            }
        }

        // ── Updates ────────────────────────────────────────────────────────
        item {
            AuroraCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsSection(AppIcons.Refresh, t.updates, TdColor.Amber)
                    Spacer(Modifier.weight(1f))
                    StatusPill("v${BuildConfig.VERSION_NAME}", TdColor.TextMid, AppIcons.History)
                }
                Spacer(Modifier.height(8.dp))
                Text(t.updatesHint, style = MaterialTheme.typography.bodySmall, color = TdColor.TextLow)
                Spacer(Modifier.height(10.dp))
                val upd = update
                val status = when (upd) {
                    is AppViewModel.UpdateUi.Available -> "v${upd.release.version} available"
                    is AppViewModel.UpdateUi.Downloading -> "downloading…"
                    is AppViewModel.UpdateUi.Ready -> "v${upd.release.version} ready"
                    is AppViewModel.UpdateUi.Checking -> t.checking
                    is AppViewModel.UpdateUi.Error -> upd.message
                    AppViewModel.UpdateUi.Idle -> t.upToDate
                }
                MetricRow(
                    t.statusLabel,
                    status,
                    if (upd is AppViewModel.UpdateUi.Error) TdColor.Amber else TdColor.TextHi,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(t.checkNow, icon = AppIcons.Refresh) { vm.checkForUpdate(manual = true) }
                    when (upd) {
                        is AppViewModel.UpdateUi.Available ->
                            GhostButton(t.updateLabel, icon = AppIcons.CloudDownload) { vm.downloadUpdate() }
                        is AppViewModel.UpdateUi.Ready ->
                            GhostButton(t.installLabel, icon = AppIcons.Download) { vm.installUpdate() }
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** One provider row: status + Link / Unlink. */
@Composable
private fun LinkRow(
    provider: DeviceAuth.Provider,
    state: AccountAuth.AccountState,
    linkLabel: String,
    unlinkLabel: String,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    val t = LocalStrings.current
    val linked = state is AccountAuth.AccountState.Authorized
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(provider.label, style = MaterialTheme.typography.titleSmall, color = TdColor.TextHi)
            Spacer(Modifier.height(4.dp))
            TokenStatus(state = state, hint = provider.accountHint)
        }
        Spacer(Modifier.width(10.dp))
        if (linked) {
            GhostButton(unlinkLabel, icon = AppIcons.Delete, onClick = onUnlink)
        } else {
            GhostButton(linkLabel, icon = AppIcons.Link, onClick = onLink)
        }
    }
}

/** Status line for a linked account. */
@Composable
private fun TokenStatus(state: AccountAuth.AccountState, hint: String) {
    val t = LocalStrings.current
    val tint = when (state) {
        is AccountAuth.AccountState.Authorized -> TdColor.Mint
        is AccountAuth.AccountState.Rejected -> TdColor.Rose
        is AccountAuth.AccountState.Unverified -> TdColor.Amber
        AccountAuth.AccountState.Checking -> TdColor.Cyan
        AccountAuth.AccountState.NotConfigured -> TdColor.TextLow
    }
    val label = when (state) {
        is AccountAuth.AccountState.Authorized -> t.linked
        is AccountAuth.AccountState.Rejected -> t.rejected
        is AccountAuth.AccountState.Unverified -> t.notVerified
        AccountAuth.AccountState.Checking -> t.checking
        AccountAuth.AccountState.NotConfigured -> t.notLinked
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(label, tint, AppIcons.Key)
        Spacer(Modifier.width(8.dp))
        Text(
            when (state) {
                is AccountAuth.AccountState.Authorized -> "${state.account} • ${state.detail}"
                is AccountAuth.AccountState.Rejected -> state.reason.take(80)
                is AccountAuth.AccountState.Unverified -> state.reason.take(80)
                else -> hint
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state is AccountAuth.AccountState.Rejected) TdColor.Rose else TdColor.TextMid,
        )
    }
}
