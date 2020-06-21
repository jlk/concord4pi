# concord4pi
A java-based integration for interacting with a Concord Alarm System (SuperBus 2000 Automation Module)

Version: 0.2

Code Changes

This is a complete re-write of the concord4pi interface bridge from the SuperBus2000 Automation Module to an IP Network.  Key changes from previous versions include:
 - Event-based actions for handling data from the serial port and sending messages through MQTT
 - Simplified / cleaned data structure for in-memory alarm state; state is built dynamically as messages are received from the automation module.
 - Simplified message processing for incoming serial data
 - Extensible interface to support additional outgoing message updates (e.g. can re-add the REST API easily, if needed)
 - MQTT topic structure simplified and allows improved discoverability

Requirements

This software requires an computer attached to the SB2000 via serial interface with network connectivity to an IPv4 network.  This has been tested on:
 - Raspberry Pi Zero W (should function on all other Raspberry Pi devices)
 - RS232 Serial to TTL Converter Board (hardwired to RPI Zero W GPIO serial pins)

## Building
This code has been tested to build with JDK13 and a modern version of [ant](https://ant.apache.org/), on MacOS Catalina and Ubuntu 19.10.

To build from the command line, in this directory run:

```
ant
```

## Configuration
Configuration happens in the two files under the `config` directory:

### Runtime configuration
...happens in `config/concord4pi.config`.

Config item | Description
----------- | -----------
`SerialDevice` | Should be set to the full path to the serial/USB device, such as `/dev/ttyUSB0`.
`MQTTBroadcaster`  | Specifies if concord4pi should send status to a MQTT broker (you probably want this to be `true`).
`MQTTConnectionString` | Connection string to mqtt broker, such as `tcp://mqttserver:1833`.
`MQTTClientID` | A name that will be used to identify concord4pi in the message broker - helps troubleshooting or identification.
`MQTTUsername` | Username to authenticate to message broker with.
`MQTTPassword` | Password to authenticate to message broker with.
`MQTTBaseTopic` | Top-level mqtt topic that other concord4pi topics will go under. `concord4pi` is suggested.
`MQTTEnableCommands` | If set to true, concord4pi will read commands from MQTT and send them to the alarm system.
`MQTTCommandTopic` | If `MQTTEnableCommands` is set to true, then concord4pi will look in the `MQTTBaseTopic`/`MQTTCommandTopic` for commands. e.g. if this is set to `cmd`, then the whole path could be `concord4pi/cmd`.

### Logging configuration
...happens in `config/log.config`.

Log levels are controlled by `writer.level` and can be any of the following values, from **least** to **most verbose**:

 * error
 * warn
 * info
 * debug
 * trace

## Running
To run concord4pi from the command line, a command like the following will work:

```
java -Dtinylog.configuration=config/log.config -cp lib/jSerialComm-2.4.2.jar:jars/concord4pi.jar:lib/org.eclipse.paho.client.mqttv3-1.1.1.jar:lib/tinylog-api-2.0.0-M2.1.jar:lib/tinylog-impl-2.0.0-M2.1.jar concord4pi.concord4pi
```

## Docker support
This project can also build to a Docker image:

```
docker build -t concord4pi .
```
**NOTE** that for the containerized version, logging is set to stdout instead of a log file by default. This is standard practice for containerized apps. This change is made by a sed command in the Dockerfile.

(This image is not yet being pushed to Docker Hub or GitHub packages. Soon...)

### Running Docker image
One can either modify the configuration file built into the container image, or bind-mount a local configuration file as in the example below (note the comment above about sending output to stdout in a container).

The serial device will also need to be bind-mounted into the container where the configuration file specified it (`SerialDevice`) so concord4pi can access it.

```
docker run -ti -v config/:/usr/src/concord4pi/config -v /dev/ttyUSB0:/dev/ttyUSB0 concord4pi 
```