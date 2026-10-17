package com.hust.it4863.searchapi.extract.model;

/** Trang thai xu ly cua 1 resource. */
public enum FetchStatus {
    /** Da tai va boc tach xong. */
    FETCHED,
    /** Moi chi phat hien qua lien ket, chua tai (vuot do sau/gioi han, hoac la anh). */
    DISCOVERED,
    /** Da tai nhung khong boc tach (loai OTHER, hoac vuot gioi han dung luong). */
    SKIPPED,
    /** Tai hoac boc tach that bai - xem truong error. */
    FAILED
}
