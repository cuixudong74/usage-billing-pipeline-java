package com.github.cuixudong74.usagebillingpipelinejava;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "billing.data-dir=target/forbidden-http-semantics-test-data")
class ForbiddenHttpSemanticsTest {
    private static final String EVENT_ID = "forbidden-event";
    private static final String ACCOUNT_ID = "forbidden-acct";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void forbiddenRequestIsDistinctFromBadRequestAndChangesNoState() {
        ResponseEntity<String> rejected = post("/api/events", EVENT_ID, ACCOUNT_ID, "AUDITOR");
        assertEquals(HttpStatus.FORBIDDEN, rejected.getStatusCode());
        String forbiddenBody = rejected.getBody();
        org.junit.jupiter.api.Assertions.assertTrue(forbiddenBody.contains("\"status\":403"));
        org.junit.jupiter.api.Assertions.assertTrue(forbiddenBody.contains("\"error\":\"Forbidden\""));

        ResponseEntity<String> badRequest = post("/api/events", null, ACCOUNT_ID, "EVENT_WRITER");
        assertEquals(HttpStatus.BAD_REQUEST, badRequest.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(badRequest.getBody().contains("\"status\":400"));
        org.junit.jupiter.api.Assertions.assertTrue(badRequest.getBody().contains("\"error\":\"Bad Request\""));

        HttpHeaders currentHeaders = new HttpHeaders();
        currentHeaders.set("X-Actor", "admin");
        currentHeaders.set("X-Roles", "ADMIN");
        ResponseEntity<String> current = restTemplate.exchange(
                url("/api/bills/current?accountId={account}&start=2026-01-01T00:00:00Z&end=2026-02-01T00:00:00Z"),
                HttpMethod.GET, new HttpEntity<>(currentHeaders), String.class, ACCOUNT_ID);
        assertEquals(HttpStatus.NOT_FOUND, current.getStatusCode());
    }

    private ResponseEntity<String> post(String path, String eventId, String accountId, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Actor", "outsider");
        headers.set("X-Roles", role);
        String body = """
                {
                  "eventId": %s,
                  "accountId": "%s",
                  "metric": "api_calls",
                  "eventTime": "2026-01-02T00:00:00Z",
                  "quantity": 1,
                  "dimensions": {}
                }
                """.formatted(eventId == null ? "null" : "\"" + eventId + "\"", accountId);
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
