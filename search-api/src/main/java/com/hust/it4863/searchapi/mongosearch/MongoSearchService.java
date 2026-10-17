package com.hust.it4863.searchapi.mongosearch;

import com.hust.it4863.searchapi.extract.model.FetchStatus;
import com.hust.it4863.searchapi.extract.model.ResourceDoc;
import com.hust.it4863.searchapi.extract.model.ResourceType;
import com.hust.it4863.searchapi.service.SnippetExtractor;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tim kiem tren du lieu da boc tach luu trong MongoDB (collection resources), xep hang bang
 * TF-IDF hoac BM25 tuy nguoi dung chon.
 *
 * MongoDB khong co san TF-IDF/BM25, nen du lieu duoc dua sang 1 Lucene index rieng
 * (app.mongo-search.index-dir, tach biet voi index cua /api/search): rebuildIndex() doc lai
 * toan bo resource co noi dung tu Mongo. Index duoc build lai tu dong sau moi lan
 * POST /api/extract, hoac goi tay POST /api/mongo-search/index.
 */
@Service
public class MongoSearchService {

    static final String F_ID = "id";
    static final String F_URL = "url";
    static final String F_TYPE = "type";
    static final String F_TITLE = "title";
    static final String F_AUTHOR = "author";
    static final String F_PUBLISHED_AT = "publishedAt";
    static final String F_CONTENT = "content";
    /** Field duoc cham diem khi tim kiem: tu khoa khop o tieu de hoac noi dung deu duoc tinh. */
    static final String[] SEARCH_FIELDS = {F_TITLE, F_CONTENT};
    private static final String COMMIT_INDEXED_AT = "indexedAt";

    public record SearchHit(int rank, String id, String url, String title, ResourceType type, String author,
                            String publishedAt, float score, List<String> snippets, String explanation) {
    }

    /**
     * @param params      tham so cua thuat toan (BM25: k1, b); rong voi TF-IDF
     * @param totalHits   tong so tai lieu khop (khong chi topN)
     */
    public record SearchResponse(String query, SearchAlgorithm algorithm, Map<String, Float> params,
                                 ResourceType typeFilter, long totalHits, int indexedDocuments,
                                 List<SearchHit> results) {
    }

    /** Trang thai index; indexedAt null neu chua build lan nao. */
    public record IndexStatus(String indexDir, int documents, Instant indexedAt) {
    }

    private final MongoTemplate mongo;
    private final Analyzer analyzer;
    private final SnippetExtractor snippetExtractor;
    private final Path indexPath;

    public MongoSearchService(MongoTemplate mongo, Analyzer analyzer, SnippetExtractor snippetExtractor,
                              @Value("${app.mongo-search.index-dir}") String indexDir) {
        this.mongo = mongo;
        this.analyzer = analyzer;
        this.snippetExtractor = snippetExtractor;
        this.indexPath = Path.of(indexDir);
    }

    // ---------------------------------------------------------------------------------------
    // Index: MongoDB -> Lucene
    // ---------------------------------------------------------------------------------------

    /**
     * Xoa va build lai toan bo index tu MongoDB: moi resource HTML/PDF da boc tach (FETCHED) va
     * co noi dung la 1 document. synchronized: 2 lan build dong thoi se tranh nhau write.lock.
     */
    public synchronized IndexStatus rebuildIndex() throws IOException {
        Files.createDirectories(indexPath);
        org.springframework.data.mongodb.core.query.Query query = org.springframework.data.mongodb.core.query.Query.query(
                Criteria.where("status").is(FetchStatus.FETCHED.name())
                        .and("type").in(ResourceType.HTML.name(), ResourceType.PDF.name())
                        .and("contentLength").gt(0));

        // CREATE = ghi de index cu; commitOnClose=false: chi commit() tuong minh ben duoi moi thay
        // the index cu. Neu doc Mongo loi giua chung (vd. Mongo tat), writer dong ma khong commit
        // -> index cu van nguyen ven (mac dinh IndexWriter tu commit khi close -> index rong).
        IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                .setCommitOnClose(false);
        try (Directory directory = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(directory, config);
             Stream<ResourceDoc> resources = mongo.stream(query, ResourceDoc.class)) {
            for (ResourceDoc r : (Iterable<ResourceDoc>) resources::iterator) {
                writer.addDocument(toLuceneDocument(r));
            }
            // Luu thoi diem build vao chinh commit cua index -> GET /index doc lai duoc.
            writer.setLiveCommitData(Map.of(COMMIT_INDEXED_AT, Instant.now().toString()).entrySet());
            writer.commit();
        }
        return status();
    }

    private Document toLuceneDocument(ResourceDoc r) {
        Document doc = new Document();
        doc.add(new StringField(F_ID, r.id(), Field.Store.YES));
        doc.add(new StringField(F_TYPE, r.type().name(), Field.Store.YES));
        doc.add(new StoredField(F_URL, r.url()));
        doc.add(new TextField(F_TITLE, r.title() == null ? "" : r.title(), Field.Store.YES));
        doc.add(new TextField(F_CONTENT, r.content(), Field.Store.YES));
        if (r.author() != null) {
            doc.add(new StoredField(F_AUTHOR, r.author()));
        }
        if (r.publishedAt() != null) {
            doc.add(new StoredField(F_PUBLISHED_AT, r.publishedAt().toString()));
        }
        return doc;
    }

    public IndexStatus status() throws IOException {
        if (!Files.isDirectory(indexPath)) {
            return new IndexStatus(indexPath.toAbsolutePath().toString(), 0, null);
        }
        try (Directory directory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(directory)) {
                return new IndexStatus(indexPath.toAbsolutePath().toString(), 0, null);
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                String indexedAt = reader.getIndexCommit().getUserData().get(COMMIT_INDEXED_AT);
                return new IndexStatus(indexPath.toAbsolutePath().toString(), reader.numDocs(),
                        indexedAt == null ? null : Instant.parse(indexedAt));
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------

    /**
     * @param typeFilter null = ca HTML va PDF
     * @param k1, b      chi dung cho BM25, null = mac dinh (1.2 / 0.75)
     * @param explain    true = kem giai thich chi tiet cach Lucene tinh diem tung ket qua
     */
    public SearchResponse search(String queryText, SearchAlgorithm algorithm, ResourceType typeFilter,
                                 int topN, Float k1, Float b, boolean explain) throws IOException, ParseException {
        Similarity similarity = algorithm.similarity(k1, b);
        Map<String, Float> params = algorithm == SearchAlgorithm.BM25
                ? Map.of("k1", k1 != null ? k1 : SearchAlgorithm.DEFAULT_K1, "b", b != null ? b : SearchAlgorithm.DEFAULT_B)
                : Map.of();

        Query textQuery = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer).parse(queryText);
        Query query = typeFilter == null ? textQuery : new BooleanQuery.Builder()
                .add(textQuery, BooleanClause.Occur.MUST)
                // FILTER: chi loc, khong cong diem -> diem van chi den tu TF-IDF/BM25 cua tu khoa.
                .add(new TermQuery(new Term(F_TYPE, typeFilter.name())), BooleanClause.Occur.FILTER)
                .build();

        if (!Files.isDirectory(indexPath)) {
            return new SearchResponse(queryText, algorithm, params, typeFilter, 0, 0, List.of());
        }
        try (Directory directory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(directory)) {
                return new SearchResponse(queryText, algorithm, params, typeFilter, 0, 0, List.of());
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(similarity);
                TopDocs topDocs = searcher.search(query, topN);

                List<SearchHit> hits = new ArrayList<>();
                int rank = 1;
                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    hits.add(new SearchHit(
                            rank++,
                            doc.get(F_ID),
                            doc.get(F_URL),
                            doc.get(F_TITLE),
                            ResourceType.valueOf(doc.get(F_TYPE)),
                            doc.get(F_AUTHOR),
                            doc.get(F_PUBLISHED_AT),
                            sd.score,
                            snippetExtractor.snippets(textQuery, F_CONTENT, doc.get(F_CONTENT)),
                            explain ? searcher.explain(query, sd.doc).toString() : null));
                }
                return new SearchResponse(queryText, algorithm, params, typeFilter,
                        topDocs.totalHits.value, reader.numDocs(), hits);
            }
        }
    }
}
