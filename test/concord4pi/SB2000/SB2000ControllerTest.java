package concord4pi.SB2000;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import concord4pi.logging.LogEngine;
import concord4pi.testsupport.FakeSerialController;
import concord4pi.testsupport.Messages;

/**
 * Drives one iteration of the controller's run loop at a time
 * (process incoming, then outgoing) without starting its thread.
 */
class SB2000ControllerTest {

	private static final String ACK = String.valueOf((char)SB2000Constants.ACK);
	private static final String NAK = String.valueOf((char)SB2000Constants.NAK);

	private FakeSerialController serial;
	private SB2000Controller controller;

	@BeforeEach
	void setUp() {
		serial = new FakeSerialController();
		controller = new SB2000Controller(serial, new LogEngine(null));
	}

	private void receive(String serialData) {
		controller.appendReceivedData(serialData.getBytes());
		loop();
	}

	private void loop() {
		controller.processIncomingMessages();
		controller.processOutgoingMessages();
	}

	@Test
	void validFrameIsQueuedAndAcked() {
		receive(Messages.wire("21", "0001000500"));

		assertTrue(controller.messageWaiting());
		IOMessage message = controller.getNextMessage();
		assertEquals("21", message.getCommandCodeString());
		assertEquals("0001000500", message.getDataString());
		assertFalse(controller.messageWaiting());
		assertEquals(Collections.singletonList("ACK"), serial.written());
	}

	@Test
	void badChecksumIsNakedAndNotQueued() {
		receive("\n" + Messages.frameWithBadChecksum("21", "0001000500"));

		assertFalse(controller.messageWaiting());
		assertEquals(Collections.singletonList("NAK"), serial.written());
	}

	@Test
	void twoFramesInOneChunkAreBothProcessedInOrder() {
		receive(Messages.wire("21", "0001000500") + Messages.wire("2201", "000100F001"));

		assertEquals("21", controller.getNextMessage().getCommandCodeString());
		assertEquals("2201", controller.getNextMessage().getCommandCodeString());
		assertFalse(controller.messageWaiting());
		assertEquals(Arrays.asList("ACK", "ACK"), serial.written());
	}

	@Test
	void frameSplitInsideLengthFieldWaitsForTheRest() {
		String wire = Messages.wire("21", "0001000500");

		receive(wire.substring(0, 2));
		assertFalse(controller.messageWaiting());
		assertTrue(serial.written().isEmpty());

		receive(wire.substring(2));
		assertEquals("21", controller.getNextMessage().getCommandCodeString());
		assertFalse(controller.messageWaiting());
		assertEquals(Collections.singletonList("ACK"), serial.written());
	}

	@Test
	void frameSplitInsideBodyWaitsForTheRest() {
		String wire = Messages.wire("2201", "000100F001");

		receive(wire.substring(0, 9));
		assertFalse(controller.messageWaiting());
		assertTrue(serial.written().isEmpty());

		receive(wire.substring(9));
		assertEquals("2201", controller.getNextMessage().getCommandCodeString());
		assertFalse(controller.messageWaiting());
		assertEquals(Collections.singletonList("ACK"), serial.written());
	}

	@Test
	void loopWithoutNewDataDoesNothing() {
		loop();

		assertFalse(controller.messageWaiting());
		assertTrue(serial.written().isEmpty());
	}

	@Test
	void outgoingMessageIsWrittenAsHex() {
		IOMessage outgoing = Messages.build("2201", "000100F001");
		controller.sendMessage(outgoing);
		loop();

		assertEquals(Collections.singletonList(outgoing.toString()), serial.written());
	}

	@Test
	void sendMessagesWritesEachInOrder() {
		IOMessage first = Messages.build("21", "0001000500");
		IOMessage second = Messages.build("2201", "000100F001");
		controller.sendMessages(new IOMessage[] { first, second });
		loop();

		assertEquals(Arrays.asList(first.toString(), second.toString()), serial.written());
	}

	@Test
	void nakRetransmitsTheUnacknowledgedMessage() {
		IOMessage outgoing = Messages.build("2201", "000100F001");
		controller.sendMessage(outgoing);
		loop();

		receive(NAK);

		assertEquals(Arrays.asList(outgoing.toString(), outgoing.toString()), serial.written());
	}

	@Test
	void ackClearsTheMessageSoALaterNakHasNothingToRetransmit() {
		IOMessage outgoing = Messages.build("2201", "000100F001");
		controller.sendMessage(outgoing);
		loop();

		receive(ACK);
		receive(NAK);

		assertEquals(Collections.singletonList(outgoing.toString()), serial.written());
	}

	@Test
	void messageIsDroppedAfterFiveRetransmits() {
		IOMessage outgoing = Messages.build("2201", "000100F001");
		controller.sendMessage(outgoing);
		loop();

		for(int i = 0; i < 7; i++) {
			receive(NAK);
		}

		//the original send plus 5 retransmits
		assertEquals(6, serial.written().size());
	}

	@Test
	void nakWithNothingToRetransmitIsIgnored() {
		assertDoesNotThrow(() -> receive(NAK));
		assertTrue(serial.written().isEmpty());
	}

	@Test
	void ackWithNothingOutstandingIsIgnored() {
		assertDoesNotThrow(() -> receive(ACK));
		assertTrue(serial.written().isEmpty());
	}

	@Test
	void zeroLengthFrameIsNakedWithoutThrowing() {
		//the panel would never send this, but line noise after a MESSAGE_START can produce it
		assertDoesNotThrow(() -> receive(String.valueOf((char)SB2000Constants.MESSAGE_START) + "00A"));

		assertFalse(controller.messageWaiting());
		assertEquals(Collections.singletonList("NAK"), serial.written());
	}

	@Test
	void leadingJunkIsSkippedWithoutStallingOrNaking() {
		receive("XY" + Messages.wire("21", "0001000500"));

		assertTrue(controller.messageWaiting());
		assertEquals(Collections.singletonList("ACK"), serial.written());
	}

	@Test
	void junkBetweenFramesIsSkipped() {
		receive(Messages.wire("21", "0001000500") + "XY" + Messages.wire("21", "0001000600"));

		assertEquals("0001000500", controller.getNextMessage().getDataString());
		assertEquals("0001000600", controller.getNextMessage().getDataString());
		assertEquals(Arrays.asList("ACK", "ACK"), serial.written());
	}

	@Test
	void junkOnItsOwnIsDroppedRatherThanNakedLater() {
		receive("XYZ");
		assertTrue(serial.written().isEmpty());

		receive(Messages.wire("21", "0001000500"));

		assertTrue(controller.messageWaiting());
		assertEquals(Collections.singletonList("ACK"), serial.written());
	}

	@Test
	void junkBeforeANakStillRetransmits() {
		IOMessage outgoing = Messages.build("2201", "000100F001");
		controller.sendMessage(outgoing);
		loop();

		receive("XY" + NAK);

		assertEquals(Arrays.asList(outgoing.toString(), outgoing.toString()), serial.written());
	}
}
