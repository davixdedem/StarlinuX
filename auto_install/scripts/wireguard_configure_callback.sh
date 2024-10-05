#!/bin/bash

# Start the main work in the background
nohup bash /root/scripts/wireguard_configure.sh > /tmp/wireguard_output.log 2>&1 &

# Return an immediate response
echo "OK"
