INTERFACE="eth0"

IPV6_ADDR=$(ifconfig $INTERFACE | grep "Scope:Global" | grep -o "2a0d:[0-9]*:[0-9a-f:]*" | awk '{print length, $0}' | sort -n | awk '{print $2}' | head -n 1)

# Define the OpenVPN configuration file
OPENVPN_CONF="/etc/openvpn/server.conf"

# Replace the line containing the local directive with the new IPv6 address
sed -i "/^local /c\local $IPV6_ADDR" $OPENVPN_CONF

# Restart OpenVPN service to apply changes
service openvpn restart
