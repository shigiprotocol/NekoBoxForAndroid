# STRIDER sub guard

A small program that sits in front of the 3x-ui subscription port and lets
subscription requests through **only from STRIDER**:

- blocks the 3x-ui browser page (it lists every config and skips the HWID check);
- requires `X-HWID`, so the panel's **HWID Limit** always applies;
- requires `X-Strider-Auth` (HMAC of the HWID with `STRIDER_LOCK_KEY`), so other
  apps, curl or a fake HWID get `404`.

Proxy traffic is not touched: only subscription downloads go through the guard.

## Install (on the server, as root)

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/shigiprotocol/NekoBoxForAndroid/main/tools/subguard/install.sh)
```

It asks for the lock key, the public subscription port (default 2096), a new
local port for 3x-ui (default 2097) and the TLS certificate. Then in the panel,
**Settings → Subscription**: listen IP `127.0.0.1`, port `2097`, certificate
paths empty, **Reverse Proxy URI** `https://your-domain:2096/sub/`.

Uninstall: run the same command with `uninstall` at the end.
