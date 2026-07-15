package com.example.eventgateway.security;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Gateway's client-facing access control independent of
 * business logic: no token and wrong scope must both be rejected; only a
 * token with "events:write" may submit events. Reads require any valid
 * token. Health stays open for the orchestrator/LB.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AccountServiceClient accountServiceClient;

    @BeforeEach
    void stubAccountServiceSuccess() {
        doNothing().when(accountServiceClient).applyTransaction(anyString(), any());
    }

    private Map<String, Object> sampleEvent() {
        return Map.of(
                "eventId", UUID.randomUUID().toString(),
                "accountId", "acct-sec-1",
                "type", "CREDIT",
                "amount", 10.0,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:02:11Z"
        );
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void submitWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleEvent())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitWithWrongScopeIsForbidden() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleEvent()))
                        .with(jwt().jwt(builder -> builder.claim("scope", "events:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void submitWithCorrectScopeIsAccepted() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleEvent()))
                        .with(jwt().jwt(builder -> builder.claim("scope", "events:write"))))
                .andExpect(status().isCreated());
    }

    @Test
    void readEndpointRequiresAuthenticationButNotWriteScope() throws Exception {
        mockMvc.perform(get("/events").param("account", "acct-sec-1"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/events").param("account", "acct-sec-1")
                        .with(jwt().jwt(builder -> builder.claim("scope", "events:read"))))
                .andExpect(status().isOk());
    }
}
