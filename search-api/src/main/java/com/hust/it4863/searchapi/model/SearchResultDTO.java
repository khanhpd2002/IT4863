package com.hust.it4863.searchapi.model;

/** Mot ket qua tim kiem, xep hang theo diem TF-IDF (Lucene ClassicSimilarity). */
public record SearchResultDTO(String id, String title, float score, String snippet) {
}
