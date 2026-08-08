package com.apliman.cvevaluator.redaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    @Nested
    @DisplayName("phone numbers")
    class Phones {

        /**
         * Every way a Lebanese number is actually written on a CV. Mobiles split
         * into two conventions - 03 keeps its leading zero, the newer 70/71/76/
         * 78/79/81 prefixes are written without one - and both appear here
         * because both appear in the corpus.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "+961 3 123 456",
                "+961 71 123 456",
                "+9613123456",
                "+961-76-123-456",
                "00961 79 123 456",
                "00961 3 123 456",
                "+961 03 123 456",
                "03 123 456",
                "03123456",
                "03.123.456",
                "70 123 456",
                "71-123-456",
                "76 123 456",
                "78 123 456",
                "79 123 456",
                "81 123 456",
                "01 234 567",
                "+1 555 123 4567",
                "+44 20 7946 0958"
        })
        void isRedacted(String number) {
            String redacted = redactor.redact("Call me on " + number + " any time.");

            assertTrue(redacted.contains(PiiRedactor.PHONE_TOKEN),
                    () -> "expected a phone token in: " + redacted);
            assertFalse(redacted.contains(number),
                    () -> "the original number survived in: " + redacted);
        }

        /**
         * The reason the pattern does not treat "/" as a separator. Employment
         * date ranges are the single most common eight-digit-adjacent thing on a
         * CV, and a phone pattern that eats them destroys the candidate's
         * timeline - which is exactly what the rubric scores.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "01/2019 - 06/2023",
                "Employed 03/2020 until 09/2024",
                "2019-2023",
                "Java 21 and Spring Boot 4.1",
                "Increased throughput by 70 percent",
                "Section 03 of the standard"
        })
        void isLeftAlone(String text) {
            assertEquals(text, redactor.redact(text));
        }

        /**
         * A national ID is a long digit run whose leading digits happen to look
         * like a mobile prefix. The lookarounds exist so it is either matched
         * whole or not at all - never chopped into a fake phone number plus a
         * leftover digit, which would be the worst outcome: PII partly leaked
         * and the text corrupted.
         */
        @Test
        void doesNotChopALongerDigitRun() {
            assertEquals("ID 7112345678 issued", redactor.redact("ID 7112345678 issued"));
        }
    }

    @Nested
    @DisplayName("emails and links")
    class EmailsAndLinks {

        @ParameterizedTest
        @ValueSource(strings = {
                "adam@example.com",
                "adam.al-khatib256@gmail.com",
                "a.b+tag@sub.domain.co.uk"
        })
        void emailIsRedacted(String email) {
            String redacted = redactor.redact("Reach me at " + email + " please.");

            assertTrue(redacted.contains(PiiRedactor.EMAIL_TOKEN), redacted);
            assertFalse(redacted.contains(email), redacted);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://github.com/adam",
                "http://portfolio.example.org/work",
                "www.mysite.io",
                "linkedin.com/in/adam-al-khatib"
        })
        void urlIsRedacted(String url) {
            String redacted = redactor.redact("Portfolio: " + url + " and more");

            assertTrue(redacted.contains(PiiRedactor.URL_TOKEN), redacted);
            assertFalse(redacted.contains(url), redacted);
        }

        /**
         * Bare domains are only redacted when they carry a path. Without that
         * rule the skills section of every JavaScript CV loses half its
         * contents.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "Node.js, React.js and Vue.js",
                "Built on ASP.NET and .NET Core",
                "Config lives in application.properties"
        })
        void bareTechnologyNamesSurvive(String text) {
            assertEquals(text, redactor.redact(text));
        }

        /**
         * Pins the ordering. If the URL pass ran first its domain matching would
         * claim "example.com" out of the address and leave "adam@" behind -
         * dropping the half that carries no information and keeping the half
         * that identifies the candidate.
         */
        @Test
        void emailIsNotHalfEatenByTheUrlPass() {
            String redacted = redactor.redact("adam@example.com");

            assertEquals(PiiRedactor.EMAIL_TOKEN, redacted);
        }

        @Test
        void sentencePunctuationSurvivesAUrl() {
            assertEquals("See " + PiiRedactor.URL_TOKEN + ".", redactor.redact("See www.example.com."));
        }
    }

    @Test
    void redactsEveryKindAtOnceAndLeavesTheRestIntact() {
        String cv = """
                Adam Al Khatib
                Beirut, Lebanon
                adam@example.com | 03 123 456 | linkedin.com/in/adam
                Backend engineer, 4 years of Java and Spring Boot.
                """;

        String redacted = redactor.redact(cv);

        assertFalse(redacted.contains("adam@example.com"), redacted);
        assertFalse(redacted.contains("03 123 456"), redacted);
        assertFalse(redacted.contains("linkedin.com/in/adam"), redacted);

        // The parts the rubric actually scores are untouched. Redaction that
        // quietly removed the name or the experience line would make the
        // assessment worthless while still "passing" a PII check.
        assertTrue(redacted.contains("Adam Al Khatib"), redacted);
        assertTrue(redacted.contains("Beirut, Lebanon"), redacted);
        assertTrue(redacted.contains("Backend engineer, 4 years of Java and Spring Boot."), redacted);
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertEquals(null, redactor.redact(null));
        assertEquals("", redactor.redact(""));
    }
}
