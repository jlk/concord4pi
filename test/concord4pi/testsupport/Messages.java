package concord4pi.testsupport;

import concord4pi.SB2000.IOMessage;
import concord4pi.SB2000.SB2000Constants;

/**
 * Builds SB2000 message fixtures from a command code and data payload, both as
 * ASCII hex, filling in the length and checksum bytes.
 */
public final class Messages {

	private Messages() {
	}

	/** A valid IOMessage, e.g. build("2201", "000100F001"). */
	public static IOMessage build(String commandHex, String dataHex) {
		return new IOMessage(frame(commandHex, dataHex).getBytes());
	}

	/** The ASCII hex frame (length + command + data + checksum), without MESSAGE_START. */
	public static String frame(String commandHex, String dataHex) {
		String body = lengthHex(commandHex, dataHex) + commandHex + dataHex;
		return (body + toHex(checksum(body))).toUpperCase();
	}

	/** Like frame(), but with the checksum off by one so the message is invalid. */
	public static String frameWithBadChecksum(String commandHex, String dataHex) {
		String body = lengthHex(commandHex, dataHex) + commandHex + dataHex;
		return (body + toHex(checksum(body) + 1)).toUpperCase();
	}

	/** frame() prefixed with MESSAGE_START, as the panel sends it over the serial line. */
	public static String wire(String commandHex, String dataHex) {
		return (char)SB2000Constants.MESSAGE_START + frame(commandHex, dataHex);
	}

	//the length byte counts itself, the command and the data, but not the checksum
	private static String lengthHex(String commandHex, String dataHex) {
		return toHex(1 + (commandHex.length() + dataHex.length()) / 2);
	}

	private static int checksum(String hex) {
		int sum = 0;
		for(int i = 0; i < hex.length(); i += 2) {
			sum += Integer.parseInt(hex.substring(i, i + 2), 16);
		}
		return sum;
	}

	private static String toHex(int value) {
		return String.format("%02X", value & 0xFF);
	}
}
