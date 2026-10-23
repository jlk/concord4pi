package concord4pi.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import concord4pi.testsupport.RecordingBroadcaster;

class AlarmPanelTest {

	private RecordingBroadcaster recorder;
	private AlarmPanel panel;

	@BeforeEach
	void setUp() {
		recorder = new RecordingBroadcaster();
		panel = RecordingBroadcaster.newAlarmSystem(recorder).getPanel();
	}

	@Test
	void panelTypeNames() {
		assertEquals("Concord", panel.getPanelTypeText("14"));
		assertEquals("Express", panel.getPanelTypeText("0B"));
		assertEquals("Express 4", panel.getPanelTypeText("1E"));
		assertEquals("Euro", panel.getPanelTypeText("0E"));
		assertEquals("UNKNOWN", panel.getPanelTypeText("99"));
	}

	@Test
	void updatePanelDataPublishesEveryField() {
		panel.updatePanelData("14", "1234", "5678", "00ABCDEF");

		assertEquals("14", recorder.valueOf("/panel/type"));
		assertEquals("Concord", recorder.valueOf("/panel/name"));
		assertEquals("1234", recorder.valueOf("/panel/hwrev"));
		assertEquals("5678", recorder.valueOf("/panel/swrev"));
		assertEquals("00ABCDEF", recorder.valueOf("/panel/serial"));
	}

	@Test
	void serialNumberIsTheID() {
		panel.updatePanelData("14", "1234", "5678", "00ABCDEF");

		assertEquals("00ABCDEF", panel.getID());
		assertEquals("Concord Panel of Type [14] with HW Rev 57-1234 and SW Rev 75-5678 and Serial Number [00ABCDEF]",
				panel.toString());
	}
}
