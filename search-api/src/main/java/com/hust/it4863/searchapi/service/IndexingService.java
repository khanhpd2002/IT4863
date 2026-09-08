package com.hust.it4863.searchapi.service;

import com.hust.it4863.searchapi.model.DocumentDTO;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.Directory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/** Ghi DocumentDTO vao Lucene index, dung ClassicSimilarity (TF-IDF) - khong dung BM25 mac dinh. */
@Service
public class IndexingService {

    private final Directory directory;
    private final Analyzer analyzer;

    public IndexingService(Directory directory, Analyzer analyzer) {
        this.directory = directory;
        this.analyzer = analyzer;
    }

    /** @return so document da index */
    public int indexDocuments(List<DocumentDTO> documents) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setSimilarity(new ClassicSimilarity());

        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (DocumentDTO doc : documents) {
                Document luceneDoc = new Document();
                luceneDoc.add(new StringField("id", safe(doc.getId()), Field.Store.YES));
                luceneDoc.add(new TextField("title", safe(doc.getTitle()), Field.Store.YES));
                luceneDoc.add(new TextField("content", safe(doc.getContent()), Field.Store.YES));
                // updateDocument = upsert theo id, tranh trung lap khi re-index cung tai lieu
                writer.updateDocument(new Term("id", safe(doc.getId())), luceneDoc);
            }
            writer.commit();
            return documents.size();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
