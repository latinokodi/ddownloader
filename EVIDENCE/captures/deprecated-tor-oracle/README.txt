DEPRECATED -- Tor-network relay oracle outputs (do not use).

The project owner corrected the scope: "tor" in this project means TorBox (the debrid
service), NOT the Tor network. scripts/verify_egress.py no longer classifies relays; it is
now an allowlist "no unexpected egress" checker (see ../../docs/ENVIRONMENT.md section 15).

These files are kept only as a historical record of the first capture-analysis round:
  egress-electron.json    old relay-oracle run on the Electron connection sample
  egress-guest-pcap.json  old relay-oracle run on the guest capture
  egress-control.json     old positive control (real relay -> tor-relay)
  onionoo-*.json          cached Onionoo relay list (9164 relays) used by the old oracle
Superseded reports: ../egress-allowlist-control.json, ../egress-allowlist-control2.json,
../egress-app-launch.json.
