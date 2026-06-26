package com.erp.common.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GenericResultTest {
    @Test void rowCreatesMap() {
        Map<String,Object> m = GenericResult.row("k1","v1","k2",2);
        assertEquals(2, m.size());
        assertEquals("v1", m.get("k1"));
        assertEquals(2, m.get("k2"));
    }

    @Test void operationContainsKeys() {
        Map<String,Object> op = GenericResult.operation("M","ACT");
        assertEquals("M", op.get("moduleCode"));
        assertEquals("ACT", op.get("action"));
        assertEquals(Boolean.TRUE, op.get("success"));
        assertNotNull(op.get("operateAt"));
    }
}
