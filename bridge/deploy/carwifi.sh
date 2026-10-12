#!/bin/bash
# PHEV Link bridge — car Wi-Fi helper.
#
# Installed root-owned (0755) as /opt/phev-bridge/carwifi.sh. The bridge runs as the unprivileged user "phev" and may
# run exactly this script as root through /etc/sudoers.d/phev-bridge, so it only does these fixed jobs and checks every
# argument:
#
#   scan <iface> [CC]          list the Wi-Fi networks <iface> sees (nmcli terse SSID:SIGNAL:SECURITY)
#   join <iface> <ssid> [CC]   save the car's network as profile "phev-car" on <iface> and bring it up;
#                              the password comes on stdin (never on the command line)
#   forget                     remove the "phev-car" profile
#   pause <iface>              leave the car's Wi-Fi and stay off it (until resume, or until NetworkManager restarts),
#                              so a phone can use the car's Wi-Fi — the car takes one device at a time
#   resume <iface>             allow the car's Wi-Fi again and join it
#
# CC = two-letter Wi-Fi country (from the phone); set on Raspberry Pi OS when it differs, which also lifts the
# rfkill block a fresh Pi OS has until a country is set.
#
# Lines starting with "@" are status words for the bridge: @saved, @up-ok, @up-failed <why>, @removed <name>,
# @disabled <name>, @paused, @resumed, @error <why>.
set -u
export LC_ALL=C
CON=phev-car

die() { echo "@error $*"; exit 2; }
valid_iface() { [[ $1 =~ ^[A-Za-z0-9_.-]{1,15}$ ]]; }
valid_ssid() { [ ${#1} -ge 1 ] && [ ${#1} -le 32 ] && [[ ! $1 =~ [[:cntrl:]] ]]; }
valid_cc() { [ -z "$1" ] || [[ $1 =~ ^[A-Z]{2}$ ]]; }

command -v nmcli >/dev/null 2>&1 || die "NetworkManager (nmcli) is not installed"

# Wi-Fi radio on, rfkill lifted, country set — a fresh Raspberry Pi OS keeps Wi-Fi blocked until a country is set.
ensure_radio() {
  local cc=$1 cur
  nmcli radio wifi on >/dev/null 2>&1 || true
  if command -v rfkill >/dev/null 2>&1; then rfkill unblock wifi >/dev/null 2>&1 || true; fi
  if [ -n "$cc" ] && command -v raspi-config >/dev/null 2>&1; then
    cur=$(iw reg get 2>/dev/null | awk '/^country/ {gsub(":", "", $2); print $2; exit}')
    if [ "$cur" != "$cc" ]; then
      raspi-config nonint do_wifi_country "$cc" >/dev/null 2>&1 && echo "@country $cc" || echo "@country-failed $cc"
    fi
  fi
}

action=${1:-}
case "$action" in
  scan)
    IFACE=${2:-}; CC=${3:-}
    valid_iface "$IFACE" || die "bad interface name"
    valid_cc "$CC" || die "bad country code"
    ensure_radio "$CC"
    out=$(nmcli -t -f SSID,SIGNAL,SECURITY device wifi list ifname "$IFACE" --rescan yes 2>&1) || die "scan failed: ${out//$'\n'/ }"
    printf '%s\n' "$out"
    ;;

  join)
    IFACE=${2:-}; SSID=${3:-}; CC=${4:-}
    valid_iface "$IFACE" || die "bad interface name"
    valid_ssid "$SSID" || die "bad network name"
    valid_cc "$CC" || die "bad country code"
    PSK=""
    IFS= read -r PSK || true
    [[ $PSK =~ ^[[:print:]]{8,63}$ ]] || die "the password must be 8-63 plain characters"
    ensure_radio "$CC"

    # Exactly one profile for the car: drop our old one and any other saved profile for the same network name.
    # Profiles that were active on the car's radio (e.g. a home Wi-Fi the Pi was set up with, while the home network
    # now runs over Ethernet) must not grab that radio back when the car drives away: switch off their autoconnect.
    escaped=${SSID//\\/\\\\}; escaped=${escaped//:/\\:}
    active_on_iface=$(nmcli -g GENERAL.CONNECTION device show "$IFACE" 2>/dev/null | head -n1)
    while IFS=: read -r uuid type; do
      [ "$type" = "802-11-wireless" ] || continue
      name=$(nmcli -g connection.id connection show uuid "$uuid" 2>/dev/null)
      ssid=$(nmcli -g 802-11-wireless.ssid connection show uuid "$uuid" 2>/dev/null)
      if [ "$name" = "$CON" ] || [ "$ssid" = "$SSID" ] || [ "$ssid" = "$escaped" ]; then
        nmcli connection delete uuid "$uuid" >/dev/null 2>&1 && echo "@removed $name"
      elif [ -n "$active_on_iface" ] && [ "$name" = "$active_on_iface" ]; then
        nmcli connection modify uuid "$uuid" connection.autoconnect no >/dev/null 2>&1 && echo "@disabled $name"
      fi
    done < <(nmcli -t -f UUID,TYPE connection show 2>/dev/null)

    out=$(nmcli connection add type wifi ifname "$IFACE" con-name "$CON" ssid "$SSID" \
            wifi-sec.key-mgmt wpa-psk wifi-sec.psk "$PSK" \
            ipv4.never-default yes ipv6.method disabled \
            connection.autoconnect yes connection.autoconnect-retries 0 connection.autoconnect-priority 50 \
            802-11-wireless.cloned-mac-address permanent 802-11-wireless.powersave 2 2>&1) \
      || die "could not save the car's network: ${out//$'\n'/ }"
    echo "@saved"
    if out=$(nmcli --wait 45 connection up "$CON" ifname "$IFACE" 2>&1); then
      echo "@up-ok"
    else
      # Saved anyway: NetworkManager joins by itself when the car is in range.
      echo "@up-failed ${out//$'\n'/ }"
    fi
    ;;

  forget)
    if nmcli connection delete "$CON" >/dev/null 2>&1; then echo "@removed $CON"; else echo "@removed none"; fi
    ;;

  pause)
    IFACE=${2:-}
    valid_iface "$IFACE" || die "bad interface name"
    # Not persistent on purpose: a reboot or NetworkManager restart brings the car's Wi-Fi back by itself.
    nmcli device disconnect "$IFACE" >/dev/null 2>&1 || true
    nmcli device set "$IFACE" autoconnect no >/dev/null 2>&1 || die "could not pause $IFACE"
    echo "@paused"
    ;;

  resume)
    IFACE=${2:-}
    valid_iface "$IFACE" || die "bad interface name"
    nmcli device set "$IFACE" autoconnect yes >/dev/null 2>&1 || die "could not resume $IFACE"
    echo "@resumed"
    if out=$(nmcli --wait 30 connection up "$CON" ifname "$IFACE" 2>&1); then
      echo "@up-ok"
    else
      echo "@up-failed ${out//$'\n'/ }"
    fi
    ;;

  *)
    die "usage: carwifi.sh scan <iface> [CC] | join <iface> <ssid> [CC] (password on stdin) | forget | pause <iface> | resume <iface>"
    ;;
esac
