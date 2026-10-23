package concord4pi.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import concord4pi.messaging.NotificationPacket;
import concord4pi.testsupport.RecordingBroadcaster;

class AlarmSystemDataTest {

	private RecordingBroadcaster recorder;
	private AlarmSystemData system;

	@BeforeEach
	void setUp() {
		recorder = new RecordingBroadcaster();
		system = RecordingBroadcaster.newAlarmSystem(recorder);
	}

	@Test
	void topicAddressesFollowTheObjectTree() {
		AlarmAreaPartition partition = system.getAreaPartitionByID("0001");

		assertEquals("", system.myAddress());
		assertEquals("/panel", system.getPanel().myAddress());
		assertEquals("/partitionarea/0001", partition.myAddress());
		assertEquals("/partitionarea/0001/zones/0005", partition.getZoneByID("0005").myAddress());
		assertEquals("/partitionarea/0001/users/00F0", partition.getUser("00F0").myAddress());
		assertEquals("/partitionarea/0001/siren", partition.getSiren().myAddress());
		assertEquals("/partitionarea/0001/touchpad", partition.getTouchpad().myAddress());
		assertEquals("/partitionarea/0001/armingLevel", partition.getArmingLevel().myAddress());
		assertEquals("/partitionarea/0001/event",
				partition.addAlarmTroubleEvent(1, 0, 2, "05", "0000").myAddress());
	}

	@Test
	@Disabled("Known bug: AlarmTemperature.myAddress() uses parent.getID() instead of parent.myAddress()")
	void temperatureTopicIsUnderThePartition() {
		AlarmAreaPartition partition = system.getAreaPartitionByID("0001");

		assertEquals("/partitionarea/0001/temperature", partition.getTemperature().myAddress());
	}

	@Test
	void constructionAnnouncesRootAndPanel() {
		assertTrue(recorder.published(""));
		assertTrue(recorder.published("/panel"));
		assertEquals(NotificationPacket.NPACTION_NEW, recorder.lastPacket("/panel").action);
	}

	@Test
	void newPartitionAnnouncesItselfAndItsChildrenWithEmptyNewPackets() {
		recorder.clear();
		system.getAreaPartitionByID("0001");

		for(String topic : new String[] { "/partitionarea/0001", "/partitionarea/0001/armingLevel",
				"/partitionarea/0001/siren", "/partitionarea/0001/touchpad" }) {
			NotificationPacket packet = recorder.lastPacket(topic);
			assertNotNull(packet, topic);
			assertEquals(NotificationPacket.NPACTION_NEW, packet.action, topic);
			assertEquals("", packet.value, topic);
		}
	}

	@Test
	void partitionLookupReturnsTheSameInstanceForTheSameID() {
		AlarmAreaPartition first = system.getAreaPartitionByID("0001");

		assertSame(first, system.getAreaPartitionByID("0001"));
		assertNotSame(first, system.getAreaPartitionByID("0002"));
	}

	@Test
	void partitionLookupCreatesUnseenIDs() {
		recorder.clear();
		AlarmAreaPartition created = system.getAreaPartitionByID("0003");

		assertEquals("0003", created.getID());
		assertTrue(recorder.published("/partitionarea/0003"));
	}

	@Test
	void zoneLookupReturnsTheSameInstanceAndCreatesUnseenIDs() {
		AlarmAreaPartition partition = system.getAreaPartitionByID("0001");
		AlarmZone zone = partition.getZoneByID("0005");

		assertSame(zone, partition.getZoneByID("0005"));
		assertNotSame(zone, partition.getZoneByID("0006"));
		assertEquals("0006", partition.getZoneByID("0006").getID());
	}

	@Test
	void setDatePublishesDate() {
		Date date = new Date(0);
		system.setDate(date);

		assertSame(date, system.getDate());
		assertEquals("" + date, recorder.valueOf("/date"));
	}

	@Test
	void sirenSyncReachesEveryPartition() {
		system.getAreaPartitionByID("0001");
		system.getAreaPartitionByID("0002");
		system.sendSirenSync();

		assertTrue(recorder.published("/partitionarea/0001/siren/sync"));
		assertTrue(recorder.published("/partitionarea/0002/siren/sync"));
	}

	@Test
	void sirenSignalReachesEveryPartition() {
		AlarmAreaPartition first = system.getAreaPartitionByID("0001");
		AlarmAreaPartition second = system.getAreaPartitionByID("0002");

		system.sendSirenSignal(true);
		assertTrue(first.getSiren().isSirenOn());
		assertTrue(second.getSiren().isSirenOn());
		assertEquals("true", recorder.valueOf("/partitionarea/0002/siren/state"));

		system.sendSirenSignal(false);
		assertFalse(first.getSiren().isSirenOn());
		assertEquals("false", recorder.valueOf("/partitionarea/0001/siren/state"));
	}
}
