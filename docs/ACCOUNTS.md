# Linking debrid accounts (URL + code)

Accounts are authorized with a **link and a code**, never with a pasted API token.

## TorBox

1. Settings → Debrid accounts → **Link** on the TorBox row.
2. The app calls `GET https://api.torbox.app/v1/api/user/auth/device/start?app=DDownloader`
   and shows the 6-digit `code` plus the short link `https://tor.box/link`.
3. Open the short link (`https://tor.box/link` -> `torbox.app/oauth/device`), type the code, approve.
   The API also returns a `verification_url` with `?app=`; the app logs it but shows the short link.
4. The dialog polls `POST /v1/api/user/auth/device/token { device_code }`; on success the
   issued token is encrypted with the Android Keystore key and stored. It is never shown.

## Real-Debrid

1. **Link** on the Real-Debrid row.
2. The app requests `GET https://api.real-debrid.com/oauth/v2/device/code?client_id=X245A4XAIBGVM&new_credentials=yes`
   and shows the 8-character code plus `https://real-debrid.com/device`.
3. After approval the app exchanges it in two steps: `device/credentials` (returns a per-user
   `client_id`/`client_secret`), then `POST /oauth/v2/token` with the device grant. The
   access token plus the client credentials and refresh token are stored encrypted, so the
   session can be refreshed without asking the user to pair again.

## Why not tokens

Tokens are long-lived passwords that users copy between devices and paste into untrusted
places; they end up in clipboards, screenshots and chat logs. A device code is short-lived
(10–15 min), single-use, and binds the authorization to this installation. The app also never
has to render a credential.

## Language

Settings → Language switches between **English** and **Español**. The choice is persisted and
applied through a `CompositionLocal` strings layer, so it takes effect without restarting.
