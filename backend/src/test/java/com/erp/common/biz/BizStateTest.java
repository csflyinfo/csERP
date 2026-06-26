package com.erp.common.biz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BizStateTest {
    @Test void constants() {
        assertEquals("DRAFT", BizState.DRAFT);
        assertEquals("VERIFIED", BizState.VERIFIED);
    }
}
