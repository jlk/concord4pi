package concord4pi.testsupport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;

/**
 * An IMqttClient with no network, standing in for Paho's MqttClient and the broker together.
 * Subscriptions are held "on the broker": they are cleared when the connection drops, the
 * way a clean-session broker discards them, and deliver() only reaches subscribed topics.
 * Failures that Paho would raise (not connected, already disconnected) are raised the same way.
 * Methods are synchronized because MQTTService's run thread and the test thread both call in.
 */
public class FakeMqttClient implements IMqttClient {

	public static final String SERVER_URI = "tcp://fake:1883";

	/** One publish call: the topic as given and the payload as text. */
	public static class Published {
		public final String topic;
		public final String payload;
		public final int qos;
		public final boolean retained;

		Published(String topic, MqttMessage message) {
			this.topic = topic;
			this.payload = new String(message.getPayload());
			this.qos = message.getQos();
			this.retained = message.isRetained();
		}

		@Override
		public String toString() {
			return topic + "=" + payload;
		}
	}

	private MqttCallback callback;
	private MqttConnectOptions lastConnectOptions;
	private boolean connected = false;
	private int connectAttempts = 0;
	private int connectFailures = 0;
	private int subscribeFailures = 0;
	private int publishFailures = 0;
	private int publishFailureReason = MqttException.REASON_CODE_CLIENT_EXCEPTION;
	private int publishCalls = 0;
	private long lastDisconnectQuiesceMs = -1;
	private int subscribeCalls = 0;
	private int disconnectCalls = 0;
	private final Set<String> subscriptions = new HashSet<String>();
	private final List<String> unsubscribed = new ArrayList<String>();
	private final List<Published> published = new ArrayList<Published>();

	/** The next n connect() calls fail as if the broker were down. */
	public synchronized void failNextConnects(int n) {
		connectFailures = n;
	}

	/** The next n subscribe() calls fail even though the client is connected. */
	public synchronized void failNextSubscribes(int n) {
		subscribeFailures = n;
	}

	/** The next n publish() calls fail even though the client is connected. */
	public synchronized void failNextPublishes(int n) {
		failNextPublishes(n, MqttException.REASON_CODE_CLIENT_EXCEPTION);
	}

	/**
	 * The next n publish() calls fail with this Paho reason code, still connected. With
	 * REASON_CODE_CLIENT_TIMEOUT this is a half-dead broker: Paho's wait ran out without an ack.
	 */
	public synchronized void failNextPublishes(int n, int reasonCode) {
		publishFailures = n;
		publishFailureReason = reasonCode;
	}

	/** The broker goes away: the client is disconnected and told so, and subscriptions are lost. */
	public void dropConnection() {
		MqttCallback toNotify;
		synchronized(this) {
			connected = false;
			subscriptions.clear();
			toNotify = callback;
		}
		if(toNotify != null) {
			toNotify.connectionLost(new MqttException(MqttException.REASON_CODE_CONNECTION_LOST));
		}
	}

	/** Paho's automatic reconnect succeeds: connected again, with no subscriptions. */
	public void reconnectAutomatically() {
		MqttCallback toNotify;
		synchronized(this) {
			connected = true;
			toNotify = callback;
		}
		if(toNotify instanceof MqttCallbackExtended) {
			((MqttCallbackExtended)toNotify).connectComplete(true, SERVER_URI);
		}
	}

	/** A message arrives from the broker; it reaches the callback only if the topic is subscribed. */
	public void deliver(String topic, String payload) throws Exception {
		MqttCallback toNotify;
		synchronized(this) {
			if(!connected || !subscriptions.contains(topic)) {
				return;
			}
			toNotify = callback;
		}
		toNotify.messageArrived(topic, new MqttMessage(payload.getBytes()));
	}

	public synchronized MqttCallback callback() { return callback; }
	public synchronized MqttConnectOptions lastConnectOptions() { return lastConnectOptions; }
	public synchronized int connectAttempts() { return connectAttempts; }
	public synchronized int subscribeCalls() { return subscribeCalls; }
	/** Every publish() call, including the ones that failed. */
	public synchronized int publishCalls() { return publishCalls; }
	public synchronized int disconnectCalls() { return disconnectCalls; }
	/** The quiesce timeout of the last disconnect; Paho's no-argument disconnect() uses 30s. */
	public synchronized long lastDisconnectQuiesceMs() { return lastDisconnectQuiesceMs; }
	public synchronized boolean isSubscribed(String topic) { return subscriptions.contains(topic); }
	public synchronized List<String> unsubscribed() { return new ArrayList<String>(unsubscribed); }
	public synchronized List<Published> published() { return new ArrayList<Published>(published); }

	/** The payload of the most recent publish to the topic, or null if there was none. */
	public synchronized String lastPayload(String topic) {
		for(int i = published.size() - 1; i >= 0; i--) {
			if(published.get(i).topic.equals(topic)) {
				return published.get(i).payload;
			}
		}
		return null;
	}

	@Override
	public synchronized void setCallback(MqttCallback callback) {
		this.callback = callback;
	}

	@Override
	public synchronized void connect(MqttConnectOptions options) throws MqttException {
		connectAttempts++;
		lastConnectOptions = options;
		if(connectFailures > 0) {
			connectFailures--;
			throw new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR);
		}
		connected = true;
	}

	@Override
	public void connect() throws MqttException {
		connect(new MqttConnectOptions());
	}

	@Override
	public synchronized boolean isConnected() {
		return connected;
	}

	@Override
	public synchronized void subscribe(String topicFilter) throws MqttException {
		subscribeCalls++;
		if(!connected) {
			throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
		}
		if(subscribeFailures > 0) {
			subscribeFailures--;
			throw new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION);
		}
		subscriptions.add(topicFilter);
	}

	@Override
	public synchronized void unsubscribe(String topicFilter) throws MqttException {
		if(!connected) {
			throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
		}
		subscriptions.remove(topicFilter);
		unsubscribed.add(topicFilter);
	}

	@Override
	public synchronized void publish(String topic, MqttMessage message) throws MqttException {
		publishCalls++;
		if(!connected) {
			throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
		}
		if(publishFailures > 0) {
			publishFailures--;
			throw new MqttException(publishFailureReason);
		}
		published.add(new Published(topic, message));
	}

	@Override
	public synchronized void disconnect() throws MqttException {
		disconnect(30000);
	}

	@Override
	public synchronized void disconnect(long quiesceTimeout) throws MqttException {
		disconnectCalls++;
		lastDisconnectQuiesceMs = quiesceTimeout;
		if(!connected) {
			throw new MqttException(MqttException.REASON_CODE_CLIENT_ALREADY_DISCONNECTED);
		}
		connected = false;
		subscriptions.clear();
	}

	@Override
	public String getClientId() { return "fake"; }

	@Override
	public String getServerURI() { return SERVER_URI; }

	//not used by MQTTService

	@Override
	public IMqttToken connectWithResult(MqttConnectOptions options) { throw new UnsupportedOperationException(); }
	@Override
	public void disconnectForcibly() { throw new UnsupportedOperationException(); }
	@Override
	public void disconnectForcibly(long disconnectTimeout) { throw new UnsupportedOperationException(); }
	@Override
	public void disconnectForcibly(long quiesceTimeout, long disconnectTimeout) { throw new UnsupportedOperationException(); }
	@Override
	public void subscribe(String[] topicFilters) { throw new UnsupportedOperationException(); }
	@Override
	public void subscribe(String topicFilter, int qos) { throw new UnsupportedOperationException(); }
	@Override
	public void subscribe(String[] topicFilters, int[] qos) { throw new UnsupportedOperationException(); }
	@Override
	public void subscribe(String topicFilter, IMqttMessageListener messageListener) { throw new UnsupportedOperationException(); }
	@Override
	public void subscribe(String[] topicFilters, IMqttMessageListener[] messageListeners) { throw new UnsupportedOperationException(); }
	@Override
	public void subscribe(String topicFilter, int qos, IMqttMessageListener messageListener) { throw new UnsupportedOperationException(); }
	@Override
	public void subscribe(String[] topicFilters, int[] qos, IMqttMessageListener[] messageListeners) { throw new UnsupportedOperationException(); }
	@Override
	public IMqttToken subscribeWithResponse(String topicFilter) { throw new UnsupportedOperationException(); }
	@Override
	public IMqttToken subscribeWithResponse(String topicFilter, IMqttMessageListener messageListener) { throw new UnsupportedOperationException(); }
	@Override
	public IMqttToken subscribeWithResponse(String topicFilter, int qos) { throw new UnsupportedOperationException(); }
	@Override
	public IMqttToken subscribeWithResponse(String topicFilter, int qos, IMqttMessageListener messageListener) { throw new UnsupportedOperationException(); }
	@Override
	public IMqttToken subscribeWithResponse(String[] topicFilters) { throw new UnsupportedOperationException(); }
	@Override
	public IMqttToken subscribeWithResponse(String[] topicFilters, IMqttMessageListener[] messageListeners) { throw new UnsupportedOperationException(); }
	@Override
	public IMqttToken subscribeWithResponse(String[] topicFilters, int[] qos) { throw new UnsupportedOperationException(); }
	@Override
	public IMqttToken subscribeWithResponse(String[] topicFilters, int[] qos, IMqttMessageListener[] messageListeners) { throw new UnsupportedOperationException(); }
	@Override
	public void unsubscribe(String[] topicFilters) { throw new UnsupportedOperationException(); }
	@Override
	public void publish(String topic, byte[] payload, int qos, boolean retained) { throw new UnsupportedOperationException(); }
	@Override
	public MqttTopic getTopic(String topic) { throw new UnsupportedOperationException(); }
	@Override
	public IMqttDeliveryToken[] getPendingDeliveryTokens() { throw new UnsupportedOperationException(); }
	@Override
	public void setManualAcks(boolean manualAcks) { throw new UnsupportedOperationException(); }
	@Override
	public void messageArrivedComplete(int messageId, int qos) { throw new UnsupportedOperationException(); }
	@Override
	public void close() { throw new UnsupportedOperationException(); }
}
