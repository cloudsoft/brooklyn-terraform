#!/bin/bash

echo "MySQL - starting install."

MYSQL_CFG=/etc/mysql/mysql.conf.d/
sudo test -d "$MYSQL_CFG" && MYSQL_EXISTS="0"

DB_gz="https://artifactory.cloudsoftcorp.com/artifactory/libs-release-local/io/cloudsoft/packs/mysql5/1.0.0/mysql5-1.0.0-tar.gz"

if [ "$MYSQL_EXISTS" ]; then
  echo "MySQL already installed." >> /tmp/terraform-provisioner.log
  sudo systemctl stop mysql

  cat >/tmp/zz-bind-address.cnf <<ENDOFTEXT
[mysqld]
bind-address = 0.0.0.0
ENDOFTEXT

    sudo mv /tmp/zz-bind-address.cnf /etc/mysql/mysql.conf.d/
    sudo chown root:root /etc/mysql/mysql.conf.d/zz-bind-address.cnf
    echo "MySQL - configured access." >> /tmp/terraform-provisioner.log
else
  echo "Installing MySQL." >> /tmp/terraform-provisioner.log
  while sudo fuser /var/{lib/{dpkg,apt/lists},cache/apt/archives}/{lock,lock-frontend} >/dev/null 2>&1; do
    echo 'Waiting for release of dpkg/apt locks...';
    sleep 5
  done

  which curl || sudo apt --assume-yes install curl

  ## Install the MySQL server
  if [ ! -z "${DB_gz}" ] ; then
    curl -L -k -f -o mysql.tar.gz "${DB_gz}"
  fi
  tar xf mysql.tar.gz
  PREV=`pwd`
  cd mysql
  while sudo fuser /var/{lib/{dpkg,apt/lists},cache/apt/archives}/{lock,lock-frontend} >/dev/null 2>&1; do
    echo 'Waiting for release of dpkg/apt locks...';
    sleep 5
  done
  sudo ./dbmanager.sh install
  cd $PREV

  sudo systemctl stop mysql

  cat >/tmp/zz-bind-address.cnf <<ENDOFTEXT
[mysqld]
bind-address = 0.0.0.0
ENDOFTEXT

  sudo mv /tmp/zz-bind-address.cnf /etc/mysql/mysql.conf.d/
  sudo chown root:root /etc/mysql/mysql.conf.d/zz-bind-address.cnf
  echo "MySQL installed." >> /tmp/terraform-provisioner.log
fi
