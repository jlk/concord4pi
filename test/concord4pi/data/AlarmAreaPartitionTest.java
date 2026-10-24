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

	@Test
	void textHistoryKeepsTheNewestFive() {
		for(int i = 0; i < 7; i++) {
			partition.setText("T" + i);
		}

		assertEquals("T6", partition.getPastText(0));
		assertEquals("T2", partition.getPastText(4));
		assertEquals("", partition.getPastText(5));
	}

	@Test
	void troubleHistoryKeepsTheNewestFive() {
		AlarmTrouble[] events = new AlarmTrouble[7];
		for(int i = 0; i < events.length; i++) {
			events[i] = partition.addAlarmTroubleEvent(1, i, 2, "05", "0000");
		}

		assertSame(events[6], partition.getPastAlarmTroubleEvent(0));
		assertSame(events[2], partition.getPastAlarmTroubleEvent(4));
		assertNull(partition.getPastAlarmTroubleEvent(5));
	}

	@Test
	void featureTextNamesEachSetFeatureInBitOrder() {
		assertEquals("", featureText(0));
		assertEquals("NO DELAY|LATCHKEY", featureText(0x0C));
		assertEquals("CHIME|ENERGY SAVER|NO DELAY|LATCHKEY|SILENT ARMING|QUICK ARM", featureText(0x5F));
	}
}
