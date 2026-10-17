package com.hust.it4863.searchapi.extract;

import com.hust.it4863.searchapi.extract.model.LinkDoc;
import com.hust.it4863.searchapi.extract.model.LinkKind;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.stream.Stream;

/**
 * Tong hop do thi lien ket doc truc tiep tu MongoDB (collection links), moi canh 1 dong:
 *   nguon --> dich : van ban mo ta
 * Lien ket tu the img duoc danh dau "[anh]" truoc van ban mo ta (alt).
 */
@Service
public class LinkGraphService {

    private static final String NO_TEXT = "(khong co van ban mo ta)";

    private final MongoTemplate mongo;

    public LinkGraphService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public String renderGraph() {
        long nodeCount = mongo.getCollection(ExtractionService.RESOURCES).countDocuments();
        long edgeCount = mongo.getCollection(ExtractionService.LINKS).countDocuments();
        Query query = new Query().with(Sort.by("sourceUrl", "targetUrl", "anchorText"));

        StringBuilder sb = new StringBuilder()
                .append("=== DO THI LIEN KET (nguon --> dich : van ban mo ta) ===\n")
                .append("So node (resources): ").append(nodeCount)
                .append(" | So canh (links): ").append(edgeCount).append('\n');
        // stream() doc theo cursor, khong nap het collection links vao List truoc.
        try (Stream<LinkDoc> links = mongo.stream(query, LinkDoc.class, ExtractionService.LINKS)) {
            links.forEach(link -> sb.append(formatEdge(link)).append('\n'));
        }
        return sb.toString();
    }

    static String formatEdge(LinkDoc link) {
        String text = link.anchorText() == null || link.anchorText().isBlank() ? NO_TEXT : link.anchorText();
        if (link.kind() == LinkKind.IMG) {
            text = "[anh] " + text;
        }
        return link.sourceUrl() + " --> " + link.targetUrl() + " : " + text;
    }
}
