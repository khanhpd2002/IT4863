package com.hust.it4863.searchapi.extract.model;

/** Loai tai nguyen ma 1 URL tro toi. */
public enum ResourceType {
    /** Trang web: boc tach tieu de, ngay dang, tac gia, noi dung. */
    HTML,
    /** Tep tai lieu PDF: boc tach noi dung van ban. */
    PDF,
    /** Tep anh: chi luu nguon gioi thieu, khong tai noi dung. */
    IMAGE,
    /** Loai khac (zip, docx, video...): chi ghi nhan, khong boc tach. */
    OTHER
}
