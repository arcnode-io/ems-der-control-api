package io.arcnode.dercontrol.derevent;

import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import io.arcnode.dercontrol.mirror.Ieee20305Xml;
import io.arcnode.dercontrol.mirror.ieee20305.ActivePower;
import io.arcnode.dercontrol.mirror.ieee20305.DERControl;
import io.arcnode.dercontrol.mirror.ieee20305.DERControlBase;
import io.arcnode.dercontrol.mirror.ieee20305.DERControlList;
import io.arcnode.dercontrol.mirror.ieee20305.Notification;
import io.arcnode.dercontrol.mirror.ieee20305.Resource;
import java.time.Instant;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

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

  // Reason: CSIP-AUS's own versioned targetNamespace, per csipaus-ext-v1.3.xsd in
  // bsgip/envoy-schema. opModImpLimW/opModExpLimW do not exist in base IEEE 2030.5, so they arrive
  // in DERControlBase's xs:any slot rather than through a generated accessor.
  private static final String CSIP_AUS_NS = "https://csipaus.org/ns/v1.3";
  private static final String IMPORT_LIMIT = "opModImpLimW";
  private static final String EXPORT_LIMIT = "opModExpLimW";

  private DerControlNotificationParser() {}

  /**
   * @param xml an IEEE 2030.5 Notification document
   * @throws IllegalArgumentException if it is unparseable, or carries no DERControl
   */
  public static DerControlRequest parse(String xml) {
    Notification notification = Ieee20305Xml.unmarshalNotification(xml);
    DERControl control = onlyControl(notification.getResource());
    if (control.getMRID() == null
        || control.getEventStatus() == null
        || control.getInterval() == null) {
      throw new IllegalArgumentException(
          "DERControl is missing a mandatory field (mRID, EventStatus or interval)");
    }
    // Reason: the schema's UInt32 permits 0, but a zero-length control commands nothing and this
    // service would publish a setpoint no one can comply with.
    if (control.getInterval().getDuration() <= 0) {
      throw new IllegalArgumentException("DERControl interval duration must be positive");
    }
    return new DerControlRequest(
        HexFormat.of().formatHex(control.getMRID().getValue()),
        DerControlStatus.fromCode(control.getEventStatus().getCurrentStatus()),
        new DerControlRequest.Interval(
            Instant.ofEpochSecond(control.getInterval().getStart().getValue()),
            control.getInterval().getDuration()),
        controlBase(control.getDERControlBase()));
  }

  /**
   * A notification about a DERControlList subscription carries that list. A bare DERControl is
   * accepted too, since the Resource slot's declared type permits any Resource.
   */
  private static DERControl onlyControl(@Nullable Resource resource) {
    if (resource instanceof DERControl control) {
      return control;
    }
    if (resource instanceof DERControlList list && !list.getDERControl().isEmpty()) {
      return list.getDERControl().get(0);
    }
    throw new IllegalArgumentException("Notification carries no DERControl in its Resource slot");
  }

  private static DerControlRequest.ControlBase controlBase(@Nullable DERControlBase base) {
    if (base == null) {
      return new DerControlRequest.ControlBase(null, null, null, null);
    }
    return new DerControlRequest.ControlBase(
        watts(base.getOpModTargetW()),
        base.isOpModEnergize(),
        extensionWatts(base, IMPORT_LIMIT),
        extensionWatts(base, EXPORT_LIMIT));
  }

  /**
   * A CSIP-AUS envelope limit out of the extension slot, or absent if this control carries none.
   */
  private static @Nullable Double extensionWatts(DERControlBase base, String localName) {
    for (Object any : base.getAny()) {
      if (any instanceof Element element
          && CSIP_AUS_NS.equals(element.getNamespaceURI())
          && localName.equals(element.getLocalName())) {
        return scaledWatts(element);
      }
    }
    return null;
  }

  /** CSIP-AUS types these as the IEEE {@code ActivePower}, so the children are the same pair. */
  private static double scaledWatts(Element activePower) {
    short value = Short.parseShort(childText(activePower, "value"));
    byte multiplier = Byte.parseByte(childText(activePower, "multiplier"));
    return value * Math.pow(10, multiplier);
  }

  private static String childText(Element parent, String localName) {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (localName.equals(child.getLocalName())) {
        return child.getTextContent();
      }
    }
    throw new IllegalArgumentException(
        "CSIP-AUS %s is missing its %s child".formatted(parent.getLocalName(), localName));
  }

  private static @Nullable Double watts(@Nullable ActivePower power) {
    if (power == null) {
      return null;
    }
    return power.getValue() * Math.pow(10, power.getMultiplier().getValue());
  }
}
