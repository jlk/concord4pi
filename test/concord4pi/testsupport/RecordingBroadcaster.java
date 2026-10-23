package concord4pi.testsupport;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import concord4pi.SB2000.IOMessage;
import concord4pi.data.AlarmSystemData;
import concord4pi.logging.LogEngine;
import concord4pi.messaging.IBroadcaster;
import concord4pi.messaging.NotificationPacket;

/**
 * An IBroadcaster that records every NotificationPacket so tests can assert
 * on the MQTT topics and values the data model would publish.
 */
public class RecordingBroadcaster implements IBroadcaster {

	private final List<NotificationPacket> packets = new ArrayList<NotificationPacket>();

	/** Builds an AlarmSystemData whose notifications go to a new RecordingBroadcaster. */
	public static AlarmSystemData newAlarmSystem(RecordingBroadcaster recorder) {
		LinkedList<IBroadcaster> broadcasters = new LinkedList<IBroadcaster>();
		broadcasters.add(recorder);
		return new AlarmSystemData(new LogEngine(null), broadcasters);
	}

	@Override
	public void sendNotification(NotificationPacket packet) {
		packets.add(packet);
	}

	public List<NotificationPacket> packets() {
		return packets;
	}

	public List<String> topics() {
		List<String> topics = new ArrayList<String>();
		for(NotificationPacket packet : packets) {
			topics.add(packet.key);
		}
		return topics;
	}

	public boolean published(String topic) {
		return topics().contains(topic);
	}

	/** The value of the most recent packet for the topic, or null if none was sent. */
	public String valueOf(String topic) {
		NotificationPacket packet = lastPacket(topic);
		return packet == null ? null : packet.value;
	}

	/** The most recent packet for the topic, or null if none was sent. */
	public NotificationPacket lastPacket(String topic) {
		for(int i = packets.size() - 1; i >= 0; i--) {
			if(packets.get(i).key.equals(topic)) {
				return packets.get(i);
			}
		}
		return null;
	}

	public void clear() {
		packets.clear();
	}

	@Override
	public void shutdown() {
	}

	@Override
	public boolean messageWaiting() {
		return false;
	}

	@Override
	public ArrayList<IOMessage> getMessageList() {
		return new ArrayList<IOMessage>();
	}
}
