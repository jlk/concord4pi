package concord4pi.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import concord4pi.testsupport.RecordingBroadcaster;

class AlarmAreaPartitionTest {

	private static final String PARTITION = "/partitionarea/0001";

	private RecordingBroadcaster recorder;
	private AlarmSystemData system;
	private AlarmAreaPartition partition;

	@BeforeEach
	void setUp() {
		recorder = new RecordingBroadcaster();
		system = RecordingBroadcaster.newAlarmSystem(recorder);
		partition = system.getAreaPartitionByID("0001");
	}

	private String featureText(int featureSet) {
		partition.setFeatureSet((byte)featureSet);
		return partition.getFeatureSetText();
	}

	@Test
	void idEncodesPartitionThenArea() {
		AlarmAreaPartition area = system.getAreaPartitionByID("0001");
		assertTrue(area.isArea());
		assertFalse(area.isPartition());

		AlarmAreaPartition part = system.getAreaPartitionByID("0100");
		assertTrue(part.isPartition());
		assertFalse(part.isArea());
	}

	@Test
	@Disabled("Known bug: isArea()/isPartition() parse the hex ID with radix 10, so IDs containing A-F throw")
	void hexIDsDoNotThrow() {
		AlarmAreaPartition area = system.getAreaPartitionByID("000A");

		assertDoesNotThrow(area::isArea);
		assertDoesNotThrow(area::isPartition);
	}

	@Test
	void featureTextForSingleFeatures() {
		assertEquals("", featureText(0));
		assertEquals("CHIME", featureText(0b1));
		assertEquals("ENERGY SAVER", featureText(0b10));
		assertEquals("NO DELAY", featureText(0b100));
		assertEquals("LATCHKEY", featureText(0b1000));
		assertEquals("SILENT ARMING", featureText(0b10000));
		assertEquals("QUICK ARM", featureText(0b1000000));
	}

	@Test
	void setFeatureSetPublishesValueAndText() {
		partition.setFeatureSet((byte)0b1000);

		assertEquals(0b1000, partition.getFeatureSet());
		assertEquals("8", recorder.valueOf(PARTITION + "/featureSet"));
		assertEquals("LATCHKEY", recorder.valueOf(PARTITION + "/featureSet/text"));
	}

	@Test
	@Disabled("Known bug: getFeatureSetText() uses an else-if chain, so only the first set feature is reported")
	void featureTextListsEverySetFeature() {
		assertEquals("CHIME|ENERGY SAVER", featureText(0b11));
	}

	@Test
	void setTextPublishes() {
		partition.setText("READY");

		assertEquals("READY", partition.getText());
		assertEquals("READY", recorder.valueOf(PARTITION + "/text"));
	}

	@Test
	void textHistoryOnNewPartitionIsEmpty() {
		assertEquals("", partition.getText());
		assertEquals("", partition.getPastText(3));
	}

	@Test
	@Disabled("Known bug: partition text history appends then trims the newest entry, and getters read the oldest")
	void getTextReturnsTheMostRecentText() {
		partition.setText("FIRST");
		partition.setText("SECOND");

		assertEquals("SECOND", partition.getText());
	}

	@Test
	void addAlarmTroubleEventReturnsTheEvent() {
		AlarmTrouble event = partition.addAlarmTroubleEvent(1, 3, 2, "05", "0000");

		assertSame(event, partition.getMostRecentAlarmTroubleEvent());
		assertNull(partition.getPastAlarmTroubleEvent(1));
	}

	@Test
	@Disabled("Known bug: trouble event history appends then trims the newest entry, and getters read the oldest")
	void mostRecentAlarmTroubleEventIsTheNewest() {
		partition.addAlarmTroubleEvent(1, 3, 2, "05", "0000");
		AlarmTrouble second = partition.addAlarmTroubleEvent(3, 3, 2, "05", "0000");

		assertSame(second, partition.getMostRecentAlarmTroubleEvent());
	}

	@Test
	void userLookupReturnsTheSameInstanceAndCreatesUnseenIDs() {
		AlarmUser user = partition.getUser("00F0");

		assertSame(user, partition.getUser("00F0"));
		assertEquals("00F1", partition.getUser("00F1").getID());
		assertTrue(recorder.published(PARTITION + "/users/00F1"));
	}

	@Test
	void temperatureValuesArePublished() {
		AlarmTemperature temperature = partition.getTemperature();
		temperature.setTemperatureAll(70, 75, 65);

		assertEquals(70, temperature.getTemperature());
		assertEquals(75, temperature.getHSP());
		assertEquals(65, temperature.getLSP());
		//topic is checked (as a known bug) in AlarmSystemDataTest
		String topic = temperature.myAddress();
		assertEquals("70", recorder.valueOf(topic));
		assertEquals("75", recorder.valueOf(topic + "/HSP"));
		assertEquals("65", recorder.valueOf(topic + "/LSP"));
	}
}
