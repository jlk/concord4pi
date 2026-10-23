package concord4pi.testsupport;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import concord4pi.SB2000.IOMessage;
import concord4pi.SB2000.SB2000Constants;
import concord4pi.messaging.NotificationPacket;

/** Checks the test helpers themselves, so failures elsewhere can be trusted. */
class TestSupportTest {

	@Test
	void messagesReproducesTheFullEquipmentListCommand() {
		assertEquals(new String(SB2000Constants.FULLCOMMAND_FullEquipmentList), Messages.frame("02", ""));
	}

	@Test
	void messagesBuildsValidMessages() {
		IOMessage message = Messages.build("2201", "000100F001");
		assertTrue(message.isValid());
		assertEquals("2201", message.getCommandCodeString());
		assertEquals("000100F001", message.getDataString());
	}

	@Test
	void messagesBadChecksumIsInvalid() {
		assertFalse(new IOMessage(Messages.frameWithBadChecksum("21", "0001000500").getBytes()).isValid());
	}

	@Test
	void wirePrefixesMessageStart() {
		assertEquals("\n020204", Messages.wire("02", ""));
	}

	@Test
	void recordingBroadcasterSeesModelNotifications() {
		RecordingBroadcaster recorder = new RecordingBroadcaster();
		RecordingBroadcaster.newAlarmSystem(recorder);

		assertTrue(recorder.published("/panel"));
		assertEquals(NotificationPacket.NPACTION_NEW, recorder.lastPacket("/panel").action);
		assertEquals("", recorder.valueOf("/panel"));
		assertNull(recorder.valueOf("/no/such/topic"));
	}

	@Test
	void fakeSerialControllerRecordsWrites() {
		FakeSerialController serial = new FakeSerialController();
		serial.writeByte(SB2000Constants.ACK);
		serial.writeByte(SB2000Constants.NAK);
		serial.writeBytes("020204");

		assertEquals(Arrays.asList("ACK", "NAK", "020204"), serial.written());
	}
}
