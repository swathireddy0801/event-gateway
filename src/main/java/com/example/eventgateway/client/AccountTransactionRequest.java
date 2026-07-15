package com.example.eventgateway.client;

import com.example.eventgateway.model.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The contract sent from the Gateway to the Account Service's
 * POST /accounts/{accountId}/transactions endpoint.
 */
public class AccountTransactionRequest {
    private String eventId;
    private EventType type;
    private BigDecimal amount;
    private String currency;
    private Instant eventTimestamp;

    public AccountTransactionRequest(String eventId, EventType type, BigDecimal amount,
                                      String currency, Instant eventTimestamp) {
        this.eventId = eventId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
    }

    public String getEventId() {
        return eventId;
    }

    public EventType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }
}
