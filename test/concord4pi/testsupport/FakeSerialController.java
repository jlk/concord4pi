package concord4pi.testsupport;

import java.util.ArrayList;
import java.util.List;

import com.fazecast.jSerialComm.SerialPortDataListener;

import concord4pi.SB2000.SB2000Constants;
import concord4pi.SB2000.SerialController;
import concord4pi.logging.LogEngine;

/**
 * A SerialController that never opens a port and records what would have been written.
 * Each write is one entry: "ACK" or "NAK" for control bytes, otherwise the message text.
 * The MESSAGE_START byte that the real writeBytes() prepends is not recorded.
 */
public class FakeSerialController extends SerialController {

	private final List<String> written = new ArrayList<String>();

	public FakeSerialController() {
		super(new LogEngine(null));
	}

	public List<String> written() {
		return written;
	}

	public void clear() {
		written.clear();
	}

	@Override
	public boolean writeByte(byte byteToSend) {
		if(byteToSend == SB2000Constants.ACK) {
			written.add("ACK");
		}
		else if(byteToSend == SB2000Constants.NAK) {
			written.add("NAK");
		}
		else {
			written.add(String.format("%02X", byteToSend));
		}
		return true;
	}

	@Override
	public boolean writeBytes(byte[] message) {
		written.add(new String(message));
		return true;
	}

	@Override
	public void addDataListener(SerialPortDataListener newListener) {
	}

	@Override
	public void removeListener() {
	}

	@Override
	public void closePort() {
	}
}
