package io.arcnode.dercontrol.derevent;

import static io.arcnode.dercontrol.derevent.DerEventFixtures.RECEIVED_DOCUMENT;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.START;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.curtailment;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.request;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.TestCerts;
import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
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

  // Reason: lenient — the decision tests never reach ingest, and strict stubs would flag it.
  @Mock(strictness = Mock.Strictness.LENIENT)
  private LfdiAllowlist allowlist;

  private DerEventService service() {
    given(allowlist.allows(any())).willReturn(true);
    return new DerEventService(repository, posture, envelopeFeedMonitor, eventLog, allowlist);
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

  @Test
  void anEnvelopeRefreshIsNotAnEvent() {
    // Arrange: the operating envelope re-POSTs every few seconds under one mRID, limits only
    DerControlRequest envelope =
        new DerControlRequest(
            "env-1",
            DerControlStatus.ACTIVE,
            new DerControlRequest.Interval(START, 360L),
            new DerControlRequest.ControlBase(null, null, 6_864_000.0, null),
            DerProgram.DLR_LINE_CONSTRAINT);
    given(repository.findByMrid("env-1")).willReturn(Optional.empty());
    given(repository.save(any(DerEvent.class))).willAnswer(inv -> withId(1, inv.getArgument(0)));

    // Act
    service().ingest(envelope, RECEIVED_DOCUMENT, TestCerts.HEADER_VALUE);

    // Assert
    verify(eventLog, never()).derEventReceived(any(), any());
    verify(eventLog, never()).derEventUpdated(any(), any());
  }
}
