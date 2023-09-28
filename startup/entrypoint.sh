#!/bin/sh

chmod 666 /dev/ttyUSB0
java -Dtinylog.configuration=config/log.config -cp lib/jSerialComm-2.9.3.jar:jars/concord4pi.jar:lib/org.eclipse.paho.client.mqttv3-1.1.1.jar:lib/tinylog-api-2.6.0.jar:lib/tinylog-impl-2.6.0.jar concord4pi.concord4pi
