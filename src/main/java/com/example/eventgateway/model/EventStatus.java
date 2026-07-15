package com.example.eventgateway.model;

public enum EventStatus {
    /** Stored at the Gateway but not yet successfully applied to the Account Service. */
    PENDING,
    /** Successfully applied to the Account Service. */
    APPLIED,
    /** The Account Service was unavailable when we tried to apply it. */
    FAILED
}
