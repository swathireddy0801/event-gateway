package com.example.eventgateway.controller;

import com.example.eventgateway.client.AccountBalanceResponse;
import com.example.eventgateway.dto.EventRequest;
import com.example.eventgateway.dto.EventResponse;
import com.example.eventgateway.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping("/events")
    public ResponseEntity<EventResponse> submitEvent(@Valid @RequestBody EventRequest request) {
        EventService.SubmitResult result = eventService.submitEvent(request);

        if (result.wasDuplicate()) {
            return ResponseEntity.ok(result.event());
        }
        if (result.accountServiceUnavailable()) {
            // Event is durably stored at the Gateway (retrievable via GET) but
            // could not be applied to the account. 503 signals the client should
            // treat the transaction as not-yet-effective and may retry later;
            // retrying is safe because of eventId idempotency.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result.event());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.event());
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable("id") String eventId) {
        return ResponseEntity.ok(eventService.getEvent(eventId));
    }

    @GetMapping("/events")
    public ResponseEntity<List<EventResponse>> getEventsForAccount(@RequestParam("account") String accountId) {
        return ResponseEntity.ok(eventService.getEventsForAccount(accountId));
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<AccountBalanceResponse> getBalance(@PathVariable String accountId) {
        return ResponseEntity.ok(eventService.getBalance(accountId));
    }
}
