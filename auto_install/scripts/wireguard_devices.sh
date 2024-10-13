#!/bin/bash

log_file="/var/log/wireguard_status.log"

# Initialize JSON structure
echo "{"

# Variables to store data
client_list=""
routing_table=""
global_stats=""

# Section flags
in_peer=false

# Run the wg command and capture its output into the log file
wg show > "$log_file"

# Read each line of the log file
while IFS= read -r line
do
    case "$line" in
        interface*)
            interface=$(echo "$line" | awk '{print $2}')
            ;;
        peer*)
            # Peer section starts
            in_peer=true
            peer=$(echo "$line" | awk '{print $2}')
            ;;
        "  allowed ips:"*)
            allowed_ips=$(echo "$line" | awk '{print $3}')
            # Extract the IPv4 address (if it exists)
            ipv4_address=$(echo "$line" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}/[0-9]+')
            # Extract the IPv6 address excluding the '::/0' address
            ipv6_address=$(echo "$line" | grep -oE '([a-fA-F0-9:]+:+)+[a-fA-F0-9]*/[0-9]+' | grep -v '::/0')
            ;;
        "  endpoint:"*)
            real_address=$(echo "$line" | awk '{print $2}')
            ;;
        "  transfer:"*)
            # Capture bytes sent and received
            bytes_received=$(echo "$line" | awk '{print $2" "$3}')
            bytes_sent=$(echo "$line" | awk '{print $5" "$6}')
            ;;
        "  latest handshake:"*)
            connected_since=$(echo "$line" | awk '{print $3" "$4" "$5" "$6" "$7}')
            # Add the peer details to the client list
            client_list+="{\"Common Name\": \"$peer\", \"Real Address\": \"$real_address\", \"Bytes Received\": \"$bytes_received\", \"Bytes Sent\": \"$bytes_sent\", \"Connected Since\": \"$connected_since\"},"
            
            # Add routing table entry for this peer, including the IPv4 and IPv6 Addresses
            routing_table+="{\"Virtual Address\": \"$allowed_ips\", \"IPv4 Address\": \"${ipv4_address:-N/A}\", \"IPv6 Address\": \"${ipv6_address:-N/A}\", \"Common Name\": \"$peer\", \"Real Address\": \"$real_address\", \"Last Ref\": \"$connected_since\"},"
        ;;
    esac
done < "$log_file"

# Remove trailing commas
client_list=$(echo "$client_list" | sed 's/,$//')
routing_table=$(echo "$routing_table" | sed 's/,$//')

# Construct the final JSON output
echo "\"CLIENT LIST\": [$client_list],"
echo "\"ROUTING TABLE\": [$routing_table],"
echo "\"GLOBAL STATS\": {\"Max bcast/mcast queue length\": 1}"

# Close JSON object
echo "}"
