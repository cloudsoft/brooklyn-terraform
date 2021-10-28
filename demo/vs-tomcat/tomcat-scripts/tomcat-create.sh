#!/bin/bash

CATALINA=/usr/local/tomcat
sudo test -d "$CATALINA" && CATALINA_EXISTS="0"

TOMCAT_gz="https://archive.apache.org/dist/tomcat/tomcat-9/v9.0.52/bin/apache-tomcat-9.0.52.tar.gz"

if [ "$CATALINA_EXISTS" ]; then
  echo "Tomcat already installed." >> /tmp/terraform-provisioner.log
else
  echo "Installing Tomcat..." >> /tmp/terraform-provisioner.log
  while sudo fuser /var/{lib/{dpkg,apt/lists},cache/apt/archives}/lock >/dev/null 2>&1; do
   echo Waiting for other instances of apt to complete...
   sleep 5
  done

  which curl || sudo apt --assume-yes install curl

  # Install java if needed
  if ( which java ) ; then
    echo "Java already installed." >> /tmp/terraform-provisioner.log
  else
    echo "Trying to install java." >> /tmp/terraform-provisioner.log
    # occasionally AWS comes up without this repo
    sudo add-apt-repository -y ppa:openjdk-r/ppa || echo could not add repo, will continue trying java install anyway
    sudo apt-get update || echo could not apt-get update, will continue trying java install anyway

    sudo apt --assume-yes install openjdk-8-jdk-headless
    sudo apt --assume-yes install openjdk-8-jre-headless
  fi

  # Install Apache Tomcat
  sudo mkdir /usr/local/tomcat
  if [ ! -z "${TOMCAT_gz}" ] ; then
    curl -L -k -f -o tomcat.tar.gz "${TOMCAT_gz}"
  fi
  sudo tar xf tomcat.tar.gz -C /usr/local/tomcat --strip-components=1
  sudo chmod 750 /usr/local/tomcat/bin
  sudo sed --in-place 's#<Connector port="8080" protocol="HTTP/1.1"#\0 address="0.0.0.0"#g' /usr/local/tomcat/conf/server.xml
fi
