router_vpn_service() {
  name="$1"
  if [ -x "/opt/etc/init.d/S24${name}" ]; then
    "/opt/etc/init.d/S24${name}" stop
    return $?
  fi
  if [ -x "/opt/etc/init.d/S99${name}" ]; then
    "/opt/etc/init.d/S99${name}" stop
    return $?
  fi
  if [ -x "/etc/init.d/${name}" ]; then
    "/etc/init.d/${name}" stop
    return $?
  fi
  if command -v service >/dev/null 2>&1; then
    service "$name" stop 2>/dev/null && return 0
  fi
  if command -v systemctl >/dev/null 2>&1; then
    systemctl stop "$name" 2>/dev/null && return 0
  fi
  pkill -x "$name" 2>/dev/null || true
  return 0
}

if [ -x /jffs/scripts/nat-start ]; then
  /jffs/scripts/nat-start stop
  exit $?
fi

for backend in sing-box xray hysteria naive wg openvpn wireguard-go; do
  router_vpn_service "$backend" >/dev/null 2>&1 || true
done
echo "router-control: service-fallback"
echo "routing: off"
