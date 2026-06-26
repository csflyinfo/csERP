package com.erp.common.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PageRequestTest {
    @Test void safePageNoAndSize() {
        PageRequest p1 = new PageRequest(null, null, null, null, null);
        assertEquals(1, p1.safePageNo());
        assertEquals(20, p1.safePageSize());

        PageRequest p2 = new PageRequest(0, -5, null, null, null);
        assertEquals(1, p2.safePageNo());
        assertEquals(20, p2.safePageSize());

        PageRequest p3 = new PageRequest(2, 500, null, null, Map.of());
        assertEquals(2, p3.safePageNo());
        assertEquals(200, p3.safePageSize());
    }
}
