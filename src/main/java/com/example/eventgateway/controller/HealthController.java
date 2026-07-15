package com.example.eventgateway.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final String accountServiceBaseUrl;

    public HealthController(JdbcTemplate jdbcTemplate,
                             RestTemplate accountServiceRestTemplate,
                             @Value("${account-service.base-url}") String accountServiceBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = accountServiceRestTemplate;
        this.accountServiceBaseUrl = accountServiceBaseUrl;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "event-gateway");

        boolean dbUp = checkDatabase();
        boolean accountServiceUp = checkAccountService();

        // The Gateway can serve most of its own API (event storage, lookups)
        // even when the Account Service is down, so overall status only
        // reflects the Gateway's own dependency: its database.
        body.put("status", dbUp ? "UP" : "DOWN");

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("database", dbUp ? "UP" : "DOWN");
        checks.put("accountService", accountServiceUp ? "UP" : "DOWN");
        body.put("checks", checks);

        return dbUp ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
    }

    private boolean checkDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkAccountService() {
        try {
            var response = restTemplate.getForEntity(accountServiceBaseUrl + "/health", String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }
}
