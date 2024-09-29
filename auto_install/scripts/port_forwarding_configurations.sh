for i in {0..9}; do
  dest=$(uci get firewall.@redirect[$i].dest 2>/dev/null)
  target=$(uci get firewall.@redirect[$i].target 2>/dev/null)
  name=$(uci get firewall.@redirect[$i].name 2>/dev/null)
  src=$(uci get firewall.@redirect[$i].src 2>/dev/null)
  src_dport=$(uci get firewall.@redirect[$i].src_dport 2>/dev/null)
  dest_ip=$(uci get firewall.@redirect[$i].dest_ip 2>/dev/null)
  dest_port=$(uci get firewall.@redirect[$i].dest_port 2>/dev/null)
  enabled=$(uci get firewall.@redirect[$i].enabled 2>/dev/null)

  # Set enabled to 1 if it doesn't exist
  if [ -z "$enabled" ]; then
    enabled=1
  fi

  # Only print the JSON if all key fields are non-empty
  if [[ -n "$dest" && -n "$target" && -n "$name" && -n "$src" && -n "$src_dport" && -n "$dest_ip" && -n "$dest_port" ]]; then
    echo "{\"id\":$i, \"dest\":\"$dest\", \"target\":\"$target\", \"name\":\"$name\", \"src\":\"$src\", \"src_dport\":\"$src_dport\", \"dest_ip\":\"$dest_ip\", \"dest_port\":\"$dest_port\", \"enabled\":\"$enabled\"}"
  fi
done
