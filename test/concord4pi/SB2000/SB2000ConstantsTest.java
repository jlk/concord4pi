package concord4pi.SB2000;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SB2000ConstantsTest {

	@Test
	void convertsDigitTokens() {
		assertEquals("0", SB2000Constants.convertTextToken("00"));
		assertEquals("9", SB2000Constants.convertTextToken("09"));
	}

	@Test
	void convertsPunctuationTokens() {
		assertEquals(".", SB2000Constants.convertTextToken("10"));
	}

	@Test
	void convertsLetterTokens() {
		assertEquals("P", SB2000Constants.convertTextToken("20"));
	}

	@Test
	void convertsWordTokens() {
		assertEquals("BREEZEWAY", SB2000Constants.convertTextToken("41"));
	}

	@Test
	void unknownTokenBecomesEmptyString() {
		assertEquals("", SB2000Constants.convertTextToken("ZZ"));
	}
}
