if [ -x /jffs/scripts/nat-start ]; then
  echo "router-control: nat-start"
  if /jffs/scripts/nat-start status 2>/tmp/router_vpn_status.err; then
    cat /tmp/router_vpn_status.err >/dev/null 2>&1 || true
  else
    cat /tmp/router_vpn_status.err 2>/dev/null || true
  fi
else
  echo "router-control: service-fallback"
  echo "/jffs/scripts/nat-start: missing"
fi
if pidof xray >/dev/null 2>&1 || { [ -x /opt/etc/init.d/S24xray ] && /opt/etc/init.d/S24xray check >/dev/null 2>&1; }; then
  echo "xray: on"
else
  echo "xray: off"
fi
for p in sing-box hysteria naive wg openvpn wireguard-go; do
  if pidof "$p" >/dev/null 2>&1; then
    echo "$p: on"
  else
    echo "$p: off"
  fi
done
netstat -lntup 2>/dev/null | grep -E 'xray|sing-box|:12345|:12346' || true
