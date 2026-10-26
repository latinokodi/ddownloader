"""Token-auth UI wiring for the debrid accounts card.

Run: python scripts/add_token_auth_ui.py
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "pro", "tordownloader", "android")

ACCOUNT_AUTH = os.path.join(JAVA, "data", "AccountAuth.kt")
SETTINGS = os.path.join(JAVA, "ui", "screens", "SettingsScreen.kt")


def patch(path, pairs, required=True):
    with io.open(path, encoding="utf-8") as f:
        text = f.read()
    for old, new in pairs:
        if old not in text:
            if required:
                print("  MISS in %s: %r" % (os.path.basename(path), old[:70]))
            continue
        text = text.replace(old, new, 1)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("  patched", os.path.basename(path))


STATUS_ROW = '''

/**
 * Shows whether the pasted token is actually authorized. A token is the only
 * credential this app accepts (no OAuth device flow), so this row is the proof
 * that the credential works: AUTHORIZED / TOKEN REJECTED / SAVED-UNVERIFIED.
 */
@Composable
private fun TokenStatusRow(state: AccountAuth.AccountState) {
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
        is AccountAuth.AccountState.Unverified -> "SAVED • UNVERIFIED"
        AccountAuth.AccountState.Checking -> "CHECKING…"
        AccountAuth.AccountState.NotConfigured -> "NO TOKEN"
    }
    val detail = when (state) {
        is AccountAuth.AccountState.Authorized -> "${state.account} • ${state.detail}"
        is AccountAuth.AccountState.Rejected -> state.reason.take(90)
        is AccountAuth.AccountState.Unverified -> state.reason.take(90)
        else -> "Paste the API token from the provider's website"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(label, tint, AppIcons.Key)
        Spacer(Modifier.width(8.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (state is AccountAuth.AccountState.Rejected) TdColor.Rose else TdColor.TextMid,
        )
    }
}
'''


def main():
    # 1) AccountAuth needs the JsonObject extension helpers imported explicitly.
    patch(ACCOUNT_AUTH, [
        ("import com.ddownloader.android.api.RealDebridApi\n",
         "import com.ddownloader.android.api.RealDebridApi\nimport com.ddownloader.android.api.str\n"),
    ])

    # 2) Settings screen: collect states, render a status row per provider,
    #    add a "Check tokens" action.
    pairs = [
        ("    val settings by vm.settings.collectAsStateWithLifecycle()\n",
         "    val settings by vm.settings.collectAsStateWithLifecycle()\n"
         "    val torboxState by vm.torboxAccount.collectAsStateWithLifecycle()\n"
         "    val rdState by vm.realDebridAccount.collectAsStateWithLifecycle()\n"),
        ('                SectionLabel("Debrid accounts")\n',
         '                Row(verticalAlignment = Alignment.CenterVertically) {\n'
         '                    SectionLabel("Debrid accounts")\n'
         '                    Spacer(Modifier.weight(1f))\n'
         '                    GhostButton("Check tokens", icon = AppIcons.Refresh) { vm.checkAccounts() }\n'
         '                }\n'),
        ("                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n"
         "                    GhostButton(\"Save\", icon = AppIcons.CheckCircle) {\n"
         "                        vm.saveTorboxToken(torbox.filterNot { it == '\u2022' })\n"
         "                    }\n"
         "                    GhostButton(\"Clear\", icon = AppIcons.Delete) {\n"
         "                        torbox = \"\"\n"
         "                        vm.saveTorboxToken(\"\")\n"
         "                    }\n"
         "                }",
         "                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n"
         "                    GhostButton(\"Save\", icon = AppIcons.CheckCircle) {\n"
         "                        vm.saveTorboxToken(torbox.filterNot { it == '\u2022' })\n"
         "                    }\n"
         "                    GhostButton(\"Clear\", icon = AppIcons.Delete) {\n"
         "                        torbox = \"\"\n"
         "                        vm.saveTorboxToken(\"\")\n"
         "                    }\n"
         "                }\n"
         "                Spacer(Modifier.height(8.dp))\n"
         "                TokenStatusRow(torboxState)"),
        ("                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n"
         "                    GhostButton(\"Save\", icon = AppIcons.CheckCircle) {\n"
         "                        vm.saveRealDebridToken(realdebrid.filterNot { it == '\u2022' })\n"
         "                    }\n"
         "                    GhostButton(\"Clear\", icon = AppIcons.Delete) {\n"
         "                        realdebrid = \"\"\n"
         "                        vm.saveRealDebridToken(\"\")\n"
         "                    }\n"
         "                }",
         "                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n"
         "                    GhostButton(\"Save\", icon = AppIcons.CheckCircle) {\n"
         "                        vm.saveRealDebridToken(realdebrid.filterNot { it == '\u2022' })\n"
         "                    }\n"
         "                    GhostButton(\"Clear\", icon = AppIcons.Delete) {\n"
         "                        realdebrid = \"\"\n"
         "                        vm.saveRealDebridToken(\"\")\n"
         "                    }\n"
         "                }\n"
         "                Spacer(Modifier.height(8.dp))\n"
         "                TokenStatusRow(rdState)"),
        ("import com.ddownloader.android.ui.theme.TdColor\n",
         "import com.ddownloader.android.data.AccountAuth\n"
         "import com.ddownloader.android.ui.icons.AppIcons\n"
         "import com.ddownloader.android.ui.theme.TdColor\n"),
    ]
    patch(SETTINGS, pairs)

    with io.open(SETTINGS, encoding="utf-8") as f:
        text = f.read()
    if "private fun TokenStatusRow" not in text:
        text += STATUS_ROW
    # imports the status row needs
    for imp in ("androidx.compose.foundation.layout.width",
                "androidx.compose.foundation.layout.Row"):
        if imp not in text:
            text = text.replace("import androidx.compose.foundation.layout.Spacer",
                                "import %s\nimport androidx.compose.foundation.layout.Spacer" % imp, 1)
    with io.open(SETTINGS, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("done")


if __name__ == "__main__":
    sys.exit(main())
