package concord4pi.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import concord4pi.testsupport.RecordingBroadcaster;

class AlarmTouchpadTest {

	private static final String TOUCHPAD = "/partitionarea/0001/touchpad";

	private RecordingBroadcaster recorder;
	private AlarmTouchpad touchpad;

	@BeforeEach
	void setUp() {
		recorder = new RecordingBroadcaster();
		touchpad = RecordingBroadcaster.newAlarmSystem(recorder).getAreaPartitionByID("0001").getTouchpad();
	}

	@Test
	void textIsDecodedFromTokensAndPublished() {
		//"P", "1", ".", "9"
		touchpad.setTextFromTokens("20011009");

		assertEquals("P1.9", touchpad.getText());
		assertEquals("P1.9", recorder.valueOf(TOUCHPAD + "/text"));
	}

	@Test
	void broadcastFlag() {
		assertFalse(touchpad.getBroadcastFlag());
		assertEquals("NORMAL", touchpad.getBroadcastFlagText());

		touchpad.setBroadcastFlag(true);
		assertTrue(touchpad.getBroadcastFlag());
		assertEquals("BROADCAST", recorder.valueOf(TOUCHPAD + "/broadcastFlag"));

		touchpad.setBroadcastFlag(false);
		assertEquals("NORMAL", recorder.valueOf(TOUCHPAD + "/broadcastFlag"));
	}
}
