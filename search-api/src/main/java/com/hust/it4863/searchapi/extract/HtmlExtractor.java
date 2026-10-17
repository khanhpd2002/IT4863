package com.hust.it4863.searchapi.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hust.it4863.searchapi.extract.model.LinkKind;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.extended.Readability4JExtended;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Boc tach 1 trang HTML: tieu de, ngay dang, tac gia, noi dung chinh, va danh sach lien ket
 * ra ngoai (the a + the img) kem van ban mo ta.
 *
 * - Noi dung chinh: Readability4J (Mozilla Readability) loai bo menu/quang cao/footer.
 * - Tieu de / tac gia / ngay dang: uu tien metadata co cau truc (meta og:/article:, JSON-LD
 *   schema.org, the time) vi chinh xac hon doan tu text; Readability chi la fallback.
 */
@Component
public class HtmlExtractor {

    private static final Logger log = LoggerFactory.getLogger(HtmlExtractor.class);
    private static final int MAX_ANCHOR_LENGTH = 500;
    private static final int MAX_AUTHOR_LENGTH = 200;
    /** Readability ra it hon so ky tu nay = nhieu kha nang da chon nham vung (footer/menu). */
    private static final int MIN_READABLE_LENGTH = 300;

    public record ExtractedLink(String url, String anchorText, LinkKind kind, boolean inMainContent) {
    }

    public record HtmlContent(String title, String author, Instant publishedAt, String publishedAtRaw,
                              String content, List<ExtractedLink> links) {
    }

    /**
     * Thong tin lay tu JSON-LD (schema.org Article/NewsArticle/...), co the null tung truong.
     * personAuthor = author co @type Person (ten phong vien); orgAuthor = author la to chuc/ten bao.
     */
    private record JsonLdInfo(String headline, String personAuthor, String orgAuthor, String datePublished) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param charset charset tu header Content-Type; null = de Jsoup tu nhan dien (BOM / meta charset)
     * @param pageUrl URL cua trang (sau redirect) - dung de doi lien ket tuong doi thanh tuyet doi
     */
    public HtmlContent extract(byte[] body, String charset, String pageUrl) throws IOException {
        Document doc = Jsoup.parse(new ByteArrayInputStream(body), charset, pageUrl);
        JsonLdInfo jsonLd = readJsonLd(doc);
        Article article = runReadability(doc, pageUrl);
        Element articleContent = headingBlockIfReadabilityFailed(doc,
                article == null ? null : article.getArticleContent());

        // Heading dau tien trong noi dung chinh dung truoc <title>: nhieu site (vd. ctt.hust.edu.vn)
        // de <title> la ten site giong nhau o moi trang, tieu de that nam o h1-h3 cua bai.
        String title = firstNonBlank(
                meta(doc, "meta[property=og:title]"),
                jsonLd.headline(),
                shortText(articleContent == null ? null : articleContent.selectFirst("h1, h2, h3"), 300),
                article == null ? null : article.getTitle(),
                doc.title(),
                textOf(doc.selectFirst("h1")));

        // Uu tien ten nguoi viet cu the; meta author cua bao Viet Nam thuong chi la ten bao
        // ("VnExpress", "Dan tri"...) nen chi dung lam fallback cuoi.
        String author = truncate(firstNonBlank(
                jsonLd.personAuthor(),
                shortText(doc.selectFirst("[itemprop=author] [itemprop=name], [rel=author],"
                        + " .author-name, .author-info .name, .article-author, .byline")),
                trailingRightAlignedByline(doc),
                article == null ? null : article.getByline(),
                meta(doc, "meta[name=dc.creator], meta[name=DC.creator]"),
                meta(doc, "meta[name=author]"),
                notUrl(meta(doc, "meta[property=article:author]")),
                jsonLd.orgAuthor()), MAX_AUTHOR_LENGTH);

        // Chi giu chuoi ngay goc khi chuan hoa duoc - tranh luu chuoi rac lay nham tu heuristic.
        String publishedAtRaw = null;
        Instant publishedAt = null;
        for (String candidate : dateCandidates(doc, jsonLd)) {
            Optional<Instant> parsed = DateParsing.parse(candidate);
            if (parsed.isPresent()) {
                publishedAtRaw = candidate;
                publishedAt = parsed.get();
                break;
            }
        }

        String content = articleContent != null ? toPlainText(articleContent) : "";
        if (content.isBlank()) {
            content = toPlainText(fallbackContentRoot(doc));
        }

        return new HtmlContent(blankToNull(title), blankToNull(author), publishedAt,
                blankToNull(publishedAtRaw), content, extractLinks(doc, articleContent, pageUrl));
    }

    // ---------------------------------------------------------------------------------------
    // Noi dung chinh
    // ---------------------------------------------------------------------------------------

    private Article runReadability(Document doc, String pageUrl) {
        try {
            // Readability sua truc tiep cay DOM truyen vao -> dua ban clone, giu nguyen doc goc
            // de con lay meta va lien ket day du.
            return new Readability4JExtended(pageUrl, doc.clone()).parse();
        } catch (RuntimeException | LinkageError e) {
            log.warn("Readability failed for {}: {}", pageUrl, e.toString());
            return null;
        }
    }

    /**
     * Readability bo qua bai qua ngan / nhieu link (vd. trang ctt.hust.edu.vn chi co tieu de + link
     * tai file) va chon nham footer. Khi ket qua qua ngan: lay khoi chua heading (h1-h3) dau tien
     * cua trang (da bo header/nav/footer), neu khoi do dai hon ket qua cua Readability.
     */
    private Element headingBlockIfReadabilityFailed(Document doc, Element readable) {
        int readableLength = readable == null ? 0 : readable.text().length();
        if (readableLength >= MIN_READABLE_LENGTH || doc.body() == null) {
            return readable;
        }
        Document copy = doc.clone();
        copy.select("script, style, noscript, nav, footer, header, aside, form").remove();
        Element heading = copy.body().selectFirst("h1, h2, h3");
        if (heading == null || heading.parent() == null || heading.parent() == copy.body()) {
            return readable;
        }
        Element block = heading.parent();
        return block.text().length() > readableLength ? block : readable;
    }

    /** Khi Readability khong ra noi dung: bo cac vung khong phai noi dung, lay article/main/body. */
    private Element fallbackContentRoot(Document doc) {
        Document copy = doc.clone();
        copy.select("script, style, noscript, nav, footer, header, aside, form").remove();
        Element root = copy.selectFirst("article");
        if (root == null) {
            root = copy.selectFirst("main");
        }
        return root != null ? root : copy.body();
    }

    /** HTML -> van ban thuan, giu xuong dong giua cac khoi (p, li, h1..h6, div, br...). */
    static String toPlainText(Element root) {
        if (root == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode textNode) {
                    sb.append(textNode.text());
                } else if (node instanceof Element el && (el.isBlock() || el.normalName().equals("br"))) {
                    sb.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element el && el.isBlock()) {
                    sb.append('\n');
                }
            }
        }, root);

        StringBuilder out = new StringBuilder();
        for (String line : sb.toString().split("\n")) {
            String cleaned = line.replaceAll("[\\s\\u00A0]+", " ").strip();
            if (!cleaned.isEmpty()) {
                out.append(cleaned).append('\n');
            }
        }
        return out.toString().strip();
    }

    // ---------------------------------------------------------------------------------------
    // Ngay dang
    // ---------------------------------------------------------------------------------------

    /** Cac chuoi co the la ngay dang, xep theo do tin cay giam dan. */
    private List<String> dateCandidates(Document doc, JsonLdInfo jsonLd) {
        Set<String> candidates = new LinkedHashSet<>();
        addIfPresent(candidates, meta(doc, "meta[property=article:published_time]"));
        addIfPresent(candidates, meta(doc, "meta[itemprop=datePublished]"));
        addIfPresent(candidates, jsonLd.datePublished());
        addIfPresent(candidates, meta(doc,
                "meta[name=pubdate], meta[name=publishdate], meta[name=publish-date], meta[name=date],"
                        + " meta[name=dc.date.issued], meta[name=DC.date.issued], meta[name=dc.date], meta[name=DC.date]"));
        Element timeEl = doc.selectFirst("[itemprop=datePublished][datetime], time[datetime]");
        if (timeEl != null) {
            addIfPresent(candidates, timeEl.attr("datetime"));
        }
        addIfPresent(candidates, textOf(doc.selectFirst("[itemprop=datePublished]")));
        // Trang tin Viet Nam hay de ngay dang trong 1 the co class kieu "date", "time", "publish"
        // (vd. VnExpress: <span class="date">Thu nam, 16/10/2025, 08:00 (GMT+7)</span>).
        for (Element el : doc.select("[class~=(?i)(date|time|publish)], [id~=(?i)(date|time|publish)]")) {
            String text = el.ownText().isBlank() ? el.text() : el.ownText();
            // Class "time"/"date" con gap o thanh phan trang, dong ho, menu... -> chi lay text
            // ngan va thuc su chua 1 ngay.
            if (text.length() <= 100 && DateParsing.containsDate(text)) {
                addIfPresent(candidates, text);
            }
            if (candidates.size() > 30) {
                break;
            }
        }
        return new ArrayList<>(candidates);
    }

    // ---------------------------------------------------------------------------------------
    // JSON-LD (schema.org)
    // ---------------------------------------------------------------------------------------

    private JsonLdInfo readJsonLd(Document doc) {
        String headline = null;
        String personAuthor = null;
        String orgAuthor = null;
        String datePublished = null;
        for (Element script : doc.select("script[type=application/ld+json]")) {
            JsonNode root;
            try {
                root = objectMapper.readTree(script.data());
            } catch (IOException e) {
                continue; // JSON-LD loi cu phap (gap kha thuong) -> bo qua block nay
            }
            for (JsonNode node : flattenJsonLd(root)) {
                headline = headline != null ? headline : text(node.get("headline"));
                personAuthor = personAuthor != null ? personAuthor : authorName(node.get("author"), true);
                orgAuthor = orgAuthor != null ? orgAuthor : authorName(node.get("author"), false);
                datePublished = datePublished != null ? datePublished : text(node.get("datePublished"));
            }
        }
        return new JsonLdInfo(headline, personAuthor, orgAuthor, datePublished);
    }

    /** JSON-LD co the la 1 object, 1 mang, hoac object chua "@graph". */
    private List<JsonNode> flattenJsonLd(JsonNode root) {
        List<JsonNode> nodes = new ArrayList<>();
        if (root == null) {
            return nodes;
        }
        if (root.isArray()) {
            root.forEach(n -> nodes.addAll(flattenJsonLd(n)));
        } else if (root.isObject()) {
            nodes.add(root);
            if (root.has("@graph")) {
                nodes.addAll(flattenJsonLd(root.get("@graph")));
            }
        }
        return nodes;
    }

    /**
     * @param person true = chi lay author la nguoi (@type Person, hoac chuoi thuan);
     *               false = chi lay author la to chuc (@type khac Person)
     */
    private String authorName(JsonNode author, boolean person) {
        if (author == null || author.isNull()) {
            return null;
        }
        if (author.isTextual()) {
            return person ? blankToNull(author.asText()) : null;
        }
        if (author.isObject()) {
            boolean isPerson = "Person".equals(text(author.get("@type")));
            return isPerson == person ? text(author.get("name")) : null;
        }
        if (author.isArray()) {
            List<String> names = new ArrayList<>();
            author.forEach(a -> {
                String name = authorName(a, person);
                if (name != null) {
                    names.add(name);
                }
            });
            return names.isEmpty() ? null : String.join(", ", names);
        }
        return null;
    }

    private String text(JsonNode node) {
        return node != null && node.isValueNode() ? blankToNull(node.asText()) : null;
    }

    // ---------------------------------------------------------------------------------------
    // Lien ket (canh do thi)
    // ---------------------------------------------------------------------------------------

    private List<ExtractedLink> extractLinks(Document doc, Element articleContent, String pageUrl) {
        String self = UrlUtils.normalize(pageUrl).orElse(pageUrl);
        Set<String> mainContentUrls = mainContentUrls(articleContent);
        Set<String> seen = new HashSet<>();
        List<ExtractedLink> links = new ArrayList<>();

        for (Element a : doc.select("a[href]")) {
            Optional<String> target = UrlUtils.normalize(a.absUrl("href"));
            if (target.isEmpty() || target.get().equals(self)) {
                continue;
            }
            String anchor = truncate(firstNonBlank(
                    a.text(), a.attr("title"), a.attr("aria-label"),
                    a.selectFirst("img[alt]") == null ? null : a.selectFirst("img[alt]").attr("alt")),
                    MAX_ANCHOR_LENGTH);
            addLink(links, seen, new ExtractedLink(target.get(), anchor == null ? "" : anchor,
                    LinkKind.A, mainContentUrls.contains(target.get())));
        }
        for (Element img : doc.select("img")) {
            Optional<String> target = UrlUtils.normalize(imageSrc(img));
            if (target.isEmpty()) {
                continue;
            }
            String alt = truncate(firstNonBlank(img.attr("alt"), img.attr("title")), MAX_ANCHOR_LENGTH);
            addLink(links, seen, new ExtractedLink(target.get(), alt == null ? "" : alt,
                    LinkKind.IMG, mainContentUrls.contains(target.get())));
        }
        return links;
    }

    private void addLink(List<ExtractedLink> links, Set<String> seen, ExtractedLink link) {
        if (seen.add(link.kind() + "\n" + link.url() + "\n" + link.anchorText())) {
            links.add(link);
        }
    }

    /** src that cua anh: nhieu trang lazy-load de anh that o data-src, src chi la anh gia/data URI. */
    private String imageSrc(Element img) {
        for (String attr : List.of("src", "data-src", "data-original", "data-lazy-src")) {
            String value = img.absUrl(attr);
            if (!value.isBlank()) {
                return value;
            }
        }
        String srcset = img.attr("srcset").isBlank() ? img.attr("data-srcset") : img.attr("srcset");
        if (!srcset.isBlank()) {
            String first = srcset.split(",")[0].strip().split("\\s+")[0];
            return resolve(img.baseUri(), first);
        }
        return "";
    }

    private Set<String> mainContentUrls(Element articleContent) {
        Set<String> urls = new HashSet<>();
        if (articleContent == null) {
            return urls;
        }
        for (Element a : articleContent.select("a[href]")) {
            UrlUtils.normalize(a.absUrl("href")).ifPresent(urls::add);
        }
        for (Element img : articleContent.select("img")) {
            UrlUtils.normalize(imageSrc(img)).ifPresent(urls::add);
        }
        return urls;
    }

    // ---------------------------------------------------------------------------------------
    // Tien ich
    // ---------------------------------------------------------------------------------------

    private static String resolve(String base, String relative) {
        try {
            return java.net.URI.create(base).resolve(relative).toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String meta(Document doc, String cssQuery) {
        for (Element el : doc.select(cssQuery)) {
            String value = el.attr("content");
            if (!value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String notUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://")) ? null : value;
    }

    /**
     * Quy uoc trinh bay cua nhieu bao Viet Nam (vd. VnExpress): ten tac gia la doan in dam, can
     * phai, o cuoi bai -  p style="text-align:right" strong Vu Tuan /strong /p.
     * Tim tren DOM goc (khong phai ket qua Readability) vi Readability xoa thuoc tinh style.
     */
    private static String trailingRightAlignedByline(Document doc) {
        Element scope = doc.selectFirst("article");
        List<Element> candidates = (scope != null ? scope : doc.body())
                .select("p[style~=(?i)text-align:\\s*right]:has(strong, b)");
        for (int i = candidates.size() - 1; i >= 0; i--) {
            String text = shortText(candidates.get(i));
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /** Text cua the neu ngan (giong ten nguoi), tranh lay nham ca 1 doan van dai. */
    private static String shortText(Element el) {
        return shortText(el, 80);
    }

    private static String shortText(Element el, int maxLength) {
        String text = textOf(el);
        return text != null && text.length() <= maxLength ? text : null;
    }

    private static String textOf(Element el) {
        return el == null ? null : blankToNull(el.text());
    }

    private static void addIfPresent(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value.strip());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String collapsed = s.replaceAll("\\s+", " ").strip();
        return collapsed.length() > max ? collapsed.substring(0, max) : collapsed;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
