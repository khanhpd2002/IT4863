package com.hust.it4863.searchapi.controller;

import com.hust.it4863.searchapi.service.IndexExportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;

@RestController
public class IndexExportController {

    private final IndexExportService exportService;

    public IndexExportController(IndexExportService exportService) {
        this.exportService = exportService;
    }

    /** Xem chi muc nguoc hien tai dang text thuan, dong thoi ghi lai file (phong khi index bi doi ngoai luong ingest). */
    @GetMapping(value = "/api/index/export", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> export() {
        try {
            Path path = exportService.exportToFile();
            String content = exportService.buildReadableInvertedIndex();
            return ResponseEntity.ok()
                    .header("X-Export-File", path.toAbsolutePath().toString())
                    .body(content);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Export failed: " + e.getMessage());
        }
    }
}
