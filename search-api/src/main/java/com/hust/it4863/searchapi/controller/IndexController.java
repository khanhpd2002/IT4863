package com.hust.it4863.searchapi.controller;

import com.hust.it4863.searchapi.model.DocumentDTO;
import com.hust.it4863.searchapi.service.DocumentParserService;
import com.hust.it4863.searchapi.service.IndexingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/documents")
public class IndexController {

    private final DocumentParserService parserService;
    private final IndexingService indexingService;

    @Value("${app.data.raw-dir}")
    private String rawDir;

    public IndexController(DocumentParserService parserService, IndexingService indexingService) {
        this.parserService = parserService;
        this.indexingService = indexingService;
    }

    /** Ingest 1 hoac nhieu file (.txt/.doc/.docx/.json) qua multipart form-data. */
    @PostMapping
    public ResponseEntity<?> ingest(@RequestParam("files") List<MultipartFile> files) {
        List<DocumentDTO> allDocs = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                allDocs.addAll(parserService.parse(file.getOriginalFilename(), file.getInputStream()));
            }
            int indexed = indexingService.indexDocuments(allDocs);
            return ResponseEntity.ok(Map.of("indexed", indexed));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Indexing failed: " + e.getMessage()));
        }
    }

    /** Quet toan bo app.data.raw-dir va index moi file tim thay - tien loi cho demo, khong can multipart. */
    @PostMapping("/import-raw")
    public ResponseEntity<?> importRaw() {
        Path dir = Path.of(rawDir);
        if (!Files.isDirectory(dir)) {
            return ResponseEntity.badRequest().body(Map.of("error", "raw-dir not found: " + rawDir));
        }
        List<DocumentDTO> allDocs = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                try (var in = Files.newInputStream(path)) {
                    allDocs.addAll(parserService.parse(path.getFileName().toString(), in));
                }
            }
            int indexed = indexingService.indexDocuments(allDocs);
            return ResponseEntity.ok(Map.of("indexed", indexed, "sourceFiles", allDocs.size()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Import failed: " + e.getMessage()));
        }
    }
}
