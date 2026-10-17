package com.hust.it4863.searchapi.controller;

import com.hust.it4863.searchapi.extract.model.ResourceType;
import com.hust.it4863.searchapi.mongosearch.MongoSearchService;
import com.hust.it4863.searchapi.mongosearch.SearchAlgorithm;
import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * Tim kiem tren du lieu da boc tach (MongoDB), chon thuat toan xep hang TF-IDF hoac BM25.
 * Khac /api/search (tim tren file trong data/raw, chi TF-IDF).
 */
@RestController
@RequestMapping("/api/mongo-search")
public class MongoSearchController {

    private final MongoSearchService searchService;

    public MongoSearchController(MongoSearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * @param algorithm TFIDF | BM25 (mac dinh BM25)
     * @param type      HTML | PDF de chi tim 1 loai; bo trong = ca hai
     * @param k1        BM25: muc bao hoa tf, >= 0 (mac dinh 1.2)
     * @param b         BM25: muc chuan hoa theo do dai van ban, 0..1 (mac dinh 0.75)
     * @param explain   true = kem giai thich cach tinh diem cua Lucene cho tung ket qua
     */
    @GetMapping
    public ResponseEntity<?> search(@RequestParam("q") String q,
                                    @RequestParam(defaultValue = "BM25") SearchAlgorithm algorithm,
                                    @RequestParam(required = false) ResourceType type,
                                    @RequestParam(defaultValue = "10") int topN,
                                    @RequestParam(required = false) Float k1,
                                    @RequestParam(required = false) Float b,
                                    @RequestParam(defaultValue = "false") boolean explain) throws IOException {
        if (q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tham so 'q' khong duoc rong"));
        }
        if (type != null && type != ResourceType.HTML && type != ResourceType.PDF) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Chi tim duoc type=HTML hoac PDF (anh va loai khac khong co noi dung de index)"));
        }
        if (algorithm == SearchAlgorithm.TFIDF && (k1 != null || b != null)) {
            return ResponseEntity.badRequest().body(Map.of("error", "k1 va b chi dung cho algorithm=BM25"));
        }
        try {
            return ResponseEntity.ok(searchService.search(q, algorithm, type,
                    Math.min(Math.max(topN, 1), 100), k1, b, explain));
        } catch (ParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cu phap truy van khong hop le: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            // BM25Similarity tu kiem tra k1 >= 0, 0 <= b <= 1
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Build lai index tu MongoDB (tu dong chay sau POST /api/extract; goi tay khi sua du lieu Mongo). */
    @PostMapping("/index")
    public MongoSearchService.IndexStatus rebuildIndex() throws IOException {
        return searchService.rebuildIndex();
    }

    /** So tai lieu dang co trong index va thoi diem build gan nhat. */
    @GetMapping("/index")
    public MongoSearchService.IndexStatus indexStatus() throws IOException {
        return searchService.status();
    }

    /** Gia tri enum sai (vd. algorithm=BM26) -> bao ro cac gia tri hop le thay vi loi 400 chung chung. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> badParam(MethodArgumentTypeMismatchException e) {
        Class<?> type = e.getRequiredType();
        String allowed = type != null && type.isEnum()
                ? " (hop le: " + Arrays.toString(type.getEnumConstants()) + ")" : "";
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Gia tri khong hop le cho '" + e.getName() + "': " + e.getValue() + allowed));
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<Map<String, String>> mongoUnavailable(DataAccessResourceFailureException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "Khong ket noi duoc MongoDB - chay 'docker compose up -d' trong search-api/",
                "detail", String.valueOf(e.getMostSpecificCause().getMessage())));
    }
}
