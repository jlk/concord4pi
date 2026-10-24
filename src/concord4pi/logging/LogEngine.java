package concord4pi.logging;

import java.util.function.Supplier;

import org.tinylog.*;
//import org.tinylog.configuration.Configuration;
//import org.tinylog.writers.*;

import concord4pi.config.Config;

public class LogEngine {

	//only the caller's frame is needed, so skip this class' own frames and stop there
	private static final StackWalker STACK_WALKER = StackWalker.getInstance();
	private static final int CALLER_DEPTH = 2;	//0: callingMethod(), 1: the log method, 2: the caller

	public LogEngine(Config appConfig) {
		log("Logger Initialized", Level.INFO);
	}

	//true when the level would actually be written.  Package-private, not public API: it's
	//here for the log methods and for tests, in the same spirit as the other test seams.
	//Use it to guard a log statement whose message is awkward to express as a Supplier.
	boolean isEnabled(Level level) {
		switch(level) {
			case TRACE: return Logger.isTraceEnabled();
			case DEBUG: return Logger.isDebugEnabled();
			case INFO:  return Logger.isInfoEnabled();
			case WARN:  return Logger.isWarnEnabled();
			case ERROR: return Logger.isErrorEnabled();
			default:    return false;
		}
	}

	public void log(String message, Level level) {
		if(!isEnabled(level)) { return; }
		write(message, level, callingMethod());
	}

	//lazy variant for hot paths: the message is only built when the level is enabled
	public void log(Supplier<String> message, Level level) {
		if(!isEnabled(level)) { return; }
		write(build(message), level, callingMethod());
	}
	
	//Building a message is deferred now, so a getter that throws (see "Known defects" for the
	//ones that do) would take the caller down only when its level happens to be enabled.
	//Logging shouldn't decide whether the app survives, so report the failure and carry on.
	private String build(Supplier<String> message) {
		try {
			return message.get();
		}
		catch(RuntimeException thrown) {
			return "<log message could not be built: " + thrown + ">";
		}
	}

	public void logObject(Object messageObject, Level level) {
		if(!isEnabled(level)) { return; }
		write(messageObject, level, callingMethod());
	}

	private String callingMethod() {
		return STACK_WALKER.walk(frames -> frames
				.skip(CALLER_DEPTH)
				.findFirst()
				.map(frame -> frame.getClassName() + "." + frame.getMethodName())
				.orElse("unknown"));
	}

	private void write(Object message, Level level, String callingMethod) {
		String text;
		try {
			text = callingMethod + ": " + message;
		}
		catch(RuntimeException thrown) {
			//same reasoning as build(): a toString() that throws must not kill the caller
			text = callingMethod + ": <log message could not be built: " + thrown + ">";
		}
		switch(level) {
			case TRACE: Logger.trace(text); break;
			case DEBUG: Logger.debug(text); break;
			case INFO:  Logger.info(text);  break;
			case WARN:  Logger.warn(text);  break;
			case ERROR: Logger.error(text); break;
			default: break;
		}
	}

}
