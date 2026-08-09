router_vpn_service() {
  action="$1"
  name="$2"
  if [ -x "/opt/etc/init.d/S24${name}" ]; then
    "/opt/etc/init.d/S24${name}" "$action"
    return $?
  fi
  if [ -x "/opt/etc/init.d/S99${name}" ]; then
    "/opt/etc/init.d/S99${name}" "$action"
    return $?
  fi
  if [ -x "/etc/init.d/${name}" ]; then
    "/etc/init.d/${name}" "$action"
    return $?
  fi
  if command -v service >/dev/null 2>&1; then
    service "$name" "$action" 2>/dev/null && return 0
  fi
  if command -v systemctl >/dev/null 2>&1; then
    systemctl "$action" "$name" 2>/dev/null && return 0
  fi
  return 127
}

cfg={{CFG}}
tag={{TAG}}
label={{LABEL}}
[ -f "$cfg" ] || { echo "sing-box config not found: $cfg"; exit 1; }
grep -q '"final"[[:space:]]*:' "$cfg" || { echo "sing-box route.final not found in $cfg"; exit 1; }
cp "$cfg" "$cfg.routervpncontrol-backup-$(date +%Y%m%d-%H%M%S)"
sed -i 's/"final"[[:space:]]*:[[:space:]]*"[^"]*"/"final": "'"$tag"'"/' "$cfg"

if command -v sing-box >/dev/null 2>&1; then
  config_dir="$(dirname "$cfg")"
  sing-box check -D /opt/var/lib/sing-box -C "$config_dir" >/tmp/routervpncontrol-singbox-check.log 2>&1 || {
    cat /tmp/routervpncontrol-singbox-check.log
    exit 1
  }
fi

router_vpn_service restart sing-box >/tmp/routervpncontrol-singbox-restart.log 2>&1 || router_vpn_service start sing-box >/tmp/routervpncontrol-singbox-restart.log 2>&1 || true
if [ -x /jffs/scripts/nat-start ]; then
  /jffs/scripts/nat-start start >/tmp/routervpncontrol-nat-start.log 2>&1 || true
fi

echo "router-control: service-backend"
echo "active-backend: $label"
echo "sing-box-final: $tag"
