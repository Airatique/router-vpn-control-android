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
  if [ "$action" = "stop" ]; then
    pkill -x "$name" 2>/dev/null || true
    return 0
  fi
  return 127
}

if [ -x /jffs/scripts/nat-start ]; then
  {{NAT_START_COMMAND}}
  exit $?
fi

{{STOP_OTHER}}
if router_vpn_service restart {{SERVICE_NAME}}; then
  echo "router-control: service-fallback"
  echo "active-backend: {{ACTIVE_BACKEND}}"
  exit 0
fi
if router_vpn_service start {{SERVICE_NAME}}; then
  echo "router-control: service-fallback"
  echo "active-backend: {{ACTIVE_BACKEND}}"
  exit 0
fi
if pidof {{SERVICE_NAME}} >/dev/null 2>&1; then
  echo "router-control: service-fallback"
  echo "active-backend: {{ACTIVE_BACKEND}}"
  exit 0
fi
echo "/jffs/scripts/nat-start is missing and {{SERVICE_NAME}} service could not be started"
exit 1
