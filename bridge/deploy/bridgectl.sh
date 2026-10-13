#!/bin/bash
# PHEV Link bridge — maintenance helper.
#
# Installed root-owned (0755) as /opt/phev-bridge/bridgectl.sh. The bridge runs as the unprivileged user "phev" and may
# run exactly this script as root through /etc/sudoers.d/phev-bridge. It takes no paths, URLs or other free input:
#
#   update-start   start an update in the background, in its own systemd unit so it survives the bridge restarting
#   update-run     (used by update-start) download the newest release from GitHub, check its SHA-256, install it
#   reboot         restart the computer
#   mqtt-broker    install/configure Mosquitto (MQTT broker) for the smart-home link: listens on the network, login
#                  required — user "phev", password on stdin (16-64 letters/digits). Run again to set a new password.
#
# Progress goes to /var/lib/phev-bridge/update.log (readable by the bridge). Status words for the bridge start with "@":
#   @started  @busy  @rebooting  @update-ok <version>  @update-failed <why>  @mqtt-ok  @error <why>
set -u
export LC_ALL=C
REPO=Anwo91/phev-remote-plus
BASE="https://github.com/$REPO/releases/latest/download"
STATE=/var/lib/phev-bridge
LOG=$STATE/update.log
SELF=$(readlink -f "$0")

case "${1:-}" in
  update-start)
    mkdir -p "$STATE"
    if systemctl is-active --quiet phev-bridge-update 2>/dev/null; then echo "@busy"; exit 0; fi
    command -v systemd-run >/dev/null 2>&1 || { echo "@update-failed systemd-run is missing"; exit 2; }
    : > "$LOG"
    chown phev "$LOG" 2>/dev/null || true
    chmod 0644 "$LOG"
    echo "$(date '+%F %T') update requested" >> "$LOG"
    systemctl reset-failed phev-bridge-update >/dev/null 2>&1 || true
    if systemd-run --unit=phev-bridge-update --collect --quiet "$SELF" update-run >/dev/null 2>&1; then
      echo "@started"
    else
      echo "@update-failed could not start the update" | tee -a "$LOG"
      exit 2
    fi
    ;;

  update-run)
    exec >> "$LOG" 2>&1
    say() { echo "$(date '+%F %T') $*"; }
    fail() { say "failed: $*"; echo "@update-failed $*"; exit 1; }
    command -v curl >/dev/null 2>&1 || { apt-get update -qq && apt-get install -y -qq curl >/dev/null; } || fail "curl is not installed"
    tmp=$(mktemp -d) || fail "no temporary folder"
    trap 'rm -rf "$tmp"' EXIT
    say "downloading the newest bridge from github.com/$REPO"
    curl -fsSL --retry 3 --max-time 300 -o "$tmp/bundle.tgz" "$BASE/phev-bridge-pi.tar.gz" || fail "download failed (is the bridge online?)"
    curl -fsSL --retry 3 --max-time 60 -o "$tmp/bundle.sha256" "$BASE/phev-bridge-pi.tar.gz.sha256" || fail "checksum download failed"
    want=$(tr -cd '0-9a-f' < "$tmp/bundle.sha256" | head -c 64)
    got=$(sha256sum "$tmp/bundle.tgz" | cut -d' ' -f1)
    { [ ${#want} -eq 64 ] && [ "$want" = "$got" ]; } || fail "checksum mismatch (download damaged)"
    say "checksum ok"
    tar -xzf "$tmp/bundle.tgz" -C "$tmp" || fail "could not unpack the download"
    { [ -f "$tmp/phev-bridge/install.sh" ] && [ -f "$tmp/phev-bridge/phev-bridge.jar" ]; } || fail "the download is incomplete"
    ver=$(tr -cd '0-9.' < "$tmp/phev-bridge/VERSION" 2>/dev/null)
    say "installing ${ver:-the new version} (the bridge restarts)"
    ( cd "$tmp/phev-bridge" && PHEV_QUIET_TOKEN=1 bash ./install.sh phev-bridge.jar ) || fail "the installer failed"
    say "done"
    echo "@update-ok ${ver:-?}"
    ;;

  reboot)
    echo "@rebooting"
    systemctl reboot
    ;;

  mqtt-broker)
    PW=""
    IFS= read -r PW || true
    [[ $PW =~ ^[A-Za-z0-9]{16,64}$ ]] || { echo "@error the password must be 16-64 letters and digits"; exit 2; }
    if ! command -v mosquitto >/dev/null 2>&1 || ! command -v mosquitto_passwd >/dev/null 2>&1; then
      echo "@installing"
      export DEBIAN_FRONTEND=noninteractive
      apt-get update -qq >/dev/null 2>&1
      apt-get install -y -qq mosquitto mosquitto-clients >/dev/null 2>&1 || { echo "@error could not install Mosquitto (is the bridge online?)"; exit 2; }
    fi
    install -d -m 0755 /etc/mosquitto/conf.d
    # Our own file only; the system's mosquitto.conf includes conf.d. Login required on the network listener.
    cat > /etc/mosquitto/conf.d/phev-link.conf <<'CONF'
# Written by the PHEV Link bridge (smart-home link). Remove this file to undo.
listener 1883
allow_anonymous false
password_file /etc/mosquitto/phev-link.passwd
CONF
    PWFILE=/etc/mosquitto/phev-link.passwd
    rm -f "$PWFILE"
    touch "$PWFILE"
    # -b takes the password as an argument (briefly visible to local users of this single-purpose computer).
    mosquitto_passwd -b "$PWFILE" phev "$PW" >/dev/null 2>&1 || { echo "@error could not set the broker password"; exit 2; }
    chown mosquitto:mosquitto "$PWFILE" 2>/dev/null || true
    chmod 0600 "$PWFILE"
    systemctl enable mosquitto >/dev/null 2>&1 || true
    systemctl restart mosquitto >/dev/null 2>&1 || { echo "@error Mosquitto did not start: $(journalctl -u mosquitto -n 3 --no-pager -q 2>/dev/null | tr '\n' ' ')"; exit 2; }
    echo "@mqtt-ok"
    ;;

  *)
    echo "@error unknown action"
    exit 2
    ;;
esac
