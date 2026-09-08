package com.hust.it4863.searchapi.config;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Beans dung chung cho ca IndexingService va SearchService: 1 Directory, 1 Analyzer. */
@Configuration
public class LuceneConfig {

    @Value("${app.lucene.index-dir}")
    private String indexDir;

    @Bean
    public Directory luceneDirectory() throws IOException {
        Path path = Path.of(indexDir);
        Files.createDirectories(path);
        return FSDirectory.open(path);
    }

    @Bean
    public Analyzer luceneAnalyzer() {
        return new StandardAnalyzer();
    }
}
