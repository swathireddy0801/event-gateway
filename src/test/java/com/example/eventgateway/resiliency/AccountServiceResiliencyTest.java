package com.example.eventgateway.resiliency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Simulates the Account Service being unavailable (hard failures and slow
 * responses) and verifies:
 *  - individual failures/timeouts surface as 503, not 500 or a hang
 *  - after repeated failures the circuit breaker opens (requests fail fast
 *    without hitting the Account Service every time)
 *  - the Gateway's own read endpoints keep working regardless
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountServiceResiliencyTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerAccountServiceUrl(DynamicPropertyRegistry registry) {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        registry.add("account-service.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private Map<String, Object> eventBody(String eventId, String accountId) {
        return Map.of(
                "eventId", eventId,
                "accountId", accountId,
                "type", "CREDIT",
                "amount", "10.00",
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:02:11Z"
        );
    }

    @Test
    void accountServiceFailureReturns503NotHangOrCrash() throws Exception {
        wireMockServer.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        String eventId = "evt-fail-" + UUID.randomUUID();
        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(eventBody(eventId, "acct-resil-1"))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void slowAccountServiceTimesOutRatherThanHanging() throws Exception {
        wireMockServer.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5000)));

        String eventId = "evt-slow-" + UUID.randomUUID();
        long start = System.currentTimeMillis();
        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(eventBody(eventId, "acct-resil-2"))))
                .andExpect(status().isServiceUnavailable());
        long elapsed = System.currentTimeMillis() - start;

        // Read timeout in test profile is 500ms; even with retry this should
        // resolve in well under the 5s WireMock delay, proving we don't hang.
        org.assertj.core.api.Assertions.assertThat(elapsed).isLessThan(4000);
    }

    @Test
    void repeatedFailuresOpenTheCircuitBreaker() throws Exception {
        wireMockServer.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        // minimumNumberOfCalls=4 in the test profile; drive enough failures
        // through the breaker to trip it open.
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/events")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(
                                    eventBody("evt-cb-" + i, "acct-resil-3"))))
                    .andExpect(status().isServiceUnavailable());
        }

        wireMockServer.resetRequests();

        // Once open, further calls should fail fast without reaching the
        // (still-failing) Account Service at all.
        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                eventBody("evt-cb-open", "acct-resil-3"))))
                .andExpect(status().isServiceUnavailable());

        wireMockServer.verify(0, WireMock.postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    void gatewayOwnEndpointsStillWorkWhenAccountServiceIsDown() throws Exception {
        wireMockServer.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        String eventId = "evt-degrade-" + UUID.randomUUID();
        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(eventBody(eventId, "acct-resil-4"))))
                .andExpect(status().isServiceUnavailable());

        // The event was still stored at the Gateway and remains readable.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/events/" + eventId))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/events")
                        .param("account", "acct-resil-4"))
                .andExpect(status().isOk());
    }
}
