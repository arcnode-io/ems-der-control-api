package io.arcnode.dercontrol;

/**
 * IEEE 2030.5 request bodies for the integration tests — the same document shape
 * mock-derms-dispatch-api's own DerControlNotificationFactory emits, so these ITs exercise the real
 * wire format rather than a convenient stand-in. Imported explicitly by each IT.
 */
final class SepXml {

  /** The media type IANA registers for IEEE 2030.5 (published specification: IEEE 2030.5). */
  static final String MEDIA_TYPE = "application/sep+xml";

  /** A target-mode setpoint of -1.5 MW with energize, scaled as the spec requires. */
  static final String TARGET_MINUS_1_5MW =
      """
      <DERControlBase><opModEnergize>true</opModEnergize>\
      <opModTargetW><multiplier>2</multiplier><value>-15000</value></opModTargetW>\
      </DERControlBase>""";

  /** A status-only retransmission carries no setpoint at all. */
  static final String NO_SETPOINT = "<DERControlBase/>";

  /** A CSIP-AUS operating envelope: 500 kW import ceiling, 300 kW export ceiling. */
  static final String ENVELOPE_500KW_IMPORT_300KW_EXPORT =
      """
      <DERControlBase>\
      <csipaus:opModImpLimW xmlns:csipaus="https://csipaus.org/ns/v1.3">\
      <multiplier>2</multiplier><value>5000</value></csipaus:opModImpLimW>\
      <csipaus:opModExpLimW xmlns:csipaus="https://csipaus.org/ns/v1.3">\
      <multiplier>2</multiplier><value>3000</value></csipaus:opModExpLimW>\
      </DERControlBase>""";

  private SepXml() {}

  /**
   * An mRID is HexBinary128 — exactly 32 hex characters — so a test cannot use a readable label.
   * This turns one into a distinct, valid mRID by hashing it into the low bytes.
   */
  static String mrid(String label) {
    return "%032x".formatted(Integer.toUnsignedLong(label.hashCode()));
  }

  /**
   * @param currentStatus an {@code EventStatus.currentStatus} code — 1 Active, 2 Cancelled, 5
   *     Completed
   * @param controlBaseXml one of this class's DERControlBase fragments
   */
  static String notification(String mrid, int currentStatus, String controlBaseXml) {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
        <Notification schemaVer="2.2" xmlns="urn:ieee:std:2030.5:ns">\
        <subscribedResource>https://utility.invalid/derp/1/derc</subscribedResource>\
        <createdDateTime>1789221600</createdDateTime>\
        <Resource xsi:type="DERControlList" all="1" results="1"\
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><DERControl>\
        <mRID>%s</mRID><creationTime>1789221600</creationTime>\
        <EventStatus><currentStatus>%d</currentStatus><dateTime>1789221600</dateTime>\
        <potentiallySuperseded>false</potentiallySuperseded></EventStatus>\
        <interval><duration>3600</duration><start>1789221600</start></interval>\
        %s</DERControl></Resource><status>0</status>\
        <subscriptionURI>https://utility.invalid/sub/1</subscriptionURI></Notification>"""
        .formatted(mrid, currentStatus, controlBaseXml);
  }
}
