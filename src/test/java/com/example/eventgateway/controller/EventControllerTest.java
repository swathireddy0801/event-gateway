package com.example.eventgateway.controller;

import com.example.eventgateway.client.AccountServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
// Security is covered separately in JwtSecurityTest; this class focuses on
// request handling/validation behavior, independent of auth.
@AutoConfigureMockMvc(addFilters = false)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // The Account Service is not running in this test class - we mock the
    // client so these tests focus purely on Gateway behavior (idempotency,
    // validation, ordering), independent of the downstream service.
    @MockBean
    private AccountServiceClient accountServiceClient;

    @BeforeEach
    void stubAccountServiceSuccess() {
        doNothing().when(accountServiceClient).applyTransaction(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private Map<String, Object> eventBody(String eventId, String accountId, String amount, String timestamp) {
        return Map.of(
                "eventId", eventId,
                "accountId", accountId,
                "type", "CREDIT",
                "amount", amount,
                "currency", "USD",
                "eventTimestamp", timestamp
        );
    }

    @Test
    void submittingSameEventIdTwiceIsIdempotent() throws Exception {
        String eventId = "evt-" + UUID.randomUUID();
        Map<String, Object> body = eventBody(eventId, "acct-idem-1", "100.00", "2026-05-15T14:02:11Z");

        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(eventId));

        // Second submission with the same eventId must not create a duplicate
        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId));

        mockMvc.perform(get("/events").param("account", "acct-idem-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        Map<String, Object> body = Map.of(
                "eventId", "evt-missing-fields",
                "type", "CREDIT",
                "amount", "10.00"
                // accountId, currency, eventTimestamp missing
        );

        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsNegativeAndZeroAmounts() throws Exception {
        Map<String, Object> body = eventBody("evt-negative", "acct-x", "-5.00", "2026-05-15T14:02:11Z");

        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownEventType() throws Exception {
        Map<String, Object> body = Map.of(
                "eventId", "evt-bad-type",
                "accountId", "acct-x",
                "type", "TRANSFER",
                "amount", "10.00",
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:02:11Z"
        );

        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eventsAreListedInChronologicalOrderRegardlessOfArrivalOrder() throws Exception {
        String accountId = "acct-order-" + UUID.randomUUID();

        // Submit out of chronological order: t2 (later) arrives before t1 (earlier)
        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                eventBody("evt-order-2", accountId, "20.00", "2026-05-15T16:00:00Z"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                eventBody("evt-order-1", accountId, "10.00", "2026-05-15T10:00:00Z"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/events").param("account", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-order-1"))
                .andExpect(jsonPath("$[1].eventId").value("evt-order-2"));
    }

    @Test
    void getUnknownEventReturns404() throws Exception {
        mockMvc.perform(get("/events/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthEndpointReportsGatewayStatus() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
