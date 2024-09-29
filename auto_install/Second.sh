#!/bin/ash

# Plug Pi Starlink to StarLink Dish and connect to the Wi-Fi "Pi-Starlink

# Enabling APIs
echo "Enabling necessary APIs..."
opkg update 
opkg install luci-mod-rpc luci-lib-ipkg luci-compat 
/etc/init.d/uhttpd restart

# Install DDNS Client
echo "Installing DDNS client..."
opkg install ddns-scripts
opkg install wget ca-certificates
opkg install curl ca-bundle

# Install OpenVPN Server
echo "Installing OpenVPN server and EasyRSA..."
opkg install openvpn-openssl 
opkg install openvpn-easy-rsa

# Install Nohup
echo "Installing Nohup..."
opkg install coreutils-nohup

# Install bash
echo "Installing Bash..."
opkg install bash

# Install SCP
echo "Installing SCP (openssh-sftp-server)..."
opkg install openssh-sftp-server

# Edit country code (2 letters) in /etc/ssl/openssl.cnf
echo "Editing country code in /etc/ssl/openssl.cnf..."
sed -i 's/^countryName_default.*/countryName_default = IT/' /etc/ssl/openssl.cnf
echo "Country code set to IT in /etc/ssl/openssl.cnf"

# Install stty
echo "Installing stty..."
opkg install coreutils-stty

# Update Easy RSA to version 3.2.1
echo "Updating EasyRSA to version 3.2.1..."
cd /etc
wget https://github.com/OpenVPN/easy-rsa/releases/download/v3.2.1/EasyRSA-3.2.1.tgz -O EasyRSA-3.2.1.tgz
tar -xvzf EasyRSA-3.2.1.tgz
rm /usr/bin/easyrsa
ln -s /etc/EasyRSA-3.2.1/easyrsa /usr/bin/easyrsa
echo "EasyRSA updated to version 3.2.1"

echo "Script execution completed."

