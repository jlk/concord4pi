package concord4pi.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import concord4pi.messaging.NotificationPacket;
import concord4pi.testsupport.RecordingBroadcaster;

class AlarmZoneTest {

	private static final String ZONE = "/partitionarea/0001/zones/0005";

	private RecordingBroadcaster recorder;
	private AlarmAreaPartition partition;
	private AlarmZone zone;

	@BeforeEach
	void setUp() {
		recorder = new RecordingBroadcaster();
		partition = RecordingBroadcaster.newAlarmSystem(recorder).getAreaPartitionByID("0001");
		zone = partition.getZoneByID("0005");
	}

	private String statusText(int status) {
		zone.setStatus(status);
		return zone.getStatusText();
	}

	@Test
	void statusTextForEachFlag() {
		assertEquals("NONE", statusText(0));
		assertEquals("TRIPPED", statusText(1));
		assertEquals("FAULTED", statusText(2));
		assertEquals("ALARM", statusText(4));
		assertEquals("TROUBLE", statusText(8));
		assertEquals("BYPASSED", statusText(16));
	}

	@Test
	void statusTextCombinesFlags() {
		assertEquals("TRIPPED|FAULTED", statusText(3));
		assertEquals("TRIPPED|FAULTED|ALARM|TROUBLE|BYPASSED", statusText(31));
	}

	@Test
	void setStatusPublishesValueAndText() {
		zone.setStatus(5);

		assertEquals(5, zone.getStatus());
		assertEquals("5", recorder.valueOf(ZONE + "/status"));
		assertEquals("TRIPPED|ALARM", recorder.valueOf(ZONE + "/status/text"));
	}

	@Test
	void typeNames() {
		zone.setType(0);
		assertEquals("Hardwired", zone.getTypeName());
		zone.setType(1);
		assertEquals("RF", zone.getTypeName());
		zone.setType(2);
		assertEquals("RF Touchpad", zone.getTypeName());
		zone.setType(3);
		assertEquals("UNKNOWN", zone.getTypeName());
		assertEquals("3", recorder.valueOf(ZONE + "/type"));
		assertEquals("UNKNOWN", recorder.valueOf(ZONE + "/type/text"));
	}

	@Test
	void groupIsAnnouncedAsNewThenUpdated() {
		zone.setGroup(3);
		NotificationPacket first = recorder.lastPacket(ZONE + "/group/3");
		assertEquals(NotificationPacket.NPACTION_NEW, first.action);

		zone.setGroup(4);
		assertEquals(NotificationPacket.NPACTION_UPDATE, recorder.lastPacket(ZONE + "/group/4").action);
		assertEquals(4, zone.getGroup());
	}

	@Test
	void updateZoneDataSetsEverything() {
		zone.updateZoneData(3, 1, 2, "FRONT DOOR");

		assertEquals(3, zone.getGroup());
		assertEquals(1, zone.getType());
		assertEquals(2, zone.getStatus());
		assertEquals("FRONT DOOR", zone.getText());
		assertEquals("FRONT DOOR", recorder.valueOf(ZONE + "/text"));
	}

	@Test
	void keyFobCommandNames() {
		String[] expected = { "DISARM", "ARM", "LIGHTS", "STAR", "ARM & DISARM", "LIGHTS & STAR",
				"LONG LIGHTS", "NONE", "NONE", "ARM & STAR", "DISARM & LIGHTS", "NONE" };

		for(int command = 0; command < expected.length; command++) {
			zone.setKeyFobCommand(command);
			assertEquals(expected[command], zone.getKeyFobCommandText(), "command " + command);
			assertEquals(command, zone.getKeyFobCommand());
		}
	}

	@Test
	void firstKeyFobCommandIsPublished() {
		zone.setKeyFobCommand(1);

		assertEquals("1", recorder.valueOf(ZONE + "/keyFob/command"));
		assertEquals("ARM", recorder.valueOf(ZONE + "/keyFob/command/text"));
	}

	@Test
	void getTextReturnsTheMostRecentText() {
		zone.setText("FIRST");
		zone.setText("SECOND");

		assertEquals("SECOND", zone.getText());
	}

	@Test
	void historyKeepsTheNewestFive() {
		for(int i = 0; i < 7; i++) {
			zone.setText("T" + i);
		}

		assertEquals("T6", zone.getPastText(0));
		assertEquals("T2", zone.getPastText(4));
	}

	@Test
	void keyFobTextMatchesTheLatestCommand() {
		zone.setKeyFobCommand(1);
		zone.setKeyFobCommand(0);

		assertEquals("DISARM", recorder.valueOf(ZONE + "/keyFob/command/text"));
		assertEquals("DISARM", zone.getKeyFobCommandText());
	}

	@Test
	void getTextOnNewZoneIsEmpty() {
		assertEquals("", zone.getText());
	}

	@Test
	void pastTextBeyondTheHistoryIsEmpty() {
		zone.setText("ONLY");

		assertEquals("", zone.getPastText(1));
	}

	@Test
	void updateZoneDataTwiceKeepsTheLatestText() {
		zone.updateZoneData(3, 1, 0, "FRONT DOOR");
		zone.updateZoneData(3, 1, 1, "BACK DOOR");

		assertEquals("BACK DOOR", zone.getText());
		assertEquals("FRONT DOOR", zone.getPastText(1));
	}

	@Test
	void keyFobHistoryKeepsTheNewestFive() {
		for(int command = 0; command < 7; command++) {
			zone.setKeyFobCommand(command);
		}

		assertEquals(6, zone.getPastKeyFobCommand(0));
		assertEquals(2, zone.getPastKeyFobCommand(4));
		assertEquals("NONE", zone.getPastKeyFobCommandText(5));
	}

	@Test
	void everyKeyFobCommandPublishesItsOwnText() {
		//more commands than the history holds, so the trim runs too
		int[] commands = { 1, 0, 2, 3, 0, 1, 6, 0 };
		String[] names = { "ARM", "DISARM", "LIGHTS", "STAR", "DISARM", "ARM", "LONG LIGHTS", "DISARM" };

		for(int i = 0; i < commands.length; i++) {
			zone.setKeyFobCommand(commands[i]);
			assertEquals("" + commands[i], recorder.valueOf(ZONE + "/keyFob/command"), "command " + i);
			assertEquals(names[i], recorder.valueOf(ZONE + "/keyFob/command/text"), "command " + i);
		}
	}

	@Test
	void keyFobOnNewZoneIsNone() {
		assertEquals(-1, zone.getKeyFobCommand());
		assertEquals("NONE", zone.getKeyFobCommandText());
	}
}
