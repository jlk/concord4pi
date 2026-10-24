package concord4pi.SB2000;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.tinylog.Level;

import concord4pi.testsupport.RecordingLogEngine;

/**
 * openSerialPort() only: setupSerialPort() exits the JVM when the port won't open, so it
 * can't run here. These use device names that can't be opened on any machine.
 */
class SerialControllerTest {

	private final RecordingLogEngine logger = new RecordingLogEngine();

	private boolean open(String deviceName) {
		return assertDoesNotThrow(() -> new SerialController(logger, deviceName).openSerialPort());
	}

	@Test
	void theCommittedConfigPlaceholderIsReportedNotThrown() {
		//config/concord4pi.config ships with this
		assertFalse(open("[serial device name]"));

		assertTrue(logger.logged(Level.ERROR, "Serial Port ([serial device name]) could not be opened"), logger.entries().toString());
	}

	@Test
	void aDeviceThatDoesNotExistIsReportedNotThrown() {
		assertFalse(open("/dev/concord4pi-no-such-device"));

		assertTrue(logger.logged(Level.ERROR, "Serial Port (/dev/concord4pi-no-such-device) could not be opened"), logger.entries().toString());
	}

	@Test
	void anEmptyDeviceNameIsReportedNotThrown() {
		assertFalse(open(""));

		assertTrue(logger.logged(Level.ERROR, "could not be opened"), logger.entries().toString());
	}
}
