package concord4pi.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.tinylog.Level;

/**
 * test/tinylog.properties keeps the writer at ERROR, so TRACE through WARN are
 * disabled here. That's what makes the "don't pay for disabled levels" contract
 * testable: the Supplier overload must not be invoked for those levels.
 */
class LogEngineTest {

	private final LogEngine logger = new LogEngine(null);

	@Test
	void supplierIsNotInvokedWhenLevelIsDisabled() {
		AtomicInteger invocations = new AtomicInteger();

		logger.log(() -> { invocations.incrementAndGet(); return "trace"; }, Level.TRACE);
		logger.log(() -> { invocations.incrementAndGet(); return "debug"; }, Level.DEBUG);
		logger.log(() -> { invocations.incrementAndGet(); return "info"; }, Level.INFO);
		logger.log(() -> { invocations.incrementAndGet(); return "warn"; }, Level.WARN);

		assertEquals(0, invocations.get(), "message supplier ran for a disabled level");
	}

	@Test
	void supplierIsInvokedWhenLevelIsEnabled() {
		AtomicInteger invocations = new AtomicInteger();

		capture(() -> logger.log(() -> { invocations.incrementAndGet(); return "error"; }, Level.ERROR));

		assertEquals(1, invocations.get());
	}

	@Test
	void isEnabledMatchesTheConfiguredWriterLevel() {
		assertFalse(logger.isEnabled(Level.TRACE));
		assertFalse(logger.isEnabled(Level.DEBUG));
		assertFalse(logger.isEnabled(Level.INFO));
		assertFalse(logger.isEnabled(Level.WARN));
		assertTrue(logger.isEnabled(Level.ERROR));
		assertFalse(logger.isEnabled(Level.OFF));
	}

	@Test
	void disabledLevelsAreAcceptedByEveryOverload() {
		assertDoesNotThrow(() -> {
			logger.log("plain", Level.TRACE);
			logger.logObject(new Object(), Level.DEBUG);
			logger.log(() -> "lazy", Level.INFO);
		});
	}

	@Test
	void aMessageSupplierThatThrowsDoesNotReachTheCaller() {
		//the lazy overload defers building the message, so without this a getter that throws
		//would take the caller down only when its level happened to be enabled
		assertDoesNotThrow(() -> logger.log(() -> { throw new NumberFormatException("boom"); }, Level.TRACE));

		String logged = capture(this::logThrowingSupplier);
		assertTrue(logged.contains("<log message could not be built:"), logged);
		assertTrue(logged.contains("boom"), logged);
	}

	@Test
	void anObjectWhoseToStringThrowsDoesNotReachTheCaller() {
		Object unprintable = new Object() {
			@Override
			public String toString() {
				throw new IllegalStateException("nope");
			}
		};

		assertDoesNotThrow(() -> logger.logObject(unprintable, Level.TRACE));
		assertTrue(capture(() -> logger.logObject(unprintable, Level.ERROR))
				.contains("<log message could not be built:"));
	}

	private void logThrowingSupplier() {
		logger.log(() -> { throw new NumberFormatException("boom"); }, Level.ERROR);
	}

	@Test
	void everyOverloadPrefixesTheMessageWithItsCaller() {
		assertEquals("concord4pi.logging.LogEngineTest.logPlain: x", capture(this::logPlain));
		assertEquals("concord4pi.logging.LogEngineTest.logLazy: x", capture(this::logLazy));
		assertEquals("concord4pi.logging.LogEngineTest.logObject: x", capture(this::logObject));
	}

	private void logPlain()  { logger.log("x", Level.ERROR); }
	private void logLazy()   { logger.log(() -> "x", Level.ERROR); }
	private void logObject() { logger.logObject("x", Level.ERROR); }

	/** returns the message part of the last line tinylog wrote while running the logging */
	private String capture(Runnable logging) {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			PrintStream sink = new PrintStream(captured, true);
			System.setOut(sink);
			System.setErr(sink);
			logging.run();
		}
		finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
		String[] lines = captured.toString().strip().split("\\R");
		String lastLine = lines[lines.length - 1].strip();
		//the default tinylog format prefixes the message with level and location
		int messageStart = lastLine.indexOf("concord4pi.logging.LogEngineTest.");
		return messageStart < 0 ? lastLine : lastLine.substring(messageStart);
	}
}
