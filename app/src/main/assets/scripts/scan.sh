echo "Router: $(hostname 2>/dev/null || nvram get productid 2>/dev/null || echo unknown)"
echo "Time: $(date 2>/dev/null || echo unknown)"
echo
echo "=== discovered backends ==="
router_vpn_natstart_backends() {
  [ -x /jffs/scripts/nat-start ] || return 1
  active="$(/jffs/scripts/nat-start status 2>/dev/null | sed -n 's/^active-backend:[[:space:]]*//p' | tail -1)"
  ports="$(netstat -lntup 2>/dev/null | grep -E 'xray|sing-box|:12345|:12346|:12347' | awk '{print $4}' | sed 's/.*://' | sort -u | tr '\n' ',' | sed 's/,$//')"
  commands="$(/jffs/scripts/nat-start bad-command 2>&1 | grep -Eo 'use-[a-z0-9-]+' | sort -u)"
  [ -n "$commands" ] || commands="$(grep -Eo 'use-[a-z0-9-]+' /jffs/scripts/nat-start 2>/dev/null | sort -u)"
  for cmd in $commands; do
    key="${cmd#use-}"
    case "$key" in
      hy2-89) label="HY2-89" ;;
      hy2-194) label="HY2-194" ;;
      vless-89) label="Xray-89 / VLESS" ;;
      vless-194) label="Xray-194 / VLESS" ;;
      naive) label="NaiveProxy" ;;
      *) label="$key" ;;
    esac
    running="off"
    [ "$active" = "$key" ] && running="on"
    echo "router-control-backend|nat-start:$key|$label|nat-start|$ports|$running|nat-start||$cmd"
  done
  return 0
}

router_vpn_backend() {
  service="$1"
  label="$2"
  pattern="$3"
  configs="$4"
  pid="$(pidof "$service" 2>/dev/null || true)"
  script=""
  [ -x "/opt/etc/init.d/S24${service}" ] && script="/opt/etc/init.d/S24${service}"
  [ -z "$script" ] && [ -x "/opt/etc/init.d/S99${service}" ] && script="/opt/etc/init.d/S99${service}"
  [ -z "$script" ] && [ -x "/etc/init.d/${service}" ] && script="/etc/init.d/${service}"
  if [ -z "$script" ]; then
    for cfg in $configs; do
      [ -f "$cfg" ] && script="$cfg" && break
    done
  fi
  ports="$(netstat -lntup 2>/dev/null | grep -E "$pattern" | awk '{print $4}' | sed 's/.*://' | sort -u | tr '\n' ',' | sed 's/,$//')"
  running="off"
  [ -n "$pid" ] && running="on"
  if [ -n "$pid" ] || [ -n "$script" ] || [ -n "$ports" ]; then
    echo "router-control-backend|service:$service|$label|$service|$ports|$running|service||"
  fi
}

if router_vpn_natstart_backends; then
  :
else
router_vpn_singbox_profiles() {
  found=0
  for cfg in /opt/etc/sing-box/config.json /etc/sing-box/config.json /usr/local/etc/sing-box/config.json; do
    [ -f "$cfg" ] || continue
    ports="$(netstat -lntup 2>/dev/null | grep -E 'sing-box|:12346|:2081' | awk '{print $4}' | sed 's/.*://' | sort -u | tr '\n' ',' | sed 's/,$//')"
    running="off"
    pidof sing-box >/dev/null 2>&1 && running="on"
    active="$(sed -n 's/.*"final"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$cfg" 2>/dev/null | tail -1)"
    py=""
    command -v python3 >/dev/null 2>&1 && py=python3
    [ -z "$py" ] && command -v python >/dev/null 2>&1 && py=python
    if [ -n "$py" ]; then
      SB_CFG="$cfg" SB_PORTS="$ports" SB_RUNNING="$running" SB_ACTIVE="$active" "$py" - <<'PY'
import json
import os

cfg = os.environ.get("SB_CFG", "")
ports = os.environ.get("SB_PORTS", "")
running = os.environ.get("SB_RUNNING", "off")
active = os.environ.get("SB_ACTIVE", "")
skip = {"direct", "block", "dns", "selector", "urltest"}
try:
    with open(cfg, "r", encoding="utf-8") as f:
        data = json.load(f)
except Exception:
    data = {}

for outbound in data.get("outbounds", []) or []:
    tag = str(outbound.get("tag") or "").strip()
    kind = str(outbound.get("type") or "").strip()
    if not tag or not kind or kind in skip:
        continue
    state = "on" if running == "on" and (not active or tag == active) else "off"
    label = f"sing-box {kind}: {tag}"
    print(f"router-control-backend|sing-box-outbound:{tag}|{label}|sing-box|{ports}|{state}|sing-box-outbound|{cfg}|{tag}")
PY
      found=1
    else
      profiles="$(awk '
        /"outbounds"[[:space:]]*:/ { in_outbounds=1; next }
        in_outbounds && /"route"[[:space:]]*:/ { exit }
        !in_outbounds { next }
        /"type"[[:space:]]*:/ {
          line=$0
          sub(/^.*"type"[[:space:]]*:[[:space:]]*"/, "", line)
          sub(/".*$/, "", line)
          type=line
        }
        /"tag"[[:space:]]*:/ {
          line=$0
          sub(/^.*"tag"[[:space:]]*:[[:space:]]*"/, "", line)
          sub(/".*$/, "", line)
          tag=line
          if (tag != "" && type != "") {
            print type "|" tag
          }
          type=""
          tag=""
        }
      ' "$cfg" 2>/dev/null)"
      if [ -n "$profiles" ]; then
        found=1
        echo "$profiles" | while IFS='|' read kind tag; do
          case "$kind" in direct|block|dns|selector|urltest|mixed|redirect|tun|tproxy) continue ;; esac
          case "$tag" in direct|block|dns|dns-out|auto|selector|urltest|mixed-*|redirect-*) continue ;; esac
          state="off"
          [ "$running" = "on" ] && { [ -z "$active" ] || [ "$tag" = "$active" ]; } && state="on"
          echo "router-control-backend|sing-box-outbound:$tag|sing-box $kind: $tag|sing-box|$ports|$state|sing-box-outbound|$cfg|$tag"
        done
      fi
    fi
  done
  [ "$found" = 1 ]
}

router_vpn_singbox_profiles || router_vpn_backend "sing-box" "sing-box" "sing-box|:12346|:2081" "/opt/etc/sing-box/config.json /etc/sing-box/config.json /usr/local/etc/sing-box/config.json"
router_vpn_backend "xray" "Xray" "xray|:12345" "/opt/etc/xray/config.json /usr/local/etc/xray/config.json /etc/xray/config.json"
router_vpn_backend "hysteria" "Hysteria" "hysteria" "/etc/hysteria/config.yaml /etc/hysteria/config.json /opt/etc/hysteria/config.yaml"
router_vpn_backend "naive" "NaiveProxy" "naive" "/etc/naive/config.json /opt/etc/naive/config.json"
router_vpn_backend "wg" "WireGuard" "wireguard|:51820" "/etc/wireguard/*.conf /opt/etc/wireguard/*.conf"
router_vpn_backend "openvpn" "OpenVPN" "openvpn" "/etc/openvpn/*.conf /opt/etc/openvpn/*.conf"
router_vpn_backend "wireguard-go" "WireGuard Go" "wireguard-go|:51820" "/etc/wireguard/*.conf /opt/etc/wireguard/*.conf"
fi
echo
echo "=== nat-start status ==="
if [ -x /jffs/scripts/nat-start ]; then
  /jffs/scripts/nat-start status 2>&1 || true
else
  echo "/jffs/scripts/nat-start: missing"
fi
echo
echo "=== VPN processes ==="
for p in xray sing-box hysteria naive wg openvpn wireguard-go; do
  if pidof "$p" >/dev/null 2>&1; then
    echo "$p: on ($(pidof "$p"))"
  else
    echo "$p: off"
  fi
done
echo
echo "=== VPN interfaces ==="
ip link show 2>/dev/null | grep -E '^[0-9]+: (wg|awgvpn|amnezia|tun|tap|ppp|tailscale|zt)' || echo "no vpn-like interfaces"
echo
echo "=== Listening VPN-related ports ==="
netstat -lntup 2>/dev/null | grep -E 'xray|sing-box|hysteria|naive|openvpn|wireguard|:12345|:12346|:443|:8443|:51820' || echo "no matching listeners"
echo
echo "=== VPN policy marks / ipsets ==="
ip rule show 2>/dev/null | grep -E 'fwmark|lookup|vpn|wan' || echo "no policy rules"
ipset list -name 2>/dev/null | grep -E 'vpn|proxy|route|white|black|direct' || echo "no matching ipsets"
