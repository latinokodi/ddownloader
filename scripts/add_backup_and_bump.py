"""Wire settings backup/restore UI + bump version to 1.0.1.

Run: python scripts/add_backup_and_bump.py
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


# 1) SettingsBackup: fix package references after the rename
bp = os.path.join(JAVA, "data", "SettingsBackup.kt")
t = read(bp)
t = t.replace("import pro.tordownloader.android.core.AppLog", "import com.ddownloader.android.core.AppLog")
t = t.replace("com.tordownloader.android.BuildConfig", "com.ddownloader.android.BuildConfig")
write(bp, t)
print("  fixed SettingsBackup imports")

# 2) Settings screen: export/import buttons (SAF, no storage permission)
SETTINGS = os.path.join(JAVA, "ui", "screens", "SettingsScreen.kt")
patch(SETTINGS, [
    ("    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->",
     "    val backup = remember { com.ddownloader.android.data.SettingsBackup(context, com.ddownloader.android.data.ServiceLocator.settings) }\n"
     "    val backupScope = androidx.compose.runtime.rememberCoroutineScope()\n"
     "    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(\"application/json\")) { uri ->\n"
     "        if (uri != null) backupScope.launch {\n"
     "            backup.exportTo(uri)\n"
     "                .onSuccess { vm.toast(\"Settings exported (tokens excluded)\") }\n"
     "                .onFailure { vm.toast(\"Export failed: ${it.message}\") }\n"
     "        }\n"
     "    }\n"
     "    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->\n"
     "        if (uri != null) backupScope.launch {\n"
     "            backup.importFrom(uri)\n"
     "                .onSuccess { r ->\n"
     "                    vm.toast(\n"
     "                        \"Restored: ${r.applied.joinToString().ifBlank { \"nothing\" }}\" +\n"
     "                            if (r.ignoredSecrets) \" — re-enter your tokens\" else \"\",\n"
     "                    )\n"
     "                }\n"
     "                .onFailure { vm.toast(\"Import failed: ${it.message}\") }\n"
     "        }\n"
     "    }\n\n"
     "    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->"),
    ("        // ── Updates ────────────────────────────────────────────────────────",
     '''        // ── Backup & restore ───────────────────────────────────────────────
        item {
            GlassCard {
                SectionLabel("Backup & restore")
                Spacer(Modifier.height(8.dp))
                Text(
                    "An in-place update keeps your data. For a reinstall or a new device, export the " +
                        "configuration (tokens are never included) and import it later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextLow,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Export", icon = AppIcons.Share) { exportPicker.launch("ddownloader-settings.json") }
                    GhostButton("Import", icon = AppIcons.FolderOpen) { importPicker.launch(arrayOf("application/json")) }
                }
            }
        }

        // ── Updates ────────────────────────────────────────────────────────'''),
])

# 3) Version bump: 1.0.0 / code 1  ->  1.0.1 / code 2
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
t = read(GRADLE)
t = t.replace("versionCode = 1", "versionCode = 2").replace('versionName = "1.0.0"', 'versionName = "1.0.1"')
write(GRADLE, t)
print("  version bumped to 1.0.1 (code 2)")
