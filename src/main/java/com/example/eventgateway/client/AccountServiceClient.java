package com.example.eventgateway.client;

import com.example.eventgateway.exception.AccountServiceUnavailableException;
import com.example.eventgateway.filter.TraceIdFilter;
import com.example.eventgateway.security.ServiceTokenIssuer;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Talks to the internal Account Service. Wrapped with a circuit breaker
 * (primary resiliency pattern - see README) plus a bounded retry and
 * connect/read timeouts, so a slow or failing Account Service degrades the
 * Gateway gracefully instead of hanging or cascading failures.
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ServiceTokenIssuer serviceTokenIssuer;

    public AccountServiceClient(RestTemplate accountServiceRestTemplate,
                                 @Value("${account-service.base-url}") String baseUrl,
                                 ServiceTokenIssuer serviceTokenIssuer) {
        this.restTemplate = accountServiceRestTemplate;
        this.baseUrl = baseUrl;
        this.serviceTokenIssuer = serviceTokenIssuer;
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    @Retry(name = "accountService")
    public void applyTransaction(String accountId, AccountTransactionRequest request) {
        HttpEntity<AccountTransactionRequest> entity = new HttpEntity<>(request, buildHeaders());
        restTemplate.exchange(
                baseUrl + "/accounts/{accountId}/transactions",
                HttpMethod.POST,
                entity,
                Void.class,
                accountId);
    }

    @SuppressWarnings("unused")
    private void applyTransactionFallback(String accountId, AccountTransactionRequest request, Throwable t) {
        log.warn("Account Service unavailable while applying transaction. accountId={} reason={}",
                accountId, t.toString());
        throw new AccountServiceUnavailableException("Account Service is unavailable", t);
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "getBalanceFallback")
    @Retry(name = "accountService")
    public AccountBalanceResponse getBalance(String accountId) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        var response = restTemplate.exchange(
                baseUrl + "/accounts/{accountId}/balance",
                HttpMethod.GET,
                entity,
                AccountBalanceResponse.class,
                accountId);
        return response.getBody();
    }

    @SuppressWarnings("unused")
    private AccountBalanceResponse getBalanceFallback(String accountId, Throwable t) {
        log.warn("Account Service unavailable while fetching balance. accountId={} reason={}",
                accountId, t.toString());
        throw new AccountServiceUnavailableException("Account Service is unreachable", t);
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(serviceTokenIssuer.issueToken());
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null) {
            headers.set(TraceIdFilter.TRACE_ID_HEADER, traceId);
        }
        return headers;
    }
}
