package com.hust.it4863.searchapi.model;

import java.util.List;

/**
 * Mot ket qua tim kiem, xep hang theo diem TF-IDF (Lucene ClassicSimilarity).
 * snippets: toi da 3 doan trich (trong tai lieu) co mat do tu khop cao nhat, xep giam dan
 * theo diem cua tung doan - xem SearchService.
 */
public record SearchResultDTO(String id, String title, float score, List<String> snippets) {
}
