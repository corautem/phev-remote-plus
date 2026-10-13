#!/usr/bin/env bash
# Installs or updates the PHEV Link bridge on a Raspberry Pi / Debian machine. Safe to run again: the config and the
# token are kept, and the service is restarted on the new version.
# Usage: sudo ./install.sh phev-bridge.jar
# Also run by bridgectl.sh when the app updates the bridge (then PHEV_QUIET_TOKEN=1 keeps the token out of its log).
set -euo pipefail
JAR=${1:-phev-bridge.jar}
HERE=$(cd "$(dirname "$0")" && pwd)
[ -f "$JAR" ] || { echo "Give the path to phev-bridge.jar"; exit 1; }
[ "$(id -u)" -eq 0 ] || { echo "Run it with sudo"; exit 1; }
command -v java >/dev/null || { apt-get update && apt-get install -y openjdk-17-jre-headless; }
command -v sudo >/dev/null || { apt-get update && apt-get install -y sudo; }
command -v curl >/dev/null || { apt-get update && apt-get install -y curl; } || echo "WARNING: curl is missing, so the app can't update the bridge."
command -v nmcli >/dev/null || echo "WARNING: NetworkManager (nmcli) is missing, so the app can't set up the car's Wi-Fi."
id phev >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin phev
install -d /opt/phev-bridge
install -m 0644 "$JAR" /opt/phev-bridge/phev-bridge.jar
if [ ! -f /etc/phev-bridge.properties ]; then
  java -jar /opt/phev-bridge/phev-bridge.jar --sample-config > /etc/phev-bridge.properties
  sed -i 's#^state.file=.*#state.file=/var/lib/phev-bridge/state.json#; s#^log.file=.*#log.file=/var/log/phev-bridge/bridge.log#' /etc/phev-bridge.properties
fi
chown phev /etc/phev-bridge.properties && chmod 600 /etc/phev-bridge.properties
install -m 0644 "$HERE/phev-bridge.service" /etc/systemd/system/phev-bridge.service

# Helpers: root-owned and not writable by the bridge user, which may run exactly these files through sudo.
# carwifi.sh: scan for, join and forget the car's network. bridgectl.sh: update the bridge from GitHub, restart the
# computer. Nothing else.
install -o root -g root -m 0755 "$HERE/carwifi.sh" /opt/phev-bridge/carwifi.sh
install -o root -g root -m 0755 "$HERE/bridgectl.sh" /opt/phev-bridge/bridgectl.sh
SUDOERS=/etc/sudoers.d/phev-bridge
printf 'phev ALL=(root) NOPASSWD: /opt/phev-bridge/carwifi.sh, /opt/phev-bridge/bridgectl.sh\n' > "$SUDOERS.tmp"
chmod 0440 "$SUDOERS.tmp"
if visudo -cf "$SUDOERS.tmp" >/dev/null; then
  mv "$SUDOERS.tmp" "$SUDOERS"
else
  rm -f "$SUDOERS.tmp"
  echo "WARNING: the sudo rule was rejected, so the app can't set up the car's Wi-Fi or update the bridge."
fi

# Matter (Google Home, Apple Home, Alexa): a small Node.js program the bridge runs when it's switched on in the app.
# Node.js 18+ from the system (Raspberry Pi OS Bookworm has 18), else the bridge's own copy of Node.js 20.
if [ -f "$HERE/matter/phev-matter.mjs" ]; then
  install -d /opt/phev-bridge/matter
  install -m 0644 "$HERE/matter/phev-matter.mjs" /opt/phev-bridge/matter/phev-matter.mjs
  node_ok() { local v; v=$("$1" --version 2>/dev/null | sed -n 's/^v\([0-9]*\).*/\1/p'); [ -n "$v" ] && [ "$v" -ge 18 ]; }
  have=0
  if node_ok node || node_ok /opt/phev-bridge/node/bin/node; then have=1; fi
  if [ $have -eq 0 ]; then
    echo "Installing Node.js for the Matter link…"
    { apt-get install -y -qq nodejs || { apt-get update -qq && apt-get install -y -qq nodejs; }; } >/dev/null 2>&1 || true
    if node_ok node; then have=1; fi
  fi
  if [ $have -eq 0 ]; then
    case "$(uname -m)" in aarch64) A=arm64 ;; armv7l) A=armv7l ;; x86_64) A=x64 ;; *) A="" ;; esac
    if [ -n "$A" ]; then
      echo "Getting Node.js 20 for the Matter link…"
      t=$(mktemp -d)
      base=https://nodejs.org/dist/latest-v20.x
      if curl -fsSL --retry 3 -o "$t/SHASUMS256.txt" "$base/SHASUMS256.txt"; then
        line=$(grep " node-v20[0-9.]*-linux-$A.tar.gz\$" "$t/SHASUMS256.txt" | head -n1 || true)
        f=${line##* }
        want=${line%% *}
        if [ -n "$line" ] && curl -fsSL --retry 3 -o "$t/$f" "$base/$f" && [ "$(sha256sum "$t/$f" | cut -d' ' -f1)" = "$want" ]; then
          rm -rf /opt/phev-bridge/node && mkdir -p /opt/phev-bridge/node
          tar -xzf "$t/$f" -C /opt/phev-bridge/node --strip-components=1 && node_ok /opt/phev-bridge/node/bin/node && have=1
        fi
      fi
      rm -rf "$t"
    fi
  fi
  [ $have -eq 1 ] || echo "WARNING: Node.js 18 or newer isn't available, so the car can't be shared with Google Home (Matter)."
fi

systemctl daemon-reload
systemctl enable phev-bridge >/dev/null
systemctl restart phev-bridge
echo "Waiting for the bridge to start (Java can take a little while on a Pi)…"
tok=""
for i in $(seq 1 60); do
  tok=$(grep '^token=' /etc/phev-bridge.properties | cut -d= -f2)
  [ -n "$tok" ] && break
  sleep 1
done
echo
if [ -n "$tok" ] && [ "${PHEV_QUIET_TOKEN:-}" = "1" ]; then
  echo "Bridge running."
elif [ -n "$tok" ]; then
  echo "Bridge running. Your API token:  $tok"
else
  echo "The bridge did not report a token yet. Check:  journalctl -u phev-bridge -n 50"
fi
[ "${PHEV_QUIET_TOKEN:-}" = "1" ] && exit 0
echo "To pair the app: unplug the bridge for 5 seconds, plug it back in, then within 10 minutes open"
echo "PHEV Link → Settings → Set up bridge. From now on the app can update the bridge itself (Settings → Bridge)."
