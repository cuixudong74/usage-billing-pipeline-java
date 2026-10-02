package com.github.cuixudong74.usagebillingpipelinejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST API 集成测试：通过 HTTP 请求头传递操作人与角色，
 * 验证出账全流程与越权 403。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebApiTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private HttpHeaders headers(String operator, String roles) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Operator", operator);
        h.set("X-Operator-Roles", roles);
        h.set("Content-Type", "application/json");
        return h;
    }

    @Test
    void fullBillingFlow_viaApi() throws Exception {
        // 1. 创建方案草稿（ADMIN）
        String planBody = """
                {
                  "planCode": "WEB",
                  "effectiveFrom": "2026-01-01T00:00:00Z",
                  "rules": {
                    "currency": "CNY",
                    "tiers": [
                      {"upTo": "100", "pricePerUnit": "1.00"},
                      {"upTo": "500", "pricePerUnit": "0.80"},
                      {"pricePerUnit": "0.60"}
                    ]
                  }
                }
                """;
        ResponseEntity<String> planResp = restTemplate.postForEntity("/api/plans",
                new HttpEntity<>(planBody, headers("admin", "ADMIN")), String.class);
        assertEquals(HttpStatus.OK, planResp.getStatusCode());
        JsonNode planNode = objectMapper.readTree(planResp.getBody());
        int version = planNode.get("version").asInt();

        // 2. 提交方案
        ResponseEntity<String> commitPlanResp = restTemplate.postForEntity(
                "/api/plans/WEB/versions/" + version + "/commit",
                new HttpEntity<>("{}", headers("admin", "ADMIN")), String.class);
        assertEquals(HttpStatus.OK, commitPlanResp.getStatusCode());

        // 3. 分配方案
        String assignBody = """
                {"accountId": "acc-web", "planCode": "WEB", "planVersion": %d,
                 "effectiveFrom": "2026-01-01T00:00:00Z"}
                """.formatted(version);
        ResponseEntity<String> assignResp = restTemplate.postForEntity("/api/plans/assignments",
                new HttpEntity<>(assignBody, headers("admin", "ADMIN")), String.class);
        assertEquals(HttpStatus.OK, assignResp.getStatusCode());

        // 4. 摄入事件（BILLER）
        for (int i = 0; i < 3; i++) {
            String eventBody = """
                    {"eventId": "web-e%d", "accountId": "acc-web", "dimension": "api",
                     "quantity": 200, "eventTime": "2026-09-1%dT00:00:00Z"}
                    """.formatted(i, i);
            ResponseEntity<String> evtResp = restTemplate.postForEntity("/api/events",
                    new HttpEntity<>(eventBody, headers("biller", "BILLER")), String.class);
            assertEquals(HttpStatus.OK, evtResp.getStatusCode());
        }

        // 5. 预览
        String billBody = """
                {"accountId": "acc-web", "windowId": "2026-09"}
                """;
        ResponseEntity<String> previewResp = restTemplate.postForEntity("/api/billing/preview",
                new HttpEntity<>(billBody, headers("biller", "BILLER")), String.class);
        assertEquals(HttpStatus.OK, previewResp.getStatusCode());
        JsonNode previewNode = objectMapper.readTree(previewResp.getBody());
        assertTrue(previewNode.get("preview").asBoolean());
        // 600 -> 480
        assertEquals(0, new BigDecimal("480.00").compareTo(
                new BigDecimal(previewNode.get("totalAmount").asText())));

        // 6. 提交出账（幂等）
        ResponseEntity<String> commitResp1 = restTemplate.postForEntity("/api/billing/commit",
                new HttpEntity<>(billBody, headers("biller", "BILLER")), String.class);
        assertEquals(HttpStatus.OK, commitResp1.getStatusCode());
        JsonNode bill1 = objectMapper.readTree(commitResp1.getBody());
        assertEquals(1, bill1.get("billVersion").asInt());

        // 重复提交：仍为同一份账单
        ResponseEntity<String> commitResp2 = restTemplate.postForEntity("/api/billing/commit",
                new HttpEntity<>(billBody, headers("biller", "BILLER")), String.class);
        assertEquals(HttpStatus.OK, commitResp2.getStatusCode());
        JsonNode bill2 = objectMapper.readTree(commitResp2.getBody());
        assertEquals(bill1.get("id").asLong(), bill2.get("id").asLong());

        // 7. 查询当前账单
        ResponseEntity<String> currentResp = restTemplate.getForEntity(
                "/api/billing/bills/current?accountId=acc-web&windowId=2026-09", String.class);
        assertEquals(HttpStatus.OK, currentResp.getStatusCode());
        JsonNode current = objectMapper.readTree(currentResp.getBody());
        assertEquals(0, new BigDecimal("480.00").compareTo(
                new BigDecimal(current.get("totalAmount").asText())));
        assertNotNull(current.get("eventIds"));
    }

    @Test
    void commit_withoutRole_returns403() {
        String body = """
                {"accountId": "acc-denied", "windowId": "2026-09"}
                """;
        ResponseEntity<String> resp = restTemplate.postForEntity("/api/billing/commit",
                new HttpEntity<>(body, headers("nobody", "")), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void adjust_withBillerRole_returns403() {
        String body = """
                {"accountId": "acc-denied", "windowId": "2026-09", "reason": "test"}
                """;
        ResponseEntity<String> resp = restTemplate.postForEntity("/api/billing/adjust",
                new HttpEntity<>(body, headers("biller", "BILLER")), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void duplicateEvent_viaApi_isIdempotent() throws Exception {
        String eventBody = """
                {"eventId": "dup-1", "accountId": "acc-dup-web", "dimension": "api",
                 "quantity": 100, "eventTime": "2026-09-10T00:00:00Z"}
                """;
        HttpEntity<String> entity = new HttpEntity<>(eventBody, headers("biller", "BILLER"));
        ResponseEntity<String> r1 = restTemplate.postForEntity("/api/events", entity, String.class);
        ResponseEntity<String> r2 = restTemplate.postForEntity("/api/events", entity, String.class);
        assertEquals(HttpStatus.OK, r1.getStatusCode());
        assertEquals(HttpStatus.OK, r2.getStatusCode());
        JsonNode n1 = objectMapper.readTree(r1.getBody());
        JsonNode n2 = objectMapper.readTree(r2.getBody());
        // 第一次 created=true，第二次 created=false
        assertTrue(n1.get("created").asBoolean());
        assertEquals(false, n2.get("created").asBoolean());
    }
}
