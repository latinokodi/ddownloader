"""Fix the TMDB "Validate & save" button.

Three defects:
1. TmdbApi.validate() performed blocking network I/O on the main thread, threw
   NetworkOnMainThreadException and runCatching turned it into `false` — so a valid key
   was always "rejected".
2. The onboarding branch of MainActivity has no SnackbarHost, so every toast raised
   during setup (including this button's) was invisible: the button looked dead.
3. The button gave no in-place feedback at all.

Run: python scripts/fix_tmdb_validate.py
"""
import io
import os

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


# ── 1. TmdbApi: validation off the main thread ─────────────────────────────
patch(os.path.join(JAVA, "api", "TmdbApi.kt"), [
    ('''    fun validate(key: String): Boolean = runCatching {
        val res = Web.get("https://api.themoviedb.org/3/configuration?api_key=$key")
        res.code in 200..299 && res.body.contains("images")
    }.getOrDefault(false)''',
     '''    /**
     * Validates a candidate key against TMDB.
     *
     * Suspend + Dispatchers.IO on purpose: this used to be a blocking call made from a
     * viewModelScope coroutine (Main), so it threw NetworkOnMainThreadException, and the
     * surrounding runCatching reported every key as invalid.
     */
    suspend fun validate(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val res = Web.get("https://api.themoviedb.org/3/configuration?api_key=$key")
            AppLog.i(TAG, "tmdb key validation: HTTP ${res.code}")
            res.code in 200..299 && res.body.contains("images")
        }.getOrDefault(false)
    }'''),
    ("import com.ddownloader.android.core.AppLog",
     "import com.ddownloader.android.core.AppLog\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext"),
])

# ── 2. ViewModel: expose a TMDB state so the UI can show progress/result ───
VM = os.path.join(JAVA, "ui", "AppViewModel.kt")
patch(VM, [
    ('''    fun saveTmdbKey(key: String) {
        viewModelScope.launch {
            if (key.isBlank()) {
                locator.settings.setTmdbApiKey("")
                toast("TMDB key cleared")
                return@launch
            }
            if (locator.tmdb.validate(key)) {
                locator.settings.setTmdbApiKey(key)
                toast("TMDB key validated and saved")
            } else {
                toast("TMDB rejected that key")
            }
        }
    }''',
     '''    /** Observable result of "Validate & save" so the button has visible feedback. */
    sealed interface TmdbUi {
        data object Idle : TmdbUi
        data object Checking : TmdbUi
        data object Valid : TmdbUi
        data class Invalid(val reason: String) : TmdbUi
    }

    private val _tmdbKeyState = MutableStateFlow<TmdbUi>(TmdbUi.Idle)
    val tmdbKeyState: StateFlow<TmdbUi> = _tmdbKeyState.asStateFlow()

    fun saveTmdbKey(key: String) {
        viewModelScope.launch {
            if (key.isBlank()) {
                locator.settings.setTmdbApiKey("")
                _tmdbKeyState.value = TmdbUi.Idle
                toast("TMDB key cleared")
                return@launch
            }
            _tmdbKeyState.value = TmdbUi.Checking
            val valid = locator.tmdb.validate(key)
            if (valid) {
                locator.settings.setTmdbApiKey(key)
                _tmdbKeyState.value = TmdbUi.Valid
                toast("TMDB key validated and saved")
            } else {
                _tmdbKeyState.value = TmdbUi.Invalid("TMDB did not accept that key (check it at themoviedb.org → Settings → API)")
                toast("TMDB rejected that key")
            }
        }
    }

    fun resetTmdbState() {
        _tmdbKeyState.value = TmdbUi.Idle
    }'''),
])

# ── 3. Onboarding: real feedback, required wording, no dead button ─────────
ONB = os.path.join(JAVA, "ui", "onboarding", "OnboardingScreen.kt")
t = read(ONB)
start = t.find("private fun TmdbPage(")
end = t.find("@Composable\nprivate fun FolderPage(")
assert start > 0 and end > start
new_page = '''private fun TmdbPage(vm: AppViewModel, tmdb: String, onTmdb: (String) -> Unit) {
    val t = LocalStrings.current
    val tmdbState by vm.tmdbKeyState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        PageHeader(
            t.tmdbTitle,
            t.tmdbBody,
        )
        GlassCard {
            SectionLabel(t.metadataLabel)
            Spacer(Modifier.height(8.dp))
            TdTextField(
                value = tmdb,
                onValueChange = {
                    onTmdb(it)
                    vm.resetTmdbState()
                },
                label = t.tmdbLabel,
                placeholder = "themoviedb.org \\u2192 Settings \\u2192 API Key",
                secret = true,
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                if (tmdbState is AppViewModel.TmdbUi.Checking) t.checking else t.validateSave,
                icon = AppIcons.Key,
                enabled = tmdbState !is AppViewModel.TmdbUi.Checking,
            ) { vm.saveTmdbKey(tmdb.trim()) }
            Spacer(Modifier.height(10.dp))
            when (val st = tmdbState) {
                is AppViewModel.TmdbUi.Checking -> StatusLine(t.checking, TdColor.Cyan)
                is AppViewModel.TmdbUi.Valid -> StatusLine(t.tmdbValid, TdColor.Mint)
                is AppViewModel.TmdbUi.Invalid -> StatusLine(st.reason, TdColor.Rose)
                AppViewModel.TmdbUi.Idle -> StatusLine(t.tmdbRequiredHint, TdColor.TextLow)
            }
        }
    }
}

/** One-line status under an action. */
@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

'''
t = t[:start] + new_page + t[end:]
write(ONB, t)
print("  patched OnboardingScreen.kt (TmdbPage)")

# ── 4. MainActivity: the assistant needs a snackbar host too ───────────────
MAIN = os.path.join(JAVA, "ui", "MainActivity.kt")
patch(MAIN, [
    ('''        TdBackground {
            // Composed here as well: a new release should be announced even while the
            // assistant is still on screen.
            UpdateDialog(vm)
            LinkAccountDialog(vm)
            OnboardingScreen(vm)
        }''',
     '''        // The assistant needs the same snackbar plumbing as the main UI: without it every
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
        }'''),
])
print("done")
