package io.arcnode.dercontrol.dispatch;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reports whether the utility's operating envelope is still arriving, on {@code
 * operating_envelope/status}. The HMI's stale-feed alarm reads that channel, so without a publisher
 * it can neither fire nor clear.
 *
 * <p>Staleness is self-describing rather than a tuned constant: every envelope carries the interval
 * it is valid for, so the feed is stale once the newest one's own window has lapsed. That stays
 * correct if the utility changes how often it sends.
 *
 * <p>Silent until the first envelope ever arrives — at boot the schedule has not stopped, it has
 * not started, and claiming STALE then would be a false alarm. Only transitions are published,
 * since the channel is retained and re-asserting an unchanged state is noise.
 */
@Component
public class EnvelopeFeedMonitor {

  // Reason: operating_envelope.status labels, per the device template's own enum. INVALID and
  // COMM_FAIL are deliberately never published: an unparseable document is rejected at ingest with
  // a
  // 400 and never becomes an event, and this service has no comms channel with the utility to lose
  // beyond notifications arriving — which is what STALE already says.
  private static final String OK = "OK";
  private static final String STALE = "STALE";

  private static final Logger LOG = LoggerFactory.getLogger(EnvelopeFeedMonitor.class);
  private static final long CHECK_MILLIS = 5_000L;

  private final SiteStatusPublisher publisher;
  private final Clock clock;

  private final AtomicReference<@Nullable Instant> validUntil = new AtomicReference<>();
  private final AtomicReference<@Nullable String> published = new AtomicReference<>();

  public EnvelopeFeedMonitor(SiteStatusPublisher publisher, Clock clock) {
    this.publisher = publisher;
    this.clock = clock;
  }

  /**
   * @param envelopeValidUntil the end of the interval the newly-arrived envelope declares itself
   *     valid for
   */
  public void recordEnvelope(Instant envelopeValidUntil) {
    validUntil.set(envelopeValidUntil);
    publishTransition(OK);
  }

  /** Publishes STALE once the newest envelope's declared window has lapsed. */
  @Scheduled(fixedDelay = CHECK_MILLIS)
  public void checkFeed() {
    Instant expiry = validUntil.get();
    if (expiry == null) {
      return;
    }
    if (clock.instant().isAfter(expiry)) {
      publishTransition(STALE);
    }
  }

  private void publishTransition(String label) {
    if (label.equals(published.getAndSet(label))) {
      return;
    }
    publisher.publishEnvelopeStatus(label);
    if (LOG.isInfoEnabled()) {
      LOG.info("📡 Operating envelope feed is {}", label);
    }
  }
}
