package io.arcnode.dercontrol.derevent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.eventlog.EventLogService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit — every operator setting change is an event. AAA. */
@ExtendWith(MockitoExtension.class)
class DispatchSettingsServiceEventLogTest {

  @Mock private DispatchSettingsRepository repository;
  @Mock private EventLogService eventLog;

  private DispatchSettingsService service() {
    return new DispatchSettingsService(repository, eventLog);
  }

  @Test
  void settingTheReserveLogsTheStoredValue() {
    // Arrange: a negative ask clamps to none, and the log must say what was stored
    given(repository.findById(DispatchSettings.SINGLETON_ID)).willReturn(Optional.empty());
    given(repository.save(any(DispatchSettings.class))).willAnswer(inv -> inv.getArgument(0));

    // Act
    service().setOperatorReserveWh(-5.0);

    // Assert
    verify(eventLog).operatorReserveSet(0.0);
  }

  @Test
  void settingTheModeLogsTheMode() {
    // Arrange
    given(repository.findById(DispatchSettings.SINGLETON_ID)).willReturn(Optional.empty());
    given(repository.save(any(DispatchSettings.class))).willAnswer(inv -> inv.getArgument(0));

    // Act
    service().setMode(DispatchMode.MANUAL);

    // Assert
    verify(eventLog).dispatchModeSet(DispatchMode.MANUAL);
  }
}
