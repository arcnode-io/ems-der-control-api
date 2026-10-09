package io.arcnode.dercontrol.derevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit — decodes the IEEE 2030.5 {@code Notification} the utility pushes into this service's own
 * internal command shape. The fixtures are the literal bytes mock-derms-dispatch-api's own
 * DerControlNotificationFactory emits, so this is a real round-trip against the other side of the
 * wire rather than against an invented document. AAA.
 */
class DerControlNotificationParserTest {

  private static final String CURTAILMENT =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
      <Notification schemaVer="2.2" xmlns="urn:ieee:std:2030.5:ns">\
      <subscribedResource>https://mock-derms.invalid/derp/1/derc</subscribedResource>\
      <createdDateTime>1790359200</createdDateTime>\
      <Resource xsi:type="DERControlList" all="1" results="1"\
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><DERControl>\
      <mRID>0123456789ABCDEF0123456789ABCDEF</mRID><creationTime>1790359200</creationTime>\
      <EventStatus><currentStatus>1</currentStatus><dateTime>1790359200</dateTime>\
      <potentiallySuperseded>false</potentiallySuperseded></EventStatus>\
      <interval><duration>360</duration><start>1790359205</start></interval>\
      <DERControlBase><opModEnergize>true</opModEnergize>\
      <opModTargetW><multiplier>2</multiplier><value>15000</value></opModTargetW>\
      </DERControlBase></DERControl></Resource><status>0</status>\
      <subscriptionURI>https://mock-derms.invalid/sub/1</subscriptionURI></Notification>\
      """;

  private static final String ENVELOPE =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
      <Notification schemaVer="2.2" xmlns="urn:ieee:std:2030.5:ns">\
      <subscribedResource>https://mock-derms.invalid/derp/1/derc</subscribedResource>\
      <createdDateTime>1790359200</createdDateTime>\
      <Resource xsi:type="DERControlList" all="1" results="1"\
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><DERControl>\
      <mRID>0123456789ABCDEF0123456789ABCDEF</mRID><creationTime>1790359200</creationTime>\
      <EventStatus><currentStatus>1</currentStatus><dateTime>1790359200</dateTime>\
      <potentiallySuperseded>false</potentiallySuperseded></EventStatus>\
      <interval><duration>360</duration><start>1790359205</start></interval>\
      <DERControlBase>\
      <csipaus:opModImpLimW xmlns:csipaus="https://csipaus.org/ns/v1.3">\
      <multiplier>2</multiplier><value>5000</value></csipaus:opModImpLimW>\
      <csipaus:opModExpLimW xmlns:csipaus="https://csipaus.org/ns/v1.3">\
      <multiplier>0</multiplier><value>0</value></csipaus:opModExpLimW>\
      </DERControlBase></DERControl></Resource><status>0</status>\
      <subscriptionURI>https://mock-derms.invalid/sub/1</subscriptionURI></Notification>\
      """;

  @Test
  void decodesTheMridAsLowercaseHexWithoutHyphens() {
    // Act
    DerControlRequest request = DerControlNotificationParser.parse(CURTAILMENT);

    // Assert
    assertThat(request.mrid()).isEqualTo("0123456789abcdef0123456789abcdef");
  }

  @Test
  void decodesTheScaledMantissaBackToWholeWatts() {
    // Act
    DerControlRequest request = DerControlNotificationParser.parse(CURTAILMENT);

    // Assert: multiplier 2, value 15000 -> 1.5 MW
    assertThat(request.derControlBase().opModTargetW()).isEqualTo(1_500_000.0);
    assertThat(request.derControlBase().opModEnergize()).isTrue();
  }

  @Test
  void decodesEpochSecondsBackToAnInstantAndDuration() {
    // Act
    DerControlRequest request = DerControlNotificationParser.parse(CURTAILMENT);

    // Assert
    assertThat(request.interval().start()).isEqualTo(Instant.ofEpochSecond(1_790_359_205L));
    assertThat(request.interval().durationSeconds()).isEqualTo(360L);
  }

  @Test
  void decodesTheEventStatusCodeToItsName() {
    // Act
    DerControlRequest request = DerControlNotificationParser.parse(CURTAILMENT);

    // Assert: currentStatus 1 = Active
    assertThat(request.eventStatus()).isEqualTo(DerControlStatus.ACTIVE);
  }

  @Test
  void decodesTheProgramFromTheResourceTheUtilityNamed() {
    // Arrange: the same control, pushed under the flex program rather than the line-constraint one
    String xml = CURTAILMENT.replace("/derp/1/derc", "/derp/2/derc");

    // Act
    DerControlRequest request = DerControlNotificationParser.parse(xml);

    // Assert: a DERControl carries no reason; the program it came from is how 2030.5 says what it
    // is for, and it is the only thing that tells a flex call from a conductor limit upstream
    assertThat(request.program()).isEqualTo(DerProgram.ERCOT_FLEX);
    assertThat(DerControlNotificationParser.parse(CURTAILMENT).program())
        .isEqualTo(DerProgram.DLR_LINE_CONSTRAINT);
  }

  @Test
  void rejectsANotificationFromAProgramThisSiteNeverSubscribedTo() {
    // Arrange
    String xml = CURTAILMENT.replace("/derp/1/derc", "/derp/7/derc");

    // Act / Assert: an event from a program we are not enrolled in is the utility's error, not
    // something to quietly file under a default
    assertThatThrownBy(() -> DerControlNotificationParser.parse(xml))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsANotificationWhoseResourceIsNotADerControl() {
    // Arrange: a structurally valid Notification with no DERControl in the Resource slot
    String noControl =
        """
        <Notification schemaVer="2.2" xmlns="urn:ieee:std:2030.5:ns">\
        <subscribedResource>https://mock-derms.invalid/derp/1/derc</subscribedResource>\
        <status>0</status>\
        <subscriptionURI>https://mock-derms.invalid/sub/1</subscriptionURI></Notification>\
        """;

    // Act / Assert
    assertThatThrownBy(() -> DerControlNotificationParser.parse(noControl))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DERControl");
  }

  @Test
  void rejectsBodyThatIsNotIeee20305Xml() {
    // Act / Assert
    assertThatThrownBy(() -> DerControlNotificationParser.parse("{\"mrid\":\"abc\"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decodesCsipAusEnvelopeLimitsOutOfTheExtensionSlot() {
    // Act
    DerControlRequest request = DerControlNotificationParser.parse(ENVELOPE);

    // Assert: 500 kW import ceiling, non-export site
    assertThat(request.derControlBase().opModImpLimW()).isEqualTo(500_000.0);
    assertThat(request.derControlBase().opModExpLimW()).isEqualTo(0.0);
  }

  @Test
  void leavesEnvelopeLimitsAbsentOnAPlainCurtailment() {
    // Act
    DerControlRequest request = DerControlNotificationParser.parse(CURTAILMENT);

    // Assert
    assertThat(request.derControlBase().opModImpLimW()).isNull();
    assertThat(request.derControlBase().opModExpLimW()).isNull();
  }

  @Test
  void rejectsADerControlMissingItsMandatoryInterval() {
    // Arrange: JAXB unmarshalling alone does not enforce minOccurs, so the parser has to
    String noInterval =
        """
        <Notification schemaVer="2.2" xmlns="urn:ieee:std:2030.5:ns">\
        <subscribedResource>https://mock-derms.invalid/derp/1/derc</subscribedResource>\
        <Resource xsi:type="DERControlList" all="1" results="1"\
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><DERControl>\
        <mRID>0123456789ABCDEF0123456789ABCDEF</mRID><creationTime>1790359200</creationTime>\
        <EventStatus><currentStatus>1</currentStatus><dateTime>1790359200</dateTime>\
        <potentiallySuperseded>false</potentiallySuperseded></EventStatus>\
        </DERControl></Resource><status>0</status>\
        <subscriptionURI>https://mock-derms.invalid/sub/1</subscriptionURI></Notification>\
        """;

    // Act / Assert
    assertThatThrownBy(() -> DerControlNotificationParser.parse(noInterval))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mandatory");
  }

  @Test
  void rejectsAnMridThatIsNotHexBinary128() {
    // Arrange: mRID is HexBinary128, so "...zz" is not a value JAXB can decode. The element is
    // present, so a present-but-undecodable mRID is a distinct case from a missing one.
    String badMrid =
        """
        <Notification schemaVer="2.2" xmlns="urn:ieee:std:2030.5:ns">\
        <subscribedResource>https://mock-derms.invalid/derp/1/derc</subscribedResource>\
        <Resource xsi:type="DERControlList" all="1" results="1"\
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><DERControl>\
        <mRID>0123456789ABCDEF0123456789ABCDzz</mRID><creationTime>1790359200</creationTime>\
        <EventStatus><currentStatus>1</currentStatus><dateTime>1790359200</dateTime>\
        <potentiallySuperseded>false</potentiallySuperseded></EventStatus>\
        <interval><duration>360</duration><start>1790359205</start></interval>\
        </DERControl></Resource><status>0</status>\
        <subscriptionURI>https://mock-derms.invalid/sub/1</subscriptionURI></Notification>\
        """;

    // Act / Assert: an IllegalArgumentException is what the controller turns into a 400. A
    // NullPointerException would surface to the utility as a 500, blaming this service for
    // their malformed document.
    assertThatThrownBy(() -> DerControlNotificationParser.parse(badMrid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mRID");
  }
}
