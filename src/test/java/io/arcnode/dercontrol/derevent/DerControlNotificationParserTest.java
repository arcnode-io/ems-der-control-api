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
      <Resource xsi:type="DERControl" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">\
      <mRID>0123456789ABCDEF0123456789ABCDEF</mRID><creationTime>1790359200</creationTime>\
      <EventStatus><currentStatus>1</currentStatus><dateTime>1790359200</dateTime>\
      <potentiallySuperseded>false</potentiallySuperseded></EventStatus>\
      <interval><duration>360</duration><start>1790359205</start></interval>\
      <DERControlBase><opModEnergize>true</opModEnergize>\
      <opModTargetW><multiplier>2</multiplier><value>15000</value></opModTargetW>\
      </DERControlBase></Resource><status>0</status>\
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
}
