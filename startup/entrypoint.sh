#!/bin/sh

chmod 666 /dev/ttyUSB0
#exec so java replaces the shell as PID 1 and receives SIGTERM from docker stop
exec java -Dtinylog.configuration=config/log.config -jar jars/concord4pi.jar
