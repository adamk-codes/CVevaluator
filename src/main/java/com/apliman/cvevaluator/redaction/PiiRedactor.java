package com.apliman.cvevaluator.redaction;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips direct contact details out of CV text before it leaves the process for
 * an embedding or chat call.
 *
 * <p>This is not anonymisation and does not pretend to be. Names, employers and
 * universities stay, because removing them would remove the thing the rubric is
 * meant to judge. What goes is the set of identifiers that are useless for
 * assessment and actively harmful to leak: how to email, call or look up this
 * person.
 *
 * <p>Each match is replaced with a token rather than deleted. {@code [EMAIL]}
 * keeps the sentence a sentence; an empty string turns "Contact me at
 * adam@x.com or 03 123 456" into something with a hole in it, and models
 * hallucinate into holes.
 */
@Component
public class PiiRedactor {

    public static final String EMAIL_TOKEN = "[EMAIL]";
    public static final String PHONE_TOKEN = "[PHONE]";
    public static final String URL_TOKEN = "[URL]";

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /**
     * Two shapes only: something with an explicit scheme or {@code www.}, and a
     * bare domain that carries a path.
     *
     * <p>The path requirement on the second shape is what keeps {@code Node.js},
     * {@code React.js}, {@code ASP.NET} and {@code .NET Core} intact — all of
     * which are bare domain-looking tokens that belong in a skills section. A
     * profile link is {@code linkedin.com/in/someone}; a framework is not
     * followed by a slash.
     *
     * <p>The trailing lookbehind backs the match off sentence punctuation, so
     * "see www.example.com." keeps its full stop instead of swallowing it into
     * the token.
     */
    private static final Pattern URL = Pattern.compile(
            "(?:https?://|www\\.)[^\\s<>\"'()\\[\\]]+(?<![.,;:!?])"
                    + "|\\b[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*"
                    + "\\.(?:com|net|org|io|dev|me|co|ai|edu|gov|lb)"
                    + "/[^\\s<>\"'()\\[\\]]*(?<![.,;:!?])",
            Pattern.CASE_INSENSITIVE);

    /**
     * Lebanese numbers first, then a generic international fallback.
     *
     * <p>Lebanese numbers are eight digits however they are written. Mobiles
     * split into two conventions and both are here: {@code 03 123 456} carries
     * its leading zero, while {@code 70/71/76/78/79/81 123 456} conventionally
     * does not. Landlines ({@code 01 234 567} and the other area codes) share
     * the leading-zero shape, so one branch covers them.
     *
     * <p>Separators are space, dot and hyphen — deliberately <em>not</em> the
     * forward slash. That single omission is what stops the pattern eating
     * {@code 01/2019 - 06/2023}, which is how half the CVs in the corpus write
     * employment dates.
     *
     * <p>The lookaround pair is the other guard: {@code (?<![\dA-Za-z])} and
     * {@code (?!\d)} mean the pattern only fires on a whole run of digits, so a
     * nine-digit national ID is not silently truncated into a "phone number"
     * plus a stray digit.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\dA-Za-z])(?:"
                    // +961 / 00961, then 1- or 2-digit prefix (leading zero optional), then 6 digits
                    + "(?:\\+|00)\\s?961[\\s.\\-]?0?\\d{1,2}[\\s.\\-]?\\d{3}[\\s.\\-]?\\d{3}"
                    // local: 0X landline/mobile, or bare 70/71/76/78/79/81 mobile
                    + "|(?:0[1-9]|7[01689]|81)[\\s.\\-]?\\d{3}[\\s.\\-]?\\d{3}"
                    // generic international: + country code then at least three more groups
                    + "|\\+\\d{1,3}[\\s.\\-]?(?:\\(?\\d{2,4}\\)?[\\s.\\-]?){2,4}\\d{2,4}"
                    + ")(?!\\d)");

    /**
     * <p>The order of the three passes is load-bearing and is the part worth
     * reading twice.
     *
     * <ol>
     *   <li><b>Email first.</b> Run the URL pass first and its domain matching
     *       claims the right-hand side of an address, leaving {@code adam@} in
     *       the text — a redaction that removed the useless half and kept the
     *       identifying one.
     *   <li><b>URL second.</b> Any digit run living inside a link is gone before
     *       the phone pass can find something phone-shaped in a profile slug.
     *   <li><b>Phone last</b>, for the same reason, on text that no longer
     *       contains either of the other two.
     * </ol>
     *
     * @param text raw extracted text; {@code null} is answered with {@code null}
     */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // quoteReplacement because a replacement string treats "$" and "\" as
        // group references. The current tokens contain neither, but the next
        // person to change one should not have to know that.
        String redacted = EMAIL.matcher(text).replaceAll(Matcher.quoteReplacement(EMAIL_TOKEN));
        redacted = URL.matcher(redacted).replaceAll(Matcher.quoteReplacement(URL_TOKEN));
        redacted = PHONE.matcher(redacted).replaceAll(Matcher.quoteReplacement(PHONE_TOKEN));
        return redacted;
    }
}
