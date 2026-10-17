package com.hust.it4863.searchapi.extract;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cau hinh app.extract.* trong application.yml - cac gioi han de 1 lan boc tach khong chay
 * qua lau khi trang nguon co hang tram lien ket.
 *
 * @param maxDepth           do sau toi da: 0 = chi tai URL dau vao; 1 = tai them trang/PDF
 *                           ma URL dau vao lien ket toi (lien ket cua chung chi duoc ghi nhan)
 * @param maxOutlinksPerPage so lien ket (HTML/PDF) toi da duoc tai tiep tu moi trang
 * @param maxPages           tong so URL toi da duoc tai trong 1 lan chay
 * @param maxBytes           dung luong toi da 1 phan hoi; lon hon thi bo qua (SKIPPED)
 * @param timeoutSeconds     timeout ket noi + doc cho moi request
 * @param userAgent          header User-Agent gui di
 * @param urlsFile           file danh sach URL dau vao tren server (moi dong 1 URL, '#' la chu thich)
 */
@ConfigurationProperties(prefix = "app.extract")
public record ExtractProperties(
        int maxDepth,
        int maxOutlinksPerPage,
        int maxPages,
        long maxBytes,
        int timeoutSeconds,
        String userAgent,
        String urlsFile) {
}
