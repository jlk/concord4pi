package concord4pi.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

	@TempDir
	Path tempDir;

	private Config configWith(String contents) throws IOException {
		Path file = tempDir.resolve("concord4pi.config");
		Files.write(file, contents.getBytes());
		return new Config(file.toString());
	}

	@Test
	void readsPropertiesFromTheGivenFile() throws IOException {
		Config config = configWith("SerialDevice=/dev/ttyUSB0\nMQTTBaseTopic=concord4pi\n");

		assertEquals("/dev/ttyUSB0", config.getSerialDeviceName());
		assertEquals("concord4pi", config.getProperty("MQTTBaseTopic"));
		assertTrue(config.valid());
	}

	@Test
	void missingSerialDeviceIsInvalid() throws IOException {
		assertFalse(configWith("MQTTBroadcaster=false\n").valid());
	}

	@Test
	void emptySerialDeviceIsInvalid() throws IOException {
		assertFalse(configWith("SerialDevice=\n").valid());
	}

	@Test
	void missingFileIsInvalid() {
		//prints a stack trace for the missing file; that's existing behavior
		Config config = new Config(tempDir.resolve("does-not-exist.config").toString());

		assertFalse(config.valid());
		assertNull(config.getSerialDeviceName());
	}

	@Test
	void missingPropertyIsNull() throws IOException {
		assertNull(configWith("SerialDevice=/dev/ttyUSB0\n").getProperty("MQTTBroadcaster"));
	}

	@Test
	void getPropertyIntParsesNumbers() throws IOException {
		Config config = configWith("Log_DefaultLevel=3\n");

		assertEquals(3, config.getPropertyInt("Log_DefaultLevel"));
		assertEquals(3, config.getDefaultLogLevel());
	}

	@Test
	void getPropertyIntOnMissingKeyNamesTheKey() throws IOException {
		Config config = configWith("SerialDevice=/dev/ttyUSB0\n");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> config.getPropertyInt("Log_DefaultLevel"));
		assertTrue(e.getMessage().contains("Log_DefaultLevel"));
	}

	@Test
	void getPropertyIntOnNonNumericValueThrows() throws IOException {
		Config config = configWith("Log_DefaultLevel=verbose\n");

		assertThrows(NumberFormatException.class, () -> config.getPropertyInt("Log_DefaultLevel"));
	}

	@Test
	void eachConfigReloadsFromItsOwnFile() throws IOException {
		configWith("SerialDevice=/dev/first\n");
		Path second = tempDir.resolve("second.config");
		Files.write(second, "SerialDevice=/dev/second\n".getBytes());

		assertEquals("/dev/second", new Config(second.toString()).getSerialDeviceName());
	}
}
