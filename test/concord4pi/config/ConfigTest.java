package concord4pi.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
	void describeListsEveryKeySortedWithItsValue() throws IOException {
		List<String> lines = configWith("SerialDevice=/dev/ttyUSB0\nMQTTBroadcaster=true\nMQTTBaseTopic=concord4pi\n").describe();

		assertEquals(List.of("MQTTBaseTopic=concord4pi", "MQTTBroadcaster=true", "SerialDevice=/dev/ttyUSB0"), lines);
	}

	@Test
	void describeHidesPasswords() throws IOException {
		List<String> lines = configWith("MQTTUsername=alarm\nMQTTPassword=s3cret\nOtherPASSWORDKey=x\n").describe();

		assertEquals(List.of("MQTTPassword=<hidden>", "MQTTUsername=alarm", "OtherPASSWORDKey=<hidden>"), lines);
		assertFalse(String.join("\n", lines).contains("s3cret"));
	}

	@Test
	void describeSaysWhenAPasswordIsEmpty() throws IOException {
		assertEquals(List.of("MQTTPassword=<empty>"), configWith("MQTTPassword=\n").describe());
	}

	@Test
	void describeHidesAPasswordInsideAUrl() throws IOException {
		List<String> lines = configWith("MQTTConnectionString=tcp://alarm:s3cret@broker:1883\n").describe();

		assertEquals(List.of("MQTTConnectionString=tcp://alarm:<hidden>@broker:1883"), lines);
	}

	@Test
	void describeLeavesAUrlWithoutAPasswordAlone() throws IOException {
		List<String> lines = configWith("MQTTConnectionString=tcp://broker:1883\n").describe();

		assertEquals(List.of("MQTTConnectionString=tcp://broker:1883"), lines);
	}

	@Test
	void missingBooleanPropertyIsFalse() throws IOException {
		//MainController.addBroadcasters() reads MQTTBroadcaster and MQTTEnableCommands this way
		assertFalse(configWith("SerialDevice=/dev/ttyUSB0\n").getPropertyBoolean("MQTTBroadcaster"));
	}

	@Test
	void booleanPropertyIsTrueOnlyForTrue() throws IOException {
		Config config = configWith("SerialDevice=/dev/ttyUSB0\nA=true\nB=false\nC=yes\nD=\n");

		assertTrue(config.getPropertyBoolean("A"));
		assertFalse(config.getPropertyBoolean("B"));
		assertFalse(config.getPropertyBoolean("C"));
		assertFalse(config.getPropertyBoolean("D"));
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
