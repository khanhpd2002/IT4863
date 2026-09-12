package com.hust.it4863.searchapi.service;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Doc lai Lucene index (dinh dang nhi phan, khong doc truc tiep duoc) va xuat ra 1 file
 * text thuan - dung dinh dang chi muc ngua kinh dien: moi term -> danh sach tai lieu chua
 * no kem tan so xuat hien (term frequency), phuc vu bao cao/demo.
 */
@Service
public class IndexExportService {

    private static final String CONTENT_FIELD = "content";

    private final Directory directory;

    @Value("${app.lucene.export-file}")
    private String exportFilePath;

    public IndexExportService(Directory directory) {
        this.directory = directory;
    }

    /** Sinh noi dung chi muc ngua dang text, khong ghi file. */
    public String buildReadableInvertedIndex() throws IOException {
        if (!DirectoryReader.indexExists(directory)) {
            return "Chua co index nao. Hay ingest tai lieu truoc (POST /api/documents hoac "
                    + "/api/documents/import-raw).\n";
        }

        StringBuilder sb = new StringBuilder();
        try (IndexReader reader = DirectoryReader.open(directory)) {
            Terms terms = MultiTerms.getTerms(reader, CONTENT_FIELD);
            sb.append("=== CHI MUC NGUOC (INVERTED INDEX) ===\n");
            sb.append("Field: ").append(CONTENT_FIELD)
              .append(" | So tai lieu: ").append(reader.numDocs()).append("\n\n");

            if (terms == null) {
                sb.append("(chua co term nao)\n");
                return sb.toString();
            }

            int termCount = 0;
            TermsEnum termsEnum = terms.iterator();
            BytesRef termRef;
            while ((termRef = termsEnum.next()) != null) {
                termCount++;
                sb.append('"').append(termRef.utf8ToString()).append('"')
                  .append(" (df=").append(termsEnum.docFreq()).append(")\n");

                PostingsEnum postings = termsEnum.postings(null, PostingsEnum.FREQS);
                int docId;
                while ((docId = postings.nextDoc()) != PostingsEnum.NO_MORE_DOCS) {
                    Document doc = reader.storedFields().document(docId);
                    sb.append("    - id=").append(doc.get("id"))
                      .append(", title=\"").append(doc.get("title")).append('"')
                      .append(", tf=").append(postings.freq()).append('\n');
                }
                sb.append('\n');
            }
            sb.append("---\nTong so term (tu vung): ").append(termCount).append('\n');
        }
        return sb.toString();
    }

    /** Sinh va ghi ra file cau hinh o app.lucene.export-file, tra ve duong dan da ghi. */
    public Path exportToFile() throws IOException {
        String content = buildReadableInvertedIndex();
        Path path = Path.of(exportFilePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}
