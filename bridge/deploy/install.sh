#!/usr/bin/env bash
# Installs the PHEV Link bridge on a Raspberry Pi / Debian machine.
# Usage: sudo ./install.sh phev-bridge.jar
set -euo pipefail
JAR=${1:-phev-bridge.jar}
[ -f "$JAR" ] || { echo "Give the path to phev-bridge.jar"; exit 1; }
command -v java >/dev/null || apt-get install -y openjdk-17-jre-headless
id phev >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin phev
install -d /opt/phev-bridge
install -m 0644 "$JAR" /opt/phev-bridge/phev-bridge.jar
if [ ! -f /etc/phev-bridge.properties ]; then
  java -jar /opt/phev-bridge/phev-bridge.jar --sample-config > /etc/phev-bridge.properties
  sed -i 's#^state.file=.*#state.file=/var/lib/phev-bridge/state.json#; s#^log.file=.*#log.file=/var/log/phev-bridge/bridge.log#' /etc/phev-bridge.properties
fi
chown phev /etc/phev-bridge.properties && chmod 600 /etc/phev-bridge.properties
install -m 0644 "$(dirname "$0")/phev-bridge.service" /etc/systemd/system/phev-bridge.service
systemctl daemon-reload
systemctl enable --now phev-bridge
sleep 4
echo
echo "Bridge started. Your API token:"
grep '^token=' /etc/phev-bridge.properties | cut -d= -f2
echo "Open http://$(hostname -I | awk '{print $1}'):8099/ in a browser, or enter the address and token in the app."
