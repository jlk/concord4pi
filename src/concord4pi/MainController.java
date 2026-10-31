package concord4pi;

import concord4pi.config.*;
import concord4pi.data.AlarmSystemData;
import concord4pi.logging.*;
import concord4pi.messaging.IBroadcaster;
import concord4pi.messaging.MQTTService;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.ListIterator;

import org.tinylog.Level;
import org.tinylog.provider.ProviderRegistry;

import concord4pi.SB2000.*;

public class MainController {
	
	private Config appConfig;
	private LogEngine logger;
	private SB2000Controller alarmSystem;
	private AlarmSystemData alarmData;
	private MessageProcessor messageProcessor;
	private LinkedList<IBroadcaster> broadcasters = new LinkedList<IBroadcaster>();
	
	
	private static boolean isRunning = true;

	//on SIGTERM both the shutdown hook and the main thread (once its loop ends) call shutdown()
	private boolean isShutDown = false;

	//only waited out when a pass found nothing to do in either direction
	private static final long IDLE_SLEEP_MS = 50;
	
	public MainController(Config appConfig, LogEngine logger) {
		this.appConfig = appConfig;
		this.logger = logger;
		alarmSystem = new SB2000Controller(appConfig.getSerialDeviceName(), logger);
		alarmData = new AlarmSystemData(logger, broadcasters);
		messageProcessor = new MessageProcessor(alarmData, logger);
		addBroadcasters();
		Runtime.getRuntime().addShutdownHook(new appTerminator());
	}
	
	public void start() {
		//start up the broadcasters		
		startBroadcasters();
		
		//start the new thread for the alarm system
		new Thread(alarmSystem).start();
		
		// At startup, do equipment/zone discovery so the zones and their names are published on the MQ.
		alarmSystem.sendMessages(messageProcessor.buildZoneInventoryQuery().toArray(new IOMessage[0]));

		//loop to do work
		while(isRunning) {
			//allocated only when there's actually something to send
			ArrayList<IOMessage> messagesToSend = null;
			boolean didWork = false;
			
			//process everything the Automation Module has sent, not just one message per pass:
			//the startup equipment list alone is ~60 frames
			while(alarmSystem.messageWaiting()) {
				//queue up any response messages to transmit back
				didWork = true;
				ArrayList<IOMessage> responses = messageProcessor.processMessage(alarmSystem.getNextMessage());
				if(!responses.isEmpty()) {
					if(messagesToSend == null) { messagesToSend = new ArrayList<IOMessage>(); }
					messagesToSend.addAll(responses);
				}
			}
			
			//process any commands coming in from the Broadcasters
			//this allows remote command execution, so we should be
			//careful about security here at some point
			ListIterator<IBroadcaster> broadcasterList = broadcasters.listIterator();
			
			while(broadcasterList.hasNext()) {
				IBroadcaster theBroadcaster = broadcasterList.next();
				if(theBroadcaster.messageWaiting()) {
					didWork = true;
					if(messagesToSend == null) { messagesToSend = new ArrayList<IOMessage>(); }
					messagesToSend.addAll(
							theBroadcaster.getMessageList()
					);
				}
			}			
		
			//queue up the messages for transmission
			if(messagesToSend != null) {
				alarmSystem.sendMessages(messagesToSend.toArray(new IOMessage[0]));
			}

			//a pass that did something goes straight back for more; only an idle pass waits
			if(!didWork) {
				try {
					Thread.sleep(IDLE_SLEEP_MS);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	//synchronized, not just guarded: whichever caller comes second has to wait for the first to
	//finish, or the hook could return while the main thread is still closing the serial port,
	//and the JVM would halt partway through
	public synchronized void shutdown() {
		if(isShutDown) {
			return;
		}
		isShutDown = true;
		
		shutdownBroadcasters();
		alarmSystem.shutdown();
		
		//This should be the last on the list
		try {
			ProviderRegistry.getLoggingProvider().shutdown();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	private void addBroadcasters() {
		if(appConfig.getPropertyBoolean("MQTTBroadcaster")) {
			boolean enableCommands = appConfig.getPropertyBoolean("MQTTEnableCommands");
			//MQTT is enabled, so add it
			MQTTService mqtt = new MQTTService(
					logger,
					appConfig.getProperty("MQTTConnectionString"),
					appConfig.getProperty("MQTTClientID"),
					appConfig.getProperty("MQTTUsername"),
					appConfig.getProperty("MQTTPassword"),
					appConfig.getProperty("MQTTBaseTopic"),
					appConfig.getProperty("MQTTCommandTopic"),
					enableCommands,
					appConfig.getMQTTTimeoutSeconds() * 1000L
					);
			
			//add to the broadcaster pool
			broadcasters.offer(mqtt);
		}
	}
	
	private void startBroadcasters() {
		ListIterator<IBroadcaster> broadcasterList = broadcasters.listIterator();
		
		while(broadcasterList.hasNext()) {
			new Thread((Runnable) broadcasterList.next()).start();
		}
	}
	
	private void shutdownBroadcasters() {
		ListIterator<IBroadcaster> broadcasterList = broadcasters.listIterator();
		
		while(broadcasterList.hasNext()) {
			IBroadcaster theBroadcaster = broadcasterList.next();
			theBroadcaster.shutdown();
		}
	}

	
	class appTerminator extends Thread {
		@Override
		public void run() {
			logger.log("Received termination signal from system", Level.WARN);
			isRunning = false;
			shutdown();
		}
	}
}
