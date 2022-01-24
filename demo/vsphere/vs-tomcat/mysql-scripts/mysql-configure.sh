#!/bin/bash

echo "MySQL - starting to configure."
CREATION_SCRIPT_URL="https://github.com/cloudsoft/demos/raw/master/family-chat/creation-script-mysql.sql"
# When MySQL is stopped `systemctl status mariadb` returns `Active: inactive (dead)`
# reminder: test for what you expect
sudo systemctl status mysql | grep 'dead' > /dev/null 2>&1
if [ $? != 0 ] ; then
  echo "MySQL is running when configure is called. Skipping configuration assuming it has already been done. If this is not correct then stop the DB before invoking this."
else
  echo "Configuring MySQL..." >> /tmp/terraform-provisioner.log
  # When MySQL is up `systemctl status mariadb` returns `Active: active (running)`
  sudo systemctl start mysql

  echo "Fetching and running creation script from ${CREATION_SCRIPT_URL}..." >> /tmp/terraform-provisioner.log
  curl -L -k -f -o creation-script-from-url.sql "${CREATION_SCRIPT_URL}"
  cat creation-script-from-url.sql >> /tmp/terraform-provisioner.log
  sudo mysql -u root < creation-script-from-url.sql
fi