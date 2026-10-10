package io.arcnode.dercontrol.derevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.eventlog.EventLogService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit — mocked repository. AAA. */
@ExtendWith(MockitoExtension.class)
class DispatchSettingsServiceTest {

  @Mock private DispatchSettingsRepository repository;
  @Mock private EventLogService eventLog;

  private DispatchSettingsService service() {
    return new DispatchSettingsService(repository, eventLog);
  }

  @Test
  void defaultsToAutoWhenNoRowExistsYet() {
    // Arrange: fresh deployment, nobody has ever set a mode
    given(repository.findById(DispatchSettings.SINGLETON_ID)).willReturn(Optional.empty());

    // Act
    DispatchMode mode = service().currentMode();

    // Assert: matches the original's "default auto — no behavior change for the running demo"
    assertThat(mode).isEqualTo(DispatchMode.AUTO);
  }

  @Test
  void returnsThePersistedModeWhenARowExists() {
    // Arrange
    given(repository.findById(DispatchSettings.SINGLETON_ID))
        .willReturn(Optional.of(new DispatchSettings(DispatchMode.MANUAL)));

    // Act
    DispatchMode mode = service().currentMode();

    // Assert
    assertThat(mode).isEqualTo(DispatchMode.MANUAL);
  }

  @Test
  void thereIsNoOperatorReserveUnlessSomebodySetOne() {
    // Arrange: a row written before this field existed carries null for it
    given(repository.findById(DispatchSettings.SINGLETON_ID))
        .willReturn(Optional.of(new DispatchSettings(DispatchMode.AUTO)));

    // Act / Assert: absent means none, matching the gateway's own "absent means no reserve"
    assertThat(service().operatorReserveWh()).isZero();
  }

  @Test
  void aNegativeReserveClampsToNone() {
    // Arrange: meaningless rather than dangerous, and a command cannot be answered with an error
    given(repository.findById(DispatchSettings.SINGLETON_ID))
        .willReturn(Optional.of(new DispatchSettings(DispatchMode.AUTO)));
    given(repository.save(any(DispatchSettings.class))).willAnswer(inv -> inv.getArgument(0));

    // Act / Assert
    assertThat(service().setOperatorReserveWh(-5_000.0)).isZero();
  }

  @Test
  void anOversizedReserveIsStoredRatherThanRejected() {
    // Arrange: refusing a reserve larger than today's capacity would be wrong the moment racks are
    // added at commissioning, and the gateway's max() makes it ineffective rather than dangerous
    given(repository.findById(DispatchSettings.SINGLETON_ID))
        .willReturn(Optional.of(new DispatchSettings(DispatchMode.AUTO)));
    given(repository.save(any(DispatchSettings.class))).willAnswer(inv -> inv.getArgument(0));

    // Act / Assert
    assertThat(service().setOperatorReserveWh(9_999_999_999.0)).isEqualTo(9_999_999_999.0);
  }

  @Test
  void settingTheModeDoesNotWipeTheOperatorReserve() {
    // Arrange: the operator has already held energy back
    DispatchSettings existing = new DispatchSettings(DispatchMode.AUTO);
    existing.setOperatorReserveWh(2_000_000.0);
    given(repository.findById(DispatchSettings.SINGLETON_ID)).willReturn(Optional.of(existing));
    given(repository.save(any(DispatchSettings.class))).willAnswer(inv -> inv.getArgument(0));

    // Act: changing an unrelated policy on the same singleton row
    service().setMode(DispatchMode.MANUAL);

    // Assert: saving a freshly constructed row would silently re-authorize the battery — the
    // operator's decision has to survive a change to anything else on this row
    ArgumentCaptor<DispatchSettings> saved = ArgumentCaptor.forClass(DispatchSettings.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getMode()).isEqualTo(DispatchMode.MANUAL);
    assertThat(saved.getValue().getOperatorReserveWh()).isEqualTo(2_000_000.0);
  }

  @Test
  void settingTheReserveDoesNotChangeTheMode() {
    // Arrange
    given(repository.findById(DispatchSettings.SINGLETON_ID))
        .willReturn(Optional.of(new DispatchSettings(DispatchMode.MANUAL)));
    given(repository.save(any(DispatchSettings.class))).willAnswer(inv -> inv.getArgument(0));

    // Act
    double result = service().setOperatorReserveWh(2_000_000.0);

    // Assert
    assertThat(result).isEqualTo(2_000_000.0);
    ArgumentCaptor<DispatchSettings> saved = ArgumentCaptor.forClass(DispatchSettings.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getMode()).isEqualTo(DispatchMode.MANUAL);
    assertThat(saved.getValue().getOperatorReserveWh()).isEqualTo(2_000_000.0);
  }

  @Test
  void setModeUpsertsTheSingletonRow() {
    // Arrange
    given(repository.save(any(DispatchSettings.class))).willAnswer(inv -> inv.getArgument(0));

    // Act
    DispatchMode result = service().setMode(DispatchMode.MANUAL);

    // Assert
    assertThat(result).isEqualTo(DispatchMode.MANUAL);
    ArgumentCaptor<DispatchSettings> saved = ArgumentCaptor.forClass(DispatchSettings.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getId()).isEqualTo(DispatchSettings.SINGLETON_ID);
    assertThat(saved.getValue().getMode()).isEqualTo(DispatchMode.MANUAL);
  }
}
