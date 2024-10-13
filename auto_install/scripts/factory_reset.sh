#1. Reset DDNS configuration
uci set ddns.myddns_ipv6.password="N/D"
uci set ddns.myddns_ipv6.username="N/D"
uci set ddns.myddns_ipv6.domain="N/D"
uci set ddns.myddns_ipv6.lookup_host="N/D"
uci commit ddns

#2. Stop VPN and delete its configurations, even for OpenVPN For Android
service openvpn stop
rm -rf /etc/openvpn/ && mkdir /etc/openvpn
echo 0 > /root/scripts/vpn_config_status

#3. Reset Port Forwarding rules
for rule in $(uci show firewall | grep "=redirect" | cut -d'=' -f1); do
    uci delete $rule
done
uci commit firewall
/etc/init.d/firewall restart

#4. Reset Wi-Fi SSID and Password
uci set wireless.@wifi-iface[0].ssid='StarlinuX'
uci set wireless.@wifi-iface[0].key='starlinux'
uci commit wireless

#5. Echo OK before reboot
echo "OK"

#6. Wait for a short time to ensure the response is sent
sleep 2

# 7. Start the reboot script in the background
nohup bash /root/scripts/reboot_script.sh > /tmp/reboot_output.log 2>&1 &

# 8. Return an immediate response
echo "OK"

#9. Reset Luci Credentials(Follow original instructions from OpenWRT)
#https://openwrt.org/docs/guide-user/troubleshooting/root_password_reset
