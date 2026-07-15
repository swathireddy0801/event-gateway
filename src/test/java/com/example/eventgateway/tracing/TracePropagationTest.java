package com.example.eventgateway.tracing;

import com.example.eventgateway.filter.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies a trace ID is generated at the Gateway, returned to the client,
 * and propagated to the Account Service via the X-Trace-Id header.
 */
@SpringBootTest
// Security is covered separately in JwtSecurityTest; this class focuses on
// trace propagation, independent of auth.
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class TracePropagationTest {

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
        wireMockServer.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(200)));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void traceIdGeneratedAtGatewayIsPropagatedToAccountService() throws Exception {
        Map<String, Object> body = Map.of(
                "eventId", "evt-trace-" + UUID.randomUUID(),
                "accountId", "acct-trace-1",
                "type", "CREDIT",
                "amount", "5.00",
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:02:11Z"
        );

        MvcResult result = mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String traceIdOnResponse = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertThat(traceIdOnResponse).isNotBlank();

        wireMockServer.verify(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))
                .withHeader(TraceIdFilter.TRACE_ID_HEADER, equalTo(traceIdOnResponse)));
    }

    @Test
    void callerSuppliedTraceIdIsHonored() throws Exception {
        Map<String, Object> body = Map.of(
                "eventId", "evt-trace-caller-" + UUID.randomUUID(),
                "accountId", "acct-trace-2",
                "type", "CREDIT",
                "amount", "5.00",
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:02:11Z"
        );
        String callerTraceId = "caller-supplied-trace-id";

        mockMvc.perform(post("/events")
                        .header(TraceIdFilter.TRACE_ID_HEADER, callerTraceId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(result -> assertThat(result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER))
                        .isEqualTo(callerTraceId));

        wireMockServer.verify(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))
                .withHeader(TraceIdFilter.TRACE_ID_HEADER, equalTo(callerTraceId)));
    }
}
