package com.hust.it4863.searchapi.service;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Sinh toi da 3 doan trich (snippet) quanh vi tri tu khop that su trong van ban, dung chung
 * cho tim kiem tren file (SearchService) va tren du lieu MongoDB (MongoSearchService).
 *
 * Highlighter re-tokenize van ban bang cung analyzer voi luc index, nen tim dung VI TRI doan
 * chua tu khoa (thay vi luon lay 200 ky tu dau van ban - sai voi tai lieu dai, doan dau chi la
 * quoc hieu/tieu de, khong lien quan gi den tu khoa nguoi dung go).
 */
@Component
public class SnippetExtractor {

    private static final int SNIPPET_LENGTH = 200;
    /** So doan trich toi da tra ve cho moi ket qua, xep theo diem khop giam dan. */
    private static final int MAX_SNIPPETS = 3;

    private final Analyzer analyzer;

    public SnippetExtractor(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * @param field ten field trong query dung de highlight (chi lay tu khoa cua field nay)
     */
    public List<String> snippets(Query query, String field, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter("", ""), new QueryScorer(query, field));
        highlighter.setTextFragmenter(new SimpleFragmenter(SNIPPET_LENGTH));
        try {
            // getBestFragments da tu sap xep giam dan theo diem mat do tu khop va loai bo
            // doan diem 0 (khong khop tu nao) - chi can gioi han so luong toi da o day.
            String[] fragments = highlighter.getBestFragments(analyzer, field, text, MAX_SNIPPETS);
            if (fragments.length > 0) {
                return Arrays.stream(fragments)
                        .map(fragment -> fragment.strip() + "...")
                        .toList();
            }
        } catch (IOException | InvalidTokenOffsetsException e) {
            // Roi vao truong hop hiem (loi tokenize doan text) - fallback ve cat dau van ban
            // ben duoi, khong de loi highlight lam hong ca ket qua tim kiem.
        }
        // Khong tim thay doan nao khop tu khoa trong field nay (vd. chi khop qua tieu de, hoac
        // query rong) - tra ve 1 doan duy nhat cat dau van ban.
        return List.of(text.length() > SNIPPET_LENGTH ? text.substring(0, SNIPPET_LENGTH) + "..." : text);
    }
}
