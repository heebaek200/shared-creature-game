package com.team.sharedcreature.payment.domain;

/** 공급자 거래 처리 결과입니다. */
public enum PaymentTransactionStatus {
    REQUESTED,
    SUCCESS,
    DEFINITIVE_FAILURE,
    UNKNOWN
}
