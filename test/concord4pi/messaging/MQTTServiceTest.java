package concord4pi.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tinylog.Level;

import concord4pi.SB2000.IOMessage;
import concord4pi.testsupport.FakeMqttClient;
import concord4pi.testsupport.RecordingLogEngine;

/**
 * MQTTService against FakeMqttClient, through the package-private constructor. Most tests call
 * sendMessage() and the Paho callback directly; the ones about the run loop (reconnects,
 * outages, shutdown) start it on a thread and wait for the fake to see the result.
 */
class MQTTServiceTest {

	private static final String BASE = "concord";
	private static final String COMMAND_TOPIC = BASE + "/cmd";
	private static final String RECEIPT = BASE + "/cmd/receipt";
	private static final long WAIT_MS = 5000;
	//short, so tests that hit the hold-off after a timed-out publish don't take long
	private static final long TIMEOUT_MS = 300;

	private FakeMqttClient client;
	private RecordingLogEngine logger;
	private MQTTService service;
	private Thread runThread;

	@BeforeEach
	void setUp() {
		client = new FakeMqttClient();
		logger = new RecordingLogEngine();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		if(runThread != null) {
			service.shutdown();
			runThread.join(WAIT_MS);
			assertFalse(runThread.isAlive(), "run thread didn't stop after shutdown");
		}
	}

	private MQTTService create(String username, String password, boolean enableCommands, long timeoutMs) {
		service = new MQTTService(logger, client, username, password, BASE, "cmd", enableCommands, timeoutMs);
		return service;
	}

	private MQTTService create(String username, String password, boolean enableCommands) {
		return create(username, password, enableCommands, TIMEOUT_MS);
	}

	private MQTTService create() {
		return create("", "", true);
	}

	private void startRunLoop() {
		runThread = new Thread(service, "mqtt-test");
		runThread.setDaemon(true);
		runThread.start();
	}

	private static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while(!condition.getAsBoolean()) {
			if(System.currentTimeMillis() >= deadline) {
				fail("timed out waiting for " + what);
			}
			Thread.sleep(10);
		}
	}

	private static NotificationPacket update(String key, String value) {
		return new NotificationPacket(key, value, NotificationPacket.NPACTION_UPDATE);
	}

	//connecting and subscribing

	@Test
	void constructorConnectsWithAutomaticReconnectAndSubscribesToTheCommandTopic() {
		create();

		assertTrue(client.isConnected());
		assertTrue(client.lastConnectOptions().isAutomaticReconnect());
		assertTrue(client.isSubscribed(COMMAND_TOPIC));
	}

	@Test
	void eachConnectAttemptIsLoggedWithTheBroker() throws Exception {
		client.failNextConnects(1);
		create();
		startRunLoop();

		await("the retried connect", client::isConnected);
		long attempts = logger.entries().stream()
				.filter(entry -> entry.level == Level.INFO && entry.message.contains("Connecting to MQTT Broker [" + FakeMqttClient.SERVER_URI + "]"))
				.count();
		assertEquals(2, attempts, logger.entries().toString());
	}

	@Test
	void credentialsArePassedOnlyWhenSet() {
		create("alarm", "secret", true);
		assertEquals("alarm", client.lastConnectOptions().getUserName());
		assertArrayEquals("secret".toCharArray(), client.lastConnectOptions().getPassword());

		client = new FakeMqttClient();
		create("", null, true);
		assertNull(client.lastConnectOptions().getUserName());
		assertNull(client.lastConnectOptions().getPassword());
	}

	//publishing

	@Test
	void updateIsPublishedUnderTheBaseTopicAtQos1NotRetained() {
		create().sendMessage(update("/partitionarea/0001/zones/0005/status", "1"));

		List<FakeMqttClient.Published> published = client.published();
		assertEquals(1, published.size());
		assertEquals(BASE + "/partitionarea/0001/zones/0005/status", published.get(0).topic);
		assertEquals("1", published.get(0).payload);
		assertEquals(1, published.get(0).qos);
		assertFalse(published.get(0).retained);
	}

	@Test
	void newObjectWithNoValueIsNotPublished() {
		create().sendMessage(new NotificationPacket("/partitionarea/0001", "", NotificationPacket.NPACTION_NEW));

		assertTrue(client.published().isEmpty());
	}

	@Test
	void newObjectWithAValueIsPublished() {
		create().sendMessage(new NotificationPacket("/panel/type", "14", NotificationPacket.NPACTION_NEW));

		assertEquals("14", client.lastPayload(BASE + "/panel/type"));
	}

	//commands

	@Test
	void validCommandIsQueuedForThePanelAndAcknowledged() throws Exception {
		create();
		client.deliver(COMMAND_TOPIC, "020204");

		assertTrue(service.messageWaiting());
		List<IOMessage> commands = service.getMessageList();
		assertEquals(1, commands.size());
		assertEquals("020204", commands.get(0).toString());
		assertFalse(service.messageWaiting());
		assertEquals("success", client.lastPayload(RECEIPT + "/code"));
		assertEquals("020204", client.lastPayload(RECEIPT + "/message"));
	}

	@Test
	void invalidCommandIsRejected() throws Exception {
		create();
		client.deliver(COMMAND_TOPIC, "0202");

		assertFalse(service.messageWaiting());
		assertEquals("fail", client.lastPayload(RECEIPT + "/code"));
	}

	@Test
	void commandsAreIgnoredWhenDisabled() throws Exception {
		create("", "", false);
		client.deliver(COMMAND_TOPIC, "020204");

		assertFalse(service.messageWaiting());
		assertTrue(client.published().isEmpty());
	}

	//run loop

	@Test
	void runLoopPublishesQueuedNotifications() throws Exception {
		create();
		startRunLoop();
		service.sendNotification(update("/panel/type", "14"));

		await("the notification to be published", () -> "14".equals(client.lastPayload(BASE + "/panel/type")));
		assertEquals(0, service.queuedNotificationCount());
	}

	@Test
	void shutdownUnsubscribesAndDisconnects() throws Exception {
		create();
		startRunLoop();

		service.shutdown();
		runThread.join(WAIT_MS);

		assertFalse(runThread.isAlive());
		assertEquals(List.of(COMMAND_TOPIC), client.unsubscribed());
		assertFalse(client.isConnected());
		//not Paho's fixed 30s, which a broker that stopped acking would make shutdown wait out
		assertEquals(TIMEOUT_MS, client.lastDisconnectQuiesceMs());
	}

	//known bugs: each asserts the correct behavior (see CLAUDE.md, "Known defects")

	@Test
	void initialConnectIsRetriedUntilTheBrokerIsUp() throws Exception {
		client.failNextConnects(2);
		create();
		assertFalse(client.isConnected());

		startRunLoop();

		await("a retried connect to succeed", client::isConnected);
		await("the command topic to be subscribed", () -> client.isSubscribed(COMMAND_TOPIC));
		service.sendNotification(update("/panel/type", "14"));
		await("the notification to be published", () -> "14".equals(client.lastPayload(BASE + "/panel/type")));
	}

	@Test
	void queueIsBoundedWhileTheBrokerIsDown() {
		client.failNextConnects(Integer.MAX_VALUE);
		create();

		for(int i = 0; i < 20000; i++) {
			service.sendNotification(update("/panel/type", "" + i));
		}

		assertTrue(service.queuedNotificationCount() <= 10000, "queued " + service.queuedNotificationCount());
	}

	@Test
	void aFullQueueDropsTheOldestNotifications() throws Exception {
		client.failNextConnects(Integer.MAX_VALUE);
		create();
		for(int i = 0; i < 10005; i++) {
			service.sendNotification(update("/count", "" + i));
		}

		client.failNextConnects(0);
		startRunLoop();

		await("the queue to drain", () -> service.queuedNotificationCount() == 0);
		List<FakeMqttClient.Published> published = client.published();
		assertEquals(10000, published.size());
		assertEquals("5", published.get(0).payload);
		assertEquals("10004", published.get(published.size() - 1).payload);
		assertTrue(logger.logged(Level.WARN, "queue"), "dropping notifications should be logged at WARN");
	}

	@Test
	void notificationsAreNotQueuedAfterShutdown() throws Exception {
		create();
		startRunLoop();
		service.shutdown();
		runThread.join(WAIT_MS);

		service.sendNotification(update("/panel/type", "14"));

		assertEquals(0, service.queuedNotificationCount());
	}

	@Test
	void commandsStillWorkAfterAnAutomaticReconnect() throws Exception {
		create();
		assertInstanceOf(MqttCallbackExtended.class, client.callback(), "the callback has to hear about reconnects");
		startRunLoop();

		client.dropConnection();
		client.reconnectAutomatically();

		await("the command topic to be subscribed again", () -> client.isSubscribed(COMMAND_TOPIC));
		client.deliver(COMMAND_TOPIC, "020204");
		assertTrue(service.messageWaiting());
	}

	@Test
	void updatesDuringAnOutageArePublishedAfterTheReconnect() throws Exception {
		create();
		startRunLoop();

		client.dropConnection();
		service.sendNotification(update("/panel/type", "14"));
		//long enough for the run loop to have taken the packet if it's going to
		Thread.sleep(700);
		client.reconnectAutomatically();

		await("the held notification to be published", () -> "14".equals(client.lastPayload(BASE + "/panel/type")));
	}

	@Test
	void subscribeFailureIsLoggedAtWarn() {
		client.failNextSubscribes(1);
		create();

		assertTrue(logger.logged(Level.WARN, COMMAND_TOPIC), logger.entries().toString());
	}

	@Test
	void publishFailureIsLoggedAtWarn() {
		create();
		client.failNextPublishes(1);

		service.sendMessage(update("/panel/type", "14"));

		assertTrue(logger.logged(Level.WARN, "/panel/type"), logger.entries().toString());
	}

	//half-dead broker: connected, but not acknowledging publishes

	@Test
	void theClientIsBuiltWithTheConfiguredTimeout() throws Exception {
		//never connected, so nothing to clean up (and Paho 1.1.1's close() throws on such a client)
		MqttClient built = MQTTService.createClient(logger, "tcp://localhost:1883", "timeout-test", 7000);
		assertEquals(7000, built.getTimeToWait());
	}

	@Test
	void aTimedOutPublishIsRetriedAfterTheTimeoutAndKeepsItsPlace() throws Exception {
		create();
		client.failNextPublishes(1, MqttException.REASON_CODE_CLIENT_TIMEOUT);
		service.sendNotification(update("/panel/type", "14"));
		service.sendNotification(update("/panel/name", "Concord"));

		long started = System.currentTimeMillis();
		startRunLoop();
		await("both notifications to be published", () -> client.published().size() == 2);

		List<FakeMqttClient.Published> published = client.published();
		assertEquals(BASE + "/panel/type", published.get(0).topic);
		assertEquals(BASE + "/panel/name", published.get(1).topic);
		assertEquals(3, client.publishCalls());
		assertTrue(System.currentTimeMillis() - started >= TIMEOUT_MS - 50, "retried without holding off");
		assertTrue(logger.logged(Level.WARN, "/panel/type"), logger.entries().toString());
	}

	@Test
	void tooManyInFlightIsHeldLikeATimeout() throws Exception {
		create();
		client.failNextPublishes(2, MqttException.REASON_CODE_MAX_INFLIGHT);
		service.sendNotification(update("/panel/type", "14"));
		startRunLoop();

		await("the notification to be published", () -> "14".equals(client.lastPayload(BASE + "/panel/type")));
		assertEquals(3, client.publishCalls());
	}

	@Test
	void anyOtherPublishFailureWhileConnectedDropsThePacket() throws Exception {
		create();
		client.failNextPublishes(1);
		service.sendNotification(update("/panel/type", "14"));
		service.sendNotification(update("/panel/name", "Concord"));
		startRunLoop();

		await("the second notification to be published", () -> client.lastPayload(BASE + "/panel/name") != null);
		assertNull(client.lastPayload(BASE + "/panel/type"));
	}

	@Test
	void shutdownDuringTheHoldOffStopsPromptly() throws Exception {
		create("", "", true, 60000);
		client.failNextPublishes(Integer.MAX_VALUE, MqttException.REASON_CODE_CLIENT_TIMEOUT);
		service.sendNotification(update("/panel/type", "14"));
		startRunLoop();
		await("the publish to be tried", () -> client.publishCalls() >= 1);

		long started = System.currentTimeMillis();
		service.shutdown();
		runThread.join(WAIT_MS);

		assertFalse(runThread.isAlive());
		assertTrue(System.currentTimeMillis() - started < 2000, "shutdown waited out the hold-off");
	}

	@Test
	void shutdownWhileWaitingToRetryTheConnectStopsPromptly() throws Exception {
		client.failNextConnects(Integer.MAX_VALUE);
		create();
		startRunLoop();
		await("a retry to be attempted", () -> client.connectAttempts() >= 2);

		long started = System.currentTimeMillis();
		service.shutdown();
		runThread.join(WAIT_MS);

		assertFalse(runThread.isAlive());
		assertTrue(System.currentTimeMillis() - started < 2000, "shutdown waited out the retry delay");
	}
}
