# Update status: Started
echo "1"
echo "1" > /root/scripts/vpn_config_status

# Install packages
opkg update
opkg install openvpn-openssl openvpn-easy-rsa

# Configuration parameters
VPN_DIR="/etc/openvpn"
VPN_PKI="/etc/easy-rsa/pki"
VPN_PORT="1194"
VPN_PROTO="udp"
VPN_PROTO6="udp6"
VPN_POOL="192.168.9.0 255.255.255.0"
VPN_POOL6="fd00:abcd::/64"
VPN_DNS="${VPN_POOL%.* *}.1"
VPN_DN="$(uci -q get dhcp.@dnsmasq[0].domain)"
INTERFACE="eth0"

# Fetch server address
NET_FQDN="$(uci -q get ddns.@service[0].lookup_host)"
. /lib/functions/network.sh
network_flush_cache
network_find_wan NET_IF
network_get_ipaddr NET_ADDR "${NET_IF}"
if [ -n "${NET_FQDN}" ]
then VPN_SERV="${NET_FQDN}"
else VPN_SERV="${NET_ADDR}"
fi

# Work around EasyRSA issues
#wget -U "" -O /tmp/easyrsa.tar.gz \
#https://github.com/OpenVPN/easy-rsa/\
#releases/download/v3.1.7/EasyRSA-3.1.7.tgz
#tar -z -x -f /tmp/easyrsa.tar.gz

# Configuration parameters
cat << EOF > /etc/profile.d/easy-rsa.sh
export EASYRSA_PKI="${VPN_PKI}"
export EASYRSA_TEMP_DIR="/tmp"
export EASYRSA_CERT_EXPIRE="3650"
export EASYRSA_BATCH="1"
EOF
. /etc/profile.d/easy-rsa.sh
 
# Remove and re-initialize PKI directory
easyrsa init-pki
 
# Generate DH parameters
easyrsa gen-dh
 
# Create a new CA
easyrsa build-ca nopass
 
# Generate server keys and certificate
easyrsa build-server-full server nopass
openvpn --genkey tls-crypt-v2-server ${EASYRSA_PKI}/private/server.pem
 
# Generate admin keys and certificate
easyrsa build-client-full admin nopass
openvpn --tls-crypt-v2 ${EASYRSA_PKI}/private/server.pem \
--genkey tls-crypt-v2-client ${EASYRSA_PKI}/private/admin.pem

# Check if configuration was successful
if [ $? -ne 0 ]; then
    echo "0" > /root/scripts/vpn_config_status
    exit 1
fi

# Configure firewall
uci rename firewall.@zone[0]="lan"
uci rename firewall.@zone[1]="wan"
uci del_list firewall.lan.device="tun+"
uci add_list firewall.lan.device="tun+"
uci -q delete firewall.ovpn
uci set firewall.ovpn="rule"
uci set firewall.ovpn.name="Allow-OpenVPN"
uci set firewall.ovpn.src="wan"
uci set firewall.ovpn.dest_port="${VPN_PORT}"
uci set firewall.ovpn.proto="${VPN_PROTO}"
uci set firewall.ovpn.target="ACCEPT"
uci commit firewall
service firewall restart

# Get WAN IPv6
IPV6_ADDR=$(ifconfig $INTERFACE | grep "Scope:Global" | grep -o "2a0d:[0-9]*:[0-9a-f:]*" | awk '{print length, $0}' | sort -n | awk '{print $2}' | head -n 1)

# Configure VPN service and generate admin profiles
umask go=
VPN_DH="$(cat ${VPN_PKI}/dh.pem)"
VPN_CA="$(openssl x509 -in ${VPN_PKI}/ca.crt)"
ls ${VPN_PKI}/issued \
| sed -e "s/\.\w*$//" \
| while read -r VPN_ID
do
VPN_TC="$(cat ${VPN_PKI}/private/${VPN_ID}.pem)"
VPN_KEY="$(cat ${VPN_PKI}/private/${VPN_ID}.key)"
VPN_CERT="$(openssl x509 -in ${VPN_PKI}/issued/${VPN_ID}.crt)"
VPN_EKU="$(echo "${VPN_CERT}" | openssl x509 -noout -purpose)"
case ${VPN_EKU} in
(*"SSL server : Yes"*)
VPN_CONF="${VPN_DIR}/${VPN_ID}.conf"
cat << EOF > ${VPN_CONF} ;;
user nobody
group nogroup
dev tun
port ${VPN_PORT}
proto ${VPN_PROTO}
proto ${VPN_PROTO6}
local ${IPV6_ADDR}

#Logging files
log /var/log/openvpn.log
status /var/log/openvpn_status.log

# IPv4 server configuration
server ${VPN_POOL}
topology subnet

# IPv6 server configuration
server-ipv6 ${VPN_POOL6}
topology subnet

# Enable client-to-client communication
client-to-client

#Keep the connection alive
keepalive 10 60

#Persist keys and tun device
persist-tun
persist-key

# Push DNS server (IPv4)
push "dhcp-option DNS ${VPN_DNS}"
push "dhcp-option DOMAIN ${VPN_DN}"

# Push DNS server(IPv6)
push "dhcp-option DNS fd00:abcd::1"
push "dhcp-option DOMAIN ${VPN_DN}"

# Push route to redirect all IPv6 and IPv4 traffic through VPN
push "redirect-gateway def1"
push "redirect-gateway ipv6"
push "route-ipv6 ::/0"
<dh>
${VPN_DH}
</dh>
EOF
(*"SSL client : Yes"*)
VPN_CONF="${VPN_DIR}/${VPN_ID}.ovpn"
cat << EOF > ${VPN_CONF} ;;
user nobody
group nogroup
dev tun
nobind
client
verb 3
remote ${IPV6_ADDR} ${VPN_PORT} ${VPN_PROTO}
auth-nocache
remote-cert-tls server
EOF
esac
cat << EOF >> ${VPN_CONF}
<tls-crypt-v2>
${VPN_TC}
</tls-crypt-v2>
<key>
${VPN_KEY}
</key>
<cert>
${VPN_CERT}
</cert>
<ca>
${VPN_CA}
</ca>
EOF
done

# Restart OpenVPN service
service openvpn restart

# Final status update: Configured
if [ $? -eq 0 ]; then
    echo "2" > /root/scripts/vpn_config_status
else
    echo "0" > /root/scripts/vpn_config_status
fi

# List generated .ovpn files
ls ${VPN_DIR}/*.ovpn
