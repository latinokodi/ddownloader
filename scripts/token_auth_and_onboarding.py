"""Token-auth validation in the ViewModel + first-run onboarding flag.

Run: python scripts/token_auth_and_onboarding.py
"""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "pro", "tordownloader", "android")

SETTINGS_SCREEN = os.path.join(JAVA, "ui", "screens", "SettingsScreen.kt")
VIEWMODEL = os.path.join(JAVA, "ui", "AppViewModel.kt")
REPO = os.path.join(JAVA, "data", "SettingsRepository.kt")


def patch(path, pairs, label=""):
    with io.open(path, encoding="utf-8") as f:
        text = f.read()
    for old, new in pairs:
        if old not in text:
            print("  MISS %s: %r" % (label or os.path.basename(path), old[:60]))
            continue
        text = text.replace(old, new, 1)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("  patched", label or os.path.basename(path))


# ── 1. SettingsScreen: drop the duplicated import ───────────────────────────
patch(SETTINGS_SCREEN, [(
    "import com.ddownloader.android.data.AccountAuth\n"
    "import com.ddownloader.android.ui.icons.AppIcons\n",
    "import com.ddownloader.android.data.AccountAuth\n",
)])

# ── 2. ViewModel: prove the token on save, expose a re-check ────────────────
OLD_SAVE = '''    fun saveTorboxToken(token: String) {
        viewModelScope.launch {
            locator.settings.setTorboxToken(token)
            toast(if (token.isBlank()) "TorBox token cleared" else "TorBox token saved (encrypted)")
        }
    }

    fun saveRealDebridToken(token: String) {
        viewModelScope.launch {
            locator.settings.setRealDebridToken(token)
            toast(if (token.isBlank()) "Real-Debrid token cleared" else "Real-Debrid token saved (encrypted)")
        }
    }'''

NEW_SAVE = '''    /**
     * Debrid auth is TOKEN auth: the pasted API token is the credential. Storing it
     * is not the same as being authorized, so it is proved against the provider's
     * authenticated endpoint and the outcome is reported.
     */
    fun saveTorboxToken(token: String) {
        viewModelScope.launch {
            locator.settings.setTorboxToken(token)
            if (token.isBlank()) {
                locator.accounts.refresh()
                toast("TorBox token cleared")
                return@launch
            }
            when (val s = locator.accounts.validateTorBox()) {
                is AccountAuth.AccountState.Authorized -> toast("TorBox authorized: ${s.account} (${s.detail})")
                is AccountAuth.AccountState.Rejected -> toast("TorBox rejected that token: ${s.reason}")
                is AccountAuth.AccountState.Unverified -> toast("Token saved — not verified (${s.reason})")
                else -> toast("Token saved")
            }
        }
    }

    fun saveRealDebridToken(token: String) {
        viewModelScope.launch {
            locator.settings.setRealDebridToken(token)
            if (token.isBlank()) {
                locator.accounts.refresh()
                toast("Real-Debrid token cleared")
                return@launch
            }
            when (val s = locator.accounts.validateRealDebrid()) {
                is AccountAuth.AccountState.Authorized -> toast("Real-Debrid authorized: ${s.account} (${s.detail})")
                is AccountAuth.AccountState.Rejected -> toast("Real-Debrid rejected that token: ${s.reason}")
                is AccountAuth.AccountState.Unverified -> toast("Token saved — not verified (${s.reason})")
                else -> toast("Token saved")
            }
        }
    }

    /** Re-checks both providers and reports a one-line status. */
    fun checkAccounts() {
        viewModelScope.launch {
            locator.accounts.refresh()
            fun describe(s: AccountAuth.AccountState) = when (s) {
                is AccountAuth.AccountState.Authorized -> "authorized (${s.account})"
                is AccountAuth.AccountState.Rejected -> "token rejected"
                is AccountAuth.AccountState.Unverified -> "unverified"
                AccountAuth.AccountState.Checking -> "checking…"
                AccountAuth.AccountState.NotConfigured -> "not configured"
            }
            toast(
                "TorBox: ${describe(locator.accounts.torboxState.value)} • " +
                    "Real-Debrid: ${describe(locator.accounts.realdebridState.value)}",
            )
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            locator.settings.setOnboardingCompleted(true)
            toast("Setup complete")
        }
    }'''

patch(VIEWMODEL, [
    (OLD_SAVE, NEW_SAVE),
    ("    fun onUiVisible() {\n        ResumeWorker.schedule(getApplication())",
     "    fun onUiVisible() {\n        ResumeWorker.schedule(getApplication())\n"
     "        viewModelScope.launch { locator.accounts.refresh() }"),
])

# ── 3. SettingsRepository: remember that onboarding was completed ───────────
patch(REPO, [
    ("        val unmeteredOnly: Boolean = false,",
     "        val unmeteredOnly: Boolean = false,\n        val onboardingCompleted: Boolean = false,"),
    ('        val unmeteredOnly = booleanPreferencesKey("unmetered_only")',
     '        val unmeteredOnly = booleanPreferencesKey("unmetered_only")\n'
     '        val onboarding = booleanPreferencesKey("onboarding_completed")'),
    ("                unmeteredOnly = p[K.unmeteredOnly] ?: false,",
     "                unmeteredOnly = p[K.unmeteredOnly] ?: false,\n"
     "                onboardingCompleted = p[K.onboarding] ?: false,"),
    ("    suspend fun setUnmeteredOnly(v: Boolean) = put { it[K.unmeteredOnly] = v }",
     "    suspend fun setUnmeteredOnly(v: Boolean) = put { it[K.unmeteredOnly] = v }\n"
     "    suspend fun setOnboardingCompleted(v: Boolean) = put { it[K.onboarding] = v }"),
])

# ── 4. MainActivity: show the assistant until setup is done ────────────────
MAIN = os.path.join(JAVA, "ui", "MainActivity.kt")
patch(MAIN, [
    ("import com.ddownloader.android.ui.screens.SearchScreen",
     "import com.ddownloader.android.ui.onboarding.OnboardingScreen\n"
     "import com.ddownloader.android.ui.screens.SearchScreen"),
    ("""@Composable
private fun AppRoot(vm: AppViewModel) {
    var tab by remember { mutableStateOf(Tab.SEARCH) }""",
     """@Composable
private fun AppRoot(vm: AppViewModel) {
    // First run: guide the user through debrid token auth, TMDB and the download
    // folder before showing the main UI.
    val settings by vm.settings.collectAsStateWithLifecycle()
    if (!settings.onboardingCompleted) {
        TdBackground { OnboardingScreen(vm) }
        return
    }

    var tab by remember { mutableStateOf(Tab.SEARCH) }"""),
])
print("done")
