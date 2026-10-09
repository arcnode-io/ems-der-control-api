package io.arcnode.dercontrol.dispatch;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — whether the operating envelope is still arriving. The HMI's "DOE Feed · STALE" alarm reads
 * operating_envelope/status, and it is only meaningful if something publishes it.
 *
 * <p>Staleness is self-describing rather than a tuned constant: each envelope carries the interval
 * it is valid for, so the feed is stale once the newest envelope's own window has lapsed. That
 * holds even if the utility changes how often it sends. AAA.
 */
@ExtendWith(MockitoExtension.class)
class EnvelopeFeedMonitorTest {

  private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

  @Mock private SiteStatusPublisher publisher;

  /** Advanceable fake — same pattern as the dispatch package's other clocks. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void saysNothingBeforeAnyEnvelopeHasEverArrived() {
    // Arrange: at boot the schedule has not stopped, it has not started — claiming STALE would be a
    // false alarm
    EnvelopeFeedMonitor monitor = new EnvelopeFeedMonitor(publisher, new MutableClock(NOW));

    // Act
    monitor.checkFeed();

    // Assert
    verify(publisher, never()).publishEnvelopeStatus(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void reportsOkWhenAnEnvelopeArrives() {
    // Arrange
    EnvelopeFeedMonitor monitor = new EnvelopeFeedMonitor(publisher, new MutableClock(NOW));

    // Act
    monitor.recordEnvelope(NOW.plusSeconds(10));

    // Assert
    verify(publisher).publishEnvelopeStatus("OK");
  }

  @Test
  void reportsStaleOnceTheNewestEnvelopesOwnWindowHasLapsed() {
    // Arrange: an envelope valid for ten seconds
    MutableClock clock = new MutableClock(NOW);
    EnvelopeFeedMonitor monitor = new EnvelopeFeedMonitor(publisher, clock);
    monitor.recordEnvelope(NOW.plusSeconds(10));

    // Act
    clock.advance(Duration.ofSeconds(11));
    monitor.checkFeed();

    // Assert
    verify(publisher).publishEnvelopeStatus("STALE");
  }

  @Test
  void staysQuietWhileTheNewestEnvelopeIsStillValid() {
    // Arrange
    MutableClock clock = new MutableClock(NOW);
    EnvelopeFeedMonitor monitor = new EnvelopeFeedMonitor(publisher, clock);
    monitor.recordEnvelope(NOW.plusSeconds(10));

    // Act: still inside the window
    clock.advance(Duration.ofSeconds(5));
    monitor.checkFeed();

    // Assert
    verify(publisher, never()).publishEnvelopeStatus("STALE");
  }

  @Test
  void publishesStaleOnceRatherThanOnEveryTick() {
    // Arrange: the channel is retained, so re-asserting an unchanged state is pure noise
    MutableClock clock = new MutableClock(NOW);
    EnvelopeFeedMonitor monitor = new EnvelopeFeedMonitor(publisher, clock);
    monitor.recordEnvelope(NOW.plusSeconds(10));
    clock.advance(Duration.ofSeconds(11));

    // Act
    monitor.checkFeed();
    monitor.checkFeed();
    monitor.checkFeed();

    // Assert
    verify(publisher, org.mockito.Mockito.times(1)).publishEnvelopeStatus("STALE");
  }

  @Test
  void recoversToOkWhenTheFeedResumes() {
    // Arrange: gone stale
    MutableClock clock = new MutableClock(NOW);
    EnvelopeFeedMonitor monitor = new EnvelopeFeedMonitor(publisher, clock);
    monitor.recordEnvelope(NOW.plusSeconds(10));
    clock.advance(Duration.ofSeconds(11));
    monitor.checkFeed();

    // Act
    monitor.recordEnvelope(clock.instant().plusSeconds(10));

    // Assert: OK once at first arrival, and again on recovery
    verify(publisher, org.mockito.Mockito.times(2)).publishEnvelopeStatus("OK");
  }
}
