#!/bin/bash

# Define the local directory where your scripts are located
LOCAL_SCRIPTS_DIR="scripts"

# Define the remote target directory
REMOTE_TARGET_DIR="root@192.168.1.1:/root/scripts"

# Check if the local scripts directory exists
if [ ! -d "$LOCAL_SCRIPTS_DIR" ]; then
  echo "Local directory $LOCAL_SCRIPTS_DIR does not exist!"
  exit 1
fi

# Ensure the remote target directory exists by creating it if it doesn't
echo "Creating remote directory $REMOTE_TARGET_DIR if it doesn't exist..."
ssh root@192.168.1.1 "mkdir -p /root/scripts"

# Check if the remote directory creation was successful
if [ $? -ne 0 ]; then
  echo "Failed to create directory on the remote host."
  exit 1
fi

# Copy all scripts from the local scripts directory to the remote target directory
echo "Copying scripts from $LOCAL_SCRIPTS_DIR to $REMOTE_TARGET_DIR..."
scp -r "$LOCAL_SCRIPTS_DIR"/* root@192.168.1.1:/root/scripts/

# Check if the copy command was successful
if [ $? -eq 0 ]; then
  echo "Scripts copied successfully to $REMOTE_TARGET_DIR!"
else
  echo "Failed to copy scripts!"
  exit 1
fi

