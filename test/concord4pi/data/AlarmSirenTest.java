package concord4pi.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import concord4pi.testsupport.RecordingBroadcaster;

class AlarmSirenTest {

	private static final String SIREN = "/partitionarea/0001/siren";

	private RecordingBroadcaster recorder;
	private AlarmSiren siren;

	@BeforeEach
	void setUp() {
		recorder = new RecordingBroadcaster();
		siren = RecordingBroadcaster.newAlarmSystem(recorder).getAreaPartitionByID("0001").getSiren();
	}

	@Test
	void cadenceIsParsedFromEightHexCharacters() {
		siren.setCadenceFromString("80000001");

		assertEquals(0x80000001, siren.getCadence());
		assertEquals("10000000000000000000000000000001", siren.getCadenceText());
		assertEquals("" + 0x80000001, recorder.valueOf(SIREN + "/cadence"));
		assertEquals("10000000000000000000000000000001", recorder.valueOf(SIREN + "/cadence/binary"));
	}

	@Test
	void cadenceWithWrongLengthIsIgnored() {
		siren.setCadenceFromString("123");

		assertEquals(0, siren.getCadence());
		assertFalse(recorder.published(SIREN + "/cadence"));
	}

	@Test
	void goAndStopPublishState() {
		assertFalse(siren.isSirenOn());

		siren.goSiren();
		assertTrue(siren.isSirenOn());
		assertEquals("true", recorder.valueOf(SIREN + "/state"));

		siren.stopSiren();
		assertFalse(siren.isSirenOn());
		assertEquals("false", recorder.valueOf(SIREN + "/state"));
	}

	@Test
	void repetitionCountIsPublished() {
		siren.setRepetitionCount(4);

		assertEquals(4, siren.getRepetitionCount());
		assertEquals("4", siren.getRepetitionCountText());
		assertEquals("4", recorder.valueOf(SIREN + "/repetitionCount"));
	}

	@Test
	void firstSyncEventIsRecordedAndPublished() {
		Date event = siren.addSyncEvent();

		assertSame(event, siren.getMostRecentSirenSyncEvent());
		assertEquals("" + event, recorder.valueOf(SIREN + "/sync"));
		assertNull(siren.getPastSirenSyncEvent(1));
	}

	@Test
	@Disabled("Known bug: siren sync history appends then trims the newest entry, and getters read the oldest")
	void mostRecentSyncEventIsTheNewest() {
		siren.addSyncEvent();
		Date second = siren.addSyncEvent();

		assertSame(second, siren.getMostRecentSirenSyncEvent());
	}
}
