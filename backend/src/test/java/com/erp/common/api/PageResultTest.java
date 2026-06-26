package com.erp.common.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PageResultTest {
    @Test void ofFiltersAndSortsAndPages() {
        List<Map<String,Object>> records = List.of(
                Map.of("name","Alice","score",10),
                Map.of("name","Bob","score",20),
                Map.of("name","Charlie","score",15)
        );
        PageRequest req = new PageRequest(1,2,"score","desc",Map.of());
        PageResult<Map<String,Object>> result = PageResult.of(records, req);
        assertEquals(2, result.records().size());
        assertEquals(1, result.pageNo());
        assertEquals(2, result.pageSize());
        assertEquals(3, result.total());
        assertEquals("Bob", result.records().get(0).get("name"));
    }

    @Test void filterMatches() {
        List<Map<String,Object>> records = List.of(
                Map.of("name","Alpha","desc","foo"),
                Map.of("name","Beta","desc","bar")
        );
        PageRequest req = new PageRequest(1,10,null,null,Map.of("name","alpha"));
        PageResult<Map<String,Object>> res = PageResult.of(records, req);
        assertEquals(1, res.total());
        assertEquals("Alpha", res.records().get(0).get("name"));
    }
}
