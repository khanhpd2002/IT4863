package com.hust.it4863.searchapi.extract;

import com.hust.it4863.searchapi.extract.HtmlExtractor.ExtractedLink;
import com.hust.it4863.searchapi.extract.HtmlExtractor.HtmlContent;
import com.hust.it4863.searchapi.extract.PdfExtractor.PdfContent;
import com.hust.it4863.searchapi.extract.model.FetchStatus;
import com.hust.it4863.searchapi.extract.model.LinkDoc;
import com.hust.it4863.searchapi.extract.model.LinkKind;
import com.hust.it4863.searchapi.extract.model.ResourceDoc;
import com.hust.it4863.searchapi.extract.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Nhan danh sach URL, tai + boc tach tung URL theo loai (HTML / PDF / anh), duyet tiep cac
 * lien ket theo chieu rong (BFS) toi maxDepth, va luu vao MongoDB:
 *  - resources: moi URL 1 document (node)
 *  - links    : moi lien ket 1 document (canh nguon --> dich : van ban mo ta)
 *
 * Moi thao tac ghi deu la upsert theo _id on dinh (sha256 cua URL / cua canh), nen chay lai
 * cung danh sach URL se cap nhat chu khong sinh ban ghi trung.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);
    static final String RESOURCES = "resources";
    static final String LINKS = "links";

    /** Ket qua xu ly 1 URL trong lan chay. */
    public record UrlOutcome(String url, int depth, ResourceType type, FetchStatus status,
                             String title, int linkCount, String error) {
    }

    /**
     * Tong ket 1 lan chay. urlsFile = file danh sach URL da doc; notFetched = so URL con trong
     * hang doi khi cham gioi han maxPages.
     */
    public record ExtractReport(String urlsFile, int inputUrls, int processed, int fetched, int skipped,
                                int failed, int linksFound, int notFetched, List<UrlOutcome> outcomes) {
    }

    private record Task(String url, int depth) {
    }

    /** Ket qua xu ly 1 URL + cac lien ket con co the tai tiep (HTML/PDF). */
    private record Processed(UrlOutcome outcome, List<String> children) {
    }

    private final MongoTemplate mongo;
    private final HttpFetcher fetcher;
    private final HtmlExtractor htmlExtractor;
    private final PdfExtractor pdfExtractor;
    private final ExtractProperties props;

    public ExtractionService(MongoTemplate mongo, HttpFetcher fetcher, HtmlExtractor htmlExtractor,
                             PdfExtractor pdfExtractor, ExtractProperties props) {
        this.mongo = mongo;
        this.fetcher = fetcher;
        this.htmlExtractor = htmlExtractor;
        this.pdfExtractor = pdfExtractor;
        this.props = props;
    }

    /**
     * Doc danh sach URL tu file app.extract.urls-file tren server roi boc tach.
     *
     * @param maxDepthOverride null = dung app.extract.max-depth
     * @throws NoSuchFileException     neu file chua ton tai
     * @throws IllegalArgumentException neu file khong co URL nao
     */
    public ExtractReport extractFromUrlsFile(Integer maxDepthOverride) throws IOException {
        Path file = Path.of(props.urlsFile());
        List<String> urls = readUrlsFile(file);
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("File " + file.toAbsolutePath() + " khong co URL nao");
        }
        return extract(file.toAbsolutePath().toString(), urls, maxDepthOverride);
    }

    /** Moi dong 1 URL; dong trong va dong bat dau bang '#' (chu thich) bi bo qua. */
    static List<String> readUrlsFile(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    private ExtractReport extract(String urlsFile, List<String> rawUrls, Integer maxDepthOverride) {
        int maxDepth = maxDepthOverride != null ? Math.max(0, maxDepthOverride) : props.maxDepth();
        ensureIndexes();

        List<UrlOutcome> outcomes = new ArrayList<>();
        Deque<Task> queue = new ArrayDeque<>();
        Set<String> enqueued = new HashSet<>();
        for (String raw : rawUrls) {
            Optional<String> url = UrlUtils.normalize(raw);
            if (url.isEmpty()) {
                outcomes.add(new UrlOutcome(raw, 0, null, FetchStatus.FAILED, null, 0,
                        "URL khong hop le (chi ho tro http/https)"));
                continue;
            }
            if (enqueued.add(url.get())) {
                markSeed(url.get());
                queue.add(new Task(url.get(), 0));
            }
        }

        int fetchedNonSeed = 0;
        while (!queue.isEmpty()) {
            Task task = queue.peek();
            // URL dau vao luon duoc xu ly; gioi han maxPages chi ap dung cho URL tim thay qua lien ket.
            if (task.depth() > 0 && fetchedNonSeed >= props.maxPages()) {
                break;
            }
            queue.poll();
            if (task.depth() > 0) {
                fetchedNonSeed++;
            }
            Processed processed = process(task);
            outcomes.add(processed.outcome());
            if (task.depth() < maxDepth) {
                processed.children().stream()
                        .filter(enqueued::add)
                        .limit(props.maxOutlinksPerPage())
                        .forEach(child -> queue.add(new Task(child, task.depth() + 1)));
            }
        }

        return new ExtractReport(
                urlsFile,
                rawUrls.size(),
                outcomes.size(),
                count(outcomes, FetchStatus.FETCHED),
                count(outcomes, FetchStatus.SKIPPED),
                count(outcomes, FetchStatus.FAILED),
                outcomes.stream().mapToInt(UrlOutcome::linkCount).sum(),
                queue.size(),
                outcomes);
    }

    // ---------------------------------------------------------------------------------------
    // Xu ly 1 URL
    // ---------------------------------------------------------------------------------------

    private Processed process(Task task) {
        String url = task.url();
        log.info("Extract [depth={}] {}", task.depth(), url);
        HttpFetcher.FetchResult result;
        try {
            result = fetcher.fetch(url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(task, null, null, "Bi ngat khi dang tai");
        } catch (IOException | IllegalArgumentException e) {
            return failed(task, null, null, "Loi tai trang: " + e);
        }

        if (result.httpStatus() >= 400) {
            return failed(task, result.httpStatus(), result.contentType(), "HTTP " + result.httpStatus());
        }
        ResourceType type = UrlUtils.typeFromContentType(result.contentType())
                .orElseGet(() -> sniffType(result.body(), result.finalUrl()));
        if (result.tooLarge()) {
            upsertResource(url, fetchedBase(task, result, type, FetchStatus.SKIPPED,
                    "Vuot gioi han dung luong " + props.maxBytes() + " bytes"));
            return new Processed(outcome(task, type, FetchStatus.SKIPPED, null, 0,
                    "Vuot gioi han dung luong"), List.of());
        }

        try {
            return switch (type) {
                case HTML -> processHtml(task, result);
                case PDF -> processPdf(task, result);
                case IMAGE -> {
                    // Anh: chi can nguon gioi thieu (tu collection links), khong luu noi dung.
                    upsertResource(url, fetchedBase(task, result, type, FetchStatus.FETCHED, null));
                    yield new Processed(outcome(task, type, FetchStatus.FETCHED, null, 0, null), List.of());
                }
                case OTHER -> {
                    upsertResource(url, fetchedBase(task, result, type, FetchStatus.SKIPPED,
                            "Khong ho tro boc tach Content-Type: " + result.contentType()));
                    yield new Processed(outcome(task, type, FetchStatus.SKIPPED, null, 0,
                            "Khong ho tro boc tach " + result.contentType()), List.of());
                }
            };
        } catch (IOException | RuntimeException e) {
            log.warn("Extract failed for {}", url, e);
            return failed(task, result.httpStatus(), result.contentType(), "Loi boc tach: " + e);
        }
    }

    private Processed processHtml(Task task, HttpFetcher.FetchResult result) throws IOException {
        HtmlContent html = htmlExtractor.extract(result.body(), charsetOf(result.contentType()), result.finalUrl());
        Update update = fetchedBase(task, result, ResourceType.HTML, FetchStatus.FETCHED, null);
        setOrUnset(update, "title", html.title());
        setOrUnset(update, "author", html.author());
        setOrUnset(update, "publishedAt", html.publishedAt());
        setOrUnset(update, "publishedAtRaw", html.publishedAtRaw());
        update.set("content", html.content());
        update.set("contentLength", html.content().length());
        update.unset("pageCount");
        upsertResource(task.url(), update);

        List<ExtractedLink> links = html.links().stream()
                .filter(l -> !l.url().equals(task.url()))
                .toList();
        saveLinks(task, links);

        // Lien ket co the tai tiep: the <a> tro toi trang/PDF; uu tien lien ket trong noi dung chinh.
        List<String> children = links.stream()
                .filter(l -> l.kind() == LinkKind.A)
                .filter(l -> {
                    ResourceType guess = UrlUtils.guessType(l.url());
                    return guess == ResourceType.HTML || guess == ResourceType.PDF;
                })
                .sorted(Comparator.comparing(l -> !l.inMainContent()))
                .map(ExtractedLink::url)
                .distinct()
                .toList();
        return new Processed(outcome(task, ResourceType.HTML, FetchStatus.FETCHED, html.title(), links.size(), null),
                children);
    }

    private Processed processPdf(Task task, HttpFetcher.FetchResult result) throws IOException {
        PdfContent pdf = pdfExtractor.extract(result.body());
        Update update = fetchedBase(task, result, ResourceType.PDF, FetchStatus.FETCHED, null);
        setOrUnset(update, "title", pdf.title());
        setOrUnset(update, "author", pdf.author());
        update.set("content", pdf.text());
        update.set("contentLength", pdf.text().length());
        setOrUnset(update, "pageCount", pdf.pageCount());
        upsertResource(task.url(), update);
        return new Processed(outcome(task, ResourceType.PDF, FetchStatus.FETCHED, pdf.title(), 0, null), List.of());
    }

    private Processed failed(Task task, Integer httpStatus, String contentType, String error) {
        boolean previouslyFetched = mongo.exists(Query.query(Criteria.where("_id").is(UrlUtils.sha256(task.url()))
                .and("status").is(FetchStatus.FETCHED.name())), RESOURCES);
        if (previouslyFetched) {
            // Lan tai lai that bai (mang chap chon, server tam loi): giu nguyen du lieu da boc tach
            // truoc do va status FETCHED, chi ghi lai loi cua lan thu nay.
            upsertResource(task.url(), new Update()
                    .set("error", "Lan tai lai gan nhat that bai (giu du lieu cu): " + error)
                    .min("depth", task.depth()));
            return new Processed(outcome(task, null, FetchStatus.FAILED, null, 0,
                    error + " (giu du lieu lan tai truoc)"), List.of());
        }
        Update update = new Update()
                .set("status", FetchStatus.FAILED.name())
                .set("error", error)
                .set("fetchedAt", Instant.now())
                .setOnInsert("type", UrlUtils.guessType(task.url()).name())
                .setOnInsert("firstSeenAt", Instant.now())
                .setOnInsert("seed", false)
                .min("depth", task.depth());
        setOrUnset(update, "httpStatus", httpStatus);
        setOrUnset(update, "contentType", contentType);
        upsertResource(task.url(), update);
        return new Processed(outcome(task, null, FetchStatus.FAILED, null, 0, error), List.of());
    }

    // ---------------------------------------------------------------------------------------
    // Ghi MongoDB
    // ---------------------------------------------------------------------------------------

    /** URL trong danh sach dau vao: tao node neu chua co, danh dau seed = true, depth = 0. */
    private void markSeed(String url) {
        Update update = new Update()
                .set("seed", true)
                .min("depth", 0)
                .setOnInsert("type", UrlUtils.guessType(url).name())
                .setOnInsert("status", FetchStatus.DISCOVERED.name())
                .setOnInsert("firstSeenAt", Instant.now());
        upsertResource(url, update);
    }

    /** Cac truong chung cho 1 URL da tai duoc (ca HTML/PDF/anh/khac). */
    private Update fetchedBase(Task task, HttpFetcher.FetchResult result, ResourceType type, FetchStatus status,
                               String error) {
        Update update = new Update()
                .set("type", type.name())
                .set("status", status.name())
                .set("httpStatus", result.httpStatus())
                .set("fetchedAt", Instant.now())
                .setOnInsert("firstSeenAt", Instant.now())
                .setOnInsert("seed", false)
                .min("depth", task.depth());
        setOrUnset(update, "error", error);
        setOrUnset(update, "contentType", result.contentType());
        String finalUrl = UrlUtils.normalize(result.finalUrl()).orElse(result.finalUrl());
        setOrUnset(update, "finalUrl", finalUrl.equals(task.url()) ? null : finalUrl);
        return update;
    }

    private void upsertResource(String url, Update update) {
        update.setOnInsert("url", url);
        mongo.upsert(Query.query(Criteria.where("_id").is(UrlUtils.sha256(url))), update, RESOURCES);
    }

    /**
     * Ghi canh + node dich (status DISCOVERED neu chua tung thay) bang bulk upsert - 1 trang co the
     * co vai tram lien ket, ghi tung cai se rat cham.
     */
    private void saveLinks(Task task, List<ExtractedLink> links) {
        if (links.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        BulkOperations resourceOps = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, RESOURCES);
        BulkOperations linkOps = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, LinkDoc.class, LINKS);
        Set<String> targets = new HashSet<>();
        for (ExtractedLink link : links) {
            ResourceType targetType = link.kind() == LinkKind.IMG ? ResourceType.IMAGE : UrlUtils.guessType(link.url());
            if (targets.add(link.url())) {
                resourceOps.upsert(
                        Query.query(Criteria.where("_id").is(UrlUtils.sha256(link.url()))),
                        new Update()
                                .setOnInsert("url", link.url())
                                .setOnInsert("type", targetType.name())
                                .setOnInsert("status", FetchStatus.DISCOVERED.name())
                                .setOnInsert("seed", false)
                                .setOnInsert("firstSeenAt", now)
                                .min("depth", task.depth() + 1));
            }
            String linkId = UrlUtils.sha256(task.url(), link.url(), link.kind().name(), link.anchorText());
            linkOps.upsert(
                    Query.query(Criteria.where("_id").is(linkId)),
                    new Update()
                            .setOnInsert("sourceUrl", task.url())
                            .setOnInsert("targetUrl", link.url())
                            .setOnInsert("anchorText", link.anchorText())
                            .setOnInsert("kind", link.kind().name())
                            .setOnInsert("discoveredAt", now)
                            .set("targetType", targetType.name())
                            .set("inMainContent", link.inMainContent()));
        }
        resourceOps.execute();
        linkOps.execute();
    }

    /**
     * Tao index neu chua co (trung ten + dinh nghia voi mongo/init/01-schema.js, nen goi lai
     * khong loi). Goi o day thay vi luc khoi dong de search-api van chay duoc khi Mongo tat.
     */
    void ensureIndexes() {
        mongo.indexOps(RESOURCES).ensureIndex(new Index("url", Sort.Direction.ASC).unique().named("uk_url"));
        mongo.indexOps(RESOURCES).ensureIndex(new Index("type", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).named("ix_type_status"));
        mongo.indexOps(LINKS).ensureIndex(new Index("sourceUrl", Sort.Direction.ASC).named("ix_source"));
        mongo.indexOps(LINKS).ensureIndex(new Index("targetUrl", Sort.Direction.ASC).named("ix_target"));
    }

    // ---------------------------------------------------------------------------------------
    // Tien ich
    // ---------------------------------------------------------------------------------------

    /** Server khong khai bao Content-Type ro rang: doan tu vai byte dau, roi tu duoi file. */
    private static ResourceType sniffType(byte[] body, String url) {
        if (body != null && body.length >= 4) {
            String head = new String(body, 0, Math.min(body.length, 1024), StandardCharsets.ISO_8859_1)
                    .stripLeading().toLowerCase(Locale.ROOT);
            if (head.startsWith("%pdf")) {
                return ResourceType.PDF;
            }
            if (head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<body")) {
                return ResourceType.HTML;
            }
        }
        return UrlUtils.guessType(url);
    }

    private static String charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String p = part.strip();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String name = p.substring("charset=".length()).replace("\"", "").strip();
                return Charset.isSupported(name) ? name : null;
            }
        }
        return null;
    }

    /** Validator $jsonSchema khong cho gia tri null -> null thi xoa truong thay vi ghi null. */
    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }

    private static UrlOutcome outcome(Task task, ResourceType type, FetchStatus status, String title,
                                      int linkCount, String error) {
        return new UrlOutcome(task.url(), task.depth(), type, status, title, linkCount, error);
    }

    private static int count(List<UrlOutcome> outcomes, FetchStatus status) {
        return (int) outcomes.stream().filter(o -> o.status() == status).count();
    }

    /** Doc 1 resource theo URL (da chuan hoa), dung cho API xem chi tiet. */
    public Optional<ResourceDoc> findResource(String rawUrl) {
        return UrlUtils.normalize(rawUrl)
                .map(url -> mongo.findById(UrlUtils.sha256(url), ResourceDoc.class, RESOURCES));
    }

    /** "Nguon gioi thieu" cua 1 URL: cac lien ket co dich la URL do. */
    public List<LinkDoc> findSources(String url) {
        return mongo.find(Query.query(Criteria.where("targetUrl").is(url))
                .with(Sort.by("sourceUrl")), LinkDoc.class, LINKS);
    }

    /** Danh sach resource (khong kem content cho nhe), loc theo type/status neu co. */
    public List<ResourceDoc> listResources(ResourceType type, FetchStatus status, int limit) {
        Query query = new Query();
        if (type != null) {
            query.addCriteria(Criteria.where("type").is(type.name()));
        }
        if (status != null) {
            query.addCriteria(Criteria.where("status").is(status.name()));
        }
        query.fields().exclude("content");
        query.with(Sort.by(Sort.Direction.ASC, "depth").and(Sort.by("url"))).limit(limit);
        return mongo.find(query, ResourceDoc.class, RESOURCES);
    }
}
