#!/bin/bash

name='default'
version=''
if [ $# -ne 0 ] ; then
  if [ "X_${1}_X" = "X_local_X" ] ; then
    name="${1}"
  elif [ "X_${1}_X" = "X_current_X" ] ; then
    name="${1}"
  else
    name="v${1}"
    version="=${1}"
  fi
fi
echo "-------------------------------------------------------------------------------------------------------------------"
echo "Installing Repose ${name} package"
echo "-------------------------------------------------------------------------------------------------------------------"

if [ "X_${name}_X" = "X_local_X" ] ; then
  dpkg -i /vagrant/repose-valve*_all.deb /vagrant/repose-filter-bundle*_all.deb /vagrant/repose-extensions-filter-bundle*_all.deb /vagrant/repose-experimental-filter-bundle*_all.deb
  apt-get install -y -f
else
  apt-get install -y repose-valve${version} repose-filter-bundle${version} repose-extensions-filter-bundle${version} repose-experimental-filter-bundle${version}
fi
mkdir -p /etc/systemd/system/repose-valve.service.d && echo "[Service]\nEnvironment=\"JAVA_OPTS=-Xdebug -Xrunjdwp:transport=dt_socket,address=10037,server=y,suspend=n\"" > /etc/systemd/system/repose-valve.service.d/local.conf
sed -i '/JAVA_OPTS/c\JAVA_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,address=10037,server=y,suspend=n"' /etc/sysconfig/repose