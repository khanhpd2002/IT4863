package com.hust.it4863.searchapi.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * Analyzer tach tu (tokenizer) cho noi dung tieng Viet lan tieng Anh.
 *
 * Pipeline:
 *  1. StandardTokenizer - tach van ban thanh tu theo ranh gioi Unicode (khoang trang,
 *     dau cau...), giu nguyen cac ky tu co dau tieng Viet la mot phan cua tu.
 *  2. LowerCaseFilter - chuan hoa ve chu thuong, dam bao khong phan biet HOA/thuong.
 *  3. ASCIIFoldingFilter - bo dau (vd: "nhật" -> "nhat", "đại học" -> "dai hoc"),
 *     dam bao tim duoc ca khi go khong dau.
 *
 * Ap dung cho ca luc index (IndexingService) va luc parse query (SearchService/QueryParser)
 * vi 2 service dung chung 1 Analyzer bean (xem LuceneConfig) - bat buoc phai dung chung de
 * token sinh ra luc index va luc query khop nhau.
 *
 * Vi du: van ban "Cơ hội thực tập tại Nhật cho sinh viên HUST" sinh ra token
 * ["co", "hoi", "thuc", "tap", "tai", "nhat", "cho", "sinh", "vien", "hust"], nen cac truy
 * van sau deu khop: "Nhật", "nhat", "NHAT", "Cơ Hội", "co hoi", ...
 */
public final class VietnameseAnalyzer extends Analyzer {

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        StandardTokenizer tokenizer = new StandardTokenizer();
        TokenStream tokenStream = new LowerCaseFilter(tokenizer);
        tokenStream = new ASCIIFoldingFilter(tokenStream);
        return new TokenStreamComponents(tokenizer, tokenStream);
    }
}
