package concord4pi.messaging;

import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.tinylog.Level;

import concord4pi.SB2000.IOMessage;
import concord4pi.logging.LogEngine;

public class MQTTService implements Runnable, IBroadcaster {

	//cap on notifications held while the broker is unreachable.  The panel's startup report is a
	//few hundred, so this holds it with room to spare; past it, the oldest are dropped first
	private static final int MAX_QUEUED_NOTIFICATIONS = 10000;

	private BlockingDeque<NotificationPacket> notificationQueue = new LinkedBlockingDeque<NotificationPacket>(MAX_QUEUED_NOTIFICATIONS);
	private AtomicLong droppedNotifications = new AtomicLong();
	private Queue<IOMessage> returnMessages = new ConcurrentLinkedQueue<IOMessage>();
	private IMqttClient clientConnection;
	private LogEngine logger;
	
	private String username;
	private String password;
	private String topicBase;
	private String listenTopic;
	private boolean enableCommands = false;
	
	private volatile boolean isRunning = false;

	//true once a connect has succeeded.  Paho's automatic reconnect only starts after that, so
	//until then the run thread retries the connect itself
	private volatile boolean hasConnected = false;
	private long connectRetryMs = CONNECT_RETRY_INITIAL_MS;
	private static final long CONNECT_RETRY_INITIAL_MS = 1000;
	private static final long CONNECT_RETRY_MAX_MS = 60000;

	//the broker drops subscriptions with the connection (clean session) and Paho doesn't restore
	//them, so the callback sets this after an automatic reconnect and the run thread resubscribes
	private volatile boolean resubscribeNeeded = false;

	//wakes the run thread early from a connect retry wait or an outage wait
	private final Object wakeSignal = new Object();

	private final boolean retainMessages = false;
	
	//bounded wait on shutdown: the run thread is the one that disconnects, and it may be
	//mid-sleep when shutdown is called.  Poll for it instead of spinning on isConnected().
	private static final long SHUTDOWN_TIMEOUT_MS = 5000;
	private static final long SHUTDOWN_POLL_MS = 50;
	
	//how long run() blocks on an empty queue before re-checking isRunning
	private static final long QUEUE_WAIT_MS = 500;
	
	public MQTTService(LogEngine logger, String connectionString, String clientID, String username, String password, String topicBase, String listenTopic, boolean enableCommands) {
		this(logger, createClient(logger, connectionString, clientID), username, password, topicBase, listenTopic, enableCommands);
	}
	
	//test seam: takes the client instead of building one, so tests can pass a fake
	MQTTService(LogEngine logger, IMqttClient clientConnection, String username, String password, String topicBase, String listenTopic, boolean enableCommands) {
		this.logger = logger;
		this.clientConnection = clientConnection;
		this.username = username;
		this.password = password;
		this.topicBase = topicBase;
		this.listenTopic = listenTopic;
		this.enableCommands = enableCommands;
	
		isRunning = true;
		hasConnected = connect();
		if(hasConnected) {
			startListening();
		}
	}
	
	private static IMqttClient createClient(LogEngine logger, String connectionString, String clientID) {
		try {
			IMqttClient client = new MqttClient(
					connectionString,
					clientID,
					//in-memory: with clean session true Paho clears the store on every connect,
					//so a file store only cost a write and a delete per QoS 1 publish
					new MemoryPersistence()
			);
			logger.log("Created MQTT Service connection", Level.DEBUG);
			return client;
		} catch (MqttException e) {
			logger.log("Exception while connecting to MQTT Service!  Exiting...", Level.ERROR);
			System.exit(0);
			return null;
		}
	}
	
	public void shutdown() {
		isRunning = false;
		wake();
		if(!waitForDisconnect()) {
			logger.log("MQTT Service still connected " + SHUTDOWN_TIMEOUT_MS + "ms after shutdown was requested... continuing anyway", Level.WARN);
		}
		logger.log("Shutdown MQTT Service", Level.INFO);
	}
	
	//returns true once the client reports disconnected, false if it hasn't within SHUTDOWN_TIMEOUT_MS
	private boolean waitForDisconnect() {
		long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS;
		while(clientConnection.isConnected()) {
			if(System.currentTimeMillis() >= deadline) {
				return false;
			}
			try {
				Thread.sleep(SHUTDOWN_POLL_MS);
			} catch (InterruptedException interrupted) {
				//don't swallow the interrupt: let whoever is shutting us down see it
				Thread.currentThread().interrupt();
				return !clientConnection.isConnected();
			}
		}
		return true;
	}

	@Override
	public void sendNotification(NotificationPacket packet) {
		if(!isRunning) {
			//nothing will drain the queue any more
			return;
		}
		logger.log(() -> "Added Notification Packet to MQTT Queue [" + packet + "]", Level.DEBUG);
		//full only when the broker has been unreachable for a while.  Drop the oldest: a newer
		//update to the same topic supersedes it, and the newest state is what matters on reconnect
		while(!notificationQueue.offerLast(packet)) {
			if(notificationQueue.pollFirst() != null) {
				long dropped = droppedNotifications.incrementAndGet();
				if(dropped == 1 || dropped % 1000 == 0) {
					logger.log("MQTT notification queue is full (" + MAX_QUEUED_NOTIFICATIONS + ")... dropped the oldest " + dropped + " so far", Level.WARN);
				}
			}
		}
	}

	@Override
	public void run() {
		while(isRunning) {
			try {
				if(!hasConnected) {
					retryConnect();
				}
				else if(!clientConnection.isConnected()) {
					//Paho is reconnecting: hold the queue rather than publish into a dead connection
					pause(QUEUE_WAIT_MS);
				}
				else {
					if(resubscribeNeeded) {
						resubscribeNeeded = false;
						startListening();
					}
					publishNext();
				}
			} catch (InterruptedException interrupted) {
				//An interrupt means stop, not spin.  Re-arming the flag and continuing would make
				//every later poll() throw immediately, turning this loop into a busy wait, so end
				//the loop and let the normal unsubscribe/disconnect below run.
				//Say so: this ends MQTT publishing, so it shouldn't happen quietly.
				logger.log("MQTT run loop interrupted... stopping", Level.WARN);
				Thread.currentThread().interrupt();
				isRunning = false;
			}
		}
		
		if(hasConnected) {
			if(clientConnection.isConnected()) {
				stopListening();
			}
			disconnect();
		}
	}
	
	private void publishNext() throws InterruptedException {
		//block until there's something to send, instead of waking 20 times a second to find an empty queue
		NotificationPacket packet = notificationQueue.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
		if(packet != null) {
			logger.log(() -> "Sending Notification packet via MQTT Service [" + packet + "]", Level.DEBUG);
			if(!sendMessage(packet) && !clientConnection.isConnected()) {
				//the connection dropped under this publish: put it back to go out after the reconnect
				notificationQueue.offerFirst(packet);
			}
		}
	}
	
	private void retryConnect() throws InterruptedException {
		pause(connectRetryMs);
		if(!isRunning) {
			return;
		}
		//back off before trying, so a failure's log line names the wait that will follow it
		connectRetryMs = Math.min(connectRetryMs * 2, CONNECT_RETRY_MAX_MS);
		if(connect()) {
			hasConnected = true;
			connectRetryMs = CONNECT_RETRY_INITIAL_MS;
			startListening();
		}
	}
	
	//waits up to ms, returning early on shutdown or a reconnect
	private void pause(long ms) throws InterruptedException {
		synchronized(wakeSignal) {
			if(isRunning) {
				wakeSignal.wait(ms);
			}
		}
	}
	
	private void wake() {
		synchronized(wakeSignal) {
			wakeSignal.notifyAll();
		}
	}
	
	private boolean connect() {
		logger.log("Connecting to MQTT Broker [" + clientConnection.getServerURI() + "]...", Level.INFO);
		try {
			
			MqttConnectOptions connectionOptions = new MqttConnectOptions();
			connectionOptions.setAutomaticReconnect(true);
			
			if((username != null) && (!username.isEmpty())) {
				connectionOptions.setUserName(username);
			}
			
			if((password != null) && (!password.isEmpty())) {
				connectionOptions.setPassword(password.toCharArray());
			}

			clientConnection.setCallback(new MQTTMessageHandler(logger));
			clientConnection.connect(connectionOptions);
			logger.log("Connection successful to MQTT Service!", Level.INFO);
			
		} catch (MqttException e) {
			logger.log("Could not connect to MQTT Broker (" + e.getMessage() + ")... retrying in " + (connectRetryMs / 1000) + "s", Level.WARN);
			return false;
		}
		return true;
	}
	
	private boolean disconnect() {
		try {
			clientConnection.disconnect();
		} catch (MqttException e) {
			logger.log("Exception while trying to disconnect from MQTT Service!", Level.WARN);
			return false;
		}
		return true;
	}
	
	
	private boolean startListening() {
		try {
			clientConnection.subscribe(topicBase + "/" + listenTopic);
			logger.log(() -> "Listening for input on MQTT topic [" + topicBase + "/" + listenTopic + "]", Level.TRACE);
		} catch (MqttException e) {
			logger.log("Could not listen for input on MQTT topic [" + topicBase + "/" + listenTopic + "]: " + e.getMessage(), Level.WARN);
			return false;
		}
		return true;
	}
	
	private boolean stopListening() {
		try {
			clientConnection.unsubscribe(topicBase + "/" + listenTopic);
			logger.log(() -> "No longer listening for input on MQTT topic [" + topicBase + "/" + listenTopic + "]", Level.TRACE);
		} catch (MqttException e) {
			logger.log("Could not stop listening for input on MQTT topic [" + topicBase + "/" + listenTopic + "]: " + e.getMessage(), Level.WARN);
			return false;
		}
		return true;
	}
	
	//true when the packet needs nothing more: published, or skipped on purpose
	public boolean sendMessage(NotificationPacket packet) {
		String messageTopic = packet.key;
		String message = packet.value;
		
		if((packet.action == NotificationPacket.NPACTION_NEW) && (message.isEmpty())) {
			logger.log("New object detected with no value... skipping MQTT messaging for this update.", Level.TRACE);
			//in the future we should really delete these topics or put in "null" as the message or something
		}
		else {
		
			//setup a new MQTT message
			MqttMessage newMessage = new MqttMessage();
			newMessage.setPayload(message.getBytes());
			newMessage.setRetained(retainMessages);
			
			try {
				clientConnection.publish(topicBase + messageTopic, newMessage);
				logger.log(() -> "Sent MQTT Message [" + message + "] in topic [" + messageTopic + "]", Level.TRACE);
			} catch (MqttException e) {
				//the topic but not the value: command receipts echo the raw command back
				logger.log("Could not publish to MQTT topic [" + topicBase + messageTopic + "]: " + e.getMessage(), Level.WARN);
				return false;
			}
		}
		return true;
	}
	
	//for tests: how many notifications are waiting to be published
	int queuedNotificationCount() {
		return notificationQueue.size();
	}
	
	@Override
	public boolean messageWaiting() {
		return !returnMessages.isEmpty();
	}
	
	@Override
	public ArrayList<IOMessage> getMessageList() {
		ArrayList<IOMessage> responses = new ArrayList<IOMessage>();
		while(messageWaiting()) {
			//move the message to the Array list
			//helps with the threading to make sure there are fewer race conditions
			responses.add(returnMessages.poll());
		}
		return responses;
	}
	
	private class MQTTMessageHandler implements MqttCallbackExtended {
		
		private LogEngine logger;
		
		public MQTTMessageHandler(LogEngine logger) {
			this.logger = logger;
			logger.log("Created MQTTMessageHandler Object", Level.TRACE);
		}
		
		@Override
		public void connectionLost(Throwable connectionLost) {
			logger.log("MQTTService Connection Lost! Attempting to Reconnect!", Level.WARN);
		}
		
		//runs on a Paho thread, so leave the subscribe (which blocks on the broker) to the run thread
		@Override
		public void connectComplete(boolean reconnect, String serverURI) {
			if(reconnect) {
				logger.log("Reconnected to MQTT Broker [" + serverURI + "]", Level.INFO);
				resubscribeNeeded = true;
				wake();
			}
		}

		@Override
		public void deliveryComplete(IMqttDeliveryToken deliveryToken) {
			logger.log("MQTT Message Delivery is completed", Level.TRACE);
		}

		@Override
		public void messageArrived(String topic, MqttMessage message) throws Exception {
			if(enableCommands) { 
				handleMessage(topic, message.toString()); 
			}
			
		}
		
		private void handleMessage( String topic, String message) {
			final String monitorTopic = topicBase + "/" + listenTopic;
			final String responseTopic = "/" + listenTopic + "/receipt";

			if(topic.contentEquals(monitorTopic)) {
				//only handle if it is specifically for the topic that I am listening for
				//if we need to handle sub-topics later, we can add an "else if" to handle that subtopic specifically
				//the message was just broadcast in the base topic... handle as a command.
				logger.log("Received MQTT Message on listen topic... treating as raw command", Level.DEBUG);
				IOMessage newMessage = new IOMessage(message.getBytes());
				if(newMessage.isValid()) {
					returnMessages.offer(newMessage);
					logger.log(() -> "Received valid MQTT message [" + message + "]", Level.DEBUG);
					sendMessage(new NotificationPacket(responseTopic + "/text", "Received valid command [" + newMessage + "]", NotificationPacket.NPACTION_NEW));
					sendMessage(new NotificationPacket(responseTopic + "/code", "success", NotificationPacket.NPACTION_NEW));
					sendMessage(new NotificationPacket(responseTopic + "/message", newMessage.toString() , NotificationPacket.NPACTION_NEW));
				}
				else {
					logger.log(() -> "Received the MQTT message [" + message + "], but not a valid Alarm System Command.  Ignoring...", Level.DEBUG);
					sendMessage(new NotificationPacket(responseTopic + "/text", "Invalid valid command [" + newMessage + "]", NotificationPacket.NPACTION_NEW));
					sendMessage(new NotificationPacket(responseTopic + "/code", "fail", NotificationPacket.NPACTION_NEW));
					sendMessage(new NotificationPacket(responseTopic + "/message", newMessage.toString() , NotificationPacket.NPACTION_NEW));
				}
			}
			else {
				//this is an unhandled topic or empty topic or something like that.
				//this should never really happen
				//We want to know when this is happening, but nothing is supported to handle this
				logger.log(() -> "Received message to invalid topic [" + topic + ":" + message + "].  Ignoring...", Level.DEBUG);
				sendMessage(new NotificationPacket(responseTopic + "/text", "Received message to invalid topic [" + topic + ":" + message + "]", NotificationPacket.NPACTION_NEW));
				sendMessage(new NotificationPacket(responseTopic + "/code", "fail", NotificationPacket.NPACTION_NEW));
				sendMessage(new NotificationPacket(responseTopic + "/message", message , NotificationPacket.NPACTION_NEW));

			}
			
		
			
		}
		
	}
}
