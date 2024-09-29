#!/bin/bash

# Start the main work in the background
nohup bash /root/scripts/openvpn_configure.sh > /tmp/openvpn_output.log 2>&1 &

# Return an immediate response
echo "OK"
