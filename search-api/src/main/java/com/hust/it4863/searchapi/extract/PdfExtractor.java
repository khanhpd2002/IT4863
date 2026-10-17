package com.hust.it4863.searchapi.extract;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Trich van ban + metadata tu PDF bang Apache Tika (PDFParser). Dung chung cho Lucene ingest. */
@Component
public class PdfExtractor {

    /** title/author lay tu metadata cua tep PDF, co the null. */
    public record PdfContent(String text, Integer pageCount, String title, String author) {
    }

    public PdfContent extract(byte[] bytes) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            // BodyContentHandler(-1) = khong gioi han do dai text (mac dinh Tika cat o 100_000
            // ky tu, khong phu hop voi PDF dai).
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            new PDFParser().parse(in, handler, metadata, new ParseContext());
            return new PdfContent(
                    normalizeWhitespace(handler.toString()),
                    metadata.getInt(PagedText.N_PAGES),
                    blankToNull(metadata.get(TikaCoreProperties.TITLE)),
                    blankToNull(metadata.get(TikaCoreProperties.CREATOR)));
        } catch (TikaException | SAXException e) {
            throw new IOException("Loi parse PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Tika giu nguyen khoang trang/tab/dong trong theo bo cuc trang PDF: gop khoang trang trong
     * tung dong, bo dong trong lien tiep (giu toi da 1 dong trong giua cac doan).
     */
    static String normalizeWhitespace(String text) {
        StringBuilder out = new StringBuilder();
        boolean previousBlank = true;
        for (String line : text.split("\\R")) {
            String cleaned = line.replaceAll("[\\s\\u00A0]+", " ").strip();
            if (cleaned.isEmpty()) {
                if (!previousBlank) {
                    out.append('\n');
                }
                previousBlank = true;
            } else {
                out.append(cleaned).append('\n');
                previousBlank = false;
            }
        }
        return out.toString().strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
