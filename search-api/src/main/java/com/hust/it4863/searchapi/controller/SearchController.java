package com.hust.it4863.searchapi.controller;

import com.hust.it4863.searchapi.model.SearchResultDTO;
import com.hust.it4863.searchapi.service.SearchService;
import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/api/search")
    public ResponseEntity<?> search(@RequestParam("q") String q,
                                     @RequestParam(value = "topN", defaultValue = "10") int topN) {
        try {
            System.out.println("Something " + LocalDateTime.now());
            List<SearchResultDTO> results = searchService.search(q, topN);
            return ResponseEntity.ok(results);
        } catch (ParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid query syntax: " + e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Search failed: " + e.getMessage()));
        }
    }
}
