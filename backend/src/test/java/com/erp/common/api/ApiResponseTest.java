package com.erp.common.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {
    @Test void okAndFail() {
        ApiResponse<String> ok = ApiResponse.ok("payload");
        assertEquals("0", ok.code());
        assertEquals("success", ok.message());
        assertEquals("payload", ok.data());

        ApiResponse<Object> fail = ApiResponse.fail("E1","error");
        assertEquals("E1", fail.code());
        assertEquals("error", fail.message());
        assertNull(fail.data());
    }
}
