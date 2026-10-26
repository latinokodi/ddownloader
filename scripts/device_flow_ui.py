"""Switch debrid accounts to the URL+code (device authorization) flow.

Replaces the API-token fields in Settings and in the onboarding assistant with
"Link <provider>" actions that show a verification URL + code and poll until the
account is authorized. Also wires the ViewModel state machine and dialog.

Run: python scripts/device_flow_ui.py
"""
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "com", "ddownloader", "android")


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def write(p, t):
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(t)


def patch(path, pairs, label=None):
    label = label or os.path.basename(path)
    t = read(path)
    for old, new in pairs:
        if old not in t:
            print("  MISS %s: %r" % (label, old[:70]))
            continue
        t = t.replace(old, new, 1)
    write(path, t)
    print("  patched", label)


def replace_between(path, start_marker, end_marker, new_text, label=None):
    t = read(path)
    i = t.find(start_marker)
    j = t.find(end_marker, i + 1)
    if i < 0 or j < 0:
        print("  MISS range %s" % (label or os.path.basename(path)))
        return
    write(path, t[:i] + new_text + t[j:])
    print("  replaced range in", label or os.path.basename(path))


# ── 1. Settings repository: keep the Real-Debrid refresh material ───────────
patch(os.path.join(JAVA, "data", "SettingsRepository.kt"), [
    ("        val realdebridToken: String = \"\",",
     "        val realdebridToken: String = \"\",\n"
     "        /** Per-user OAuth material from the device flow, needed to refresh. */\n"
     "        val realdebridClientId: String = \"\",\n"
     "        val realdebridClientSecret: String = \"\",\n"
     "        val realdebridRefreshToken: String = \"\","),
    ("                realdebridToken = CryptoBox.decrypt(p[K.rdToken]).orEmpty(),",
     "                realdebridToken = CryptoBox.decrypt(p[K.rdToken]).orEmpty(),\n"
     "                realdebridClientId = CryptoBox.decrypt(p[K.rdClientId]).orEmpty(),\n"
     "                realdebridClientSecret = CryptoBox.decrypt(p[K.rdClientSecret]).orEmpty(),\n"
     "                realdebridRefreshToken = CryptoBox.decrypt(p[K.rdRefresh]).orEmpty(),"),
    ("    suspend fun setRealDebridToken(token: String) = put { it[K.rdToken] = CryptoBox.encrypt(token.trim()) }",
     "    suspend fun setRealDebridToken(token: String) = put { it[K.rdToken] = CryptoBox.encrypt(token.trim()) }\n"
     "    suspend fun setRealDebridOauth(clientId: String, clientSecret: String, refreshToken: String) = put {\n"
     "        it[K.rdClientId] = CryptoBox.encrypt(clientId.trim())\n"
     "        it[K.rdClientSecret] = CryptoBox.encrypt(clientSecret.trim())\n"
     "        it[K.rdRefresh] = CryptoBox.encrypt(refreshToken.trim())\n"
     "    }"),
])

# keys for the new fields
repo = os.path.join(JAVA, "data", "SettingsRepository.kt")
t = read(repo)
if "rdClientId" not in t:
    t = t.replace('        val rdToken = stringPreferencesKey("rd_token")',
                  '        val rdToken = stringPreferencesKey("rd_token")\n'
                  '        val rdClientId = stringPreferencesKey("rd_client_id")\n'
                  '        val rdClientSecret = stringPreferencesKey("rd_client_secret")\n'
                  '        val rdRefresh = stringPreferencesKey("rd_refresh_token")')
    write(repo, t)
    print("  added Real-Debrid keys")

# ── 2. ServiceLocator: device auth ─────────────────────────────────────────
patch(os.path.join(JAVA, "data", "ServiceLocator.kt"), [
    ("    lateinit var updateChecker: UpdateChecker\n        private set",
     "    lateinit var deviceAuth: DeviceAuth\n        private set\n\n"
     "    lateinit var updateChecker: UpdateChecker\n        private set"),
    ("        updateChecker = UpdateChecker()",
     "        deviceAuth = DeviceAuth(settings)\n        updateChecker = UpdateChecker()"),
])

# ── 3. ViewModel: link state machine ───────────────────────────────────────
VM = os.path.join(JAVA, "ui", "AppViewModel.kt")
LINK_BLOCK = '''    // ── Debrid account linking (URL + code device flow) ───────────────────
    sealed interface LinkUi {
        data object Idle : LinkUi
        data class Starting(val provider: DeviceAuth.Provider) : LinkUi
        data class Waiting(
            val prompt: DeviceAuth.Prompt,
            val status: String,
            val startedAt: Long,
        ) : LinkUi
        data class Linked(val provider: DeviceAuth.Provider, val account: String) : LinkUi
        data class Failed(val provider: DeviceAuth.Provider, val message: String) : LinkUi
    }

    private val _linkState = MutableStateFlow<LinkUi>(LinkUi.Idle)
    val linkState: StateFlow<LinkUi> = _linkState.asStateFlow()
    private var linkJob: Job? = null

    /**
     * Starts the provider's device authorization: fetch a code, show it, and poll until
     * the user authorizes. The credential that comes back is stored encrypted; the UI
     * never sees it.
     */
    fun linkAccount(provider: DeviceAuth.Provider) {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            _linkState.value = LinkUi.Starting(provider)
            val prompt = locator.deviceAuth.start(provider).getOrElse { e ->
                _linkState.value = LinkUi.Failed(provider, e.message ?: "could not start pairing")
                return@launch
            }
            val started = System.currentTimeMillis()
            _linkState.value = LinkUi.Waiting(prompt, "Waiting for you to authorize…", started)
            val deadline = started + prompt.expiresInSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(prompt.intervalSeconds * 1000L)
                when (val outcome = locator.deviceAuth.poll(prompt)) {
                    is DeviceAuth.Outcome.Pending -> {
                        val left = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                        _linkState.value = LinkUi.Waiting(prompt, "Waiting for you to authorize… (${left}s)", started)
                    }
                    is DeviceAuth.Outcome.Linked -> {
                        // Persist the credential (encrypted) and the refresh material.
                        when (outcome.provider) {
                            DeviceAuth.Provider.TORBOX -> locator.settings.setTorboxToken(outcome.token)
                            DeviceAuth.Provider.REALDEBRID -> {
                                locator.settings.setRealDebridToken(outcome.token)
                                if (outcome.clientId != null && outcome.clientSecret != null) {
                                    locator.settings.setRealDebridOauth(
                                        outcome.clientId,
                                        outcome.clientSecret,
                                        outcome.refreshToken.orEmpty(),
                                    )
                                }
                            }
                        }
                        locator.settings.setOnboardingCompleted(
                            locator.settings.current().onboardingCompleted,
                        )
                        _linkState.value = LinkUi.Linked(outcome.provider, outcome.account)
                        locator.accounts.refresh()
                        return@launch
                    }
                    is DeviceAuth.Outcome.Failed -> {
                        _linkState.value = LinkUi.Failed(provider, outcome.reason)
                        return@launch
                    }
                }
            }
            _linkState.value = LinkUi.Failed(provider, "The code expired before the account was authorized.")
        }
    }

    fun cancelLink() {
        linkJob?.cancel()
        linkJob = null
        _linkState.value = LinkUi.Idle
    }

    fun unlink(provider: DeviceAuth.Provider) {
        viewModelScope.launch {
            when (provider) {
                DeviceAuth.Provider.TORBOX -> locator.settings.setTorboxToken("")
                DeviceAuth.Provider.REALDEBRID -> locator.settings.setRealDebridToken("")
            }
            locator.accounts.refresh()
            toast("${provider.label} unlinked")
        }
    }

'''
patch(VM, [
    ("    // ── Self-update ───────────────────────────────────────────────────────",
     LINK_BLOCK + "    // ── Self-update ───────────────────────────────────────────────────────"),
    ("import com.ddownloader.android.data.AccountAuth",
     "import com.ddownloader.android.data.AccountAuth\nimport com.ddownloader.android.data.DeviceAuth"),
    ("import kotlinx.coroutines.flow.MutableStateFlow",
     "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.delay\nimport kotlinx.coroutines.flow.MutableStateFlow"),
])

# ── 4. MainActivity: the dialog is global (works during onboarding too) ────
patch(os.path.join(JAVA, "ui", "MainActivity.kt"), [
    ("import com.ddownloader.android.ui.updates.UpdateDialog",
     "import com.ddownloader.android.ui.auth.LinkAccountDialog\n"
     "import com.ddownloader.android.ui.updates.UpdateDialog"),
    ("            UpdateDialog(vm)\n            OnboardingScreen(vm)",
     "            UpdateDialog(vm)\n            LinkAccountDialog(vm)\n            OnboardingScreen(vm)"),
    ("        UpdateDialog(vm)\n        Scaffold(",
     "        UpdateDialog(vm)\n        LinkAccountDialog(vm)\n        Scaffold("),
])

# ── 5. Settings: link buttons instead of token fields ──────────────────────
SETTINGS = os.path.join(JAVA, "ui", "screens", "SettingsScreen.kt")
replace_between(SETTINGS, '                SectionLabel("TorBox")', "        // ── Metadata", '''                SectionLabel("DDownloader accounts")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Accounts are added with a link and a code — no API tokens to copy around. " +
                        "Authorization happens in the provider's own web page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextLow,
                )
                Spacer(Modifier.height(12.dp))
                LinkRow(
                    provider = DeviceAuth.Provider.TORBOX,
                    state = torboxState,
                    onLink = { vm.linkAccount(DeviceAuth.Provider.TORBOX) },
                    onUnlink = { vm.unlink(DeviceAuth.Provider.TORBOX) },
                )
                Spacer(Modifier.height(10.dp))
                LinkRow(
                    provider = DeviceAuth.Provider.REALDEBRID,
                    state = rdState,
                    onLink = { vm.linkAccount(DeviceAuth.Provider.REALDEBRID) },
                    onUnlink = { vm.unlink(DeviceAuth.Provider.REALDEBRID) },
                )
            }
        }

''')

# append the LinkRow composable
t = read(SETTINGS)
if "private fun LinkRow(" not in t:
    t += '''

/** A provider row: status + "Link" / "Unlink". */
@Composable
private fun LinkRow(
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
            TokenStatusRow(state)
        }
        Spacer(Modifier.width(8.dp))
        if (linked) {
            GhostButton("Unlink", icon = AppIcons.Delete, onClick = onUnlink)
        } else {
            GhostButton("Link", icon = AppIcons.Link, onClick = onLink)
        }
    }
}
'''
if "import com.ddownloader.android.data.DeviceAuth" not in t:
    t = t.replace("import com.ddownloader.android.data.AccountAuth",
                  "import com.ddownloader.android.data.AccountAuth\nimport com.ddownloader.android.data.DeviceAuth", 1)
write(SETTINGS, t)
print("  appended LinkRow")

# ── 6. Onboarding: page 2 uses the same flow ───────────────────────────────
ONB = os.path.join(JAVA, "ui", "onboarding", "OnboardingScreen.kt")
replace_between(ONB, "private fun DebridPage(", "@Composable\nprivate fun TmdbPage(", '''private fun DebridPage(
    vm: AppViewModel,
    torboxState: AccountAuth.AccountState,
    rdState: AccountAuth.AccountState,
) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        PageHeader(
            "Link\\nyour debrid",
            "Pick a provider, open the link it gives you and type the code. One account is " +
                "enough; both works too. Nothing to copy or paste.",
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
                "The code screen finishes on its own once you authorize. Credentials stay " +
                    "encrypted on this device and are never shown.",
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

''')

# onboarding: drop the token text fields from state + call site
patch(ONB, [
    ("    var torbox by remember { mutableStateOf(\"\") }\n    var realdebrid by remember { mutableStateOf(\"\") }\n", ""),
    ("""                1 -> DebridPage(
                    vm = vm,
                    torbox = torbox,
                    onTorbox = { torbox = it },
                    realdebrid = realdebrid,
                    onRealDebrid = { realdebrid = it },
                    torboxState = torboxState,
                    rdState = rdState,
                )""",
     """                1 -> DebridPage(
                    vm = vm,
                    torboxState = torboxState,
                    rdState = rdState,
                )"""),
    ("import com.ddownloader.android.data.AccountAuth",
     "import com.ddownloader.android.data.AccountAuth\nimport com.ddownloader.android.data.DeviceAuth"),
])
print("done")
