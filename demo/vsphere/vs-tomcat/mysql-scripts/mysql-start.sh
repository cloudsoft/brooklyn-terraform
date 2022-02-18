#!/bin/bash

sudo systemctl status mysql | grep 'running' > /dev/null 2>&1
if [ $? == 0 ]; then
  echo "MySQL is up. All is well with the world."
else
  echo "MySQL is down. Starting it..."
  sudo systemctl start mysql
fi