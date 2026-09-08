package com.hust.it4863.searchapi.cli;

import com.hust.it4863.searchapi.model.DocumentDTO;
import com.hust.it4863.searchapi.model.SearchResultDTO;
import com.hust.it4863.searchapi.service.DocumentParserService;
import com.hust.it4863.searchapi.service.IndexingService;
import com.hust.it4863.searchapi.service.SearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * CLI thay the cho REST API - dung khi chay voi profile "cli", khong bat web server.
 *
 * Vi du (chay tu thu muc search-api/):
 *   mvn spring-boot:run -Dspring-boot.run.profiles=cli -Dspring-boot.run.arguments=index
 *   mvn spring-boot:run -Dspring-boot.run.profiles=cli -Dspring-boot.run.arguments="search,may tinh"
 */
@Component
@Profile("cli")
public class DemoCliRunner implements CommandLineRunner {

    private final DocumentParserService parserService;
    private final IndexingService indexingService;
    private final SearchService searchService;

    @Value("${app.data.raw-dir}")
    private String rawDir;

    public DemoCliRunner(DocumentParserService parserService,
                          IndexingService indexingService,
                          SearchService searchService) {
        this.parserService = parserService;
        this.indexingService = indexingService;
        this.searchService = searchService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: index | search <query>");
            return;
        }
        switch (args[0]) {
            case "index" -> runIndex();
            case "search" -> runSearch(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            default -> System.out.println("Unknown command: " + args[0] + " (dung 'index' hoac 'search <query>')");
        }
    }

    private void runIndex() throws Exception {
        Path dir = Path.of(rawDir);
        if (!Files.isDirectory(dir)) {
            System.out.println("raw-dir not found: " + rawDir);
            return;
        }
        List<DocumentDTO> allDocs = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                try (var in = Files.newInputStream(path)) {
                    allDocs.addAll(parserService.parse(path.getFileName().toString(), in));
                }
            }
        }
        int indexed = indexingService.indexDocuments(allDocs);
        System.out.println("Indexed " + indexed + " document(s) from " + rawDir);
    }

    private void runSearch(String query) throws Exception {
        if (query.isBlank()) {
            System.out.println("Usage: search <query>");
            return;
        }
        List<SearchResultDTO> results = searchService.search(query, 10);
        if (results.isEmpty()) {
            System.out.println("No results.");
            return;
        }
        for (SearchResultDTO r : results) {
            System.out.printf("[%.4f] %s (id=%s)%n    %s%n", r.score(), r.title(), r.id(), r.snippet());
        }
    }
}
