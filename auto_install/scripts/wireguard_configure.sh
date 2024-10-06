# Update VPN status
echo "1"
echo "1" > /root/scripts/wireguard_config_status

# Install packages
opkg update
opkg install wireguard-tools
opkg instal luci-proto-wireguard
 
# Configuration parameters
VPN_IF="vpn"
VPN_PORT="51820"
VPN_ADDR="192.168.8.1/24"
VPN_ADDR6="fd00:9::1/64"

# Create wireguard directory
mkdir -p /etc/config/wireguard
cd /etc/config/wireguard

# Generate keys
umask go=
wg genkey | tee wgserver.key | wg pubkey > wgserver.pub
wg genkey | tee wgclient.key | wg pubkey > wgclient.pub
wg genpsk > wgclient.psk
 
# Server private key
VPN_KEY="$(cat wgserver.key)"
 
# Pre-shared key
VPN_PSK="$(cat wgclient.psk)"
 
# Client public key
VPN_PUB="$(cat wgclient.pub)"

INTERFACE="eth0"
SERVER_IP=$(ifconfig $INTERFACE | grep "Scope:Global" | grep -o "2a0d:[0-9]*:[0-9a-f:]*" | awk '{print length, $0}' | sort -n | awk '{print $2}' | head -n 1)
SERVER_PORT=51820
if [[ "$SERVER_IP" == *:* && "$SERVER_IP" != \[*\] ]]; then
    # Enclose IPv6 in square brackets
    SERVER_IP="[$SERVER_IP]"
fi

# Configure firewall
#uci rename firewall.@zone[0]="lan"
#uci rename firewall.@zone[1]="wan"
#uci del_list firewall.lan.network="${VPN_IF}"
uci add_list firewall.lan.network="${VPN_IF}"
uci -q delete firewall.wg
uci set firewall.wg="rule"
uci set firewall.wg.name="Allow-WireGuard"
uci set firewall.wg.src="wan"
uci set firewall.wg.dest_port="${VPN_PORT}"
uci set firewall.wg.proto="udp"
uci set firewall.wg.target="ACCEPT"
uci commit firewall
service firewall restart

# Configure network
#uci -q delete network.${VPN_IF}
uci set network.${VPN_IF}="interface"
uci set network.${VPN_IF}.proto="wireguard"
uci set network.${VPN_IF}.private_key="${VPN_KEY}"
uci set network.${VPN_IF}.listen_port="${VPN_PORT}"
uci add_list network.${VPN_IF}.addresses="${VPN_ADDR}"
uci add_list network.${VPN_IF}.addresses="${VPN_ADDR6}"
 
# Add VPN peers
#uci -q delete network.wgclient
uci set network.wgclient="wireguard_${VPN_IF}"
uci set network.wgclient.public_key="${VPN_PUB}"
uci set network.wgclient.preshared_key="${VPN_PSK}"
uci add_list network.wgclient.allowed_ips="${VPN_ADDR%.*}.2/32"
uci add_list network.wgclient.allowed_ips="${VPN_ADDR6%:*}:2/128"
uci commit network
service network restart

# Create tunnel.conf
WG_DIR="/etc/config/wireguard"
CLIENT_KEY="$WG_DIR/wgclient.key"
CLIENT_PUB="$WG_DIR/wgclient.pub"
CLIENT_PSK="$WG_DIR/wgclient.psk"
SERVER_PUB="$WG_DIR/wgserver.pub"
CONFIG_FILE="$WG_DIR/admin.conf"

# Check if required files exist
if [[ ! -f "$CLIENT_KEY" || ! -f "$CLIENT_PUB" || ! -f "$CLIENT_PSK" || ! -f "$SERVER_PUB" ]]; then
    echo "Error: One or more required files (wgclient.key, wgclient.pub, wgclient.psk, wgserver.pub) are missing."
    exit 1
fi

# Define client configuration
cat << EOF > "$CONFIG_FILE"
[Interface]
PrivateKey = $(cat $CLIENT_KEY)
Address = 192.168.8.2/24,fd00:9::2/128
DNS = 8.8.8.8

[Peer]
PublicKey = $(cat $SERVER_PUB)
PresharedKey = $(cat $CLIENT_PSK)
Endpoint = $SERVER_IP:$SERVER_PORT
AllowedIPs = 0.0.0.0/0    
PersistentKeepalive = 25      
EOF

# Final status update: Configured
if [ $? -eq 0 ]; then
    echo "2" > /root/scripts/wireguard_config_status
else
    echo "0" > /root/scripts/wireguard_config_status
fi

# Output success message
echo "Client configuration successfully created at $CONFIG_FILE"
