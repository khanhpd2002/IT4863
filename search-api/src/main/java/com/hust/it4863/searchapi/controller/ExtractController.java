package com.hust.it4863.searchapi.controller;

import com.hust.it4863.searchapi.extract.ExtractionService;
import com.hust.it4863.searchapi.extract.LinkGraphService;
import com.hust.it4863.searchapi.extract.model.FetchStatus;
import com.hust.it4863.searchapi.extract.model.LinkDoc;
import com.hust.it4863.searchapi.extract.model.ResourceDoc;
import com.hust.it4863.searchapi.extract.model.ResourceType;
import com.hust.it4863.searchapi.mongosearch.MongoSearchService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;

/**
 * Boc tach noi dung tu danh sach link (HTML/PDF/anh), luu MongoDB, tong hop do thi lien ket.
 * Can MongoDB dang chay: cd search-api && docker compose up -d
 */
@RestController
@RequestMapping("/api/extract")
public class ExtractController {

    /** Chi tiet 1 resource + nguon gioi thieu (cac trang lien ket toi no, kem van ban lien ket). */
    public record ResourceDetail(ResourceDoc resource, List<LinkDoc> sources) {
    }

    private final ExtractionService extractionService;
    private final LinkGraphService graphService;
    private final MongoSearchService mongoSearchService;

    public ExtractController(ExtractionService extractionService, LinkGraphService graphService,
                             MongoSearchService mongoSearchService) {
        this.extractionService = extractionService;
        this.graphService = graphService;
        this.mongoSearchService = mongoSearchService;
    }

    /**
     * Boc tach danh sach URL doc tu file app.extract.urls-file tren server (mac dinh
     * data/extract-urls.txt) - khong nhan body. Muon doi URL thi sua file do.
     *
     * @param maxDepth null = dung app.extract.max-depth
     */
    @PostMapping
    public ResponseEntity<?> extract(@RequestParam(required = false) Integer maxDepth) throws IOException {
        try {
            ExtractionService.ExtractReport report = extractionService.extractFromUrlsFile(maxDepth);
            // Du lieu Mongo vua thay doi -> build lai index tim kiem de /api/mongo-search thay ngay.
            mongoSearchService.rebuildIndex();
            return ResponseEntity.ok(report);
        } catch (NoSuchFileException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Chua co file danh sach URL: " + e.getFile()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/resources")
    public List<ResourceDoc> listResources(@RequestParam(required = false) ResourceType type,
                                           @RequestParam(required = false) FetchStatus status,
                                           @RequestParam(defaultValue = "100") int limit) {
        return extractionService.listResources(type, status, Math.min(Math.max(limit, 1), 1000));
    }

    @GetMapping("/resource")
    public ResponseEntity<?> resource(@RequestParam String url) {
        return extractionService.findResource(url)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(new ResourceDetail(r, extractionService.findSources(r.url()))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Chua co resource cho URL: " + url)));
    }

    /** Do thi lien ket doc tu MongoDB, dang text: moi dong "nguon --> dich : van ban mo ta". */
    @GetMapping(value = "/graph", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String graph() {
        return graphService.renderGraph();
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<Map<String, String>> mongoUnavailable(DataAccessResourceFailureException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "Khong ket noi duoc MongoDB - chay 'docker compose up -d' trong search-api/",
                "detail", String.valueOf(e.getMostSpecificCause().getMessage())));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> mongoError(DataAccessException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Loi MongoDB", "detail", String.valueOf(e.getMostSpecificCause().getMessage())));
    }
}
