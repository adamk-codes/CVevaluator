import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Builds every binary file under {@code fixtures/} from the plain-text sources
 * in {@code fixtures/content/}.
 *
 * <p>Run it with {@code fixtures/tools/generate.cmd}. It is not part of the
 * Maven build and never runs at application startup — the generated files are
 * committed, and this exists so that a reviewer can see how they were made and
 * regenerate them after editing a source.
 *
 * <p>It deliberately uses the same PDFBox and POI versions the application
 * reads with, taken off the project's own classpath. A fixture written by one
 * library and read by another proves less than it appears to.
 *
 * <p>The markup in {@code fixtures/content} is five characters wide:
 * <pre>
 *   # name          the candidate's name, set large
 *   @ contact       a contact or strapline row under the name
 *   ## SECTION      a section heading
 *   &gt; role          an employer / role / date line, set bold
 *   - bullet        a bullet
 *   (anything else) a body paragraph; a blank line is vertical space
 * </pre>
 * Whether a CV <em>has</em> {@code ##} headings at all is a property of the
 * source file, which is how the corpus varies between structured and prose
 * layouts.
 */
public final class GenerateFixtures {

    // ---------------------------------------------------------------- model

    private enum Kind { NAME, CONTACT, SECTION, ROLE, BULLET, PARA, BLANK }

    private record Block(Kind kind, String text) {}

    /**
     * A PDF look. Three of these exist so the corpus is not twenty copies of
     * one template — an extractor that only ever sees Helvetica-with-a-rule is
     * not being tested on much.
     */
    private record PdfTheme(
            Standard14Fonts.FontName regular,
            Standard14Fonts.FontName bold,
            float bodySize,
            float leading,
            float margin,
            boolean ruleUnderSection,
            boolean uppercaseSection
    ) {}

    private static final PdfTheme MODERN =
            new PdfTheme(Standard14Fonts.FontName.HELVETICA, Standard14Fonts.FontName.HELVETICA_BOLD,
                    10f, 13.5f, 56f, true, true);
    private static final PdfTheme CLASSIC =
            new PdfTheme(Standard14Fonts.FontName.TIMES_ROMAN, Standard14Fonts.FontName.TIMES_BOLD,
                    11f, 14.5f, 68f, false, true);
    private static final PdfTheme TYPEWRITER =
            new PdfTheme(Standard14Fonts.FontName.COURIER, Standard14Fonts.FontName.COURIER_BOLD,
                    9.5f, 13f, 60f, false, false);

    private enum DocxStyle { PLAIN, SKILLS_TABLE, COMPACT }

    private record Spec(String source, String out, String format, Object style, String fontFamily) {}

    /**
     * Format and styling per CV. The mix is deliberate: nine PDF, six DOCX,
     * five TXT, and no two consecutive CVs sharing a look.
     */
    private static final List<Spec> CVS = List.of(
            new Spec("cv-01-rami-haddad",       "cv-01-rami-haddad.pdf",        "pdf",  MODERN,      null),
            new Spec("cv-02-lina-doumit",       "cv-02-lina-doumit.docx",       "docx", DocxStyle.SKILLS_TABLE, "Calibri"),
            new Spec("cv-03-karim-mansour",     "cv-03-karim-mansour.pdf",      "pdf",  CLASSIC,     null),
            new Spec("cv-04-yara-khoury",       "cv-04-yara-khoury.txt",        "txt",  null,        null),
            new Spec("cv-05-georges-semaan",    "cv-05-georges-semaan.docx",    "docx", DocxStyle.PLAIN,   "Calibri"),
            new Spec("cv-06-nour-aziz",         "cv-06-nour-aziz.pdf",          "pdf",  CLASSIC,     null),
            new Spec("cv-07-elie-bou-chedid",   "cv-07-elie-bou-chedid.pdf",    "pdf",  MODERN,      null),
            new Spec("cv-08-maya-chalhoub",     "cv-08-maya-chalhoub.docx",     "docx", DocxStyle.COMPACT, "Arial"),
            new Spec("cv-09-tarek-ghanem",      "cv-09-tarek-ghanem.txt",       "txt",  null,        null),
            new Spec("cv-10-sandra-ibrahim",    "cv-10-sandra-ibrahim.pdf",     "pdf",  TYPEWRITER,  null),
            new Spec("cv-11-hadi-nasrallah",    "cv-11-hadi-nasrallah.docx",    "docx", DocxStyle.PLAIN,   "Georgia"),
            new Spec("cv-12-joelle-feghali",    "cv-12-joelle-feghali.txt",     "txt",  null,        null),
            new Spec("cv-13-ziad-abou-jaoude",  "cv-13-ziad-abou-jaoude.pdf",   "pdf",  MODERN,      null),
            new Spec("cv-14-rita-sfeir",        "cv-14-rita-sfeir.pdf",         "pdf",  CLASSIC,     null),
            new Spec("cv-15-ahmad-zeineddine",  "cv-15-ahmad-zeineddine.txt",   "txt",  null,        null),
            new Spec("cv-16-carla-mouawad",     "cv-16-carla-mouawad.docx",     "docx", DocxStyle.COMPACT, "Arial"),
            new Spec("cv-17-fadi-rizk",         "cv-17-fadi-rizk.pdf",          "pdf",  MODERN,      null),
            new Spec("cv-18-nadine-saad",       "cv-18-nadine-saad.pdf",        "pdf",  CLASSIC,     null),
            new Spec("cv-19-omar-chidiac",      "cv-19-omar-chidiac.txt",       "txt",  null,        null),
            new Spec("cv-20-perla-haddad",      "cv-20-perla-haddad.docx",      "docx", DocxStyle.PLAIN,   "Times New Roman")
    );

    private static Path fixtures;

    public static void main(String[] args) throws Exception {
        fixtures = Path.of(args.length > 0 ? args[0] : "fixtures").toAbsolutePath().normalize();
        Path cvs = fixtures.resolve("cvs");
        Path adversarial = fixtures.resolve("adversarial");
        Files.createDirectories(cvs);
        Files.createDirectories(adversarial);
        // Emptied rather than overwritten, so renaming a spec does not leave the
        // old output behind looking like a current fixture.
        clear(cvs);
        clear(adversarial);

        for (Spec spec : CVS) {
            List<Block> blocks = parse(read(spec.source()));
            Path target = cvs.resolve(spec.out());
            switch (spec.format()) {
                case "pdf" -> writePdf(target, blocks, (PdfTheme) spec.style(), null);
                case "docx" -> writeDocx(target, blocks, (DocxStyle) spec.style(), spec.fontFamily());
                case "txt" -> writeTxt(target, blocks);
                default -> throw new IllegalStateException(spec.format());
            }
            report(target);
        }

        writeScannedPdf(adversarial.resolve("adv-01-scanned-no-text-layer.pdf"));
        writeLongPdf(adversarial.resolve("adv-02-forty-page-cv.pdf"));
        writeArabicPdf(adversarial.resolve("adv-03-arabic-rtl-cv.pdf"));
        writePdf(adversarial.resolve("adv-04-restaurant-menu.pdf"),
                parse(read("adv-restaurant-menu")), CLASSIC, null);
        report(adversarial.resolve("adv-04-restaurant-menu.pdf"));
        writeZeroBytes(adversarial.resolve("adv-05-zero-bytes.pdf"));
        writeRenamedExecutable(adversarial.resolve("adv-06-executable-renamed.pdf"));
    }

    // --------------------------------------------------------------- parsing

    private static String read(String name) throws IOException {
        return Files.readString(fixtures.resolve("content").resolve(name + ".txt"), StandardCharsets.UTF_8);
    }

    private static List<Block> parse(String source) {
        List<Block> blocks = new ArrayList<>();
        for (String raw : source.split("\\R", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                blocks.add(new Block(Kind.BLANK, ""));
            } else if (line.startsWith("## ")) {
                blocks.add(new Block(Kind.SECTION, line.substring(3).strip()));
            } else if (line.startsWith("# ")) {
                blocks.add(new Block(Kind.NAME, line.substring(2).strip()));
            } else if (line.startsWith("@ ")) {
                blocks.add(new Block(Kind.CONTACT, line.substring(2).strip()));
            } else if (line.startsWith("> ")) {
                blocks.add(new Block(Kind.ROLE, line.substring(2).strip()));
            } else if (line.startsWith("- ")) {
                blocks.add(new Block(Kind.BULLET, line.substring(2).strip()));
            } else {
                blocks.add(new Block(Kind.PARA, line));
            }
        }
        return blocks;
    }

    // ------------------------------------------------------------------ txt

    private static void writeTxt(Path target, List<Block> blocks) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Block block : blocks) {
            switch (block.kind()) {
                case BLANK -> text.append('\n');
                case SECTION -> text.append(block.text().toUpperCase(Locale.ROOT)).append('\n');
                case BULLET -> text.append("  * ").append(block.text()).append('\n');
                default -> text.append(block.text()).append('\n');
            }
        }
        Files.writeString(target, text.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ pdf

    /**
     * Lays blocks out over as many A4 pages as they need.
     *
     * @param embedded a TrueType font to use instead of the theme's Standard-14
     *                 pair, for text the Standard-14 fonts cannot encode;
     *                 {@code null} for everything else
     * @param maxPages stop after this many pages, or {@code 0} for no limit
     */
    private static void writePdf(Path target, List<Block> blocks, PdfTheme theme, PDFont embedded)
            throws IOException {
        writePdf(target, blocks, theme, embedded, 0);
    }

    private static void writePdf(Path target, List<Block> blocks, PdfTheme theme, PDFont embedded, int maxPages)
            throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont regular = embedded != null ? embedded : new PDType1Font(theme.regular());
            PDFont bold = embedded != null ? embedded : new PDType1Font(theme.bold());
            // Standard-14 fonts are WinAnsi and throw on anything outside it.
            // An embedded TrueType font carries its own cmap, so leave it alone.
            boolean sanitise = embedded == null;

            float width = PDRectangle.A4.getWidth();
            float height = PDRectangle.A4.getHeight();
            float usable = width - 2 * theme.margin();

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = height - theme.margin();
            int pages = 1;

            for (Block block : blocks) {
                float size = switch (block.kind()) {
                    case NAME -> theme.bodySize() + 8f;
                    case SECTION -> theme.bodySize() + 1f;
                    case CONTACT -> theme.bodySize() - 1f;
                    default -> theme.bodySize();
                };
                boolean isBold = block.kind() == Kind.NAME
                        || block.kind() == Kind.SECTION
                        || block.kind() == Kind.ROLE;
                PDFont font = isBold ? bold : regular;

                String text = block.text();
                if (block.kind() == Kind.SECTION && theme.uppercaseSection()) {
                    text = text.toUpperCase(Locale.ROOT);
                }
                if (sanitise) {
                    text = sanitiseForWinAnsi(text);
                }

                float indent = block.kind() == Kind.BULLET ? 14f : 0f;
                float spaceBefore = switch (block.kind()) {
                    case SECTION -> theme.leading() * 0.9f;
                    case ROLE -> theme.leading() * 0.35f;
                    default -> 0f;
                };

                List<String> lines = block.kind() == Kind.BLANK
                        ? List.of("")
                        : wrap(text, font, size, usable - indent);

                for (int i = 0; i < lines.size(); i++) {
                    y -= (i == 0 ? spaceBefore : 0) + theme.leading();
                    if (y < theme.margin()) {
                        cs.close();
                        if (maxPages > 0 && pages >= maxPages) {
                            doc.save(target.toFile());
                            return;
                        }
                        page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        pages++;
                        cs = new PDPageContentStream(doc, page);
                        y = height - theme.margin() - theme.leading();
                    }
                    String line = lines.get(i);
                    if (line.isEmpty()) {
                        continue;
                    }
                    float x = theme.margin() + indent;
                    cs.beginText();
                    cs.setFont(font, size);
                    cs.newLineAtOffset(x, y);
                    cs.showText(block.kind() == Kind.BULLET && i == 0 ? "\u2022  " + line : line);
                    cs.endText();
                }

                if (block.kind() == Kind.SECTION && theme.ruleUnderSection()) {
                    cs.setLineWidth(0.6f);
                    cs.moveTo(theme.margin(), y - 3.5f);
                    cs.lineTo(width - theme.margin(), y - 3.5f);
                    cs.stroke();
                    y -= 3f;
                }
            }
            cs.close();
            doc.save(target.toFile());
        }
    }

    /**
     * Greedy word wrap. Splits an over-long single word by character rather
     * than overflowing the margin, because a URL or a long identifier in a
     * skills list otherwise runs off the page.
     */
    private static List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (widthOf(font, candidate, size) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
                current.setLength(0);
            }
            while (widthOf(font, word, size) > maxWidth && word.length() > 1) {
                int cut = word.length();
                while (cut > 1 && widthOf(font, word.substring(0, cut), size) > maxWidth) {
                    cut--;
                }
                lines.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private static float widthOf(PDFont font, String text, float size) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    /**
     * Folds the typographic characters that creep into written prose down to
     * their ASCII equivalents, then drops anything still outside Latin-1.
     * Without this a single em dash aborts the whole document, because
     * Standard-14 fonts encode WinAnsi and {@code showText} throws rather than
     * substituting.
     */
    private static String sanitiseForWinAnsi(String text) {
        String folded = text
                .replace('\u2014', '-').replace('\u2013', '-')
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace("\u2026", "...")
                .replace('\u00A0', ' ');
        StringBuilder out = new StringBuilder(folded.length());
        for (char c : folded.toCharArray()) {
            out.append(c < 0x100 ? c : '?');
        }
        return out.toString();
    }

    // ----------------------------------------------------------------- docx

    private static void writeDocx(Path target, List<Block> blocks, DocxStyle style, String fontFamily)
            throws IOException {
        boolean compact = style == DocxStyle.COMPACT;
        int body = compact ? 10 : 11;

        try (XWPFDocument doc = new XWPFDocument();
             OutputStream out = Files.newOutputStream(target)) {

            for (int i = 0; i < blocks.size(); i++) {
                Block block = blocks.get(i);

                // A skills section written as "Label: values" bullets becomes a
                // real two-column table, so at least one fixture forces the
                // extractor down POI's table path rather than its paragraph path.
                if (style == DocxStyle.SKILLS_TABLE
                        && block.kind() == Kind.SECTION
                        && block.text().toUpperCase(Locale.ROOT).contains("SKILL")) {
                    heading(doc, block.text().toUpperCase(Locale.ROOT), fontFamily, body + 2);
                    List<Block> rows = new ArrayList<>();
                    int j = i + 1;
                    while (j < blocks.size()
                            && (blocks.get(j).kind() == Kind.BULLET || blocks.get(j).kind() == Kind.BLANK)) {
                        if (blocks.get(j).kind() == Kind.BULLET && blocks.get(j).text().contains(": ")) {
                            rows.add(blocks.get(j));
                        }
                        j++;
                    }
                    if (!rows.isEmpty()) {
                        XWPFTable table = doc.createTable(rows.size(), 2);
                        table.setWidth("100%");
                        for (int r = 0; r < rows.size(); r++) {
                            String[] parts = rows.get(r).text().split(": ", 2);
                            XWPFTableRow row = table.getRow(r);
                            cell(row, 0, parts[0], fontFamily, body, true);
                            cell(row, 1, parts.length > 1 ? parts[1] : "", fontFamily, body, false);
                        }
                        i = j - 1;
                        continue;
                    }
                }

                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                r.setFontFamily(fontFamily);

                switch (block.kind()) {
                    case NAME -> {
                        r.setText(block.text());
                        r.setBold(true);
                        r.setFontSize(body + 9);
                        p.setSpacingAfter(0);
                    }
                    case CONTACT -> {
                        r.setText(block.text());
                        r.setFontSize(body - 1);
                        r.setColor("595959");
                        p.setSpacingAfter(0);
                    }
                    case SECTION -> {
                        r.setText(compact ? block.text() : block.text().toUpperCase(Locale.ROOT));
                        r.setBold(true);
                        r.setFontSize(body + 2);
                        p.setSpacingBefore(compact ? 120 : 200);
                        p.setSpacingAfter(40);
                    }
                    case ROLE -> {
                        r.setText(block.text());
                        r.setBold(true);
                        r.setFontSize(body);
                        p.setSpacingAfter(0);
                    }
                    case BULLET -> {
                        r.setText("\u2022  " + block.text());
                        r.setFontSize(body);
                        p.setIndentationLeft(360);
                        p.setSpacingAfter(compact ? 0 : 40);
                    }
                    case BLANK -> {
                        r.setFontSize(body);
                        r.setText("");
                    }
                    case PARA -> {
                        r.setText(block.text());
                        r.setFontSize(body);
                        p.setSpacingAfter(80);
                    }
                }
            }
            doc.write(out);
        }
    }

    private static void heading(XWPFDocument doc, String text, String fontFamily, int size) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(200);
        p.setSpacingAfter(40);
        XWPFRun r = p.createRun();
        r.setFontFamily(fontFamily);
        r.setText(text);
        r.setBold(true);
        r.setFontSize(size);
    }

    private static void cell(XWPFTableRow row, int index, String text, String fontFamily, int size, boolean bold) {
        XWPFParagraph p = row.getCell(index).getParagraphs().get(0);
        XWPFRun r = p.createRun();
        r.setFontFamily(fontFamily);
        r.setText(text);
        r.setFontSize(size);
        r.setBold(bold);
    }

    // ---------------------------------------------------------- adversarial

    /**
     * A CV that exists only as pixels. The page carries one JPEG and no text
     * operators at all, so {@code PDFTextStripper} returns an empty string and
     * the ingestion service should fail it with the no-text-layer message
     * rather than storing a blank CV as a success.
     *
     * <p>The rotation and speckle are not decoration — a fixture that is
     * obviously synthetic invites the objection that a real scan would have
     * behaved differently.
     */
    private static void writeScannedPdf(Path target) throws IOException {
        List<Block> blocks = parse(read("adv-scanned-cv"));

        int w = 1240;
        int h = 1754;                       // A4 at 150 dpi
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xF6, 0xF4, 0xEF));
        g.fillRect(0, 0, w, h);

        g.rotate(Math.toRadians(0.45), w / 2.0, h / 2.0);
        g.setColor(new Color(0x22, 0x22, 0x28));

        int y = 150;
        for (Block block : blocks) {
            java.awt.Font font = switch (block.kind()) {
                case NAME -> new java.awt.Font("Serif", java.awt.Font.BOLD, 40);
                case SECTION -> new java.awt.Font("Serif", java.awt.Font.BOLD, 24);
                case ROLE -> new java.awt.Font("Serif", java.awt.Font.BOLD, 21);
                case CONTACT -> new java.awt.Font("Serif", java.awt.Font.PLAIN, 19);
                default -> new java.awt.Font("Serif", java.awt.Font.PLAIN, 21);
            };
            g.setFont(font);
            if (block.kind() == Kind.BLANK) {
                y += 16;
                continue;
            }
            String text = (block.kind() == Kind.BULLET ? "\u2022  " : "") + block.text();
            int x = 130 + (block.kind() == Kind.BULLET ? 26 : 0);
            for (String line : wrapAwt(g, text, w - x - 130)) {
                g.drawString(line, x, y);
                y += font.getSize() + 12;
            }
            y += block.kind() == Kind.SECTION ? 10 : 4;
        }
        g.setTransform(new AffineTransform());

        // Scanner grain, plus the grey wedge a lid that does not sit flat leaves
        // down one edge.
        Random random = new Random(20260810L);
        for (int i = 0; i < 26000; i++) {
            int px = random.nextInt(w);
            int py = random.nextInt(h);
            int shade = 200 + random.nextInt(45);
            image.setRGB(px, py, new Color(shade, shade, shade - 6).getRGB());
        }
        Graphics2D edge = image.createGraphics();
        for (int i = 0; i < 26; i++) {
            edge.setColor(new Color(120, 120, 120, 90 - i * 3));
            edge.drawLine(w - 1 - i, 0, w - 1 - i, h);
        }
        edge.dispose();
        g.dispose();

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject jpeg = JPEGFactory.createFromImage(doc, image, 0.72f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(jpeg, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            doc.save(target.toFile());
        }
        report(target);
    }

    private static List<String> wrapAwt(Graphics2D g, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (g.getFontMetrics().stringWidth(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                }
                current.setLength(0);
                current.append(word);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    /**
     * Forty pages. Academic CVs really do run this long once the publication
     * list is included, so the content is a plausible reason rather than one
     * page repeated forty times — the point is to push a genuinely large body
     * of text through extraction and into whatever the prompt budget turns out
     * to be.
     */
    private static void writeLongPdf(Path target) throws IOException {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block(Kind.NAME, "Prof. Samir El-Khoury"));
        blocks.add(new Block(Kind.CONTACT, "Professor of Computer Science - Beirut, Lebanon"));
        blocks.add(new Block(Kind.CONTACT, "samir.elkhoury@example.com | 01 350 774"));
        blocks.add(new Block(Kind.BLANK, ""));
        blocks.add(new Block(Kind.SECTION, "Academic Appointments"));
        blocks.add(new Block(Kind.ROLE, "Professor of Computer Science, Lebanese University, 2016 - Present"));
        blocks.add(new Block(Kind.ROLE, "Associate Professor, Lebanese University, 2010 - 2016"));
        blocks.add(new Block(Kind.ROLE, "Assistant Professor, Universite Saint-Joseph, 2005 - 2010"));
        blocks.add(new Block(Kind.BLANK, ""));
        blocks.add(new Block(Kind.SECTION, "Research Interests"));
        blocks.add(new Block(Kind.PARA, "Distributed consensus, storage systems, formal verification of "
                + "concurrent data structures, and the teaching of systems programming."));
        blocks.add(new Block(Kind.BLANK, ""));

        String[] venues = { "PODC", "DISC", "OSDI", "SOSP", "EuroSys", "VLDB", "SIGMOD", "ICDCS", "NSDI", "ATC" };
        String[] topics = {
                "Consensus under partial synchrony", "A lock-free skip list revisited",
                "Verifying linearisability by refinement", "Snapshot isolation in practice",
                "On the cost of exactly-once delivery", "Replication without coordination",
                "Bounded staleness for read replicas", "A calculus for transactional outboxes",
                "Recovery time in log-structured stores", "Fault injection as a first-class test",
                "The price of serialisability", "Partitioning strategies for multi-tenant stores",
                "Clock skew and its consequences", "Compaction scheduling under load",
                "Write amplification in tiered storage"
        };

        // Sized to overflow forty pages by a little; writePdf stops at forty on
        // a block boundary, so the count is exact rather than approximate.
        blocks.add(new Block(Kind.SECTION, "Selected Publications"));
        int year = 2025;
        for (int i = 0; i < 700; i++) {
            String venue = venues[i % venues.length];
            String topic = topics[i % topics.length];
            if (i % 7 == 0 && year > 2004) {
                year--;
            }
            blocks.add(new Block(Kind.BULLET, String.format(
                    "S. El-Khoury, %s and %s. \"%s: part %d.\" In Proceedings of %s %d, pages %d-%d.",
                    surname(i), surname(i + 3), topic, (i % 4) + 1, venue, year,
                    100 + (i * 13) % 800, 118 + (i * 13) % 800)));
        }
        blocks.add(new Block(Kind.BLANK, ""));
        blocks.add(new Block(Kind.SECTION, "Invited Talks"));
        for (int i = 0; i < 200; i++) {
            blocks.add(new Block(Kind.BULLET, String.format(
                    "\"%s\", %s seminar series, %d.", topics[i % topics.length], venues[i % venues.length],
                    2024 - (i / 4))));
        }
        blocks.add(new Block(Kind.BLANK, ""));
        blocks.add(new Block(Kind.SECTION, "Teaching"));
        for (int i = 0; i < 130; i++) {
            blocks.add(new Block(Kind.BULLET, String.format(
                    "CS%d %s, %s semester %d, enrolment %d.",
                    300 + i, topics[i % topics.length], i % 2 == 0 ? "Fall" : "Spring", 2024 - (i / 2),
                    28 + (i * 7) % 90)));
        }
        blocks.add(new Block(Kind.BLANK, ""));
        blocks.add(new Block(Kind.SECTION, "Doctoral Supervision"));
        for (int i = 0; i < 130; i++) {
            blocks.add(new Block(Kind.BULLET, String.format(
                    "%s, PhD %d. Thesis: \"%s\". Now at %s.",
                    surname(i + 11), 2010 + (i % 15), topics[(i + 5) % topics.length], venues[i % venues.length])));
        }

        writePdf(target, blocks, CLASSIC, null, 40);
        report(target);
    }

    private static String surname(int i) {
        String[] names = { "A. Nassar", "L. Tannous", "R. Bitar", "M. Yazbeck", "K. Rahme",
                "N. Sleiman", "H. Daher", "J. Aoun", "P. Sarkis", "T. Chami" };
        return names[Math.floorMod(i, names.length)];
    }

    /**
     * An Arabic CV, right to left in reading order.
     *
     * <p>Two things this fixture is and is not. It <em>is</em> a real test of
     * the extraction path: the text is genuine Arabic, stored in logical order,
     * and it will come back out of PDFBox as Arabic codepoints that then have
     * to survive redaction, the database column and the prompt. It is
     * <em>not</em> a faithful rendering — PDFBox does no bidirectional
     * reordering and no Arabic contextual shaping, so on screen the letters
     * appear in isolated form and run left to right. A CV exported from Word
     * would look right and extract to the same characters.
     *
     * <p>Arial is used because it is the font on this machine with Arabic
     * coverage; PDFBox subsets it into the file, which is what any word
     * processor does.
     */
    private static void writeArabicPdf(Path target) throws IOException {
        Path font = Path.of("C:/Windows/Fonts/arial.ttf");
        if (!Files.exists(font)) {
            font = Path.of("C:/Windows/Fonts/tahoma.ttf");
        }
        if (!Files.exists(font)) {
            throw new IOException("No Arabic-capable font found; looked for arial.ttf and tahoma.ttf");
        }
        List<Block> blocks = parse(read("adv-arabic-cv"));
        try (PDDocument probe = new PDDocument()) {
            PDFont arabic = PDType0Font.load(probe, font.toFile());
            // writePdf opens its own document, so the font has to be loaded
            // into that one. Load it there instead and use this only to fail
            // early if the file is not a usable TrueType font.
            if (arabic.getName() == null) {
                throw new IOException("Font loaded but unusable: " + font);
            }
        }
        writeArabicPdfInternal(target, blocks, font);
        report(target);
    }

    private static void writeArabicPdfInternal(Path target, List<Block> blocks, Path fontFile) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont arabic = PDType0Font.load(doc, fontFile.toFile());
            float margin = 60f;
            float leading = 17f;
            float width = PDRectangle.A4.getWidth();
            float height = PDRectangle.A4.getHeight();

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = height - margin;

            for (Block block : blocks) {
                float size = switch (block.kind()) {
                    case NAME -> 20f;
                    case SECTION -> 13f;
                    case CONTACT -> 10.5f;
                    default -> 11.5f;
                };
                if (block.kind() == Kind.BLANK) {
                    y -= leading;
                    continue;
                }
                String text = (block.kind() == Kind.BULLET ? "\u2022  " : "") + block.text();
                for (String line : wrap(text, arabic, size, width - 2 * margin)) {
                    y -= leading;
                    if (y < margin) {
                        cs.close();
                        page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        cs = new PDPageContentStream(doc, page);
                        y = height - margin - leading;
                    }
                    String visual = toVisualOrder(line);
                    float x = width - margin - widthOf(arabic, visual, size);
                    cs.beginText();
                    cs.setFont(arabic, size);
                    cs.newLineAtOffset(Math.max(margin, x), y);
                    cs.showText(visual);
                    cs.endText();
                }
                y -= block.kind() == Kind.SECTION ? 6f : 2f;
            }
            cs.close();
            doc.save(target.toFile());
        }
    }

    /**
     * Reorders a logical-order line into the visual order a PDF actually stores
     * it in — leftmost glyph first, so an Arabic run reads right to left on the
     * page.
     *
     * <p>This is the difference between a fixture that works and one that
     * silently lies. {@code showText} paints characters left to right in the
     * order given, so writing Arabic in logical order puts the <em>first</em>
     * letter at the left, which is backwards. Worse, it survives a naive check:
     * {@code PDFTextStripper} runs the Unicode bidi algorithm over what it
     * finds, assumes glyph order is visual order, and reverses every RTL run to
     * recover logical order. Feed it logical order and it hands back the string
     * reversed — real Arabic characters, right count, wrong order, and nothing
     * about the file looks broken until someone reads it.
     *
     * <p>Laying the glyphs out visually is also simply what Word and LibreOffice
     * do, so the fixture matches the files it stands in for, and extraction
     * round-trips to the exact source text.
     *
     * <p>Runs are reversed by grapheme cluster rather than by {@code char}: the
     * source carries five Arabic diacritics, and a plain character reversal
     * would detach each one from the letter it sits on.
     */
    private static String toVisualOrder(String logical) {
        if (logical.isBlank()) {
            return logical;
        }
        Bidi bidi = new Bidi(logical, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        if (bidi.isLeftToRight()) {
            return logical;
        }
        int count = bidi.getRunCount();
        Run[] runs = new Run[count];
        byte[] levels = new byte[count];
        for (int i = 0; i < count; i++) {
            runs[i] = new Run(logical.substring(bidi.getRunStart(i), bidi.getRunLimit(i)),
                    (byte) bidi.getRunLevel(i));
            levels[i] = (byte) bidi.getRunLevel(i);
        }
        Bidi.reorderVisually(levels, 0, runs, 0, count);

        StringBuilder visual = new StringBuilder(logical.length());
        for (Run run : runs) {
            visual.append(run.level() % 2 == 1 ? reverseGraphemes(run.text()) : run.text());
        }
        return visual.toString();
    }

    private record Run(String text, byte level) {}

    private static String reverseGraphemes(String text) {
        BreakIterator boundaries = BreakIterator.getCharacterInstance(new Locale("ar"));
        boundaries.setText(text);
        StringBuilder reversed = new StringBuilder(text.length());
        int end = boundaries.last();
        for (int start = boundaries.previous(); start != BreakIterator.DONE;
             end = start, start = boundaries.previous()) {
            reversed.append(text, start, end);
        }
        return reversed.toString();
    }

    /** Exactly what it says. Rejected before anything tries to parse it. */
    private static void writeZeroBytes(Path target) throws IOException {
        Files.write(target, new byte[0]);
        report(target);
    }

    /**
     * A Windows PE file wearing a {@code .pdf} extension — the case
     * {@code FileSignatureValidator} exists for. It starts {@code 4D 5A}, not
     * {@code %PDF-}, so it is a 400 at upload and never reaches disk.
     *
     * <p>Deliberately inert: the headers are well formed enough to be
     * unmistakably PE, and there are no sections and no entry point, so there
     * is nothing here that could run. Committing a real executable to test a
     * rejection path would be a poor trade.
     */
    private static void writeRenamedExecutable(Path target) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] dos = new byte[0x80];
        dos[0] = 'M';
        dos[1] = 'Z';
        dos[2] = (byte) 0x90;                    // e_cblp
        dos[4] = 0x03;                           // e_cp
        dos[8] = 0x04;                           // e_cparhdr
        dos[0x0A] = (byte) 0xFF;                 // e_maxalloc
        dos[0x0B] = (byte) 0xFF;
        dos[0x18] = 0x40;                        // e_lfarlc
        dos[0x3C] = (byte) 0x80;                 // e_lfanew -> PE header at 0x80
        byte[] stub = "This program cannot be run in DOS mode.\r\r\n$".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(stub, 0, dos, 0x4E, Math.min(stub.length, 0x80 - 0x4E));
        out.write(dos);

        out.write(new byte[] { 'P', 'E', 0, 0 });
        // COFF header: x86-64, zero sections, PE32+ optional header size.
        byte[] coff = new byte[20];
        coff[0] = (byte) 0x64;
        coff[1] = (byte) 0x86;
        coff[16] = (byte) 0xF0;
        coff[18] = 0x22;
        out.write(coff);

        byte[] optional = new byte[0xF0];
        optional[0] = 0x0B;
        optional[1] = 0x02;                      // PE32+ magic
        out.write(optional);

        Files.write(target, out.toByteArray());
        report(target);
    }

    private static void clear(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static void report(Path target) throws IOException {
        System.out.printf("  %-42s %8d bytes%n",
                fixtures.relativize(target).toString().replace('\\', '/'), Files.size(target));
    }

    private GenerateFixtures() {}
}
