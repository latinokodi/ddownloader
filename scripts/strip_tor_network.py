"""Remove the onion-routing scope from the Android port.

The owner clarified that "tor" in this project means TorBox, not the Tor network.
This patch rewrites every place that assumed a Tor SOCKS circuit and replaces the
UI hero with a "secure session / pipeline" card that reflects what the app
actually does (HTTPS-only API traffic, Keystore-held tokens, FGS downloads).

Run: python scripts/strip_tor_network.py
"""
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "pro", "tordownloader", "android")


def patch(rel, replacements, must_exist=True):
    path = os.path.join(JAVA, rel)
    with io.open(path, encoding="utf-8") as f:
        text = f.read()
    original = text
    for old, new in replacements:
        if old not in text:
            if must_exist:
                print("  !! not found in %s: %r" % (rel, old[:70]))
            continue
        text = text.replace(old, new)
    if text != original:
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("  patched", rel)
    else:
        print("  unchanged", rel)


# ── SettingsRepository: drop torEnabled / keepTorAlive ──────────────────────
patch("data/SettingsRepository.kt", [
    ("        val torEnabled: Boolean = true,\n", ""),
    ("        val keepTorAlive: Boolean = true,\n", ""),
    ("        val torEnabled = booleanPreferencesKey(\"tor_enabled\")\n", ""),
    ("        val keepTorAlive = booleanPreferencesKey(\"keep_tor_alive\")\n", ""),
    ("                torEnabled = p[K.torEnabled] ?: true,\n", ""),
    ("                keepTorAlive = p[K.keepTorAlive] ?: true,\n", ""),
    ("    suspend fun setTorEnabled(v: Boolean) = put { it[K.torEnabled] = v }\n", ""),
    ("    suspend fun setKeepTorAlive(v: Boolean) = put { it[K.keepTorAlive] = v }\n", ""),
])

# ── ServiceLocator: no TorController ────────────────────────────────────────
patch("data/ServiceLocator.kt", [
    ("import com.ddownloader.android.tor.TorController\n", ""),
    ("    lateinit var tor: TorController\n        private set\n\n", ""),
    ("        tor = TorController(app)\n", ""),
])

# ── DownloadService: foreground only for transfers ──────────────────────────
patch("download/DownloadService.kt", [
    ("""            else -> {
                if (ServiceLocator.settings.settings.value.torEnabled) {
                    ServiceLocator.tor.start()
                }
                scope.launch {
                    val resumed = ServiceLocator.manager.resumeInterrupted()
                    AppLog.i(TAG, "service started (resumed=$resumed)")
                }
            }""",
     """            else -> {
                scope.launch {
                    val resumed = ServiceLocator.manager.resumeInterrupted()
                    AppLog.i(TAG, "service started (resumed=$resumed)")
                }
            }"""),
    ("""        if (ServiceLocator.manager.activeIds().isEmpty() && !ServiceLocator.settings.settings.value.keepTorAlive) {
            // Nothing left to do and Tor keep-alive is off → leave the foreground.
            stopSelf()
        }""",
     """        if (ServiceLocator.manager.activeIds().isEmpty()) {
            // Nothing left to do → leave the foreground so the notification goes away.
            stopSelf()
        }"""),
    ("""        // Tor has no independent foreground host; stop it with the service
        // unless the user asked to keep it alive.
        if (!ServiceLocator.settings.settings.value.keepTorAlive) {
            ServiceLocator.tor.stop()
        }
""", ""),
    (" * Foreground service that hosts (a) the embedded Tor process and (b) the\n * transfer queue.",
     " * Foreground service that hosts the transfer queue."),
    (" * Why a foreground service: the process must stay alive for a multi-hour\n * download, and tor-android's service is a plain background service that the OS\n * would otherwise kill. Type = `dataSync`",
     " * Why a foreground service: the process must stay alive for a multi-hour\n * download that the user started. Type = `dataSync`"),
])

# ── AppViewModel: remove Tor state/actions ──────────────────────────────────
patch("ui/AppViewModel.kt", [
    ("import com.ddownloader.android.tor.TorController\n", ""),
    ("    val torState: StateFlow<TorController.State> = locator.tor.state\n", ""),
    ("    val bootstrap: StateFlow<Int> = locator.tor.bootstrap\n", ""),
    ("""    // ── Tor ───────────────────────────────────────────────────────────────
    fun startTor() {
        locator.tor.start()
    }

    fun restartTor() {
        locator.tor.restart()
        toast("Restarting Tor…")
    }

    fun probeTor() {
        viewModelScope.launch {
            val result = locator.tor.probe()
            if (result == null) {
                toast("Tor probe failed — circuit is not usable")
            } else {
                toast("IsTor=true • exit ${result.second} • ${result.third} ms")
            }
        }
    }

    /** Called when the UI becomes visible: keep Tor warm while the app is foreground. */
    fun onUiVisible() {
        if (settings.value.torEnabled) startTor()
        ResumeWorker.schedule(getApplication())""",
     """    // ── Session ───────────────────────────────────────────────────────────
    /** Called when the UI becomes visible: re-arm the durable queue. */
    fun onUiVisible() {
        ResumeWorker.schedule(getApplication())"""),
    ("""    fun onUiHidden() {
        // Tor is hosted by the foreground service while transfers run; otherwise
        // drop the circuit to avoid a background process holding relays open.
        if (locator.manager.activeCount.value == 0 && !settings.value.keepTorAlive) {
            locator.tor.stop()
        }
    }

""", ""),
    ("""    fun setKeepTorAlive(v: Boolean) {
        viewModelScope.launch { locator.settings.setKeepTorAlive(v) }
    }

    fun setTorEnabled(v: Boolean) {
        viewModelScope.launch {
            locator.settings.setTorEnabled(v)
            if (v) locator.tor.start() else locator.tor.stop()
        }
    }

""", ""),
])

# ── MainActivity: top bar reflects the real pipeline, not a circuit ─────────
patch("ui/MainActivity.kt", [
    ("import com.ddownloader.android.tor.TorController\n", ""),
    ("""    val torState by vm.torState.collectAsStateWithLifecycle()
    val active by vm.activeCount.collectAsStateWithLifecycle()""",
     """    val active by vm.activeCount.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val torState = Unit"""),
    ("""                TopBar(
                    torState = torState,
                    activeDownloads = active,
                    onLogs = { showLogs = !showLogs },
                )""",
     """                TopBar(
                    hasDebrid = settings.hasAnyDebrid,
                    activeDownloads = active,
                    onLogs = { showLogs = !showLogs },
                )"""),
    ("""@Composable
private fun TopBar(
    torState: TorController.State,
    activeDownloads: Int,
    onLogs: () -> Unit,
) {
    val gradients = LocalTdGradients.current
    val (dot, label) = when (torState) {
        is TorController.State.Ready -> TdColor.Mint to "TOR"
        is TorController.State.Starting -> TdColor.Amber to "BOOT"
        is TorController.State.Failed -> TdColor.Rose to "OFFLINE"
        TorController.State.Off -> TdColor.TextLow to "IDLE"
    }""",
     """@Composable
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
    }"""),
    ("""    override fun onStop() {
        super.onStop()
        vm.onUiHidden()
    }

""", ""),
    ("import androidx.lifecycle.compose.collectAsStateWithLifecycle\n", "import androidx.lifecycle.compose.collectAsStateWithLifecycle\n"),
])

print("done")
