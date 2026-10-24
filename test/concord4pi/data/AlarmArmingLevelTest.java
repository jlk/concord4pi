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

	private String delayText(int delayFlags) {
		armingLevel.setDelayFlags(delayFlags);
		return armingLevel.getDelayFlagsText();
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

	private static boolean armed(AlarmArmingLevel level, int value) {
		level.setArmingLevel(value);
		return level.isArmed();
	}

	@Test
	void isArmedIsFalseWhenOffAndTrueWhenAway() {
		armingLevel.setArmingLevel(1);
		assertFalse(armingLevel.isArmed());

		armingLevel.setArmingLevel(3);
		assertTrue(armingLevel.isArmed());
	}

	@Test
	void areaIsArmedAtHomeAwayNightAndSilent() {
		assertFalse(armed(armingLevel, 0), "Zone Test");
		assertFalse(armed(armingLevel, 1), "Off");
		assertTrue(armed(armingLevel, 2), "Home/Perimeter");
		assertTrue(armed(armingLevel, 3), "Away/Full");
		assertTrue(armed(armingLevel, 4), "Night");
		assertTrue(armed(armingLevel, 5), "Silent");
		assertFalse(armed(armingLevel, 6), "unknown level");
	}

	@Test
	void partitionIsArmedAtStayAndAwayButNotInTestModes() {
		AlarmArmingLevel partitionLevel = system.getAreaPartitionByID("0100").getArmingLevel();

		assertFalse(armed(partitionLevel, 1), "Off");
		assertTrue(armed(partitionLevel, 2), "Stay");
		assertTrue(armed(partitionLevel, 3), "Away");
		assertFalse(armed(partitionLevel, 8), "Phone Test");
		assertFalse(armed(partitionLevel, 9), "Sensor Test");
		assertFalse(armed(partitionLevel, 0), "unknown level");
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

	//bit positions per real panel traffic (see RealFramesTest): bits 4-5 delay length,
	//bit 6 (0x40) exit delay, bit 7 (0x80) end of delay

	@Test
	void delayFlagsAreStored() {
		armingLevel.setDelayFlags(0x40);

		assertEquals(0x40, armingLevel.getDelayFlags());
	}

	@Test
	void delayFlagsExitBit() {
		armingLevel.setDelayFlags(0x40);

		assertTrue(armingLevel.getDelayFlagsText().contains("EXIT DELAY"));
	}

	@Test
	void delayFlagsExtendedAndTwiceExtended() {
		armingLevel.setDelayFlags(0x10);
		assertTrue(armingLevel.getDelayFlagsText().startsWith("EXTENDED"));

		armingLevel.setDelayFlags(0x20);
		assertTrue(armingLevel.getDelayFlagsText().startsWith("TWICE EXTENDED"));
	}

	@Test
	void delayFlagsEndBit() {
		armingLevel.setDelayFlags(0x80);

		assertTrue(armingLevel.getDelayFlagsText().contains("END DELAY"));
	}

	@Test
	void delayFlagsNumberAndTextUseSeparateTopics() {
		armingLevel.setDelayFlags(0x40);

		assertEquals("64", recorder.valueOf(ARMING + "/delayFlags"));
		assertEquals(armingLevel.getDelayFlagsText(), recorder.valueOf(ARMING + "/delayFlags/text"));
	}

	@Test
	void delayFlagsTextForEachCombination() {
		assertEquals("STANDARD|ENTRY DELAY|START DELAY", delayText(0x00));
		assertEquals("EXTENDED|ENTRY DELAY|START DELAY", delayText(0x10));
		assertEquals("TWICE EXTENDED|ENTRY DELAY|START DELAY", delayText(0x20));
		assertEquals("STANDARD|EXIT DELAY|START DELAY", delayText(0x40));
		assertEquals("STANDARD|ENTRY DELAY|END DELAY", delayText(0x80));
		assertEquals("STANDARD|EXIT DELAY|END DELAY", delayText(0xC0));
		assertEquals("TWICE EXTENDED|EXIT DELAY|END DELAY", delayText(0xE0));
	}

	@Test
	void delayFlagsWithBothLengthBitsAreUnknown() {
		//0x30 isn't a delay length the protocol defines
		assertEquals("UNKNOWN|ENTRY DELAY|START DELAY", delayText(0x30));
	}

	@Test
	void delayFlagsTextIgnoresTheLowBits() {
		assertEquals(delayText(0x40), delayText(0x4F));
	}
}
