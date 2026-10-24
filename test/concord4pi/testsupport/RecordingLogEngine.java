package concord4pi.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.tinylog.Level;

import concord4pi.logging.LogEngine;

/**
 * A LogEngine that records every call with its level instead of writing it, so tests can
 * check what is logged at levels test/tinylog.properties turns off. Supplier messages are
 * built when they're logged, at every level.
 */
public class RecordingLogEngine extends LogEngine {

	/** One log call. */
	public static class Entry {
		public final Level level;
		public final String message;

		Entry(Level level, String message) {
			this.level = level;
			this.message = message;
		}

		@Override
		public String toString() {
			return level + ": " + message;
		}
	}

	//no initializer: LogEngine's constructor logs before this class' fields would be set
	private List<Entry> entries;

	public RecordingLogEngine() {
		super(null);
	}

	private synchronized void record(Level level, String message) {
		if(entries == null) {
			entries = new ArrayList<Entry>();
		}
		entries.add(new Entry(level, message));
	}

	@Override
	public void log(String message, Level level) {
		record(level, message);
	}

	@Override
	public void log(Supplier<String> message, Level level) {
		record(level, message.get());
	}

	@Override
	public void logObject(Object messageObject, Level level) {
		record(level, String.valueOf(messageObject));
	}

	public synchronized List<Entry> entries() {
		return entries == null ? new ArrayList<Entry>() : new ArrayList<Entry>(entries);
	}

	/** True if something was logged at exactly this level with a message containing the text. */
	public synchronized boolean logged(Level level, String text) {
		for(Entry entry : entries()) {
			if(entry.level == level && entry.message.contains(text)) {
				return true;
			}
		}
		return false;
	}
}
