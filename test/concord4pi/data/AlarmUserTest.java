package concord4pi.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import concord4pi.testsupport.RecordingBroadcaster;

class AlarmUserTest {

	private AlarmAreaPartition partition;

	@BeforeEach
	void setUp() {
		partition = RecordingBroadcaster.newAlarmSystem(new RecordingBroadcaster()).getAreaPartitionByID("0001");
	}

	private String typeOf(int userNumber) {
		return partition.getUser(String.format("00%02X", userNumber)).getType();
	}

	@Test
	void regularUserRange() {
		assertEquals("Regular User", typeOf(0));
		assertEquals("Regular User", typeOf(229));
	}

	@Test
	void masterCodeRange() {
		assertEquals("Master Code", typeOf(230));
		assertEquals("Master Code", typeOf(237));
	}

	@Test
	void duressCodeRange() {
		assertEquals("Duress Code", typeOf(238));
		assertEquals("Duress Code", typeOf(245));
	}

	@Test
	void specialCodes() {
		assertEquals("System Master Code", typeOf(246));
		assertEquals("Installer Code", typeOf(247));
		assertEquals("Dealer Code", typeOf(248));
		assertEquals("AVM Code", typeOf(249));
		assertEquals("Quick Arm", typeOf(250));
		assertEquals("Key Switch Arm", typeOf(251));
		assertEquals("System", typeOf(252));
		assertEquals("UNKNOWN", typeOf(253));
	}

	@Test
	void fobIsIdentifiedByTheHighByte() {
		assertTrue(partition.getUser("0105").isFob());
		assertFalse(partition.getUser("0005").isFob());
	}
}
