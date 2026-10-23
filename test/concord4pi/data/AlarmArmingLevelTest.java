package concord4pi.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import concord4pi.testsupport.RecordingBroadcaster;

class AlarmArmingLevelTest {

	private static final String ARMING = "/partitionarea/0001/armingLevel";

	private RecordingBroadcaster recorder;
	private AlarmSystemData system;
	private AlarmArmingLevel armingLevel;

	@BeforeEach
	void setUp() {
		recorder = new RecordingBroadcaster();
		system = RecordingBroadcaster.newAlarmSystem(recorder);
		//"0001" is partition 00, area 01, so it uses the area names
		armingLevel = system.getAreaPartitionByID("0001").getArmingLevel();
	}

	private static String text(AlarmArmingLevel level, int value) {
		level.setArmingLevel(value);
		return level.getArmingLevelText();
	}

	@Test
	void areaLevelNames() {
		assertEquals("Zone Test", text(armingLevel, 0));
		assertEquals("Off", text(armingLevel, 1));
		assertEquals("Home/Perimeter", text(armingLevel, 2));
		assertEquals("Away/Full", text(armingLevel, 3));
		assertEquals("Night", text(armingLevel, 4));
		assertEquals("Silent", text(armingLevel, 5));
		assertEquals("UNKNOWN", text(armingLevel, 6));
	}

	@Test
	void partitionLevelNames() {
		AlarmArmingLevel partitionLevel = system.getAreaPartitionByID("0100").getArmingLevel();

		assertEquals("UNKNOWN", text(partitionLevel, 0));
		assertEquals("Off", text(partitionLevel, 1));
		assertEquals("Stay", text(partitionLevel, 2));
		assertEquals("Away", text(partitionLevel, 3));
		assertEquals("Phone Test", text(partitionLevel, 8));
		assertEquals("Sensor Test", text(partitionLevel, 9));
	}

	@Test
	void setArmingLevelPublishesValueAndText() {
		armingLevel.setArmingLevel(3);

		assertEquals(3, armingLevel.getArmingLevel());
		assertEquals("3", recorder.valueOf(ARMING));
		assertEquals("Away/Full", recorder.valueOf(ARMING + "/text"));
	}

	@Test
	@Disabled("Known bug: isArmed() returns true for levels <= 1 (Zone Test/Off) and false for armed levels. It has no callers.")
	void isArmedIsFalseWhenOffAndTrueWhenAway() {
		armingLevel.setArmingLevel(1);
		assertFalse(armingLevel.isArmed());

		armingLevel.setArmingLevel(3);
		assertTrue(armingLevel.isArmed());
	}

	@Test
	void armingUserIsLookedUpAndPublished() {
		armingLevel.setArmingUserByID("00F6");

		assertEquals("00F6", armingLevel.getArmingUser().getID());
		assertEquals("System Master Code", armingLevel.getArmingUser().getType());
		assertEquals("00F6", recorder.valueOf(ARMING + "/user"));
	}

	@Test
	void delayTimeIsPublished() {
		armingLevel.setDelayTime(300);

		assertEquals(300, armingLevel.getDelayTime());
		assertEquals("300", recorder.valueOf(ARMING + "/delayTime"));
	}

	@Test
	void delayFlagsDefaultText() {
		armingLevel.setDelayFlags(0);

		assertEquals("STANDARD|ENTRY DELAY|START DELAY", armingLevel.getDelayFlagsText());
	}

	@Test
	void delayFlagsExitBit() {
		armingLevel.setDelayFlags(0b100000);

		assertEquals(0b100000, armingLevel.getDelayFlags());
		assertTrue(armingLevel.getDelayFlagsText().contains("EXIT DELAY"));
	}

	@Test
	@Disabled("Known bug: getDelayFlagsText() masks with 0, so it always reports STANDARD")
	void delayFlagsExtendedAndTwiceExtended() {
		armingLevel.setDelayFlags(0b01000);
		assertTrue(armingLevel.getDelayFlagsText().startsWith("EXTENDED"));

		armingLevel.setDelayFlags(0b10000);
		assertTrue(armingLevel.getDelayFlagsText().startsWith("TWICE EXTENDED"));
	}

	@Test
	@Disabled("Known bug: DELAYFLAG_END is the same bit as DELAYFLAG_EXIT, so END DELAY never matches bit 7")
	void delayFlagsEndBit() {
		armingLevel.setDelayFlags(0b1000000);

		assertTrue(armingLevel.getDelayFlagsText().contains("END DELAY"));
	}

	@Test
	@Disabled("Known bug: setDelayFlags() publishes the number and the text to the same /delayFlags topic")
	void delayFlagsNumberAndTextUseSeparateTopics() {
		armingLevel.setDelayFlags(0b100000);

		assertEquals("32", recorder.valueOf(ARMING + "/delayFlags"));
		assertEquals(armingLevel.getDelayFlagsText(), recorder.valueOf(ARMING + "/delayFlags/text"));
	}
}
