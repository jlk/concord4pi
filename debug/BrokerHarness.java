import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import concord4pi.logging.LogEngine;
import concord4pi.messaging.MQTTService;
import concord4pi.messaging.NotificationPacket;

/**
 * Drives MQTTService through its public constructor against a real Mosquitto container.
 *   late:    broker down when the service starts, then started; then restarted
 *   restart: broker up when the service starts; then restarted
 * Prints PASS/FAIL per check and exits non-zero on any failure. Uses only the public API, so it
 * runs against older builds too. Run it with debug/brokerReconnectCheck.
 */
public class BrokerHarness {

	static final String CONTAINER = "concord4pi-harness-broker";
	static final String URI = "tcp://localhost:18830";
	static final Map<String, String> seen = new ConcurrentHashMap<>();
	static MqttClient observer;
	static int failures = 0;

	public static void main(String[] args) throws Exception {
		String mode = args[0];
		if(mode.equals("late")) {
			docker("stop", CONTAINER);
		}
		else {
			docker("start", CONTAINER);
			connectObserver();
		}

		MQTTService service = new MQTTService(new LogEngine(null), URI, "harness-" + System.nanoTime(), "", "", "concord", "cmd", true);
		Thread runThread = new Thread(service, "mqtt-run");
		runThread.setDaemon(true);
		runThread.start();

		if(mode.equals("late")) {
			service.sendNotification(new NotificationPacket("/test/early", "1", NotificationPacket.NPACTION_UPDATE));
			System.out.println("== starting broker after the service");
			docker("start", CONTAINER);
			connectObserver();
			check("notification queued before the broker came up is published", 20000, () -> "1".equals(seen.get("concord/test/early")));
			checkCommand(service, "command works after a late broker start");
		}
		else {
			checkCommand(service, "command works with the broker up at start");
		}

		System.out.println("== restarting broker");
		docker("stop", CONTAINER);
		service.sendNotification(new NotificationPacket("/test/outage", "2", NotificationPacket.NPACTION_UPDATE));
		Thread.sleep(2000);
		docker("start", CONTAINER);
		connectObserver();
		check("notification sent during the outage is published after the reconnect", 30000, () -> "2".equals(seen.get("concord/test/outage")));
		checkCommand(service, "command works after the broker restart");

		service.sendNotification(new NotificationPacket("/test/after", "3", NotificationPacket.NPACTION_UPDATE));
		check("notification after the reconnect is published", 10000, () -> "3".equals(seen.get("concord/test/after")));

		service.shutdown();
		runThread.join(5000);
		System.out.println("== " + (failures == 0 ? "ALL PASSED" : failures + " FAILED") + " (" + mode + ")");
		System.exit(failures == 0 ? 0 : 1);
	}

	//keep publishing the command until the service has it, since its reconnect may still be pending
	static void checkCommand(MQTTService service, String what) throws Exception {
		check(what, 30000, () -> {
			if(!service.messageWaiting()) {
				try {
					observer.publish("concord/cmd", new MqttMessage("020204".getBytes()));
					Thread.sleep(500);
				} catch(Exception ignored) { }
			}
			return service.messageWaiting();
		});
		service.getMessageList();
	}

	static void connectObserver() throws Exception {
		long deadline = System.currentTimeMillis() + 15000;
		while(true) {
			try {
				if(observer != null) {
					try { observer.close(); } catch(Exception ignored) { }
				}
				observer = new MqttClient(URI, "observer-" + System.nanoTime(), new MemoryPersistence());
				observer.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
					public void connectionLost(Throwable cause) { }
					public void messageArrived(String topic, MqttMessage message) { seen.put(topic, new String(message.getPayload())); }
					public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) { }
				});
				observer.connect(new MqttConnectOptions());
				observer.subscribe("concord/test/#");
				return;
			} catch(Exception notYet) {
				if(System.currentTimeMillis() > deadline) { throw notYet; }
				Thread.sleep(50);
			}
		}
	}

	static void check(String what, long timeoutMs, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while(!condition.getAsBoolean()) {
			if(System.currentTimeMillis() > deadline) {
				System.out.println("FAIL: " + what);
				failures++;
				return;
			}
			Thread.sleep(50);
		}
		System.out.println("PASS: " + what);
	}

	static void docker(String... args) throws Exception {
		String[] command = new String[args.length + 1];
		command[0] = "docker";
		System.arraycopy(args, 0, command, 1, args.length);
		new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
	}
}
