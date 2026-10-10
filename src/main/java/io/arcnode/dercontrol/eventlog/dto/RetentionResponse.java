package io.arcnode.dercontrol.eventlog.dto;

/** How long the event log keeps a row, so a reader can say so without hardcoding the number. */
public record RetentionResponse(int days) {}
