package com.hust.it4863.searchapi.service;

import com.hust.it4863.searchapi.model.SearchResultDTO;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.Directory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Truy van Lucene index, xep hang theo diem TF-IDF (ClassicSimilarity). */
@Service
public class SearchService {

    private final Directory directory;
    private final Analyzer analyzer;
    private final SnippetExtractor snippetExtractor;

    public SearchService(Directory directory, Analyzer analyzer, SnippetExtractor snippetExtractor) {
        this.directory = directory;
        this.analyzer = analyzer;
        this.snippetExtractor = snippetExtractor;
    }

    public List<SearchResultDTO> search(String queryText, int topN) throws IOException, ParseException {
        if (!DirectoryReader.indexExists(directory)) {
            return List.of();
        }
        try (IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new ClassicSimilarity());

            QueryParser parser = new QueryParser("content", analyzer);
            Query query = parser.parse(queryText);

            TopDocs topDocs = searcher.search(query, topN);
            List<SearchResultDTO> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                results.add(new SearchResultDTO(
                        doc.get("id"),
                        doc.get("title"),
                        scoreDoc.score,
                        snippetExtractor.snippets(query, "content", doc.get("content"))));
            }
            return results;
        }
    }
}
