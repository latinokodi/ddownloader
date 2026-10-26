"""Second half of the Tor-network removal: replace the circuit hero with a
pipeline/session card and rewrite the Settings "Tor" block into a privacy block.

Run: python scripts/strip_tor_ui.py
"""
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "pro", "tordownloader", "android")

SESSION_HERO = '''
/**
 * Session hero: the status centrepiece. It answers the three questions that
 * matter in this app — is an account linked, is work running, and where does the
 * output land — using the same concentric-ring language as the rest of the theme.
 */
@Composable
fun SessionHero(
    activeTransfers: Int,
    queuedTransfers: Int,
    completedTransfers: Int,
    hasDebrid: Boolean,
    onOpenDownloads: () -> Unit,
) {
    val gradients = LocalTdGradients.current
    val running = activeTransfers > 0
    val accent = when {
        running -> TdColor.Cyan
        hasDebrid -> TdColor.Mint
        else -> TdColor.Amber
    }

    val transition = rememberInfiniteTransition(label = "hero")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "pulse",
    )

    GlassCard(borderColor = accent.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = if (running) rotation else 0f
                            scaleX = if (running) pulse else 1f
                            scaleY = if (running) pulse else 1f
                        }
                ) {
                    val sw = 3.dp.toPx()
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(accent.copy(alpha = 0.05f), accent, accent.copy(alpha = 0.05f))
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(sw, cap = StrokeCap.Round),
                        topLeft = Offset(sw / 2, sw / 2),
                        size = Size(size.width - sw, size.height - sw),
                    )
                    drawCircle(brush = gradients.heroGlow, radius = size.minDimension / 2.4f)
                }
                Icon(
                    imageVector = if (running) AppIcons.CloudDownload else AppIcons.VerifiedUser,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        running -> "TRANSFERRING"
                        hasDebrid -> "SECURE SESSION"
                        else -> "READY TO SET UP"
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        running -> "$activeTransfers running" + if (queuedTransfers > 0) " • $queuedTransfers queued" else ""
                        hasDebrid -> "TorBox / Real-Debrid linked"
                        else -> "Link a debrid account in Settings"
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = TdColor.TextHi,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill("HTTPS only", TdColor.Cyan, AppIcons.Lock)
                    StatusPill("$completedTransfers done", TdColor.TextMid, AppIcons.CheckCircle)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = "Downloads",
                icon = AppIcons.Download,
                onClick = onOpenDownloads,
            )
            Spacer(Modifier.weight(1f))
            StatusPill("tokens in Keystore", TdColor.Mint, AppIcons.VerifiedUser, filled = true)
        }
    }
}
'''


def edit(rel, fn):
    path = os.path.join(JAVA, rel)
    with io.open(path, encoding="utf-8") as f:
        text = f.read()
    new = fn(text)
    if new != text:
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(new)
        print("  patched", rel)
    else:
        print("  unchanged", rel)


def components(text):
    text = text.replace("import pro.tordownloader.android.tor.TorController\n", "")
    start = text.find("/**\n * Hero card for the Tor circuit")
    if start < 0:
        start = text.find("/**\n * Hero card")
    end = text.find("@Composable\nfun GradientButton", start)
    if start < 0 or end < 0:
        raise SystemExit("could not locate TorHero block")
    text = text[:start] + SESSION_HERO.strip() + "\n\n" + text[end:]
    return text


def search(text):
    text = text.replace(
        "    val torState by vm.torState.collectAsStateWithLifecycle()\n"
        "    val bootstrap by vm.bootstrap.collectAsStateWithLifecycle()\n",
        "    val active by vm.activeCount.collectAsStateWithLifecycle()\n"
        "    val records by vm.records.collectAsStateWithLifecycle()\n",
    )
    text = text.replace(
        "            TorHero(\n"
        "                state = torState,\n"
        "                bootstrap = bootstrap,\n"
        "                onRestart = { vm.restartTor() },\n"
        "                onProbe = { vm.probeTor() },\n"
        "            )",
        "            SessionHero(\n"
        "                activeTransfers = active,\n"
        "                queuedTransfers = records.count { it.state == pro.tordownloader.android.data.db.TransferState.QUEUED },\n"
        "                completedTransfers = records.count { it.state == pro.tordownloader.android.data.db.TransferState.COMPLETED },\n"
        "                hasDebrid = settings.hasAnyDebrid,\n"
        "                onOpenDownloads = { },\n"
        "            )",
    )
    text = text.replace(
        "import pro.tordownloader.android.ui.components.TorHero",
        "import pro.tordownloader.android.ui.components.SessionHero",
    )
    return text


PRIVACY_BLOCK = '''        // ── Privacy & security ─────────────────────────────────────────────
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
                    "This app talks to TorBox, Real-Debrid and TMDB over HTTPS. " +
                        "\\"tor\\" here is TorBox — no anonymity network is involved.",
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

'''


def settings(text):
    text = text.replace("import pro.tordownloader.android.tor.TorController\n", "")
    text = text.replace("    val torState by vm.torState.collectAsStateWithLifecycle()\n", "")
    start = text.find("        // ── Tor ─")
    end = text.find("        // ── Accounts ─")
    if start < 0 or end < 0:
        raise SystemExit("could not locate Settings Tor block")
    text = text[:start] + PRIVACY_BLOCK + text[end:]
    # remove now-unused Tor imports/state handled above; drop duplicated keystore button later if any
    return text


edit("ui/components/Components.kt", components)
edit("ui/screens/SearchScreen.kt", search)
edit("ui/screens/SettingsScreen.kt", settings)

# Small copy fixes elsewhere
edit("download/DownloadService.kt", lambda t: t.replace(
    'description = "Tor bootstrap and circuit state"',
    'description = "Transfer notifications"'))
edit("ui/screens/LogsScreen.kt", lambda t: t.replace(
    'MetricRow("Tor", "0.4.9.14 (embedded)")',
    'MetricRow("Transport", "HTTPS only")'))
edit("ui/MainActivity.kt", lambda t: t.replace("    val torState = Unit\n", ""))
print("done")
