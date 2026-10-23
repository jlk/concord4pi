package concord4pi.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import concord4pi.testsupport.RecordingBroadcaster;

class AlarmTroubleTest {

	//every trouble event in a partition publishes to the same topics
	private static final String EVENT = "/partitionarea/0001/event";

	private RecordingBroadcaster recorder;
	private AlarmAreaPartition partition;

	@BeforeEach
	void setUp() {
		recorder = new RecordingBroadcaster();
		partition = RecordingBroadcaster.newAlarmSystem(recorder).getAreaPartitionByID("0001");
	}

	@Test
	void fieldsAndTextsArePublished() {
		AlarmTrouble event = partition.addAlarmTroubleEvent(1, 3, 2, "05", "0000");

		assertEquals("1.3", event.getTypeString());
		assertEquals("Alarm", event.getGeneralTypeText());
		assertEquals("Police", event.getSpecificTypeText());
		assertEquals("Zone", event.getSourceTypeText());

		assertEquals("1", recorder.valueOf(EVENT + "/general"));
		assertEquals("Alarm", recorder.valueOf(EVENT + "/general/text"));
		assertEquals("3", recorder.valueOf(EVENT + "/specific"));
		assertEquals("Police", recorder.valueOf(EVENT + "/specific/text"));
		assertEquals("2", recorder.valueOf(EVENT + "/source"));
		assertEquals("Zone", recorder.valueOf(EVENT + "/source/text"));
		assertEquals("05", recorder.valueOf(EVENT + "/sourceNumber"));
		assertEquals("0000", recorder.valueOf(EVENT + "/eventData"));
	}

	@Test
	void sourceTypeNames() {
		String[] expected = { "Bus Device", "Local Phone", "Zone", "System", "Remote Phone", "UNKNOWN" };

		for(int sourceType = 0; sourceType < expected.length; sourceType++) {
			AlarmTrouble event = partition.addAlarmTroubleEvent(1, 0, sourceType, "000001", "0000");
			assertEquals(expected[sourceType], event.getSourceTypeText(), "source " + sourceType);
		}
	}

	@Test
	void generalTypeNames() {
		assertEquals("Fire Trouble", partition.addAlarmTroubleEvent(4, 0, 3, "000000", "0000").getGeneralTypeText());
		assertEquals("System Event", partition.addAlarmTroubleEvent(18, 0, 3, "000000", "0000").getGeneralTypeText());
		assertEquals("UNKNOWN", partition.addAlarmTroubleEvent(99, 0, 3, "000000", "0000").getGeneralTypeText());
	}

	@Test
	void unknownGeneralTypeHasUnknownSpecificText() {
		assertEquals("UNKNOWN", partition.addAlarmTroubleEvent(99, 0, 3, "000000", "0000").getSpecificTypeText());
	}
}
