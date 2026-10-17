package com.hust.it4863.searchapi.mongosearch;

import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.Similarity;

/**
 * Thuat toan xep hang nguoi dung chon khi tim kiem. Ca 2 dung chung 1 Lucene index: tu Lucene 7,
 * ClassicSimilarity va BM25Similarity ma hoa norm giong het nhau (do dai field), nen similarity
 * chi quyet dinh cach tinh diem luc search, khong can index rieng cho tung thuat toan.
 */
public enum SearchAlgorithm {

    /**
     * TF-IDF (Lucene ClassicSimilarity), voi moi term t trong query va document d:
     *   score = tf * idf * fieldNorm
     *   tf = sqrt(freq), idf = 1 + ln((N + 1) / (df + 1)), fieldNorm = 1 / sqrt(dl)
     * (Lucene 7+ bo queryNorm nen idf chi xuat hien 1 lan, khong con binh phuong nhu Lucene cu.)
     * tf tang mai theo so lan xuat hien (khong bao hoa); van ban ngan duoc uu tien manh qua fieldNorm.
     */
    TFIDF,

    /**
     * Okapi BM25 (Lucene BM25Similarity), voi moi term t:
     *   score = idf(t) * freq / (freq + k1 * (1 - b + b * dl / avgdl))
     *   idf = ln(1 + (N - df + 0.5) / (df + 0.5))
     * k1 dieu khien muc bao hoa cua tf (tu lap lai nhieu lan khong lam diem tang mai),
     * b dieu khien muc chuan hoa theo do dai van ban (dl = do dai doc, avgdl = do dai trung binh).
     * Lucene 9 bo thua so (k1 + 1) o tu so - chi nhan diem voi 1 hang so, khong doi thu hang.
     */
    BM25;

    public static final float DEFAULT_K1 = 1.2f;
    public static final float DEFAULT_B = 0.75f;

    /**
     * @param k1 chi dung cho BM25; null = 1.2
     * @param b  chi dung cho BM25; null = 0.75
     * @throws IllegalArgumentException neu k1 < 0 hoac b ngoai [0, 1]
     */
    public Similarity similarity(Float k1, Float b) {
        return switch (this) {
            case TFIDF -> new ClassicSimilarity();
            case BM25 -> new BM25Similarity(k1 != null ? k1 : DEFAULT_K1, b != null ? b : DEFAULT_B);
        };
    }
}
