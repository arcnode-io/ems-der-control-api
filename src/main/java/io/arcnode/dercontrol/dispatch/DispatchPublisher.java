package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DerEvent;
import io.arcnode.dercontrol.derevent.DerEventState;
import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.derevent.DispatchSettingsService;
import io.arcnode.dercontrol.dispatch.dto.BooleanSample;
import io.arcnode.dercontrol.dispatch.dto.EnumSample;
import io.arcnode.dercontrol.dispatch.dto.FloatSample;
import java.time.Clock;
import java.time.Instant;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translates a {@link DerEvent} into canonical arcnode measurement samples and publishes them to
 * the deployment broker on the {@code der_dispatch} device's channels (system_adr §12/§13/§18),
 * plus the site's {@code operating_envelope} device when the same DERControlBase payload carried
 * envelope-mode limits (opModImpLimW/opModExpLimW) alongside or instead of a target-mode setpoint.
 *
 * <p>Every ingested event publishes {@code event_active} and {@code der_event_state} (ADR-002 §16 —
 * reflects the site's current {@link DispatchMode}, not just the utility's raw status). {@code
 * target_active_power}, {@code energize_enabled}, {@code import_limit}, and {@code export_limit}
 * each publish only when the DERControlBase carried that control.
 */
@Component
public class DispatchPublisher {

  /** The program label when nothing is in force — retained, so no stale name outlives its event. */
  private static final String NO_PROGRAM = "NONE";

  private static final Logger LOG = LoggerFactory.getLogger(DispatchPublisher.class);
  private static final String DEVICE_ID = "der_dispatch";
  private static final String ENVELOPE_DEVICE_ID = "operating_envelope";
  private static final String TOPIC = "sites/%s/devices/%s/measurements/%s/%s";
  private static final int QOS = 0;
  private static final boolean RETAIN = true;

  private final MqttClient mqtt;
  private final JsonMapper mapper;
  private final Config config;
  private final Clock clock;
  private final DispatchSettingsService dispatchSettings;

  public DispatchPublisher(
      MqttClient mqtt,
      JsonMapper mapper,
      Config config,
      Clock clock,
      DispatchSettingsService dispatchSettings) {
    this.mqtt = mqtt;
    this.mapper = mapper;
    this.config = config;
    this.clock = clock;
    this.dispatchSettings = dispatchSettings;
  }

  /**
   * States the uncommanded posture: no event, no setpoint. For a site whose retained state a
   * consumer cannot otherwise distinguish from a site mid-curtailment.
   */
  public void publishIdlePosture() {
    String ts = clock.instant().toString();
    // Reason: these four are retained and are published together by publish(), so a site that has
    // never been curtailed has none of them. A consumer then cannot tell "no event" from "an event
    // whose state I have not heard yet", and one that gates real power on event_active has to
    // assume the unsafe case and do nothing. Zero with target_setpoint_present false says nothing
    // is commanded, rather than commanding zero.
    send(DEVICE_ID, "target_active_power", "watts", new FloatSample(ts, 0.0));
    send(DEVICE_ID, "target_setpoint_present", "none", new BooleanSample(ts, false));
    send(DEVICE_ID, "event_active", "none", new BooleanSample(ts, false));
    send(DEVICE_ID, "der_event_state", "none", new EnumSample(ts, DerEventState.IDLE.name()));
    send(DEVICE_ID, "der_event_program", "none", new EnumSample(ts, NO_PROGRAM));
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
      Double targetActivePowerW = event.getTargetActivePowerW();
      // Reason: target_active_power is retained, so an event that is over has to say so on the
      // channel itself. Leaving the last setpoint there tells any consumer that reads retained
      // state — a gateway restarting, a new subscriber — to keep curtailing a dispatch the utility
      // ended, and event_active alone doesn't undo a stale number. IDLE is a terminal utility
      // status, REJECTED is an operator refusal; neither commands anything.
      // Reason: this channel is a direct setpoint written straight through to the plant and it
      // is retained, so silence would hand a consumer the previous event's number to keep
      // dispatching — real power, not a stale display value. Zero covers every case where
      // nothing is commanded: an event that is over, one awaiting an operator's decision, and
      // one that constrains without commanding, since a line-constraint event carries an
      // envelope and no opModTargetW.
      boolean setpointInForce = inForce && targetActivePowerW != null;
      double commanded = setpointInForce ? targetActivePowerW : 0.0;
      send(DEVICE_ID, "target_active_power", "watts", new FloatSample(ts, commanded));
      // Reason: zero is itself a legitimate full-curtailment setpoint, so the number alone
      // cannot say whether anything is commanded. This says which of the two a zero is, for any
      // consumer that would otherwise have to guess before writing it to a device.
      send(DEVICE_ID, "target_setpoint_present", "none", new BooleanSample(ts, setpointInForce));
      send(DEVICE_ID, "event_active", "none", new BooleanSample(ts, event.isActive(mode, now)));
      send(DEVICE_ID, "der_event_state", "none", new EnumSample(ts, state.name()));
      // Reason: a DERControl carries no reason, so the program it arrived under is the only
      // thing that lets a consumer say "line constraint" rather than "contracted call".
      send(DEVICE_ID, "der_event_program", "none", new EnumSample(ts, event.getProgram().name()));
      Boolean energize = event.getEnergize();
      if (energize != null) {
        send(DEVICE_ID, "energize_enabled", "none", new BooleanSample(ts, energize));
      }
    }

    Double importLimitW = event.getImportLimitW();
    if (importLimitW != null && mandatoryInForce) {
      send(ENVELOPE_DEVICE_ID, "import_limit", "watts", new FloatSample(ts, importLimitW));
    }
    Double exportLimitW = event.getExportLimitW();
    if (exportLimitW != null && mandatoryInForce) {
      send(ENVELOPE_DEVICE_ID, "export_limit", "watts", new FloatSample(ts, exportLimitW));
    }

    if (LOG.isInfoEnabled()) {
      LOG.info("published dispatch for mRID {} (status {})", event.getMrid(), event.getStatus());
    }
  }

  /**
   * Publishes sustained under-delivery detection (Phase III) — orthogonal to {@code
   * der_event_state}, since a physical shortfall is a delivery concern, not a policy/authorization
   * one. Kept as a separate channel from {@link #publishOverdelivery}, not one signed signal —
   * over-delivery (e.g. exceeding an export cap) can be the more dangerous direction and deserves
   * its own name, not to be buried under "shortfall."
   */
  public void publishShortfall(boolean shortfall) {
    send(
        DEVICE_ID,
        "dispatch_shortfall",
        "none",
        new BooleanSample(clock.instant().toString(), shortfall));
  }

  /** Publishes sustained over-delivery detection (Phase III) — see {@link #publishShortfall}. */
  public void publishOverdelivery(boolean overdelivering) {
    send(
        DEVICE_ID,
        "dispatch_overdelivery",
        "none",
        new BooleanSample(clock.instant().toString(), overdelivering));
  }

  /**
   * Publish the site's dispatch policy. Retained, so a screen opened later knows the posture
   * without asking, and so it survives a broker session being dropped.
   *
   * @param mode the policy now in effect
   */
  public void publishDispatchMode(DispatchMode mode) {
    send(
        DEVICE_ID,
        "dispatch_mode",
        "none",
        new EnumSample(clock.instant().toString(), mode.name()));
  }

  /**
   * Publish the operator's energy reserve. Retained: the gateway subscribes unconditionally and
   * treats an absent value as no reserve, so this has to be present and current rather than only
   * sent on change.
   *
   * @param reserveWh watt-hours the operator is holding back from answering the envelope
   */
  public void publishOperatorReserve(double reserveWh) {
    send(
        DEVICE_ID,
        "operator_reserve",
        "watt_hours",
        new FloatSample(clock.instant().toString(), reserveWh));
  }

  /**
   * Publish the operating envelope's feed health. The HMI's stale-feed alarm reads this channel,
   * and {@link EnvelopeFeedMonitor} decides when it changes.
   *
   * @param label an {@code operating_envelope.status} label — {@code OK} or {@code STALE}
   */
  public void publishEnvelopeStatus(String label) {
    send(ENVELOPE_DEVICE_ID, "status", "none", new EnumSample(clock.instant().toString(), label));
  }

  private void send(String deviceId, String measurement, String unit, Object sample) {
    String topic = TOPIC.formatted(config.siteId(), deviceId, measurement, unit);
    try {
      mqtt.publish(topic, mapper.writeValueAsBytes(sample), QOS, RETAIN);
    } catch (JacksonException | MqttException e) {
      // Reason: a failed publish means the dispatch never reached the bus — fail loud so the
      // ingest transaction rolls back rather than silently accepting a lost setpoint.
      throw new IllegalStateException("failed to publish " + topic, e);
    }
  }
}
