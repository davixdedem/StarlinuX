#!/bin/ash

# Function to check SBC model and set WiFi band
check_sbc_model() {
  local model=$(cat /proc/device-tree/model)
  echo "Detected SBC: $model"
  band="2g"  # Defaulting to 2g 
}

# Function to modify wireless configuration
modify_wireless_config() {
  local wireless_config="/etc/config/wireless"

  echo "Modifying wireless configuration..."

  # Enable wireless
  sed -i "s/option disabled '1'/option disabled '0'/" $wireless_config
  
  # Set SSID and security
  sed -i "s/option ssid '.*'/option ssid 'StarlinuX'/" $wireless_config
  sed -i "s/option encryption '.*'/option encryption 'psk2'/" $wireless_config
  # Set band (2g or 5g)
  sed -i "s/option band '.*'/option band '$band'/" $wireless_config

  # Check if 'option key' exists in the configuration file
  if grep -q "option key" "$wireless_config"; then
      # If it exists, replace the existing key with 'StarlinuX'
      sed -i "s/option key '.*'/option key 'starlinux'/" "$wireless_config"
  else
      # If it doesn't exist, add the 'option key' line under the SSID configuration
      sed -i "/option ssid 'StarlinuX'/a \    option key 'starlinux'" "$wireless_config"
  fi
  
  echo "Wireless configuration updated successfully!"
}

# Function to modify DHCP configuration
modify_dhcp_config() {
  local dhcp_config="/etc/config/dhcp"

  echo "Configuring DHCP for 'wan6'..."

  # Check if 'wan6' configuration already exists
  if ! grep -q "config dhcp 'wan6'" $dhcp_config; then
    # Add the 'wan6' DHCP configuration if it doesn't exist
    echo "Adding DHCP configuration for 'wan6'..."
    cat <<EOL >> $dhcp_config

config dhcp 'wan6'
    option interface 'wan6'
    option ignore '1'
EOL
    echo "DHCP configuration for 'wan6' added successfully!"
  else
    echo "'wan6' DHCP configuration already exists. Skipping addition."
  fi
}

# Function to modify network configuration
modify_network_config() {
  local network_config="/etc/config/network"

  echo "Configuring network..."

  # Append the required network configuration
  cat <<EOL >> $network_config

config device
	option name 'br-lan'
	option type 'bridge'
	list ports 'eth0'
	list ports 'wan'

config interface 'lan'
	option proto 'static'
	option ipaddr '192.168.1.1'
	option netmask '255.255.255.0'
	option device 'br-lan'
	option ip6assign '60'

config interface 'wan'
	option device 'eth0'
	option proto 'dhcp'
	option gateway '100.64.0.1'

config interface 'wan6'
	option device 'eth0'
	option proto 'dhcpv6'
	option ip6assign '64'
	option gateway 'fe80::200:5eff:fe00:101'
	option reqaddress 'try'
	option reqprefix 'auto'

config device
	option name 'br-lan'
	option type 'bridge'
	list ports 'wlan0'

config interface 'LANvpn'
	option proto 'static'
	option device 'tun0'
	option ip6assign '64'
	list ip6class 'wan6'
EOL

  echo "Network configuration updated successfully!"
}

# Function to hardcode the firewall configuration
configure_firewall() {
  local firewall_config="/etc/config/firewall"

  echo "Hardcoding firewall configuration..."

  # Write the complete firewall configuration
  cat <<EOL > $firewall_config
config defaults
	option input 'REJECT'
	option output 'ACCEPT'
	option forward 'REJECT'
	option synflood_protect '1'

config zone 'lan'
	option name 'lan'
	option input 'ACCEPT'
	option output 'ACCEPT'
	option forward 'ACCEPT'
	list device 'tun+'
	list network 'lan'

config zone 'wan'
	option name 'wan'
	option input 'ACCEPT'
	option output 'ACCEPT'
	option forward 'REJECT'
	option mtu_fix '1'
	option masq '1'
	list network 'wan'
	list network 'wan6'
	list network 'LANvpn'

config forwarding
	option src 'lan'
	option dest 'wan'

config rule
	option name 'Allow-DHCP-Renew'
	option src 'wan'
	option proto 'udp'
	option dest_port '68'
	option target 'ACCEPT'
	option family 'ipv4'

config rule
	option name 'Allow-Ping'
	option src 'wan'
	option proto 'icmp'
	option icmp_type 'echo-request'
	option family 'ipv4'
	option target 'ACCEPT'

config rule
	option name 'Allow-IGMP'
	option src 'wan'
	option proto 'igmp'
	option family 'ipv4'
	option target 'ACCEPT'

config rule
	option name 'Allow-DHCPv6'
	option src 'wan'
	option proto 'udp'
	option dest_port '546'
	option family 'ipv6'
	option target 'ACCEPT'

config rule
	option name 'Allow-MLD'
	option src 'wan'
	option proto 'icmp'
	option src_ip 'fe80::/10'
	list icmp_type '130/0'
	list icmp_type '131/0'
	list icmp_type '132/0'
	list icmp_type '143/0'
	option family 'ipv6'
	option target 'ACCEPT'

config rule
	option name 'Allow-ICMPv6-Input'
	option src 'wan'
	option proto 'icmp'
	list icmp_type 'echo-request'
	list icmp_type 'echo-reply'
	list icmp_type 'destination-unreachable'
	list icmp_type 'packet-too-big'
	list icmp_type 'time-exceeded'
	list icmp_type 'bad-header'
	list icmp_type 'unknown-header-type'
	list icmp_type 'router-solicitation'
	list icmp_type 'neighbour-solicitation'
	list icmp_type 'router-advertisement'
	list icmp_type 'neighbour-advertisement'
	option limit '1000/sec'
	option family 'ipv6'
	option target 'ACCEPT'

config rule
	option name 'Allow-ICMPv6-Forward'
	option src 'wan'
	option dest '*'
	option proto 'icmp'
	list icmp_type 'echo-request'
	list icmp_type 'echo-reply'
	list icmp_type 'destination-unreachable'
	list icmp_type 'packet-too-big'
	list icmp_type 'time-exceeded'
	list icmp_type 'bad-header'
	list icmp_type 'unknown-header-type'
	option limit '1000/sec'
	option family 'ipv6'
	option target 'ACCEPT'

config rule
	option name 'Allow-IPSec-ESP'
	option src 'wan'
	option dest 'lan'
	option proto 'esp'
	option target 'ACCEPT'

config rule
	option name 'Allow-ISAKMP'
	option src 'wan'
	option dest 'lan'
	option dest_port '500'
	option proto 'udp'
	option target 'ACCEPT'

config rule
	option name 'Restrict LuCI to LAN'
	list proto 'tcp'
	option src 'wan'
	option dest_port '80-443'
	option target 'REJECT'

config redirect
	option dest 'lan'
	option target 'DNAT'
	option name 'SSH'
	option src 'wan'
	option src_dport '2222'
	option dest_ip ''
	option dest_port '22'
	option family 'ipv6'
	option enabled '0'

config redirect
	option dest 'lan'
	option target 'DNAT'
	option name 'HTTP'
	option src 'wan'
	option src_dport '8080'
	option dest_ip ''
	option dest_port '80'
	option family 'ipv6'
	option enabled '0'

config rule 'ovpn'
	option name 'Allow-OpenVPN'
	option src 'wan'
	option dest_port '1194'
	option proto 'udp'
	option target 'ACCEPT'
EOL

  echo "Firewall configuration applied successfully!"
}

# Main script execution
echo "Starting SBC wireless, DHCP, network, and firewall configuration..."

# Check the SBC model and set WiFi band
check_sbc_model

# Modify wireless, DHCP, and network configurations
modify_wireless_config
modify_dhcp_config
modify_network_config

# Hardcode firewall configuration
configure_firewall

echo "Configuration completed! Connect StarlinuX to your Starlink Dish."
