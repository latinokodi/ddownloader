"""Wire the GitHub-Releases self-updater.

Run: python scripts/add_updater_wiring.py
"""
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "pro", "tordownloader", "android")


def patch(path, pairs, label=None, required=True):
    label = label or os.path.basename(path)
    with io.open(path, encoding="utf-8") as f:
        text = f.read()
    for old, new in pairs:
        if old not in text:
            if required:
                print("  MISS %s: %r" % (label, old[:70]))
            continue
        text = text.replace(old, new, 1)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("  patched", label)


# ── 1. BuildConfig: where updates come from ────────────────────────────────
patch(os.path.join(ROOT, "app", "build.gradle.kts"), [(
    '        buildConfigField("boolean", "VERIFY_HARNESS", "false")',
    '        buildConfigField("boolean", "VERIFY_HARNESS", "false")\n'
    '        // Self-update feed: this project\'s own GitHub Releases.\n'
    '        buildConfigField("String", "UPDATE_REPO", "\\"latinokodi/ddownloader\\"")',
)])

# ── 2. Manifest: install permission + FileProvider ─────────────────────────
patch(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"), [
    ('    <uses-permission android:name="android.permission.WAKE_LOCK" />',
     '    <uses-permission android:name="android.permission.WAKE_LOCK" />\n\n'
     '    <!-- Self-update: the app downloads a release APK from its GitHub Releases and\n'
     '         hands it to the system installer. The user still confirms the install;\n'
     '         the APK is checksum- and signer-verified first. -->\n'
     '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />'),
    ("        <!-- Resumable download engine.",
     "        <!-- Exposes only the downloaded update APK to the system installer. -->\n"
     '        <provider\n'
     '            android:name="androidx.core.content.FileProvider"\n'
     '            android:authorities="${applicationId}.updates"\n'
     '            android:exported="false"\n'
     '            android:grantUriPermissions="true">\n'
     '            <meta-data\n'
     '                android:name="android.support.FILE_PROVIDER_PATHS"\n'
     '                android:resource="@xml/update_paths" />\n'
     "        </provider>\n\n"
     "        <!-- Resumable download engine."),
])

# ── 3. Settings: remember dismissed versions + last check ──────────────────
patch(os.path.join(JAVA, "data", "SettingsRepository.kt"), [
    ("        val onboardingCompleted: Boolean = false,",
     "        val onboardingCompleted: Boolean = false,\n"
     "        /** Version the user said \"later\" to, so the prompt does not nag. */\n"
     "        val dismissedUpdateVersion: String = \"\",\n"
     "        val lastUpdateCheckAt: Long = 0L,"),
    ('        val onboarding = booleanPreferencesKey("onboarding_completed")',
     '        val onboarding = booleanPreferencesKey("onboarding_completed")\n'
     '        val dismissedUpdate = stringPreferencesKey("dismissed_update_version")\n'
     '        val lastUpdateCheck = longPreferencesKey("last_update_check_at")'),
    ("                onboardingCompleted = p[K.onboarding] ?: false,",
     "                onboardingCompleted = p[K.onboarding] ?: false,\n"
     "                dismissedUpdateVersion = p[K.dismissedUpdate] ?: \"\",\n"
     "                lastUpdateCheckAt = p[K.lastUpdateCheck] ?: 0L,"),
    ("    suspend fun setOnboardingCompleted(v: Boolean) = put { it[K.onboarding] = v }",
     "    suspend fun setOnboardingCompleted(v: Boolean) = put { it[K.onboarding] = v }\n"
     "    suspend fun setDismissedUpdateVersion(v: String) = put { it[K.dismissedUpdate] = v }\n"
     "    suspend fun setLastUpdateCheckAt(v: Long) = put { it[K.lastUpdateCheck] = v }"),
    ('import androidx.datastore.preferences.core.intPreferencesKey',
     'import androidx.datastore.preferences.core.intPreferencesKey\n'
     'import androidx.datastore.preferences.core.longPreferencesKey'),
])

# ── 4. ServiceLocator: construct the updater ───────────────────────────────
patch(os.path.join(JAVA, "data", "ServiceLocator.kt"), [
    ("    lateinit var accounts: AccountAuth\n        private set",
     "    lateinit var accounts: AccountAuth\n        private set\n\n"
     "    lateinit var updateChecker: UpdateChecker\n        private set\n\n"
     "    lateinit var updateInstaller: UpdateInstaller\n        private set"),
    ("        accounts = AccountAuth(settings, torbox, realdebrid)",
     "        accounts = AccountAuth(settings, torbox, realdebrid)\n"
     "        updateChecker = UpdateChecker()\n"
     "        updateInstaller = UpdateInstaller(app)"),
])

# ── 5. ViewModel: update state machine ─────────────────────────────────────
VM = os.path.join(JAVA, "ui", "AppViewModel.kt")
UPDATE_BLOCK = '''    // ── Self-update ───────────────────────────────────────────────────────
    sealed interface UpdateUi {
        data object Idle : UpdateUi
        data object Checking : UpdateUi
        data class Available(val release: UpdateChecker.AvailableRelease) : UpdateUi
        data class Downloading(val bytes: Long, val total: Long) : UpdateUi
        data class Ready(
            val release: UpdateChecker.AvailableRelease,
            val apk: java.io.File,
            val sha256: String,
        ) : UpdateUi
        data class Error(val message: String) : UpdateUi
    }

    val currentVersion: String = BuildConfig.VERSION_NAME
    private val _updateState = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val updateState: StateFlow<UpdateUi> = _updateState.asStateFlow()

    /**
     * Checks GitHub Releases. Auto-checks are throttled to once every 6 h and skipped
     * for a version the user already dismissed; a manual check always runs.
     */
    fun checkForUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            val cfg = settings.value
            val fresh = System.currentTimeMillis() - cfg.lastUpdateCheckAt < 6 * 60 * 60 * 1000L
            if (!manual && fresh) return@launch
            _updateState.value = UpdateUi.Checking
            val result = locator.updateChecker.check(currentVersion)
            locator.settings.setLastUpdateCheckAt(System.currentTimeMillis())
            _updateState.value = when (result) {
                is UpdateChecker.Result.UpToDate -> {
                    if (manual) toast("You are on the latest version (v$currentVersion)")
                    UpdateUi.Idle
                }
                is UpdateChecker.Result.Available -> {
                    if (result.release.version == cfg.dismissedUpdateVersion) {
                        UpdateUi.Idle
                    } else {
                        UpdateUi.Available(result.release)
                    }
                }
                is UpdateChecker.Result.Failed -> {
                    if (manual) toast("Update check failed: ${result.reason}")
                    UpdateUi.Idle
                }
            }
        }
    }

    fun downloadUpdate() {
        val current = _updateState.value
        val release = when (current) {
            is UpdateUi.Available -> current.release
            is UpdateUi.Ready -> current.release
            else -> return
        }
        viewModelScope.launch {
            _updateState.value = UpdateUi.Downloading(0, release.sizeBytes)
            when (val res = locator.updateInstaller.download(release) { bytes, total ->
                _updateState.value = UpdateUi.Downloading(bytes, total)
            }) {
                is UpdateInstaller.DownloadResult.Ready ->
                    _updateState.value = UpdateUi.Ready(release, res.apk, res.sha256)
                is UpdateInstaller.DownloadResult.Failed -> {
                    _updateState.value = UpdateUi.Error(res.reason)
                    toast("Update failed: ${res.reason}")
                }
            }
        }
    }

    fun installUpdate() {
        val ready = _updateState.value as? UpdateUi.Ready ?: return
        locator.updateInstaller.install(ready.apk)
            .onFailure { toast("Could not start the installer: ${it.message}") }
    }

    /** "Later": remember this version so the prompt does not reappear every launch. */
    fun laterUpdate() {
        val version = when (val s = _updateState.value) {
            is UpdateUi.Available -> s.release.version
            is UpdateUi.Ready -> s.release.version
            is UpdateUi.Downloading -> null
            else -> null
        }
        viewModelScope.launch {
            if (version != null) locator.settings.setDismissedUpdateVersion(version)
            _updateState.value = UpdateUi.Idle
        }
    }
'''

patch(VM, [
    ("    // ── Session ───────────────────────────────────────────────────────────",
     UPDATE_BLOCK + "\n    // ── Session ───────────────────────────────────────────────────────────"),
    ('import com.ddownloader.android.data.AccountAuth',
     'import com.ddownloader.android.BuildConfig\n'
     'import com.ddownloader.android.data.AccountAuth\n'
     'import com.ddownloader.android.data.UpdateChecker\n'
     'import com.ddownloader.android.data.UpdateInstaller\n'
     'import kotlinx.coroutines.flow.MutableStateFlow'),
    ("        viewModelScope.launch { locator.accounts.refresh() }",
     "        viewModelScope.launch { locator.accounts.refresh() }\n"
     "        checkForUpdate(manual = false)"),
])

# ── 6. MainActivity: show the dialog ───────────────────────────────────────
patch(os.path.join(JAVA, "ui", "MainActivity.kt"), [
    ("import com.ddownloader.android.ui.onboarding.OnboardingScreen",
     "import com.ddownloader.android.ui.onboarding.OnboardingScreen\n"
     "import com.ddownloader.android.ui.updates.UpdateDialog"),
    ("    TdBackground {\n        Scaffold(",
     "    TdBackground {\n"
     "        UpdateDialog(vm)\n"
     "        Scaffold("),
])

# ── 7. Settings: an Updates card ───────────────────────────────────────────
SETTINGS = os.path.join(JAVA, "ui", "screens", "SettingsScreen.kt")
patch(SETTINGS, [
    ('''        // ── Diagnostics ────────────────────────────────────────────────────''',
     '''        // ── Updates ────────────────────────────────────────────────────────
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

        // ── Diagnostics ────────────────────────────────────────────────────'''),
])
print("done")
