package com.example.eventgateway.repository;

import com.example.eventgateway.model.EventRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<EventRecord, Long> {

    Optional<EventRecord> findByEventId(String eventId);

    List<EventRecord> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
