#!/bin/bash

trap '' HUP  # without this vsphere the executor kills the shel and tomcat too

sudo lsof -i:8080 && CATALINA_STARTED="0"

if [ "$CATALINA_STARTED" ]; then
  echo "Tomcat already started." >> /tmp/terraform-provisioner.log
else
  sudo /usr/local/tomcat/bin/startup.sh
  echo "Tomcat starting..." >> /tmp/terraform-provisioner.log
fi