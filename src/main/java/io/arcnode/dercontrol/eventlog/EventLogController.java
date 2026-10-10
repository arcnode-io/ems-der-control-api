package io.arcnode.dercontrol.eventlog;

import io.arcnode.dercontrol.eventlog.dto.EventLogResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The site's event history: what happened, when, and who did it. Read-only; rows are appended by
 * the code that makes things happen.
 */
@Tag(name = "events")
@RestController
@RequestMapping("/events")
@Validated
public class EventLogController {

  /** How far back a read with no {@code since} looks — a shift, not forever. */
  private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 1000;

  private final EventLogService service;
  private final Clock clock;

  public EventLogController(EventLogService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @Operation(summary = "Events after an instant, oldest first (default: the last 24 h, 200 rows)")
  @GetMapping
  public List<EventLogResponse> since(
      @RequestParam(required = false) @Nullable Instant since,
      @RequestParam(required = false) @Positive @Max(MAX_LIMIT) @Nullable Integer limit) {
    Instant from = since != null ? since : clock.instant().minus(DEFAULT_WINDOW);
    int cap = limit != null ? limit : DEFAULT_LIMIT;
    return service.since(from, cap).stream().map(EventLogResponse::from).toList();
  }
}
