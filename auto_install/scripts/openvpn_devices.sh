log_file="/var/log/openvpn_status.log"

# Initialize JSON structure
echo "{"

# Variables to store data
client_list=""
routing_table=""
global_stats=""

# Section flags
in_client_list=false
in_routing_table=false
in_global_stats=false

# Read each line of the log file
while IFS= read -r line
do
    case "$line" in
        "OpenVPN CLIENT LIST")
            in_client_list=true
            in_routing_table=false
            in_global_stats=false
            ;;
        "ROUTING TABLE")
            in_client_list=false
            in_routing_table=true
            in_global_stats=false
            ;;
        "GLOBAL STATS")
            in_client_list=false
            in_routing_table=false
            in_global_stats=true
            ;;
        "END")
            break
            ;;
        *)
            if $in_client_list; then
                if [[ "$line" != "Common Name,Real Address,Bytes Received,Bytes Sent,Connected Since" ]]; then
                    # Use default value 0 if Bytes Received or Bytes Sent are empty
                    client_list+="$(echo "$line" | awk -F, -v OFS=, '
                        {
                            bytes_received = ($3 == "" ? "0" : $3)
                            bytes_sent = ($4 == "" ? "0" : $4)
                            print "{\"Common Name\": \""$1"\", \"Real Address\": \""$2"\", \"Bytes Received\": "bytes_received", \"Bytes Sent\": "bytes_sent", \"Connected Since\": \""$5"\"},"
                        }')"
                fi
            elif $in_routing_table; then
                if [[ "$line" != "Virtual Address,Common Name,Real Address,Last Ref" ]]; then
                    routing_table+="$(echo "$line" | awk -F, '{print "{\"Virtual Address\": \""$1"\", \"Common Name\": \""$2"\", \"Real Address\": \""$3"\", \"Last Ref\": \""$4"\"},"}')"
                fi
            elif $in_global_stats; then
                global_stats+="$(echo "$line" | awk -F, '{print "\""$1"\": "$2","}')"
            fi
            ;;
    esac
done < "$log_file"

# Remove trailing commas
client_list=$(echo "$client_list" | sed 's/,$//')
routing_table=$(echo "$routing_table" | sed 's/,$//')
global_stats=$(echo "$global_stats" | sed 's/,$//')

# Construct JSON structure and output it directly
echo "\"CLIENT LIST\": [$client_list],"
echo "\"ROUTING TABLE\": [$routing_table],"
echo "\"GLOBAL STATS\": {$global_stats}"

# Close JSON object
echo "}"
