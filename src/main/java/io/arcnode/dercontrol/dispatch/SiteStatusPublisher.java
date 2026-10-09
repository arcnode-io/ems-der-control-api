package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.dispatch.dto.BooleanSample;
import io.arcnode.dercontrol.dispatch.dto.EnumSample;
import io.arcnode.dercontrol.dispatch.dto.FloatSample;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Publishes the site's standing state — channels that describe the site rather than any one event:
 * delivery health on {@code der_dispatch}, the operator's policy on {@code der_dispatch}, and the
 * operating envelope's feed health. What an event itself says is {@link DispatchPublisher}'s.
 */
@Component
public class SiteStatusPublisher {

  private static final String DEVICE_ID = "der_dispatch";
  private static final String ENVELOPE_DEVICE_ID = "operating_envelope";

  private final MeasurementPublisher bus;
  private final Clock clock;

  public SiteStatusPublisher(MeasurementPublisher bus, Clock clock) {
    this.bus = bus;
    this.clock = clock;
  }

  /**
   * Publishes sustained under-delivery detection (Phase III) — orthogonal to {@code
   * der_event_state}, since a physical shortfall is a delivery concern, not a policy/authorization
   * one. Kept as a separate channel from {@link #publishOverdelivery}, not one signed signal —
   * over-delivery (e.g. exceeding an export cap) can be the more dangerous direction and deserves
   * its own name, not to be buried under "shortfall."
   */
  public void publishShortfall(boolean shortfall) {
    bus.publish(DEVICE_ID, "dispatch_shortfall", "none", new BooleanSample(now(), shortfall));
  }

  /** Publishes sustained over-delivery detection (Phase III) — see {@link #publishShortfall}. */
  public void publishOverdelivery(boolean overdelivering) {
    bus.publish(
        DEVICE_ID, "dispatch_overdelivery", "none", new BooleanSample(now(), overdelivering));
  }

  /**
   * Publish the site's dispatch policy. Retained, so a screen opened later knows the posture
   * without asking, and so it survives a broker session being dropped.
   *
   * @param mode the policy now in effect
   */
  public void publishDispatchMode(DispatchMode mode) {
    bus.publish(DEVICE_ID, "dispatch_mode", "none", new EnumSample(now(), mode.name()));
  }

  /**
   * Publish the operator's energy reserve. Retained: the gateway subscribes unconditionally and
   * treats an absent value as no reserve, so this has to be present and current rather than only
   * sent on change.
   *
   * @param reserveWh watt-hours the operator is holding back from answering the envelope
   */
  public void publishOperatorReserve(double reserveWh) {
    bus.publish(DEVICE_ID, "operator_reserve", "watt_hours", new FloatSample(now(), reserveWh));
  }

  /**
   * Publish the operating envelope's feed health. The HMI's stale-feed alarm reads this channel,
   * and {@link EnvelopeFeedMonitor} decides when it changes.
   *
   * @param label an {@code operating_envelope.status} label — {@code OK} or {@code STALE}
   */
  public void publishEnvelopeStatus(String label) {
    bus.publish(ENVELOPE_DEVICE_ID, "status", "none", new EnumSample(now(), label));
  }

  private String now() {
    return clock.instant().toString();
  }
}
