package io.arcnode.dercontrol.mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.arcnode.dercontrol.mirror.ieee20305.MirrorUsagePointElement;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Unit — builds a real IEEE 2030.5 {@code MirrorUsagePoint} for der_dispatch's actual delivered
 * power. The happy-path assertion is schema validation against the real {@code sep.xsd}, not just
 * "the Java object was constructed" — a JAXB type mismatch is a compile error, but a semantically
 * wrong-but-well-typed value (missing required field, wrong enum code) would only be caught here.
 * AAA.
 */
class MirrorUsagePointFactoryTest {

  @Test
  void buildsAMirrorUsagePointThatValidatesAgainstTheRealIeee20305Schema() {
    // Arrange
    long activeWatts = 500_000L;

    // Act
    MirrorUsagePointElement usagePoint = MirrorUsagePointFactory.build(activeWatts);
    String xml = Ieee20305Xml.marshal(usagePoint);

    // Assert
    assertThatCode(() -> Ieee20305Xml.validate(xml)).doesNotThrowAnyException();
    assertThat(usagePoint.getSchemaVer()).isEqualTo("2.2");
  }

  @Test
  void carriesThisServicesOwnLfdiAndTheActualWattsReading() {
    // Arrange
    long activeWatts = 500_000L;

    // Act
    MirrorUsagePointElement usagePoint = MirrorUsagePointFactory.build(activeWatts);

    // Assert
    assertThat(usagePoint.getDeviceLFDI())
        .isEqualTo(HexFormat.of().parseHex(DerDispatchIdentity.LFDI));
    assertThat(usagePoint.getMirrorMeterReading()).hasSize(1);
    var reading = usagePoint.getMirrorMeterReading().get(0);
    assertThat(reading.getReading().getValue()).isEqualTo(activeWatts);
    // kind=37 (Power), uom=38 (W), multiplier=0 (x1) — verified against the real schema's own
    // documented codes, not guessed.
    assertThat(reading.getReadingType().getKind().getValue()).isEqualTo((short) 37);
    assertThat(reading.getReadingType().getUom().getValue()).isEqualTo((short) 38);
    assertThat(reading.getReadingType().getPowerOfTenMultiplier().getValue()).isEqualTo((byte) 0);
  }

  @Test
  void supportsNegativeWattsForCharging() {
    // Arrange: bess_rack's own active_power convention — negative means charging
    long chargingWatts = -250_000L;

    // Act
    MirrorUsagePointElement usagePoint = MirrorUsagePointFactory.build(chargingWatts);

    // Assert
    assertThat(usagePoint.getMirrorMeterReading().get(0).getReading().getValue())
        .isEqualTo(chargingWatts);
  }
}
