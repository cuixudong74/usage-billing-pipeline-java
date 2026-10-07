package com.github.cuixudong74.usagebillingpipelinejava;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ForbiddenException;
import com.github.cuixudong74.usagebillingpipelinejava.service.AdjustmentService;
import com.github.cuixudong74.usagebillingpipelinejava.service.AuditService;
import com.github.cuixudong74.usagebillingpipelinejava.service.BillingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
import com.github.cuixudong74.usagebillingpipelinejava.web.ApiExceptionHandler;
import com.github.cuixudong74.usagebillingpipelinejava.web.BillingController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ForbiddenHttpSemanticsTest {

    private EventService eventService;
    private BillingService billingService;
    private AdjustmentService adjustmentService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        eventService = Mockito.mock(EventService.class);
        PlanService planService = Mockito.mock(PlanService.class);
        billingService = Mockito.mock(BillingService.class);
        adjustmentService = Mockito.mock(AdjustmentService.class);
        AuditService auditService = Mockito.mock(AuditService.class);
        BillingController controller = new BillingController(
                eventService, planService, billingService, adjustmentService, auditService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void forbiddenCommitReturns403WithDistinctErrorSemantics() throws Exception {
        doThrow(new ForbiddenException("Access denied: actor writer is not authorized to COMMIT_BILL for account acct"))
                .when(billingService).commit(any(), any(), any(), any(), any(Actor.class));

        mockMvc.perform(post("/api/bills/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", "writer")
                        .header("X-Roles", "EVENT_WRITER")
                        .content("""
                                {"accountId":"acct","start":"2026-01-01T00:00:00Z","end":"2026-02-01T00:00:00Z"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Access denied")));
    }

    @Test
    void badRequestStillReturns400SoCallersCanDistinguish() throws Exception {
        // An empty/invalid window (start == end) is rejected as a request validation error (400),
        // which must be distinguishable from an authorization denial (403).
        doThrow(new com.github.cuixudong74.usagebillingpipelinejava.exception.ValidationException(
                "Window must be a non-empty half-open interval [start,end)"))
                .when(billingService).preview(any(), any(), any(), any(), any(Actor.class));

        mockMvc.perform(post("/api/bills/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", "biller")
                        .header("X-Roles", "BILLER")
                        .content("""
                                {"accountId":"acct","start":"2026-02-01T00:00:00Z","end":"2026-02-01T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void forbiddenIngestProducesNoBillingSideEffects() throws Exception {
        doThrow(new ForbiddenException("Access denied: actor outsider is not authorized to INGEST_EVENT for account acct"))
                .when(eventService).ingest(any(), any(Actor.class));

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", "outsider")
                        .header("X-Roles", "AUDITOR")
                        .content("""
                                {"eventId":"evt-x","accountId":"acct","metric":"api_calls",
                                 "eventTime":"2026-01-02T00:00:00Z","quantity":1,"dimensions":{}}
                                """))
                .andExpect(status().isForbidden());

        // A forbidden ingest must not lead to any billing action for that account.
        verify(billingService, never()).commit(any(), any(), any(), any(), any());
        verify(billingService, never()).preview(any(), any(), any(), any(), any());
        verify(adjustmentService, never()).correct(any(), any(), any());
    }

    @Test
    void forbiddenReadReturns403() throws Exception {
        when(billingService.current(eq("acct"), any(), any(), any(Actor.class)))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(get("/api/bills/current")
                        .param("accountId", "acct")
                        .param("start", "2026-01-01T00:00:00Z")
                        .param("end", "2026-02-01T00:00:00Z")
                        .header("X-Actor", "outsider")
                        .header("X-Roles", "EVENT_WRITER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }
}
