package com.hust.it4863.searchapi.cli;

import com.hust.it4863.searchapi.model.DocumentDTO;
import com.hust.it4863.searchapi.model.SearchResultDTO;
import com.hust.it4863.searchapi.service.DocumentParserService;
import com.hust.it4863.searchapi.service.IndexExportService;
import com.hust.it4863.searchapi.service.IndexingService;
import com.hust.it4863.searchapi.service.SearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * CLI thay the cho REST API - dung khi chay voi profile "cli", khong bat web server.
 *
 * Vi du (chay tu thu muc search-api/):
 *   mvn spring-boot:run -Dspring-boot.run.profiles=cli -Dspring-boot.run.arguments=index
 *   mvn spring-boot:run -Dspring-boot.run.profiles=cli -Dspring-boot.run.arguments="search,may tinh"
 *   mvn spring-boot:run -Dspring-boot.run.profiles=cli -Dspring-boot.run.arguments=export-index
 */
@Component
@Profile("cli")
public class DemoCliRunner implements ApplicationRunner {

    private final DocumentParserService parserService;
    private final IndexingService indexingService;
    private final SearchService searchService;
    private final IndexExportService exportService;

    @Value("${app.data.raw-dir}")
    private String rawDir;

    public DemoCliRunner(DocumentParserService parserService,
                          IndexingService indexingService,
                          SearchService searchService,
                          IndexExportService exportService) {
        this.parserService = parserService;
        this.indexingService = indexingService;
        this.searchService = searchService;
        this.exportService = exportService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Dung getNonOptionArgs() thay vi args tho, vi Spring Boot dua ca cac co dang
        // "--spring.profiles.active=cli" vao args tho, lam sai lech vi tri arg[0].
        List<String> commandArgs = args.getNonOptionArgs();
        if (commandArgs.isEmpty()) {
            System.out.println("Usage: index | search <query> | export-index");
            return;
        }
        String command = commandArgs.get(0);
        switch (command) {
            case "index" -> runIndex();
            case "search" -> runSearch(String.join(" ", commandArgs.subList(1, commandArgs.size())));
            case "export-index" -> runExportIndex();
            default -> System.out.println("Unknown command: " + command
                    + " (dung 'index', 'search <query>' hoac 'export-index')");
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

    private void runExportIndex() throws Exception {
        var path = exportService.exportToFile();
        System.out.println("Da xuat chi muc nguoc ra: " + path.toAbsolutePath());
    }
}
