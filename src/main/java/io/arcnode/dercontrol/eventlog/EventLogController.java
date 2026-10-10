package io.arcnode.dercontrol.eventlog;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DerProgram;
import io.arcnode.dercontrol.eventlog.dto.EventLogResponse;
import io.arcnode.dercontrol.eventlog.dto.RetentionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
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
// Reason: no class-level @Validated on purpose. With it, a bad query parameter surfaces from the
// AOP proxy as a ConstraintViolationException and a 500; without it Spring MVC's own method
// validation answers 400 with a ProblemDetail, which is what a bad `limit` or `order` deserves.
public class EventLogController {

  /** How far back a read with no {@code since} looks — a shift, not forever. */
  private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 1000;

  private final EventLogService service;
  private final Clock clock;
  private final Config config;

  public EventLogController(EventLogService service, Clock clock, Config config) {
    this.service = service;
    this.clock = clock;
    this.config = config;
  }

  @Operation(
      summary =
          "Events, oldest first after `since` (default: the last 24 h), or newest first below the"
              + " `before` id cursor or with `order=desc`; `until`, `types` and `program` filter;"
              + " `limit` caps (200, max 1000)")
  @GetMapping
  public List<EventLogResponse> query(
      @RequestParam(required = false) @Nullable Instant since,
      @RequestParam(required = false) @Nullable Instant until,
      @RequestParam(required = false) @Nullable Long before,
      @RequestParam(required = false) @Nullable List<EventType> types,
      @RequestParam(required = false) @Nullable DerProgram program,
      @RequestParam(required = false) @Pattern(regexp = "asc|desc") @Nullable String order,
      @RequestParam(required = false) @Positive @Max(MAX_LIMIT) @Nullable Integer limit) {
    // Reason: the 24 h default is for a live tail. A reader paging back from a cursor wants the
    // whole history behind it, so the default lower bound applies only when no cursor is given.
    Instant from = since != null || before != null ? since : clock.instant().minus(DEFAULT_WINDOW);
    EventLogQuery query =
        new EventLogQuery(
            from,
            until,
            before,
            types == null ? List.of() : types,
            program,
            "desc".equals(order),
            limit != null ? limit : DEFAULT_LIMIT);
    return service.query(query).stream().map(EventLogResponse::from).toList();
  }

  @Operation(summary = "How many days the event log keeps a row")
  @GetMapping("/retention")
  public RetentionResponse retention() {
    return new RetentionResponse(config.eventRetentionDays());
  }
}
