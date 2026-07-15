package com.example.eventgateway.dto;

import com.example.eventgateway.model.EventRecord;
import com.example.eventgateway.model.EventStatus;
import com.example.eventgateway.model.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public class EventResponse {
    private String eventId;
    private String accountId;
    private EventType type;
    private BigDecimal amount;
    private String currency;
    private Instant eventTimestamp;
    private EventStatus status;
    private Instant receivedAt;
    private Map<String, Object> metadata;

    public static EventResponse from(EventRecord record, ObjectMapper objectMapper) {
        EventResponse r = new EventResponse();
        r.eventId = record.getEventId();
        r.accountId = record.getAccountId();
        r.type = record.getType();
        r.amount = record.getAmount();
        r.currency = record.getCurrency();
        r.eventTimestamp = record.getEventTimestamp();
        r.status = record.getStatus();
        r.receivedAt = record.getReceivedAt();
        if (record.getMetadataJson() != null) {
            try {
                r.metadata = objectMapper.readValue(record.getMetadataJson(), Map.class);
            } catch (Exception e) {
                r.metadata = null;
            }
        }
        return r;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
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

    public EventStatus getStatus() {
        return status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
