package io.arcnode.dercontrol.mirror;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.mirror.ieee20305.MirrorUsagePointElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — ties the actual-power reading to the outbound POST on a scheduled tick. Mocked
 * collaborators, AAA.
 */
@ExtendWith(MockitoExtension.class)
class MirrorReportPublisherTest {

  @Mock private ActualActivePowerSubscriber subscriber;
  @Mock private MirrorUsagePointClient client;

  private MirrorReportPublisher publisher() {
    return new MirrorReportPublisher(subscriber, client);
  }

  @Test
  void doesNothingBeforeAnyReadingHasArrived() {
    // Arrange
    given(subscriber.currentActiveWatts()).willReturn(null);

    // Act
    publisher().tick();

    // Assert
    verify(client, never()).post(any());
  }

  @Test
  void postsAMirrorUsagePointCarryingTheLatestReading() {
    // Arrange
    given(subscriber.currentActiveWatts()).willReturn(500_000.0);

    // Act
    publisher().tick();

    // Assert
    ArgumentCaptor<MirrorUsagePointElement> captor =
        ArgumentCaptor.forClass(MirrorUsagePointElement.class);
    verify(client).post(captor.capture());
    org.assertj.core.api.Assertions.assertThat(
            captor.getValue().getMirrorMeterReading().get(0).getReading().getValue())
        .isEqualTo(500_000L);
  }

  @Test
  void roundsAFractionalWattsReadingToTheNearestWholeWatt() {
    // Arrange: real IEEE 2030.5 Reading.value is Int48 — sub-watt precision isn't meaningful for
    // grid-scale power anyway.
    given(subscriber.currentActiveWatts()).willReturn(499_999.6);

    // Act
    publisher().tick();

    // Assert
    ArgumentCaptor<MirrorUsagePointElement> captor =
        ArgumentCaptor.forClass(MirrorUsagePointElement.class);
    verify(client).post(captor.capture());
    org.assertj.core.api.Assertions.assertThat(
            captor.getValue().getMirrorMeterReading().get(0).getReading().getValue())
        .isEqualTo(500_000L);
  }
}
