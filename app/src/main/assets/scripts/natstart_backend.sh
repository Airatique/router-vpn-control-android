cmd={{QUOTED_CMD}}
label={{QUOTED_LABEL}}
[ -x /jffs/scripts/nat-start ] || { echo "/jffs/scripts/nat-start is missing"; exit 1; }
/jffs/scripts/nat-start "$cmd"
status=$?
[ "$status" -eq 0 ] || exit "$status"
echo "router-control: nat-start"
echo "active-backend: $label"
