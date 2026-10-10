package io.arcnode.dercontrol.derevent;

import static io.arcnode.dercontrol.derevent.DerEventFixtures.RECEIVED_DOCUMENT;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.START;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.curtailment;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.request;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.TestCerts;
import io.arcnode.dercontrol.dispatch.EnvelopeFeedMonitor;
import io.arcnode.dercontrol.eventlog.EventLogService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit — what the ingest and decision paths tell the event log. AAA. */
@ExtendWith(MockitoExtension.class)
class DerEventServiceEventLogTest {

  @Mock private DerEventRepository repository;
  @Mock private DerEventPostureService posture;
  @Mock private EnvelopeFeedMonitor envelopeFeedMonitor;
  @Mock private EventLogService eventLog;

  private DerEventService service() {
    return new DerEventService(repository, posture, envelopeFeedMonitor, eventLog);
  }

  @Test
  void aNewMridIsLoggedAsReceivedBySubmitter() {
    // Arrange
    given(repository.findByMrid("mrid-1")).willReturn(Optional.empty());
    given(repository.save(any(DerEvent.class))).willAnswer(inv -> withId(1, inv.getArgument(0)));

    // Act
    service()
        .ingest(
            request("mrid-1", DerControlStatus.ACTIVE), RECEIVED_DOCUMENT, TestCerts.HEADER_VALUE);

    // Assert
    ArgumentCaptor<DerEvent> logged = ArgumentCaptor.forClass(DerEvent.class);
    verify(eventLog)
        .derEventReceived(logged.capture(), org.mockito.ArgumentMatchers.eq(TestCerts.LFDI));
    assertThat(logged.getValue().getMrid()).isEqualTo("mrid-1");
  }

  @Test
  void aRetransmitIsLoggedAsAnUpdate() {
    // Arrange: the utility cancels an event it sent earlier
    DerEvent existing =
        withId(1, curtailment("mrid-1", DerControlStatus.ACTIVE, START, -1_000_000.0));
    given(repository.findByMrid("mrid-1")).willReturn(Optional.of(existing));
    given(repository.save(any(DerEvent.class))).willAnswer(inv -> inv.getArgument(0));

    // Act
    service()
        .ingest(
            request("mrid-1", DerControlStatus.CANCELLED),
            RECEIVED_DOCUMENT,
            TestCerts.HEADER_VALUE);

    // Assert
    ArgumentCaptor<DerEvent> logged = ArgumentCaptor.forClass(DerEvent.class);
    verify(eventLog)
        .derEventUpdated(logged.capture(), org.mockito.ArgumentMatchers.eq(TestCerts.LFDI));
    assertThat(logged.getValue().getStatus()).isEqualTo(DerControlStatus.CANCELLED);
  }

  @Test
  void anOperatorDecisionIsLogged() {
    // Arrange: a pending event, approved by mRID
    DerEvent pending =
        withId(1, curtailment("mrid-1", DerControlStatus.ACTIVE, START, -1_000_000.0));
    given(repository.findByMrid("mrid-1")).willReturn(Optional.of(pending));
    given(repository.save(any(DerEvent.class))).willAnswer(inv -> inv.getArgument(0));

    // Act
    service().approveCurrentPending("mrid-1");

    // Assert
    verify(eventLog).derEventDecided(pending, true);
  }
}
