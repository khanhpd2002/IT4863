package com.hust.it4863.searchapi.model;

/**
 * Mot lien ket di ra trong noi dung tai lieu. Crawler tai that trang nay (1 cap, khong de quy)
 * nen co day du title/content, cung format voi tai lieu chinh - khong chi la anchor text.
 */
public record OutLink(String title, String content, String url) {
}
