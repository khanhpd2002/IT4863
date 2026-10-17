package com.hust.it4863.searchapi.extract.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Collection "links": 1 canh  sourceUrl --> targetUrl : anchorText  cua do thi lien ket. */
@Document("links")
public record LinkDoc(
        @Id String id,
        String sourceUrl,
        String targetUrl,
        String anchorText,
        LinkKind kind,
        ResourceType targetType,
        boolean inMainContent,
        Instant discoveredAt) {
}
