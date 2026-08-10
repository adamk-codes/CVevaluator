import com.apliman.cvevaluator.redaction.PiiRedactor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads every generated fixture back with the same libraries and the same
 * settings {@code CvTextExtractor} uses, and prints what the ingestion pipeline
 * would see.
 *
 * <p>This is the check that the corpus is worth committing. A CV fixture that
 * extracts to nothing is not a CV fixture, and the only way to know is to run
 * the reader over it. It also asserts the intent of each adversarial file — the
 * scanned PDF is supposed to fail, so a run where it succeeds is a broken
 * fixture, not a passing test.
 *
 * <p>Redaction is included because the corpus exists partly to exercise it:
 * every CV carries a real-shaped email and Lebanese phone number, and a few
 * carry the tokens that the URL pattern must not eat.
 */
public final class VerifyFixtures {

    private static final int MINIMUM_TEXT_LENGTH = 100;   // cvevaluator.extraction.minimum-text-length

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args.length > 0 ? args[0] : "fixtures").toAbsolutePath().normalize();
        PiiRedactor redactor = new PiiRedactor();
        int failures = 0;

        for (String dir : List.of("cvs", "adversarial")) {
            System.out.println();
            System.out.println("== " + dir);
            System.out.printf("%-42s %5s %7s %7s %6s %5s %5s  %s%n",
                    "file", "pages", "chars", "visible", "email", "phone", "url", "verdict");

            List<Path> files;
            try (var stream = Files.list(fixtures.resolve(dir))) {
                files = stream.sorted(Comparator.comparing(Path::getFileName)).toList();
            }

            for (Path file : files) {
                String name = file.getFileName().toString();
                String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
                Result result = extract(file, extension);

                String redacted = redactor.redact(result.text());
                int emails = count(redacted, "[EMAIL]");
                int phones = count(redacted, "[PHONE]");
                int urls = count(redacted, "[URL]");

                int visible = result.text() == null ? 0
                        : result.text().replaceAll("\\s", "").length();
                String verdict = result.error() != null
                        ? "REJECTED: " + result.error()
                        : visible < MINIMUM_TEXT_LENGTH ? "FAILED: too little text (" + visible + ")"
                        : "COMPLETED";

                boolean ok = expected(name).equals(verdict.split(":")[0]);
                if (!ok) {
                    failures++;
                }
                System.out.printf("%-42s %5s %7d %7d %6d %5d %5d  %s %s%n",
                        name, result.pages() < 0 ? "-" : String.valueOf(result.pages()),
                        result.text() == null ? 0 : result.text().length(), visible,
                        emails, phones, urls, verdict, ok ? "" : "  <-- UNEXPECTED");

                // Framework names that look like bare domains must survive the
                // URL pass; this is the regression the redactor's javadoc warns
                // about, checked against real corpus text rather than a unit
                // test string.
                for (String token : List.of("Node.js", ".NET Core", "ASP.NET", "React.js")) {
                    if (result.text() != null && result.text().contains(token) && !redacted.contains(token)) {
                        System.out.println("      redaction ate a framework name: " + token);
                        failures++;
                    }
                }
            }
        }

        System.out.println();
        System.out.println(failures == 0
                ? "All fixtures behaved as documented."
                : failures + " fixture(s) did not behave as documented.");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** The verdict each fixture is committed in order to produce. */
    private static String expected(String name) {
        return switch (name) {
            case "adv-01-scanned-no-text-layer.pdf" -> "FAILED";
            case "adv-05-zero-bytes.pdf", "adv-06-executable-renamed.pdf" -> "REJECTED";
            default -> "COMPLETED";
        };
    }

    private record Result(String text, int pages, String error) {}

    private static Result extract(Path file, String extension) {
        try {
            byte[] header = readHeader(file);
            String rejection = signatureCheck(extension, header);
            if (rejection != null) {
                return new Result("", -1, rejection);
            }
            return switch (extension) {
                case "pdf" -> readPdf(file);
                case "docx" -> new Result(readDocx(file), -1, null);
                case "txt" -> new Result(Files.readString(file, StandardCharsets.UTF_8), -1, null);
                default -> new Result("", -1, "unsupported extension");
            };
        } catch (Exception e) {
            return new Result("", -1, e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** The subset of {@code FileSignatureValidator} the fixtures actually hit. */
    private static String signatureCheck(String extension, byte[] header) {
        if (header.length == 0) {
            return "empty file";
        }
        boolean matches = switch (extension) {
            case "pdf" -> startsWith(header, new byte[] { 0x25, 0x50, 0x44, 0x46, 0x2D });
            case "docx" -> startsWith(header, new byte[] { 0x50, 0x4B, 0x03, 0x04 });
            default -> true;
        };
        return matches ? null : "content does not match ." + extension;
    }

    private static boolean startsWith(byte[] header, byte[] signature) {
        if (header.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (header[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readHeader(Path file) throws Exception {
        byte[] all = Files.readAllBytes(file);
        int length = Math.min(all.length, 512);
        byte[] header = new byte[length];
        System.arraycopy(all, 0, header, 0, length);
        return header;
    }

    private static Result readPdf(Path file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return new Result(stripper.getText(document), document.getNumberOfPages(), null);
        }
    }

    private static String readDocx(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            total++;
            at += needle.length();
        }
        return total;
    }

    private VerifyFixtures() {}
}
