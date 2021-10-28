#!/bin/bash

sudo systemctl status mysql | grep 'running' > /dev/null 2>&1
if [ $? == 0 ]; then
  echo "MySQL is up. Shutting it down..."
  sudo systemctl stop mysql
else
  echo "MySQL is already down."
fi