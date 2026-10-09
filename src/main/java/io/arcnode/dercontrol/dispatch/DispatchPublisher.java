package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.derevent.DerEvent;
import io.arcnode.dercontrol.derevent.DerEventState;
import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.derevent.DispatchSettingsService;
import io.arcnode.dercontrol.dispatch.dto.BooleanSample;
import io.arcnode.dercontrol.dispatch.dto.EnumSample;
import io.arcnode.dercontrol.dispatch.dto.FloatSample;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Translates a {@link DerEvent} into canonical arcnode measurement samples and publishes them on
 * the {@code der_dispatch} device's channels (system_adr §12/§13/§18), plus the site's {@code
 * operating_envelope} device when the same DERControlBase payload carried envelope-mode limits
 * (opModImpLimW/opModExpLimW) alongside or instead of a target-mode setpoint.
 *
 * <p>Every ingested event publishes {@code event_active}, {@code der_event_state} and {@code
 * der_event_program} (ADR-002 §16 — reflects the site's current {@link DispatchMode}, not just the
 * utility's raw status). {@code target_active_power}, {@code energize_enabled}, {@code
 * import_limit}, and {@code export_limit} each publish only when the DERControlBase carried that
 * control. The site's standing channels (policy, delivery health) are {@link
 * SiteStatusPublisher}'s.
 */
@Component
public class DispatchPublisher {

  /** The program label when nothing is in force — retained, so no stale name outlives its event. */
  private static final String NO_PROGRAM = "NONE";

  private static final Logger LOG = LoggerFactory.getLogger(DispatchPublisher.class);
  private static final String DEVICE_ID = "der_dispatch";
  private static final String ENVELOPE_DEVICE_ID = "operating_envelope";

  private final MeasurementPublisher bus;
  private final Clock clock;
  private final DispatchSettingsService dispatchSettings;

  public DispatchPublisher(
      MeasurementPublisher bus, Clock clock, DispatchSettingsService dispatchSettings) {
    this.bus = bus;
    this.clock = clock;
    this.dispatchSettings = dispatchSettings;
  }

  /**
   * States the uncommanded posture: no event, no setpoint. For a site whose retained state a
   * consumer cannot otherwise distinguish from a site mid-curtailment.
   */
  public void publishIdlePosture() {
    String ts = clock.instant().toString();
    // Reason: these are retained and are published together by publish(), so a site that has
    // never been curtailed has none of them. A consumer then cannot tell "no event" from "an event
    // whose state I have not heard yet", and one that gates real power on event_active has to
    // assume the unsafe case and do nothing. Zero with target_setpoint_present false says nothing
    // is commanded, rather than commanding zero.
    bus.publish(DEVICE_ID, "target_active_power", "watts", new FloatSample(ts, 0.0));
    bus.publish(DEVICE_ID, "target_setpoint_present", "none", new BooleanSample(ts, false));
    bus.publish(DEVICE_ID, "event_active", "none", new BooleanSample(ts, false));
    bus.publish(
        DEVICE_ID, "der_event_state", "none", new EnumSample(ts, DerEventState.IDLE.name()));
    bus.publish(DEVICE_ID, "der_event_program", "none", new EnumSample(ts, NO_PROGRAM));
  }

  /** Publish the setpoint + status + envelope channels for one event. */
  public void publish(DerEvent event) {
    String ts = clock.instant().toString();
    Instant now = clock.instant();
    DispatchMode mode = dispatchSettings.currentMode();
    DerEventState state = event.derEventState(mode, now);
    boolean inForce = state == DerEventState.ACTIVE;
    // Reason: a limit is mandatory and a setpoint is not, so they publish on different
    // predicates. A site cannot decline the boundary it is given — withholding one pending an
    // approval would be designed non-compliance — while asking a site to move power is a request
    // it may refuse. What an operator actually decides is which resource answers the envelope.
    boolean mandatoryInForce = event.isMandatoryInForce(now);

    // Reason: the operating envelope is continuous, so reporting its arrival on der_dispatch would
    // leave event_active permanently true and der_event_state permanently ACTIVE — the HMI would
    // show a curtailment always in progress. An envelope constrains; it commands nothing.
    if (!event.isEnvelopeOnly()) {
      Double targetActivePowerW = event.getControl().targetActivePowerW();
      // Reason: target_active_power is a direct setpoint written straight through to the plant,
      // and it is retained, so silence would hand a consumer the previous event's number to keep
      // dispatching — real power, not a stale display value. Zero covers every case where nothing
      // is commanded: an event that is over (IDLE is a terminal utility status), one an operator
      // refused (REJECTED), one awaiting a decision, and one that constrains without commanding,
      // since a line-constraint event carries an envelope and no opModTargetW.
      boolean setpointInForce = inForce && targetActivePowerW != null;
      double commanded = setpointInForce ? targetActivePowerW : 0.0;
      bus.publish(DEVICE_ID, "target_active_power", "watts", new FloatSample(ts, commanded));
      // Reason: zero is itself a legitimate full-curtailment setpoint, so the number alone
      // cannot say whether anything is commanded. This says which of the two a zero is, for any
      // consumer that would otherwise have to guess before writing it to a device.
      bus.publish(
          DEVICE_ID, "target_setpoint_present", "none", new BooleanSample(ts, setpointInForce));
      bus.publish(
          DEVICE_ID, "event_active", "none", new BooleanSample(ts, event.isActive(mode, now)));
      bus.publish(DEVICE_ID, "der_event_state", "none", new EnumSample(ts, state.name()));
      // Reason: a DERControl carries no reason, so the program it arrived under is the only
      // thing that lets a consumer say "line constraint" rather than "contracted call". Once
      // the event is over it names nothing — the channel is retained, and the last event's
      // program must not outlive it.
      String program = state == DerEventState.IDLE ? NO_PROGRAM : event.getProgram().name();
      bus.publish(DEVICE_ID, "der_event_program", "none", new EnumSample(ts, program));
      Boolean energize = event.getControl().energize();
      if (energize != null) {
        bus.publish(DEVICE_ID, "energize_enabled", "none", new BooleanSample(ts, energize));
      }
    }

    Double importLimitW = event.getControl().importLimitW();
    if (importLimitW != null && mandatoryInForce) {
      bus.publish(ENVELOPE_DEVICE_ID, "import_limit", "watts", new FloatSample(ts, importLimitW));
    }
    Double exportLimitW = event.getControl().exportLimitW();
    if (exportLimitW != null && mandatoryInForce) {
      bus.publish(ENVELOPE_DEVICE_ID, "export_limit", "watts", new FloatSample(ts, exportLimitW));
    }

    if (LOG.isInfoEnabled()) {
      LOG.info("published dispatch for mRID {} (status {})", event.getMrid(), event.getStatus());
    }
  }
}
