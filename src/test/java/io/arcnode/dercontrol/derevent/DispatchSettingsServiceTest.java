package io.arcnode.dercontrol.derevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

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

  private DispatchSettingsService service() {
    return new DispatchSettingsService(repository);
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
