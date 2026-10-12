#!/bin/bash
# PHEV Link bridge — maintenance helper.
#
# Installed root-owned (0755) as /opt/phev-bridge/bridgectl.sh. The bridge runs as the unprivileged user "phev" and may
# run exactly this script as root through /etc/sudoers.d/phev-bridge. It takes no paths, URLs or other free input:
#
#   update-start   start an update in the background, in its own systemd unit so it survives the bridge restarting
#   update-run     (used by update-start) download the newest release from GitHub, check its SHA-256, install it
#   reboot         restart the computer
#
# Progress goes to /var/lib/phev-bridge/update.log (readable by the bridge). Status words for the bridge start with "@":
#   @started  @busy  @rebooting  @update-ok <version>  @update-failed <why>  @error <why>
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

  *)
    echo "@error unknown action"
    exit 2
    ;;
esac
