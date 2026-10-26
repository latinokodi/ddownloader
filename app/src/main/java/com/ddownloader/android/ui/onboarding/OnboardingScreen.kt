package com.ddownloader.android.ui.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.ddownloader.android.data.AccountAuth
import com.ddownloader.android.data.DeviceAuth
import com.ddownloader.android.ui.i18n.LocalStrings
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.components.GlassCard
import com.ddownloader.android.ui.components.GradientButton
import com.ddownloader.android.ui.components.SectionLabel
import com.ddownloader.android.ui.components.StatusPill
import com.ddownloader.android.ui.components.TdTextField
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.LocalTdGradients
import com.ddownloader.android.ui.theme.TdColor

/**
 * First-launch assistant. Four pages, in the order the app actually needs them:
 *
 *  1. what this app is
 *  2. debrid authorization by API token (TorBox and/or Real-Debrid), proved on save
 *  3. TMDB key (optional; unlocks Discover/search)
 *  4. download folder (SAF picker or app storage)
 *
 * Everything can be skipped — the assistant is a guide, not a gate — but each step
 * shows the real validation result so a bad token is visible immediately.
 */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val torboxState by vm.torboxAccount.collectAsStateWithLifecycle()
    val rdState by vm.realDebridAccount.collectAsStateWithLifecycle()
    val t = LocalStrings.current
    val pager = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var tmdb by remember { mutableStateOf("") }

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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${t.setup} ${pager.currentPage + 1}/4",
                style = MaterialTheme.typography.labelSmall,
                color = TdColor.TextLow,
            )
            Spacer(Modifier.weight(1f))
            GhostButton(t.skip, icon = AppIcons.Close) { vm.completeOnboarding() }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top,
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> DebridPage(
                    vm = vm,
                    torboxState = torboxState,
                    rdState = rdState,
                )
                2 -> TmdbPage(vm, tmdb) { tmdb = it }
                else -> FolderPage(
                    outputTreeUri = settings.outputTreeUri,
                    onChoose = { treePicker.launch(null) },
                    onReset = { vm.setOutputTree("") },
                )
            }
        }

        // progress dots
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { i ->
                val active = i == pager.currentPage
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (active) 18.dp else 7.dp)
                        .clip(CircleShape)
                        .background(if (active) TdColor.Cyan else TdColor.Line),
                )
            }
        }

        GradientButton(
            text = if (pager.currentPage == 3) t.startUsing else t.continueLabel,
            icon = if (pager.currentPage == 3) AppIcons.CheckCircle else AppIcons.PlayArrow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (pager.currentPage == 3) {
                vm.completeOnboarding()
            } else {
                scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.displayMedium, color = TdColor.TextHi)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TdColor.TextMid)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun WelcomePage() {
    val t = LocalStrings.current
    val gradients = LocalTdGradients.current
    Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
        Box(
            Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(gradients.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Shield, contentDescription = null, tint = TdColor.TextHi, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text("D", style = MaterialTheme.typography.displayLarge, color = TdColor.TextHi, letterSpacing = (-1).sp)
        Text("DOWNLOADER", style = MaterialTheme.typography.displayLarge, color = TdColor.Cyan, letterSpacing = (-1).sp)
        Spacer(Modifier.height(16.dp))
        Text(
            t.welcomeBody,
            style = MaterialTheme.typography.bodyLarge,
            color = TdColor.TextMid,
        )
        Spacer(Modifier.height(22.dp))
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Shield, contentDescription = null, tint = TdColor.Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    t.welcomeNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextMid,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("HTTPS only", TdColor.Cyan, AppIcons.Shield)
            StatusPill("tokens in Keystore", TdColor.Mint, AppIcons.Key)
        }
    }
}

@Composable
private fun DebridPage(
    vm: AppViewModel,
    torboxState: AccountAuth.AccountState,
    rdState: AccountAuth.AccountState,
) {
    val t = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        PageHeader(
            t.debridTitle,
            t.debridBody,
        )
        GlassCard {
            ProviderLinkRow(
                provider = DeviceAuth.Provider.TORBOX,
                state = torboxState,
                onLink = { vm.linkAccount(DeviceAuth.Provider.TORBOX) },
                onUnlink = { vm.unlink(DeviceAuth.Provider.TORBOX) },
            )
            Spacer(Modifier.height(14.dp))
            ProviderLinkRow(
                provider = DeviceAuth.Provider.REALDEBRID,
                state = rdState,
                onLink = { vm.linkAccount(DeviceAuth.Provider.REALDEBRID) },
                onUnlink = { vm.unlink(DeviceAuth.Provider.REALDEBRID) },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                t.debridNote,
                style = MaterialTheme.typography.bodySmall,
                color = TdColor.TextLow,
            )
        }
    }
}

/** Provider row for the assistant. */
@Composable
private fun ProviderLinkRow(
    provider: DeviceAuth.Provider,
    state: AccountAuth.AccountState,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    val linked = state is AccountAuth.AccountState.Authorized
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(provider.label, style = MaterialTheme.typography.titleSmall, color = TdColor.TextHi)
            Spacer(Modifier.height(4.dp))
            TokenState(state = state, hint = provider.accountHint)
        }
        Spacer(Modifier.width(8.dp))
        if (linked) GhostButton("Unlink", icon = AppIcons.Delete, onClick = onUnlink)
        else GhostButton("Link", icon = AppIcons.Link, onClick = onLink)
    }
}

@Composable
private fun TmdbPage(vm: AppViewModel, tmdb: String, onTmdb: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        PageHeader(
            "Optional:\nTMDB key",
            "A free TMDB API key unlocks Discover and title search. The app asks TMDB for " +
                "metadata only; streams come from the embedded Comet add-on.",
        )
        GlassCard {
            SectionLabel("Metadata")
            Spacer(Modifier.height(8.dp))
            TdTextField(
                value = tmdb,
                onValueChange = onTmdb,
                label = "TMDB API key",
                placeholder = "themoviedb.org \u2192 Settings \u2192 API Key",
                secret = true,
            )
            Spacer(Modifier.height(10.dp))
            GhostButton("Validate & save", icon = AppIcons.Key) { vm.saveTmdbKey(tmdb.trim()) }
            Spacer(Modifier.height(8.dp))
            Text(
                "You can add this later in Settings \u2014 direct links in the Search tab work without it.",
                style = MaterialTheme.typography.bodySmall,
                color = TdColor.TextLow,
            )
        }
    }
}

@Composable
private fun FolderPage(outputTreeUri: String, onChoose: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        PageHeader(
            "Where should\nfiles go?",
            "Pick a folder once and Android remembers the permission. Without a choice, files land " +
                "in this app's own Download folder.",
        )
        GlassCard {
            SectionLabel("Destination")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.FolderOpen, contentDescription = null, tint = TdColor.Cyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (outputTreeUri.isBlank()) "App storage \u2192 Download/" else "Custom folder selected",
                    style = MaterialTheme.typography.titleSmall,
                    color = TdColor.TextHi,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Choose folder", icon = AppIcons.FolderOpen, onClick = onChoose)
                if (outputTreeUri.isNotBlank()) GhostButton("Use app storage", icon = AppIcons.Delete, onClick = onReset)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "No storage permission is requested: downloads are written through the system " +
                    "folder picker (SAF).",
                style = MaterialTheme.typography.bodySmall,
                color = TdColor.TextLow,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("no MANAGE_EXTERNAL_STORAGE", TdColor.Mint, AppIcons.VerifiedUser)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Ready. You can change everything later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = TdColor.TextMid,
            textAlign = TextAlign.Start,
        )
    }
}

/** Inline proof that the token works. */
@Composable
private fun TokenState(state: AccountAuth.AccountState, hint: String) {
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
        is AccountAuth.AccountState.Unverified -> "SAVED \u2022 NOT VERIFIED"
        AccountAuth.AccountState.Checking -> "CHECKING\u2026"
        AccountAuth.AccountState.NotConfigured -> "NOT SET"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(label, tint, AppIcons.Key)
        Spacer(Modifier.width(8.dp))
        Text(
            when (state) {
                is AccountAuth.AccountState.Authorized -> "${state.account} \u2022 ${state.detail}"
                is AccountAuth.AccountState.Rejected -> state.reason.take(80)
                is AccountAuth.AccountState.Unverified -> state.reason.take(80)
                else -> hint
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state is AccountAuth.AccountState.Rejected) TdColor.Rose else TdColor.TextMid,
            fontWeight = FontWeight.Normal,
        )
    }
}
