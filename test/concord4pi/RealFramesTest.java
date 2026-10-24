package concord4pi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import concord4pi.SB2000.IOMessage;
import concord4pi.data.AlarmArmingLevel;
import concord4pi.data.AlarmSystemData;
import concord4pi.logging.LogEngine;
import concord4pi.testsupport.RecordingBroadcaster;

/**
 * Regression tests using frames captured from a real Concord panel (debug log, 2026-10-23).
 * Frames are verbatim except that only non-identifying zone names were kept. User data (09)
 * frames were deliberately left out because they carry user codes.
 * This panel reports its partition as "0100" (partition 01, area 00).
 */
class RealFramesTest {

	private static final String PARTITION = "/partitionarea/0100";

	//equipment list responses
	private static final String ZONE_FRONT_DOOR = "0B0301000A000100006E57DF";
	private static final String ZONE_FIRE_ALARM = "100301001A0008000068111C11221D2B46";
	private static final String PARTITION_OFF = "05040100010B";
	private static final String SIREN_SETUP = "0A2204010001AA8000005C";

	//arming away, then stay, then disarm, with the delay messages that followed each
	private static final String ARM_AWAY = "082201010000FF032E";
	private static final String EXIT_DELAY_60S = "082203010040003CAA";
	private static final String ARM_STAY = "082201010000FF022D";
	private static final String EXIT_DELAY_1S = "08220301004000016F";
	private static final String EXIT_DELAY_END = "0822030100C00000EE";
	private static final String DISARM = "082201010000FF012C";
	private static final String DELAY_END_AFTER_DISARM = "0822030100800000AE";

	private static final String ZONE_18_TRIPPED_BYPASSED = "0721010000181152";
	private static final String EVENT_ZONE_18_BYPASS = "0D2202010002000018080100FF54";
	private static final String EVENT_ARMING_PROTEST = "0D220201000057E6760D0F000304";
	private static final String SIREN_SYNC = "0322052A";
	private static final String SIREN_GO = "0322062B";
	private static final String SIREN_STOP = "05220B010033";
	private static final String TOUCHPAD_DATE = "1F2209010001FE2F1622192B1F13242B0203FB00030D04002B201D2B2001FB39";

	//command codes MessageProcessor has no handler for (see CLAUDE.md "Known defects")
	private static final String[] UNHANDLED = {
		"0805010059C5150041",            //05
		"070659C515110051",              //06
		"0A07004100000001020156",        //07
		"02080A",                        //08
		"0A0A000000000000000014",        //0A
		"0D0C01000000000000000000001A",  //0C
		"072301010002002E",              //2301
	};

	private RecordingBroadcaster recorder;
	private AlarmSystemData system;
	private MessageProcessor processor;

	@BeforeEach
	void setUp() {
		recorder = new RecordingBroadcaster();
		system = RecordingBroadcaster.newAlarmSystem(recorder);
		processor = new MessageProcessor(system, new LogEngine(null));
	}

	private ArrayList<IOMessage> process(String frame) {
		IOMessage message = new IOMessage(frame.getBytes());
		assertTrue(message.isValid(), "captured frame should be valid: " + frame);
		return processor.processMessage(message);
	}

	private AlarmArmingLevel armingLevel() {
		return system.getAreaPartitionByID("0100").getArmingLevel();
	}

	@Test
	void zoneData() {
		process(ZONE_FRONT_DOOR);

		assertTrue(recorder.published(PARTITION + "/zones/0001/group/10"));
		assertEquals("Hardwired", recorder.valueOf(PARTITION + "/zones/0001/type/text"));
		assertEquals("NONE", recorder.valueOf(PARTITION + "/zones/0001/status/text"));
	}

	@Test
	@Disabled("Known bug: word tokens are joined with no space, so FRONT|DOOR becomes FRONTDOOR")
	void zoneNameWordsAreSeparated() {
		process(ZONE_FRONT_DOOR);
		process(ZONE_FIRE_ALARM);

		assertEquals("FRONT DOOR", recorder.valueOf(PARTITION + "/zones/0001/text").trim());
		//word token FIRE, then the letters A L A R M and a space token
		assertEquals("FIRE ALARM", recorder.valueOf(PARTITION + "/zones/0008/text").trim());
	}

	@Test
	void partitionData() {
		process(PARTITION_OFF);

		assertTrue(system.getAreaPartitionByID("0100").isPartition());
		assertEquals("Off", recorder.valueOf(PARTITION + "/armingLevel/text"));
		//this panel sends no partition text, so there's nothing to publish
		assertFalse(recorder.published(PARTITION + "/text"));
	}

	@Test
	void sirenSetup() {
		process(SIREN_SETUP);

		assertEquals("1", recorder.valueOf(PARTITION + "/siren/repetitionCount"));
		assertEquals("10101010100000000000000000000000", recorder.valueOf(PARTITION + "/siren/cadence/binary"));
	}

	@Test
	void armingSequence() {
		process(ARM_AWAY);
		assertEquals("Away", recorder.valueOf(PARTITION + "/armingLevel/text"));
		assertEquals("00FF", recorder.valueOf(PARTITION + "/armingLevel/user"));

		process(ARM_STAY);
		assertEquals("Stay", recorder.valueOf(PARTITION + "/armingLevel/text"));

		process(DISARM);
		assertEquals("Off", recorder.valueOf(PARTITION + "/armingLevel/text"));
	}

	@Test
	void exitDelayTime() {
		process(EXIT_DELAY_60S);
		assertEquals("60", recorder.valueOf(PARTITION + "/armingLevel/delayTime"));

		process(EXIT_DELAY_1S);
		assertEquals("1", recorder.valueOf(PARTITION + "/armingLevel/delayTime"));
	}

	@Test
	void delayFlagsMatchWhatThePanelSends() {
		process(ARM_AWAY);
		process(EXIT_DELAY_60S);
		assertTrue(armingLevel().getDelayFlagsText().contains("EXIT DELAY"));
		assertTrue(armingLevel().getDelayFlagsText().contains("START DELAY"));

		process(EXIT_DELAY_END);
		assertTrue(armingLevel().getDelayFlagsText().contains("EXIT DELAY"));
		assertTrue(armingLevel().getDelayFlagsText().contains("END DELAY"));

		process(DISARM);
		process(DELAY_END_AFTER_DISARM);
		assertTrue(armingLevel().getDelayFlagsText().contains("END DELAY"));
	}

	@Test
	void zoneStatus() {
		process(ZONE_18_TRIPPED_BYPASSED);

		assertEquals("TRIPPED|BYPASSED", recorder.valueOf(PARTITION + "/zones/0018/status/text"));
	}

	@Test
	void zoneBypassEvent() {
		process(EVENT_ZONE_18_BYPASS);

		assertEquals("Bypass", recorder.valueOf(PARTITION + "/event/general/text"));
		assertEquals("Zone", recorder.valueOf(PARTITION + "/event/source/text"));
		assertEquals("0018", recorder.valueOf(PARTITION + "/event/sourceNumber"));
	}

	@Test
	void armingProtestEventFromABusDevice() {
		process(EVENT_ARMING_PROTEST);

		assertEquals("Partition Event", recorder.valueOf(PARTITION + "/event/general/text"));
		assertEquals("Arming Protest Begun", recorder.valueOf(PARTITION + "/event/specific/text"));
		assertEquals("Bus Device", recorder.valueOf(PARTITION + "/event/source/text"));
		assertEquals("57E676", recorder.valueOf(PARTITION + "/event/sourceNumber"));
	}

	@Test
	void sirenSyncGoAndStop() {
		system.getAreaPartitionByID("0100");

		process(SIREN_SYNC);
		assertTrue(recorder.published(PARTITION + "/siren/sync"));

		process(SIREN_GO);
		assertEquals("true", recorder.valueOf(PARTITION + "/siren/state"));

		process(SIREN_STOP);
		assertEquals("false", recorder.valueOf(PARTITION + "/siren/state"));
	}

	@Test
	void touchpadDisplay() {
		process(TOUCHPAD_DATE);

		assertEquals("BROADCAST", recorder.valueOf(PARTITION + "/touchpad/broadcastFlag"));
		assertTrue(recorder.valueOf(PARTITION + "/touchpad/text").startsWith("*FRI OCT 23"));
	}

	@Test
	void touchpadLinesAreSeparated() {
		process(TOUCHPAD_DATE);

		assertFalse(recorder.valueOf(PARTITION + "/touchpad/text").contains("2303:40"));
	}

	@Test
	void touchpadTextIsOneLineWithNoTrailingSpace() {
		//FE at the start decodes to nothing; FB between the lines becomes a space, and the FB
		//that ends the second line doesn't leave a trailing one
		process(TOUCHPAD_DATE);

		assertEquals("*FRI OCT 23 03:40 PM P1", recorder.valueOf(PARTITION + "/touchpad/text"));
	}

	@Test
	void unhandledCommandsAreIgnoredWithoutError() {
		for(String frame : UNHANDLED) {
			recorder.clear();
			ArrayList<IOMessage> responses = assertDoesNotThrow(() -> process(frame), frame);

			assertTrue(responses.isEmpty(), frame);
			assertTrue(recorder.packets().isEmpty(), frame);
		}
	}
}
