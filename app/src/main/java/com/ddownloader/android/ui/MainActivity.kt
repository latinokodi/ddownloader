package com.ddownloader.android.ui

import com.ddownloader.android.core.LogL10n
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.ddownloader.android.BuildConfig
import com.ddownloader.android.core.AppLog
import com.ddownloader.android.ui.components.StatusDot
import com.ddownloader.android.ui.components.TdBackground
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.screens.DiscoverScreen
import com.ddownloader.android.ui.screens.DownloadsScreen
import com.ddownloader.android.ui.screens.LogsScreen
import com.ddownloader.android.ui.onboarding.OnboardingScreen
import androidx.compose.runtime.CompositionLocalProvider
import com.ddownloader.android.ui.auth.LinkAccountDialog
import com.ddownloader.android.ui.i18n.Language
import com.ddownloader.android.ui.i18n.LocalStrings
import com.ddownloader.android.ui.i18n.stringsFor
import com.ddownloader.android.ui.updates.UpdateDialog
import com.ddownloader.android.ui.screens.SearchScreen
import com.ddownloader.android.ui.screens.SettingsScreen
import com.ddownloader.android.ui.theme.LocalTdGradients
import com.ddownloader.android.ui.theme.Motion
import com.ddownloader.android.ui.theme.TdColor
import com.ddownloader.android.ui.theme.TdTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("Main", LogL10n.of("POST_NOTIFICATIONS granted=$granted", "POST_NOTIFICATIONS concedido=$granted"))
            val copy = com.ddownloader.android.ui.i18n.stringsFor(
                com.ddownloader.android.ui.i18n.Language.fromTag(vm.settings.value.language),
            )
            vm.toast(if (granted) copy.notifEnabled else copy.notifBlocked)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)
        setContent {
            TdTheme {
                AppRoot(vm)
            }
        }
        askNotificationPermission()
        vm.onUiVisible()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.onUiVisible()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * DEBUG-ONLY automation hook so verification scripts can enqueue a transfer
     * without driving the UI:
     *
     *   adb shell am start -a android.intent.action.VIEW \
     *     -d "tddl://download?url=http%3A%2F%2F10.0.2.2%3A8787%2Ffile%2Fplain.bin&name=plain.bin&expected=8388608"
     *
     * Compiled out of release builds (BuildConfig.DEBUG guard).
     */
    private fun handleLaunchIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG && !BuildConfig.VERIFY_HARNESS) return
        val uri: Uri = intent?.data ?: return
        if (uri.scheme != "tddl") return
        val url = uri.getQueryParameter("url").orEmpty()
        if (url.isBlank()) return
        val name = uri.getQueryParameter("name")
        val expected = uri.getQueryParameter("expected")?.toLongOrNull() ?: 0L
        AppLog.i("Main", LogL10n.of("test intent: enqueue ${AppLog.redact(url)} name=$name expected=$expected", "intent de prueba: encolar ${AppLog.redact(url)} name=$name expected=$expected"))
        vm.addUrl(url, name, expected)
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    SEARCH("Search", AppIcons.Search),
    DISCOVER("Discover", AppIcons.Explore),
    DOWNLOADS("Downloads", AppIcons.Download),
    SETTINGS("Settings", AppIcons.Settings),
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    // First run: guide the user through debrid token auth, TMDB and the download
    // folder before showing the main UI.
    val settings by vm.settings.collectAsStateWithLifecycle()
    // The whole UI reads its copy from LocalStrings; switching language re-composes here.
    val strings = stringsFor(Language.fromTag(settings.language))
    CompositionLocalProvider(LocalStrings provides strings) {
        AppRootContent(vm, settings)
    }
}

@Composable
private fun AppRootContent(
    vm: AppViewModel,
    settings: com.ddownloader.android.data.SettingsRepository.Settings,
) {
    // Verification harness runs journeys against a clean device; onboarding is skipped there so
    // journeys can reach the real screens without provider credentials.
    if (!settings.onboardingCompleted && !BuildConfig.VERIFY_HARNESS) {
        // The assistant needs the same snackbar plumbing as the main UI: without it every
        // toast raised during setup (bad TMDB key, link errors, "resuming N downloads")
        // was silently dropped and buttons looked dead.
        val setupSnackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm.message) {
            vm.message?.let {
                setupSnackbar.showSnackbar(it)
                vm.consumeMessage()
            }
        }
        TdBackground {
            Box(Modifier.fillMaxSize()) {
                UpdateDialog(vm)
                LinkAccountDialog(vm)
                OnboardingScreen(vm)
                SnackbarHost(
                    hostState = setupSnackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
                )
            }
        }
        return
    }

    var tab by remember { mutableStateOf(Tab.SEARCH) }
    var showLogs by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val active by vm.activeCount.collectAsStateWithLifecycle()
    // Badge count: every transfer that has not reached a terminal state yet.
    val allRecords by vm.records.collectAsStateWithLifecycle()
    val pendingCount = allRecords.count { it.state.isActive }
    

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.consumeMessage()
        }
    }

    TdBackground {
        UpdateDialog(vm)
        LinkAccountDialog(vm)
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(
                    snackbar,
                    modifier = Modifier.padding(bottom = 96.dp),
                )
            },
        ) { inner ->
            // Mobile-first: phones get a single pane with a bottom bar; only wide windows get the
            // studio rail. The rail is 212 dp, which on a 411 dp phone would eat half the screen.
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .statusBarsPadding(),
            ) {
                val compact = maxWidth < 700.dp
                if (compact) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            ScreenContent(
                                vm = vm,
                                tab = tab,
                                showLogs = showLogs,
                                onOpenDownloads = {
                                    showLogs = false
                                    tab = Tab.DOWNLOADS
                                },
                            )
                        }
                        CompactBar(
                            selected = if (showLogs) null else tab,
                            pending = pendingCount,
                            onSelect = {
                                showLogs = false
                                tab = it
                            },
                            onLogs = { showLogs = true },
                        )
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        NavRail(
                            selected = if (showLogs) null else tab,
                            pending = pendingCount,
                            active = active,
                            hasDebrid = settings.hasAnyDebrid,
                            onSelect = {
                                showLogs = false
                                tab = it
                            },
                            onLogs = { showLogs = true },
                        )
                        Box(Modifier.weight(1f)) {
                            ScreenContent(
                                vm = vm,
                                tab = tab,
                                showLogs = showLogs,
                                onOpenDownloads = {
                                    showLogs = false
                                    tab = Tab.DOWNLOADS
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(
    vm: AppViewModel,
    tab: Tab,
    showLogs: Boolean,
    onOpenDownloads: () -> Unit,
) {
    AnimatedContent(
        targetState = if (showLogs) null else tab,
        transitionSpec = {
            (slideInHorizontally(
                animationSpec = tween(Motion.MEDIUM, easing = Motion.emphasized),
            ) { it / 14 } + fadeIn(tween(Motion.MEDIUM)))
                .togetherWith(fadeOut(tween(Motion.FAST)))
        },
        label = "screen",
    ) { target ->
        if (target == null) {
            ContentPane { LogsScreen(vm) }
        } else {
            when (target) {
                Tab.SEARCH -> ContentPane { SearchScreen(vm, onOpenDownloads) }
                Tab.DISCOVER -> ContentPane { DiscoverScreen(vm) }
                Tab.DOWNLOADS -> ContentPane { DownloadsScreen(vm) }
                Tab.SETTINGS -> ContentPane { SettingsScreen(vm) }
            }
        }
    }
}

/** Phone navigation: flat bar, amber indicator, no floating pill. */
@Composable
private fun CompactBar(
    selected: Tab?,
    pending: Int,
    onSelect: (Tab) -> Unit,
    onLogs: () -> Unit,
) {
    val str = com.ddownloader.android.ui.i18n.LocalStrings.current
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(TdColor.Line))
        Row(
            Modifier
                .fillMaxWidth()
                .background(TdColor.Ink1)
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val items = listOf(
                Triple(Tab.SEARCH, AppIcons.Search, str.search),
                Triple(Tab.DISCOVER, AppIcons.Explore, str.discover),
                Triple(Tab.DOWNLOADS, AppIcons.Download, str.downloads),
                Triple(Tab.SETTINGS, AppIcons.Settings, str.settings),
            )
            items.forEach { (t, icon, label) ->
                val isActive = selected == t
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(t) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (isActive) TdColor.Primary else TdColor.TextLow,
                            modifier = Modifier.size(20.dp),
                        )
                        if (t == Tab.DOWNLOADS && pending > 0) {
                            Text(
                                "$pending",
                                style = MaterialTheme.typography.labelSmall,
                                color = TdColor.Ink0,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 9.dp, y = (-5).dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(TdColor.Primary)
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) TdColor.Primary else TdColor.TextLow,
                        maxLines = 1,
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onLogs() }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    AppIcons.Terminal,
                    contentDescription = null,
                    tint = if (selected == null) TdColor.Primary else TdColor.TextLow,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    str.logs,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected == null) TdColor.Primary else TdColor.TextLow,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    hasDebrid: Boolean,
    activeDownloads: Int,
    onLogs: () -> Unit,
) {
    val gradients = LocalTdGradients.current
    val str = com.ddownloader.android.ui.i18n.LocalStrings.current
    val (dot, label) = when {
        activeDownloads > 0 -> TdColor.Cyan to str.stDownloading.uppercase()
        hasDebrid -> TdColor.Mint to str.secureSession
        else -> TdColor.TextLow to str.noDebridLinked
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(gradients.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Shield,
                contentDescription = null,
                tint = TdColor.TextHi,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = "DDOWNLOADER",
                style = MaterialTheme.typography.titleMedium,
                color = TdColor.TextHi,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dot)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = label + if (activeDownloads > 0) " · $activeDownloads" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = TdColor.TextLow,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // Icon-only control: give the *button* the label, not just the glyph
        // (uiautomator flagged the clickable node as having no accessible name).
        IconButton(
            onClick = onLogs,
            modifier = Modifier.semantics { contentDescription = str.logs },
        ) {
            Icon(AppIcons.Terminal, contentDescription = null, tint = TdColor.TextMid)
        }
    }
}

@Composable
private fun BottomNav(
    selectedTab: Tab?,
    pending: Int,
    onSelect: (Tab) -> Unit,
    onLogs: () -> Unit,
) {
    val gradients = LocalTdGradients.current
    val str = com.ddownloader.android.ui.i18n.LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .clip(RoundedCornerShape(24.dp))
            .background(TdColor.Ink2)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val activeTab = selectedTab == t
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .semantics {
                        role = androidx.compose.ui.semantics.Role.Tab
                        selected = activeTab
                    }
                    .clickable { onSelect(t) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            Modifier.background(
                                animateColorAsState(
                                    targetValue = if (activeTab) TdColor.Cyan.copy(alpha = 0.14f) else Color.Transparent,
                                    animationSpec = tween(Motion.MEDIUM),
                                    label = "nav-pill",
                                ).value,
                            ),
                        )
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = t.icon,
                        // The adjacent Text already carries the label; announcing it
                        // twice is an accessibility defect (WCAG 1.1.1 / TalkBack).
                        contentDescription = null,
                        tint = if (activeTab) TdColor.Cyan else TdColor.TextLow,
                        modifier = Modifier.size(22.dp),
                    )
                    // Live count of unfinished transfers: goes up when something is
                    // queued and down as transfers reach a terminal state.
                    val badgeScale by animateFloatAsState(
                        targetValue = if (t == Tab.DOWNLOADS && pending > 0) 1f else 0f,
                        animationSpec = spring(dampingRatio = 0.45f, stiffness = 700f),
                        label = "badge-scale",
                    )
                    if (badgeScale > 0.01f) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .graphicsLayer {
                                    scaleX = badgeScale
                                    scaleY = badgeScale
                                    alpha = badgeScale
                                }
                                .offset(x = 10.dp, y = (-6).dp)
                                .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TdColor.Violet)
                                .padding(horizontal = 4.dp)
                                .semantics { contentDescription = str.pendingDownloadsDesc.format(pending) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (pending > 99) "99+" else "$pending",
                                style = MaterialTheme.typography.labelSmall,
                                color = TdColor.TextHi,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = when (t) {
                            Tab.SEARCH -> str.search
                            Tab.DISCOVER -> str.discover
                            Tab.DOWNLOADS -> str.downloads
                            Tab.SETTINGS -> str.settings
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (activeTab) TdColor.TextHi else TdColor.TextLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            Modifier
                .weight(0.7f)
                .clip(RoundedCornerShape(18.dp))
                .semantics { role = androidx.compose.ui.semantics.Role.Tab }
                .clickable(onClick = onLogs)
                .padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(
                    AppIcons.Terminal,
                    contentDescription = str.logs,
                    tint = TdColor.TextLow,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(str.logs, style = MaterialTheme.typography.labelSmall, color = TdColor.TextLow)
        }
    }
}


/** Persistent left navigation rail: brand, status, destinations, log console. */
@Composable
private fun NavRail(
    selected: Tab?,
    pending: Int,
    active: Int,
    hasDebrid: Boolean,
    onSelect: (Tab) -> Unit,
    onLogs: () -> Unit,
) {
    val str = com.ddownloader.android.ui.i18n.LocalStrings.current
    Column(
        Modifier
            .width(212.dp)
            .fillMaxHeight()
            .background(TdColor.Ink1)
            .padding(vertical = 18.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                AppIcons.Shield,
                contentDescription = null,
                tint = TdColor.Primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "DDOWNLOADER",
                style = MaterialTheme.typography.labelSmall,
                color = TdColor.TextHi,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (active > 0) TdColor.Primary else if (hasDebrid) TdColor.Mint else TdColor.TextLow),
            )
            Text(
                when {
                    active > 0 -> "${active} ${str.tileRunning}"
                    hasDebrid -> str.statLinked
                    else -> str.statNone
                },
                style = MaterialTheme.typography.labelSmall,
                color = TdColor.TextLow,
            )
        }

        Spacer(Modifier.height(26.dp))

        RailItem(AppIcons.Search, str.search, selected == Tab.SEARCH, pending = 0) { onSelect(Tab.SEARCH) }
        RailItem(AppIcons.Explore, str.discover, selected == Tab.DISCOVER, pending = 0) { onSelect(Tab.DISCOVER) }
        RailItem(AppIcons.Download, str.downloads, selected == Tab.DOWNLOADS, pending = pending) { onSelect(Tab.DOWNLOADS) }
        RailItem(AppIcons.Settings, str.settings, selected == Tab.SETTINGS, pending = 0) { onSelect(Tab.SETTINGS) }

        Spacer(Modifier.weight(1f))

        RailItem(AppIcons.Terminal, str.logs, selected == null, pending = 0, onClick = onLogs)
        Spacer(Modifier.height(10.dp))
        Text(
            "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = TdColor.TextLow,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun RailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    pending: Int,
    onClick: () -> Unit,
) {
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        // 2 dp amber keyline marks the current destination; no pill, no background plate.
        if (active) {
            Box(
                Modifier
                    .padding(vertical = 8.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(TdColor.Primary),
            )
        }
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 19.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) TdColor.Primary else TdColor.TextLow,
                modifier = Modifier.size(16.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (active) TdColor.TextHi else TdColor.TextMid,
                modifier = Modifier.weight(1f),
            )
            if (pending > 0) {
                Text(
                    "$pending",
                    style = MaterialTheme.typography.labelSmall,
                    color = TdColor.Ink0,
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(TdColor.Primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}


/**
 * Content column of the studio layout: left-aligned, capped at 1040 dp.
 * A phone layout stretched across a 1920 px window was the main thing that read as unfinished.
 */
@Composable
private fun ContentPane(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Box(
            Modifier
                .widthIn(max = 1040.dp)
                .fillMaxHeight(),
        ) {
            content()
        }
    }
}
