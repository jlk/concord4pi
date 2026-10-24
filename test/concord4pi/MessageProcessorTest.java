package concord4pi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import concord4pi.SB2000.IOMessage;
import concord4pi.data.AlarmAreaPartition;
import concord4pi.data.AlarmSystemData;
import concord4pi.data.AlarmZone;
import concord4pi.logging.LogEngine;
import concord4pi.testsupport.Messages;
import concord4pi.testsupport.RecordingBroadcaster;

/**
 * One test per handled command code. Data payloads are built from the field
 * offsets that MessageProcessor's handlers use, not from captured panel traffic.
 */
class MessageProcessorTest {

	private static final String PARTITION = "/partitionarea/0001";

	private RecordingBroadcaster recorder;
	private AlarmSystemData system;
	private MessageProcessor processor;

	@BeforeEach
	void setUp() {
		recorder = new RecordingBroadcaster();
		system = RecordingBroadcaster.newAlarmSystem(recorder);
		processor = new MessageProcessor(system, new LogEngine(null));
	}

	private ArrayList<IOMessage> process(String commandHex, String dataHex) {
		return processor.processMessage(Messages.build(commandHex, dataHex));
	}

	@Test
	void panelType01() {
		//type, hardware rev, software rev, serial number
		assertTrue(process("01", "14" + "0102" + "0304" + "00ABCDEF").isEmpty());

		assertEquals("14", recorder.valueOf("/panel/type"));
		assertEquals("Concord", recorder.valueOf("/panel/name"));
		assertEquals("0102", recorder.valueOf("/panel/hwrev"));
		assertEquals("0304", recorder.valueOf("/panel/swrev"));
		assertEquals("00ABCDEF", recorder.valueOf("/panel/serial"));
	}

	@Test
	void automationEventLost02RequestsARefresh() {
		ArrayList<IOMessage> responses = process("02", "");

		assertEquals(2, responses.size());
		assertEquals("020204", responses.get(0).toString());
		assertEquals("022022", responses.get(1).toString());
	}

	@Test
	void zoneData03() {
		//partition/area, group, zone, type, status, text tokens ("P", "1", ".")
		process("03", "0001" + "03" + "0005" + "01" + "02" + "200110");

		AlarmZone zone = system.getAreaPartitionByID("0001").getZoneByID("0005");
		assertEquals(3, zone.getGroup());
		assertEquals(1, zone.getType());
		assertEquals(2, zone.getStatus());
		assertEquals("P1.", zone.getText());
		assertEquals("P1.", recorder.valueOf(PARTITION + "/zones/0005/text"));
		assertEquals("FAULTED", recorder.valueOf(PARTITION + "/zones/0005/status/text"));
	}

	@Test
	void partitionData04SetsArmingLevel() {
		//partition/area, arming level, text tokens
		process("04", "0001" + "03" + "2001");

		assertEquals("3", recorder.valueOf(PARTITION + "/armingLevel"));
		assertEquals("Away/Full", recorder.valueOf(PARTITION + "/armingLevel/text"));
	}

	@Test
	void partitionData04StoresAndPublishesItsText() {
		//text tokens "P", "1"
		process("04", "0001" + "03" + "2001");

		assertEquals("P1", system.getAreaPartitionByID("0001").getText());
		assertEquals("P1", recorder.valueOf(PARTITION + "/text"));
	}

	@Test
	void partitionData04WithNoTextPublishesNoText() {
		//what this user's panel sends (see RealFramesTest): arming level only
		process("04", "0001" + "03");

		assertEquals("", system.getAreaPartitionByID("0001").getText());
		assertFalse(recorder.published(PARTITION + "/text"));
	}

	@Test
	void zoneStatus21() {
		//partition/area, zone, status
		process("21", "0001" + "0005" + "05");

		assertEquals("5", recorder.valueOf(PARTITION + "/zones/0005/status"));
		assertEquals("TRIPPED|ALARM", recorder.valueOf(PARTITION + "/zones/0005/status/text"));
	}

	@Test
	void armingLevel2201() {
		//partition/area, user, level
		process("2201", "0001" + "00F6" + "03");

		assertEquals("3", recorder.valueOf(PARTITION + "/armingLevel"));
		assertEquals("Away/Full", recorder.valueOf(PARTITION + "/armingLevel/text"));
		assertEquals("00F6", recorder.valueOf(PARTITION + "/armingLevel/user"));
	}

	@Test
	void alarmTrouble2202FromAZoneShortensTheSourceNumber() {
		//partition/area, source type, source number, general type, specific type, event data
		process("2202", "0001" + "02" + "000005" + "01" + "03" + "0000");

		assertEquals("0005", recorder.valueOf(PARTITION + "/event/sourceNumber"));
		assertEquals("Zone", recorder.valueOf(PARTITION + "/event/source/text"));
		assertEquals("Alarm", recorder.valueOf(PARTITION + "/event/general/text"));
		assertEquals("Police", recorder.valueOf(PARTITION + "/event/specific/text"));
		assertEquals("0000", recorder.valueOf(PARTITION + "/event/eventData"));
	}

	@Test
	void alarmTrouble2202FromANonZoneKeepsTheFullSourceNumber() {
		process("2202", "0001" + "03" + "000005" + "0F" + "00" + "1234");

		assertEquals("000005", recorder.valueOf(PARTITION + "/event/sourceNumber"));
		assertEquals("System", recorder.valueOf(PARTITION + "/event/source/text"));
		assertEquals("System Trouble", recorder.valueOf(PARTITION + "/event/general/text"));
	}

	@Test
	void entryExitDelay2203() {
		//partition/area, flags, 16-bit delay time (0x012C = 300)
		process("2203", "0001" + "20" + "012C");

		assertEquals("300", recorder.valueOf(PARTITION + "/armingLevel/delayTime"));
		assertEquals(0x20, system.getAreaPartitionByID("0001").getArmingLevel().getDelayFlags());
	}

	@Test
	void sirenSetup2204() {
		//partition/area, repetition count, cadence
		process("2204", "0001" + "04" + "80000001");

		assertEquals("4", recorder.valueOf(PARTITION + "/siren/repetitionCount"));
		assertEquals("" + 0x80000001, recorder.valueOf(PARTITION + "/siren/cadence"));
	}

	@Test
	void sirenSync2205ReachesEveryPartition() {
		system.getAreaPartitionByID("0001");
		system.getAreaPartitionByID("0002");
		process("2205", "");

		assertTrue(recorder.published(PARTITION + "/siren/sync"));
		assertTrue(recorder.published("/partitionarea/0002/siren/sync"));
	}

	@Test
	void sirenGo2206AndStop220B() {
		AlarmAreaPartition partition = system.getAreaPartitionByID("0001");

		process("2206", "");
		assertTrue(partition.getSiren().isSirenOn());
		assertEquals("true", recorder.valueOf(PARTITION + "/siren/state"));

		process("220B", "");
		assertFalse(partition.getSiren().isSirenOn());
		assertEquals("false", recorder.valueOf(PARTITION + "/siren/state"));
	}

	@Test
	void touchpadDisplay2209() {
		//partition/area, message type (1 = broadcast), text tokens ("P", "1")
		process("2209", "0001" + "01" + "2001");

		assertEquals("P1", recorder.valueOf(PARTITION + "/touchpad/text"));
		assertEquals("BROADCAST", recorder.valueOf(PARTITION + "/touchpad/broadcastFlag"));
	}

	@Test
	void featureState220C() {
		//partition/area, feature bits (0x08 = latchkey; values below 0x0A parse the same in base 10)
		process("220C", "0001" + "08");

		assertEquals("8", recorder.valueOf(PARTITION + "/featureSet"));
		assertEquals("LATCHKEY", recorder.valueOf(PARTITION + "/featureSet/text"));
	}

	@Test
	void featureState220CParsesHex() {
		process("220C", "0001" + "40");

		assertEquals("QUICK ARM", system.getAreaPartitionByID("0001").getFeatureSetText());
	}

	@Test
	void temperature220D() {
		//partition/area, temperature, low set point, high set point
		process("220D", "0001" + "46" + "41" + "4B");

		AlarmAreaPartition partition = system.getAreaPartitionByID("0001");
		assertEquals(70, partition.getTemperature().getTemperature());
		assertEquals(65, partition.getTemperature().getLSP());
		assertEquals(75, partition.getTemperature().getHSP());
	}

	@Test
	void timeAndDate220EPublishesADate() {
		//hour, minute, month, day, year
		process("220E", "0E" + "1E" + "0A" + "17" + "1A");

		assertNotNull(system.getDate());
		assertTrue(recorder.published("/date"));
	}

	private Calendar processDate(String hour, String minute, String month, String day, String year) {
		process("220E", hour + minute + month + day + year);
		Calendar date = Calendar.getInstance();
		date.setTime(system.getDate());
		return date;
	}

	@Test
	void timeAndDate220EHandlesTheFirstAndLastMonths() {
		Calendar january = processDate("00", "00", "01", "01", "1B");
		assertEquals(Calendar.JANUARY, january.get(Calendar.MONTH));
		assertEquals(1, january.get(Calendar.DAY_OF_MONTH));
		assertEquals(2027, january.get(Calendar.YEAR));

		Calendar december = processDate("17", "3B", "0C", "1F", "1A");
		assertEquals(Calendar.DECEMBER, december.get(Calendar.MONTH));
		assertEquals(31, december.get(Calendar.DAY_OF_MONTH));
		assertEquals(23, december.get(Calendar.HOUR_OF_DAY));
		assertEquals(59, december.get(Calendar.MINUTE));
	}

	@Test
	void timeAndDate220EHasNoSecondsOrMilliseconds() {
		//the panel sends minutes; the bridge's own clock shouldn't leak into the seconds
		Calendar date = processDate("0E", "1E", "0A", "17", "1A");

		assertEquals(0, date.get(Calendar.SECOND));
		assertEquals(0, date.get(Calendar.MILLISECOND));
	}

	@Test
	void timeAndDate220EPublishesThePanelsDate() {
		processDate("0E", "1E", "0A", "17", "1A");

		assertTrue(recorder.valueOf("/date").startsWith("Fri Oct 23 14:30:00"), recorder.valueOf("/date"));
	}

	@Test
	void timeAndDate220EProducesTheRightDate() {
		//14:30 on 23 October 2026
		process("220E", "0E" + "1E" + "0A" + "17" + "1A");

		Calendar date = Calendar.getInstance();
		date.setTime(system.getDate());
		assertEquals(2026, date.get(Calendar.YEAR));
		assertEquals(Calendar.OCTOBER, date.get(Calendar.MONTH));
		assertEquals(23, date.get(Calendar.DAY_OF_MONTH));
		assertEquals(14, date.get(Calendar.HOUR_OF_DAY));
		assertEquals(30, date.get(Calendar.MINUTE));
	}

	@Test
	void keyFob2303() {
		//partition/area, zone, command
		process("2303", "0001" + "0005" + "01");

		assertEquals("1", recorder.valueOf(PARTITION + "/zones/0005/keyFob/command"));
		assertEquals("ARM", recorder.valueOf(PARTITION + "/zones/0005/keyFob/command/text"));
	}

	@Test
	void unknownCommandIsIgnored() {
		recorder.clear();
		ArrayList<IOMessage> responses = assertDoesNotThrow(() -> process("99", "0001"));

		assertTrue(responses.isEmpty());
		assertTrue(recorder.packets().isEmpty());
	}

	@Test
	void zoneInventoryQueryIsTheFullEquipmentList() {
		ArrayList<IOMessage> query = processor.buildZoneInventoryQuery();

		assertEquals(1, query.size());
		assertEquals("020204", query.get(0).toString());
	}

	@Test
	void featureState220CWithAHexLetterDoesNotThrow() {
		//0x0C is NO DELAY + LATCHKEY. Base 10 can't parse "0C", and nothing above processMessage
		//catches the exception, so it would end MainController's loop
		assertDoesNotThrow(() -> process("220C", "0001" + "0C"));

		assertEquals("12", recorder.valueOf(PARTITION + "/featureSet"));
		assertEquals("NO DELAY|LATCHKEY", recorder.valueOf(PARTITION + "/featureSet/text"));
	}

	@Test
	void armingLevel2201WithAHexLetterDoesNotThrow() {
		assertDoesNotThrow(() -> process("2201", "0001" + "00FF" + "0A"));

		assertEquals("10", recorder.valueOf(PARTITION + "/armingLevel"));
		assertEquals("UNKNOWN", recorder.valueOf(PARTITION + "/armingLevel/text"));
	}

	@Test
	void partitionData04WithAHexLetterDoesNotThrow() {
		assertDoesNotThrow(() -> process("04", "0001" + "0A" + "2001"));

		assertEquals("10", recorder.valueOf(PARTITION + "/armingLevel"));
	}

	@Test
	void keyFob2303PublishesTheTextOfEachCommand() {
		process("2303", "0001" + "0005" + "01");
		process("2303", "0001" + "0005" + "00");

		assertEquals("0", recorder.valueOf(PARTITION + "/zones/0005/keyFob/command"));
		assertEquals("DISARM", recorder.valueOf(PARTITION + "/zones/0005/keyFob/command/text"));
	}

	@Test
	void shortPayloadDoesNotThrow() {
		//length and checksum are valid, but feature state needs 3 data bytes and gets 1
		ArrayList<IOMessage> responses = assertDoesNotThrow(() -> process("220C", "00"));

		assertTrue(responses.isEmpty());
	}

	@Test
	void shortPayloadForEveryHandlerDoesNotThrow() {
		String[] commands = { "01", "02", "03", "04", "21", "2201", "2202", "2203", "2204", "2205",
				"2206", "2209", "220B", "220C", "220D", "220E", "2303" };

		for(String command : commands) {
			assertDoesNotThrow(() -> process(command, ""), command);
			assertDoesNotThrow(() -> process(command, "00"), command);
		}
	}

	@Test
	void messagesAfterAFailedOneAreStillProcessed() {
		process("220C", "00");
		process("220C", "0001" + "08");

		assertEquals("LATCHKEY", recorder.valueOf(PARTITION + "/featureSet/text"));
	}
}
