package io.arcnode.dercontrol.derevent;

import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import io.arcnode.dercontrol.mirror.Ieee20305Xml;
import io.arcnode.dercontrol.mirror.ieee20305.ActivePower;
import io.arcnode.dercontrol.mirror.ieee20305.DERControl;
import io.arcnode.dercontrol.mirror.ieee20305.DERControlBase;
import io.arcnode.dercontrol.mirror.ieee20305.Notification;
import io.arcnode.dercontrol.mirror.ieee20305.Resource;
import java.time.Instant;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * Decodes the IEEE 2030.5 {@code Notification} a utility pushes — a {@code DERControl} in the
 * Resource slot — into this service's own internal command shape. The inverse of
 * mock-derms-dispatch-api's own {@code DerControlNotificationFactory}, and the only place the
 * spec's wire encodings are understood: past here everything is natural Java types.
 *
 * <p>Decode-only, so the scaling arithmetic is a single expression rather than the value type the
 * sending side needs.
 */
public final class DerControlNotificationParser {

  private DerControlNotificationParser() {}

  /**
   * @param xml an IEEE 2030.5 Notification document
   * @throws IllegalArgumentException if it is unparseable, or carries no DERControl
   */
  public static DerControlRequest parse(String xml) {
    Notification notification = Ieee20305Xml.unmarshalNotification(xml);
    Resource resource = notification.getResource();
    if (!(resource instanceof DERControl control)) {
      throw new IllegalArgumentException("Notification carries no DERControl in its Resource slot");
    }
    return new DerControlRequest(
        HexFormat.of().formatHex(control.getMRID().getValue()),
        DerControlStatus.fromCode(control.getEventStatus().getCurrentStatus()),
        new DerControlRequest.Interval(
            Instant.ofEpochSecond(control.getInterval().getStart().getValue()),
            control.getInterval().getDuration()),
        controlBase(control.getDERControlBase()));
  }

  private static DerControlRequest.ControlBase controlBase(@Nullable DERControlBase base) {
    if (base == null) {
      return new DerControlRequest.ControlBase(null, null, null, null);
    }
    // Reason: envelope limits are CSIP-AUS extensions carried in DERControlBase's xs:any slot, not
    // named elements, so they are not readable through a generated accessor.
    return new DerControlRequest.ControlBase(
        watts(base.getOpModTargetW()), base.isOpModEnergize(), null, null);
  }

  private static @Nullable Double watts(@Nullable ActivePower power) {
    if (power == null) {
      return null;
    }
    return power.getValue() * Math.pow(10, power.getMultiplier().getValue());
  }
}
