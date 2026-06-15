package com.erp.common.biz;

public final class BizState {
    private BizState() {
    }

    public static final String DRAFT = "DRAFT";
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CLOSED = "CLOSED";
    public static final String CANCELLED = "CANCELLED";

    public static final String UNVERIFIED = "UNVERIFIED";
    public static final String PART_VERIFIED = "PART_VERIFIED";
    public static final String VERIFIED = "VERIFIED";
}
