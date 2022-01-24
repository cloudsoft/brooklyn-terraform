#!/bin/bash

CFG_LOG=/usr/local/tomcat/logs/configure.log
sudo test -f "$CFG_LOG" && CFG_EXISTS="0"

if [ "$CFG_EXISTS" ]; then
  echo "Skipping configuration assuming it has already been done. If this is not correct, review your blueprint scripts." >> /tmp/terraform-provisioner.log
else
  curl -L -k -f -o /tmp/ROOT.war https://github.com/cloudsoft/demos/raw/master/terraform/brooklyn-example-hello-world-sql-webapp.war
  sudo rm -rf  /usr/local/tomcat/webapps/ROOT
  sudo cp /tmp/ROOT.war /usr/local/tomcat/webapps/ROOT.war

  sudo sed -i '$ d' /usr/local/tomcat/conf/tomcat-users.xml
  sudo su -c "echo '<user username=\"test\" password=\"test\" roles=\"admin-script,manager-jmx\"/>' >> /usr/local/tomcat/conf/tomcat-users.xml"
  sudo su -c "echo '</tomcat-users>' >> /usr/local/tomcat/conf/tomcat-users.xml"

  sudo mkdir /usr/local/tomcat/webapps/health
  sudo su -c "echo 'true' > /usr/local/tomcat/webapps/health/index.html"
  echo "brooklyn.example.db.url=jdbc:mysql://${1}:3306/visitors?user=brooklyn&password=br00k11n" | sudo tee -a /usr/local/tomcat/conf/catalina.properties
  echo "brooklyn.example.db.driver=com.mysql.jdbc.Driver" | sudo tee -a /usr/local/tomcat/conf/catalina.properties
  echo "Tomcat configured." | sudo tee /usr/local/tomcat/logs/configure.log
  echo "War copied. Configuration done." >> /tmp/terraform-provisioner.log
fi
