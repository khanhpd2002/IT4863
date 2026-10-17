package com.hust.it4863.searchapi.extract.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Collection "resources": moi URL la 1 node cua do thi lien ket. Truong nao co gia tri tuy
 * theo type - xem docs/MONGO_SCHEMA.md. Ghi bang ExtractionService (upsert qua MongoTemplate).
 */
@Document("resources")
public record ResourceDoc(
        @Id String id,
        String url,
        String finalUrl,
        ResourceType type,
        FetchStatus status,
        boolean seed,
        int depth,
        Integer httpStatus,
        String contentType,
        String title,
        String author,
        Instant publishedAt,
        String publishedAtRaw,
        String content,
        Integer contentLength,
        Integer pageCount,
        String error,
        Instant firstSeenAt,
        Instant fetchedAt) {
}
