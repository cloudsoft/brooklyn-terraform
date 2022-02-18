#!/bin/bash

# TODO figure out how terraform can be used to call this
sudo lsof -i:8080 && CATALINA_STARTED="0"

if [ "$CATALINA_STARTED" ]; then
  sudo /usr/local/tomcat/bin/shutdown.sh
  echo "Tomcat stopping..."
else
  echo "Tomcat already stopped."
fi