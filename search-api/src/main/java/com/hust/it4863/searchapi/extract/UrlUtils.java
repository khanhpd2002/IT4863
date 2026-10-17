package com.hust.it4863.searchapi.extract;

import com.hust.it4863.searchapi.extract.model.ResourceType;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Chuan hoa URL, sinh id on dinh, du doan loai tai nguyen theo duoi file. */
public final class UrlUtils {

    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico", "avif", "tif", "tiff");
    private static final Set<String> OTHER_EXTENSIONS = Set.of(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar", "7z", "gz", "tar",
            "mp3", "mp4", "avi", "mov", "wmv", "webm", "exe", "dmg", "apk", "csv", "xml", "json");

    private UrlUtils() {
    }

    /**
     * Chuan hoa de cung 1 tai nguyen chi co 1 node: chi nhan http/https, bo #fragment, scheme
     * va host ve chu thuong, bo port mac dinh, path rong -> "/". Giu nguyen query string.
     *
     * @return empty neu khong phai URL http(s) hop le (mailto:, javascript:, data:, ...)
     */
    public static Optional<String> normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(escapeIllegalChars(rawUrl.strip()));
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme) || uri.getHost() == null) {
                return Optional.empty();
            }
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            StringBuilder sb = new StringBuilder()
                    .append(scheme).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
            if (port != -1) {
                sb.append(':').append(port);
            }
            sb.append(path);
            if (uri.getRawQuery() != null) {
                sb.append('?').append(uri.getRawQuery());
            }
            return Optional.of(sb.toString());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** SHA-256 hex cua cac phan ghep bang '\n' - dung lam _id on dinh, upsert khong bi trung. */
    public static String sha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Du doan loai tai nguyen tu duoi file trong path (khi chua tai ve, chua co Content-Type). */
    public static ResourceType guessType(String url) {
        String ext = extension(url);
        if (ext.equals("pdf")) {
            return ResourceType.PDF;
        }
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return ResourceType.IMAGE;
        }
        if (OTHER_EXTENSIONS.contains(ext)) {
            return ResourceType.OTHER;
        }
        return ResourceType.HTML;
    }

    /** Loai tai nguyen tu header Content-Type; empty neu header khong ro rang. */
    public static Optional<ResourceType> typeFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return Optional.empty();
        }
        String mime = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (mime.equals("text/html") || mime.equals("application/xhtml+xml")) {
            return Optional.of(ResourceType.HTML);
        }
        if (mime.equals("application/pdf") || mime.equals("application/x-pdf")) {
            return Optional.of(ResourceType.PDF);
        }
        if (mime.startsWith("image/")) {
            return Optional.of(ResourceType.IMAGE);
        }
        // application/octet-stream thuong gap khi server khong khai bao dung -> de caller doan tiep.
        if (mime.equals("application/octet-stream") || mime.equals("binary/octet-stream")) {
            return Optional.empty();
        }
        return Optional.of(ResourceType.OTHER);
    }

    /**
     * Percent-encode ky tu khong hop le trong URI (dau cach, ky tu tieng Viet chua encode,
     * |, {, }, ...) - trinh duyet chap nhan cac URL nay nhung java.net.URI thi khong.
     * Khong dung lai cac ky tu da hop le (ke ca %xx da encode san) nen goi nhieu lan van an toan.
     */
    private static String escapeIllegalChars(String url) {
        StringBuilder sb = new StringBuilder(url.length());
        url.codePoints().forEach(cp -> {
            if (cp > 127 || cp <= 32 || " \"<>\\^`{|}".indexOf(cp) >= 0) {
                for (byte b : Character.toString(cp).getBytes(StandardCharsets.UTF_8)) {
                    sb.append('%').append(String.format("%02X", b & 0xFF));
                }
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    private static String extension(String url) {
        try {
            String path = new URI(url).getPath();
            if (path == null) {
                return "";
            }
            int slash = path.lastIndexOf('/');
            int dot = path.lastIndexOf('.');
            return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        } catch (URISyntaxException e) {
            return "";
        }
    }
}
