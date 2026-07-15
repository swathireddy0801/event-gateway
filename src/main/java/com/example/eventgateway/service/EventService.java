package com.example.eventgateway.service;

import com.example.eventgateway.client.AccountBalanceResponse;
import com.example.eventgateway.client.AccountServiceClient;
import com.example.eventgateway.client.AccountTransactionRequest;
import com.example.eventgateway.dto.EventRequest;
import com.example.eventgateway.dto.EventResponse;
import com.example.eventgateway.exception.AccountServiceUnavailableException;
import com.example.eventgateway.exception.EventNotFoundException;
import com.example.eventgateway.model.EventRecord;
import com.example.eventgateway.model.EventStatus;
import com.example.eventgateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;
    private final Counter eventsReceivedCounter;
    private final Counter eventsDuplicateCounter;
    private final Counter eventsAppliedCounter;
    private final Counter eventsFailedCounter;
    private final Timer accountServiceCallTimer;

    public EventService(EventRepository eventRepository,
                         AccountServiceClient accountServiceClient,
                         ObjectMapper objectMapper,
                         MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
        this.eventsReceivedCounter = Counter.builder("event_gateway.events.received")
                .description("Total events received on POST /events")
                .register(meterRegistry);
        this.eventsDuplicateCounter = Counter.builder("event_gateway.events.duplicate")
                .description("Duplicate event submissions detected by eventId")
                .register(meterRegistry);
        this.eventsAppliedCounter = Counter.builder("event_gateway.events.applied")
                .description("Events successfully applied to the Account Service")
                .register(meterRegistry);
        this.eventsFailedCounter = Counter.builder("event_gateway.events.failed")
                .description("Events that could not be applied because the Account Service was unavailable")
                .register(meterRegistry);
        this.accountServiceCallTimer = Timer.builder("event_gateway.account_service.call.latency")
                .description("Latency of Gateway -> Account Service calls")
                .register(meterRegistry);
    }

    public record SubmitResult(EventResponse event, boolean wasDuplicate, boolean accountServiceUnavailable) {
    }

    @Transactional
    public SubmitResult submitEvent(EventRequest request) {
        eventsReceivedCounter.increment();

        var existing = eventRepository.findByEventId(request.getEventId());
        if (existing.isPresent()) {
            eventsDuplicateCounter.increment();
            log.info("Duplicate event submission detected. eventId={}", request.getEventId());
            return new SubmitResult(EventResponse.from(existing.get(), objectMapper), true, false);
        }

        String metadataJson = serializeMetadata(request);
        EventRecord record = new EventRecord(
                request.getEventId(),
                request.getAccountId(),
                request.getType(),
                request.getAmount(),
                request.getCurrency(),
                request.getEventTimestamp(),
                metadataJson
        );
        record = eventRepository.save(record);
        log.info("Stored event. eventId={} accountId={}", request.getEventId(), request.getAccountId());

        boolean accountServiceUnavailable = false;
        try {
            accountServiceCallTimer.record(() -> accountServiceClient.applyTransaction(
                    request.getAccountId(),
                    new AccountTransactionRequest(
                            request.getEventId(),
                            request.getType(),
                            request.getAmount(),
                            request.getCurrency(),
                            request.getEventTimestamp())
            ));
            record.setStatus(EventStatus.APPLIED);
            eventsAppliedCounter.increment();
        } catch (AccountServiceUnavailableException ex) {
            record.setStatus(EventStatus.FAILED);
            eventsFailedCounter.increment();
            accountServiceUnavailable = true;
            log.warn("Event stored but not applied - Account Service unavailable. eventId={}", request.getEventId());
        }
        record = eventRepository.save(record);

        return new SubmitResult(EventResponse.from(record, objectMapper), false, accountServiceUnavailable);
    }

    public EventResponse getEvent(String eventId) {
        EventRecord record = eventRepository.findByEventId(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        return EventResponse.from(record, objectMapper);
    }

    public List<EventResponse> getEventsForAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId).stream()
                .map(r -> EventResponse.from(r, objectMapper))
                .toList();
    }

    public AccountBalanceResponse getBalance(String accountId) {
        return accountServiceClient.getBalance(accountId);
    }

    private String serializeMetadata(EventRequest request) {
        if (request.getMetadata() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(request.getMetadata());
        } catch (Exception e) {
            log.warn("Failed to serialize metadata for eventId={}", request.getEventId());
            return null;
        }
    }
}
