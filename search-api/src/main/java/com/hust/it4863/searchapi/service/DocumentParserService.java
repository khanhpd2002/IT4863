package com.hust.it4863.searchapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hust.it4863.searchapi.extract.PdfExtractor;
import com.hust.it4863.searchapi.model.DocumentDTO;
import com.hust.it4863.searchapi.model.OutLink;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Chuan hoa moi dinh dang dau vao (.txt, .doc, .docx, .json - ke ca JSON tu crawler)
 * ve cung 1 DocumentDTO truoc khi IndexingService dua vao Lucene.
 */
@Service
public class DocumentParserService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PdfExtractor pdfExtractor;

    public DocumentParserService(PdfExtractor pdfExtractor) {
        this.pdfExtractor = pdfExtractor;
    }

    /** Mot file co the sinh ra 1 document (.txt/.doc/.docx) hoac nhieu document (.json dang mang). */
    public List<DocumentDTO> parse(String filename, InputStream input) throws IOException {
        if (filename == null) {
            throw new IllegalArgumentException("Missing filename");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        byte[] bytes = readAll(input);

        if (lower.endsWith(".json")) {
            return parseJson(bytes);
        } else if (lower.endsWith(".docx")) {
            return List.of(parseDocx(filename, bytes));
        } else if (lower.endsWith(".doc")) {
            return List.of(parseDoc(filename, bytes));
        } else if (lower.endsWith(".pdf")) {
            return List.of(parsePdf(filename, bytes));
        } else if (lower.endsWith(".txt")) {
            return List.of(parseTxt(filename, bytes));
        }
        throw new IllegalArgumentException("Unsupported file type: " + filename
                + " (chi ho tro .txt, .doc, .docx, .pdf, .json)");
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        input.transferTo(buffer);
        return buffer.toByteArray();
    }

    private DocumentDTO parseTxt(String filename, byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        // id = ten file day du (ke ca duoi) de tranh dung do voi file cung ten khac dinh dang,
        // vi du "report.doc" va "report.docx" khong duoc trung id.
        return new DocumentDTO(filename, stripExtension(filename), content, List.of());
    }

    private DocumentDTO parseDocx(String filename, byte[] bytes) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes);
             XWPFDocument doc = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return new DocumentDTO(filename, stripExtension(filename), extractor.getText(), List.of());
        }
    }

    private DocumentDTO parseDoc(String filename, byte[] bytes) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes);
             HWPFDocument doc = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(doc)) {
            return new DocumentDTO(filename, stripExtension(filename), extractor.getText(), List.of());
        }
    }

    /** Dung Apache Tika (PDFParser, qua PdfExtractor) de trich text tu PDF. */
    private DocumentDTO parsePdf(String filename, byte[] bytes) throws IOException {
        try {
            String text = pdfExtractor.extract(bytes).text();
            return new DocumentDTO(filename, stripExtension(filename), text, List.of());
        } catch (IOException e) {
            throw new IOException("Loi parse PDF: " + filename, e);
        }
    }

    private List<DocumentDTO> parseJson(byte[] bytes) throws IOException {
        JsonNode root = objectMapper.readTree(bytes);
        List<DocumentDTO> results = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                results.add(toDocumentDTO(node));
            }
        } else {
            results.add(toDocumentDTO(root));
        }
        return results;
    }

    private DocumentDTO toDocumentDTO(JsonNode node) {
        String id = node.hasNonNull("id") ? node.get("id").asText() : UUID.randomUUID().toString();
        String title = node.hasNonNull("title") ? node.get("title").asText() : id;
        String content = node.hasNonNull("content") ? node.get("content").asText() : "";

        List<OutLink> outlinks = new ArrayList<>();
        if (node.has("outlinks") && node.get("outlinks").isArray()) {
            for (JsonNode link : node.get("outlinks")) {
                String linkTitle = link.hasNonNull("title") ? link.get("title").asText() : "";
                String linkContent = link.hasNonNull("content") ? link.get("content").asText() : "";
                String url = link.hasNonNull("url") ? link.get("url").asText() : "";
                outlinks.add(new OutLink(linkTitle, linkContent, url));
            }
        }
        return new DocumentDTO(id, title, content, outlinks);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
