package concord4pi.SB2000;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import concord4pi.testsupport.Messages;

class IOMessageTest {

	private static IOMessage parse(String asciiHex) {
		return new IOMessage(asciiHex.getBytes());
	}

	@Test
	void parsesSingleByteCommand() {
		IOMessage message = Messages.build("21", "0001000500");

		assertTrue(message.isValid());
		assertEquals("21", message.getCommandCodeString());
		assertEquals("0001000500", message.getDataString());
		assertEquals(7, message.getMessageLength());
		assertEquals("07", message.getMessageLengthString());
	}

	@Test
	void parsesTwoByteCommandWith22Prefix() {
		IOMessage message = Messages.build("2201", "000100F001");

		assertTrue(message.isValid());
		assertEquals("2201", message.getCommandCodeString());
		assertEquals("000100F001", message.getDataString());
	}

	@Test
	void parsesTwoByteCommandWith23Prefix() {
		IOMessage message = Messages.build("2303", "0001000502");

		assertTrue(message.isValid());
		assertEquals("2303", message.getCommandCodeString());
		assertEquals("0001000502", message.getDataString());
	}

	@Test
	void commandWithNoDataHasEmptyPayload() {
		IOMessage message = new IOMessage(SB2000Constants.FULLCOMMAND_FullEquipmentList);

		assertTrue(message.isValid());
		assertEquals("02", message.getCommandCodeString());
		assertEquals("", message.getDataString());
		assertEquals(0x04, message.getMessageCheckSum());
	}

	@Test
	void fullCommandConstantsAreValid() {
		assertTrue(new IOMessage(SB2000Constants.FULLCOMMAND_FullEquipmentList).isValid());

		IOMessage refresh = new IOMessage(SB2000Constants.FULLCOMMAND_DynamicDataRefresh);
		assertTrue(refresh.isValid());
		assertEquals("20", refresh.getCommandCodeString());
	}

	@Test
	void checksumWrapsPast255AndHandlesHighBitBytes() {
		//byte sum is well over 255 and includes bytes that are negative as Java bytes
		IOMessage message = Messages.build("21", "FFFF80FE");

		assertTrue(message.isValid());
		assertEquals(message.getCalcCheckSum(), message.getMessageCheckSum());
	}

	@Test
	void corruptedChecksumIsInvalid() {
		assertFalse(parse(Messages.frameWithBadChecksum("21", "0001000500")).isValid());
	}

	@Test
	void declaredLengthTooLongIsInvalid() {
		IOMessage message = parse("030204");

		assertFalse(message.isValid());
		assertEquals("", message.getCommandCodeString());
		assertEquals("", message.getDataString());
	}

	@Test
	void declaredLengthTooShortIsInvalid() {
		assertFalse(parse("01020403").isValid());
	}

	@Test
	void oddNumberOfCharactersIsInvalidWithoutThrowing() {
		IOMessage message = assertDoesNotThrow(() -> parse("02020"));
		assertFalse(message.isValid());
	}

	@Test
	void nonHexCharactersAreInvalidWithoutThrowing() {
		IOMessage message = assertDoesNotThrow(() -> parse("ZZ0204"));
		assertFalse(message.isValid());
	}

	@Test
	void emptyInputIsInvalidWithoutThrowing() {
		IOMessage message = assertDoesNotThrow(() -> parse(""));
		assertFalse(message.isValid());
	}

	@Test
	void lowercaseHexIsAccepted() {
		assertTrue(parse(Messages.frame("2201", "000100F001").toLowerCase()).isValid());
	}

	@Test
	void toStringRoundTripsAsUppercaseHex() {
		String frame = Messages.frame("2209", "0001000A0B0C");

		assertEquals(frame, parse(frame).toString());
		assertEquals(frame, parse(frame.toLowerCase()).toString());
	}

	@Test
	void zeroLengthMessageIsInvalidWithoutThrowing() {
		//messageLength 0 equals encodedMessage.length - 1, so initMessage treats the length as
		//valid and then reads encodedMessage[1] for the command code
		IOMessage message = assertDoesNotThrow(() -> parse("00"));

		assertFalse(message.isValid());
		assertEquals("", message.getCommandCodeString());
	}

	@Test
	void summaryNamesTheCommandAndSizeButNotTheData() {
		//shaped like 09 user data, whose payload appears to hold user codes in BCD
		IOMessage message = Messages.build("09", "0001" + "00F0" + "123456");

		assertEquals("[command 09, 7 data bytes]", message.getSummary());
		assertFalse(message.getSummary().contains("123456"));
	}

	@Test
	void summaryOfATwoByteCommand() {
		assertEquals("[command 2303, 5 data bytes]", Messages.build("2303", "0001000501").getSummary());
	}

	@Test
	void summaryOfAnInvalidMessageHasNoCommand() {
		assertEquals("[command , 0 data bytes]", parse("ZZ").getSummary());
	}

	@Test
	void isNotAControlMessage() {
		assertFalse(Messages.build("21", "0001000500").isControlMessage());
	}

	@Test
	void retransmitAllowedForFiveAttemptsThenRefused() {
		IOMessage message = Messages.build("21", "0001000500");
		assertTrue(message.isReTransmittable());

		for(int i = 1; i <= 5; i++) {
			message.incrementTransmissionCount();
			assertTrue(message.isReTransmittable(), "after " + i + " retransmits");
		}

		message.incrementTransmissionCount();
		assertFalse(message.isReTransmittable());
	}
}
