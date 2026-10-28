#!/usr/bin/env bash
# STRIDER sub guard installer (run as root on the 3x-ui server).
#   install:    bash <(curl -fsSL https://raw.githubusercontent.com/shigiprotocol/NekoBoxForAndroid/main/tools/subguard/install.sh)
#   uninstall:  bash <(curl -fsSL .../install.sh) uninstall
set -euo pipefail

REPO="shigiprotocol/NekoBoxForAndroid"
BIN=/usr/local/bin/strider-subguard
ENV_FILE=/etc/strider-subguard.env
UNIT=/etc/systemd/system/strider-subguard.service

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }

[ "$(id -u)" = 0 ] || { red "Run as root."; exit 1; }

if [ "${1:-}" = "uninstall" ]; then
  systemctl disable --now strider-subguard 2>/dev/null || true
  rm -f "$BIN" "$UNIT" "$ENV_FILE"
  systemctl daemon-reload
  green "Removed. In the 3x-ui panel, set the subscription listen IP / port back to what it was."
  exit 0
fi

case "$(uname -m)" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) red "Unsupported CPU: $(uname -m)"; exit 1 ;;
esac

echo "=== STRIDER sub guard ==="
read -rsp "STRIDER_LOCK_KEY (the same value as the GitHub secret): " LOCK_KEY; echo
if ! printf '%s' "$LOCK_KEY" | grep -Eq '^[A-Za-z0-9+/=_-]{16,128}$'; then
  red "That key doesn't look right (16-128 characters: letters, digits, + / = _ -)."; exit 1
fi
read -rp "Public subscription port users already use [2096]: " PUB; PUB=${PUB:-2096}
read -rp "New local port for the 3x-ui subscription server [2097]: " LOC; LOC=${LOC:-2097}

GUESS_CERT=$(ls /root/cert/*/fullchain.pem 2>/dev/null | head -1 || true)
GUESS_KEY=${GUESS_CERT%fullchain.pem}privkey.pem
read -rp "TLS certificate (fullchain) [${GUESS_CERT:-none, plain HTTP}]: " CERT; CERT=${CERT:-$GUESS_CERT}
KEY=""
if [ -n "$CERT" ]; then
  read -rp "TLS private key [$GUESS_KEY]: " KEY; KEY=${KEY:-$GUESS_KEY}
  [ -f "$CERT" ] && [ -f "$KEY" ] || { red "Certificate or key file not found."; exit 1; }
fi

echo "Downloading..."
curl -fL --retry 3 -o "$BIN.new" "https://github.com/$REPO/releases/download/subguard/strider-subguard-linux-$ARCH"
chmod 755 "$BIN.new" && mv "$BIN.new" "$BIN"

umask 077
cat > "$ENV_FILE" <<CONF
LISTEN=:$PUB
UPSTREAM=http://127.0.0.1:$LOC
CERT=$CERT
KEY=$KEY
STRIDER_LOCK_KEY=$LOCK_KEY
CONF

cat > "$UNIT" <<'UNITEOF'
[Unit]
Description=STRIDER sub guard (in front of the 3x-ui subscription port)
After=network-online.target x-ui.service
Wants=network-online.target

[Service]
EnvironmentFile=/etc/strider-subguard.env
ExecStart=/usr/local/bin/strider-subguard
Restart=always
RestartSec=3
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
UNITEOF

systemctl daemon-reload
systemctl enable --now strider-subguard
green "Installed and started."
echo
echo "Last step, in the 3x-ui panel -> Settings -> Subscription:"
echo "  - Listen IP:   127.0.0.1"
echo "  - Listen port: $LOC"
echo "  - Certificate / key paths: empty (the guard handles TLS)"
echo "  - Reverse Proxy URI: https://YOUR-DOMAIN:$PUB/sub/   (your real domain and sub path),"
echo "    so the panel keeps showing the links with port $PUB"
echo "  Save and restart the panel. Subscription links stay the same (port $PUB)."
echo
echo "Status: systemctl status strider-subguard   |   Log: journalctl -u strider-subguard -f"
