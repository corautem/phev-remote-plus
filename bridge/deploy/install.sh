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
