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

{{STOP_OTHER}}
if router_vpn_service restart "{{SERVICE_NAME}}"; then
  echo "router-control: service-backend"
  echo "active-backend: {{BACKEND_NAME}}"
  exit 0
fi
if router_vpn_service start "{{SERVICE_NAME}}"; then
  echo "router-control: service-backend"
  echo "active-backend: {{BACKEND_NAME}}"
  exit 0
fi
if pidof "{{SERVICE_NAME}}" >/dev/null 2>&1; then
  echo "router-control: service-backend"
  echo "active-backend: {{BACKEND_NAME}}"
  exit 0
fi
echo "Service {{SERVICE_NAME}} could not be started. Run Scan VPN again to see installed services."
exit 1
