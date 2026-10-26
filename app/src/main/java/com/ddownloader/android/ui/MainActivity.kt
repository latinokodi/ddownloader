package com.ddownloader.android.ui

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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.ddownloader.android.ui.updates.UpdateDialog
import com.ddownloader.android.ui.screens.SearchScreen
import com.ddownloader.android.ui.screens.SettingsScreen
import com.ddownloader.android.ui.theme.LocalTdGradients
import com.ddownloader.android.ui.theme.TdColor
import com.ddownloader.android.ui.theme.TdTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("Main", "POST_NOTIFICATIONS granted=$granted")
            vm.toast(if (granted) "Notifications enabled" else "Notifications blocked — transfers still run")
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
        AppLog.i("Main", "test intent: enqueue ${AppLog.redact(url)} name=$name expected=$expected")
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
    if (!settings.onboardingCompleted) {
        TdBackground { OnboardingScreen(vm) }
        return
    }

    var tab by remember { mutableStateOf(Tab.SEARCH) }
    var showLogs by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val active by vm.activeCount.collectAsStateWithLifecycle()
    

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.consumeMessage()
        }
    }

    TdBackground {
        UpdateDialog(vm)
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(
                    snackbar,
                    modifier = Modifier.padding(bottom = 96.dp),
                )
            },
        ) { inner ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .statusBarsPadding()
            ) {
                TopBar(
                    hasDebrid = settings.hasAnyDebrid,
                    activeDownloads = active,
                    onLogs = { showLogs = !showLogs },
                )
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = if (showLogs) null else tab,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "screen",
                    ) { target ->
                        if (target == null) {
                            LogsScreen(vm)
                        } else {
                            when (target) {
                                Tab.SEARCH -> SearchScreen(vm) {
                                    showLogs = false
                                    tab = Tab.DOWNLOADS
                                }
                                Tab.DISCOVER -> DiscoverScreen(vm)
                                Tab.DOWNLOADS -> DownloadsScreen(vm)
                                Tab.SETTINGS -> SettingsScreen(vm)
                            }
                        }
                    }
                }
                BottomNav(
                    selectedTab = if (showLogs) null else tab,
                    onSelect = {
                        showLogs = false
                        tab = it
                    },
                    onLogs = { showLogs = true },
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
    val (dot, label) = when {
        activeDownloads > 0 -> TdColor.Cyan to "TRANSFERRING"
        hasDebrid -> TdColor.Mint to "SECURE SESSION"
        else -> TdColor.TextLow to "NO ACCOUNT"
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
                    text = label + if (activeDownloads > 0) " • $activeDownloads ACTIVE" else "",
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
            modifier = Modifier.semantics { contentDescription = "Open logs" },
        ) {
            Icon(AppIcons.Terminal, contentDescription = null, tint = TdColor.TextMid)
        }
    }
}

@Composable
private fun BottomNav(
    selectedTab: Tab?,
    onSelect: (Tab) -> Unit,
    onLogs: () -> Unit,
) {
    val gradients = LocalTdGradients.current
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
                        .then(if (activeTab) Modifier.background(gradients.brandSoft) else Modifier)
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
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = t.label,
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
                    contentDescription = "Logs",
                    tint = TdColor.TextLow,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text("Logs", style = MaterialTheme.typography.labelSmall, color = TdColor.TextLow)
        }
    }
}

