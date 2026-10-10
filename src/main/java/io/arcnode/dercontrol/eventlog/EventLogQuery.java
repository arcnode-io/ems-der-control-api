package io.arcnode.dercontrol.eventlog;

import io.arcnode.dercontrol.derevent.DerProgram;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * One read of the event log: optional bounds and filters, plus how many rows at most. Every field
 * but {@code limit} may be absent, and an absent one filters nothing.
 *
 * @param since rows strictly after this instant
 * @param until rows at or before this instant
 * @param before rows with an id below this one — the paging cursor, newest first
 * @param types only these kinds; empty means every kind
 * @param program only events under this program
 * @param newestFirst newest first even without a cursor — the page a history view opens on
 * @param limit at most this many rows
 */
public record EventLogQuery(
    @Nullable Instant since,
    @Nullable Instant until,
    @Nullable Long before,
    List<EventType> types,
    @Nullable DerProgram program,
    boolean newestFirst,
    int limit) {

  /** The WHERE clause these filters amount to. */
  public Specification<EventLog> specification() {
    return (root, query, cb) -> {
      List<Predicate> where = new ArrayList<>();
      if (since != null) {
        where.add(cb.greaterThan(root.get("occurredAt"), since));
      }
      if (until != null) {
        where.add(cb.lessThanOrEqualTo(root.get("occurredAt"), until));
      }
      if (before != null) {
        where.add(cb.lessThan(root.get("id"), before));
      }
      if (!types.isEmpty()) {
        where.add(root.get("type").in(types));
      }
      if (program != null) {
        where.add(cb.equal(root.get("program"), program));
      }
      return cb.and(where.toArray(Predicate[]::new));
    };
  }

  /**
   * Oldest first for a live tail; newest first when asked, or when paging back from a cursor.
   * Reason: ids are an identity column, so they order insertion and never shift a page when new
   * rows arrive.
   */
  public Sort sort() {
    return newestFirst || before != null
        ? Sort.by(Sort.Order.desc("id"))
        : Sort.by(Sort.Order.asc("occurredAt"), Sort.Order.asc("id"));
  }
}
