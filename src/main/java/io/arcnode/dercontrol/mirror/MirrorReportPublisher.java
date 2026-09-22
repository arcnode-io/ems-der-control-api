package io.arcnode.dercontrol.mirror;

import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ties {@link ActualActivePowerSubscriber}'s latest reading to {@link MirrorUsagePointClient}'s
 * outbound POST on a scheduled tick — the real IEEE 2030.5 {@code postRate} concept ("how often
 * mirrored data should be POSTed"), not a reactive per-message POST on every broker update.
 */
@Component
public class MirrorReportPublisher {

  // Reason: sep.xsd's own MirrorUsagePoint::postRate doc — "If not specified, a default of 900
  // seconds (15 minutes) is used."
  private static final long POST_RATE_MILLIS = 900_000L;

  private final ActualActivePowerSubscriber subscriber;
  private final MirrorUsagePointClient client;

  public MirrorReportPublisher(
      ActualActivePowerSubscriber subscriber, MirrorUsagePointClient client) {
    this.subscriber = subscriber;
    this.client = client;
  }

  @Scheduled(fixedDelay = POST_RATE_MILLIS)
  public void tick() {
    @Nullable Double activeWatts = subscriber.currentActiveWatts();
    if (activeWatts == null) {
      return;
    }
    client.post(MirrorUsagePointFactory.build(Math.round(activeWatts)));
  }
}
