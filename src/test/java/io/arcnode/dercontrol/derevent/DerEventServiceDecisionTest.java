package io.arcnode.dercontrol.derevent;

import static io.arcnode.dercontrol.derevent.DerEventFixtures.START;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.curtailment;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.dispatch.EnvelopeFeedMonitor;
import io.arcnode.dercontrol.eventlog.EventLogService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit — an operator's approve/reject command applied by {@link DerEventService}. AAA. */
@ExtendWith(MockitoExtension.class)
class DerEventServiceDecisionTest {

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

  /** A scheduled event with no decision yet. */
  private static DerEvent undecided(String mrid) {
    return withId(1, curtailment(mrid, DerControlStatus.SCHEDULED, START, null));
  }

  @Test
  void approveCurrentPendingByMridTargetsThatExactEvent() {
    // Arrange: two events pending at once — mrid disambiguates which one
    DerEvent target = undecided("mrid-1");
    given(repository.findByMrid("mrid-1")).willReturn(Optional.of(target));
    given(repository.save(target)).willReturn(target);

    // Act
    service().approveCurrentPending("mrid-1");

    // Assert
    assertThat(target.getApproved()).isTrue();
    verify(posture).publishGoverning(target);
    verify(repository, never()).findFirstByApprovedIsNullOrderByIntervalStartAsc();
  }

  @Test
  void rejectCurrentPendingByMridTargetsThatExactEvent() {
    // Arrange
    DerEvent target = undecided("mrid-1");
    given(repository.findByMrid("mrid-1")).willReturn(Optional.of(target));
    given(repository.save(target)).willReturn(target);

    // Act
    service().rejectCurrentPending("mrid-1");

    // Assert
    assertThat(target.getApproved()).isFalse();
    verify(posture).publishGoverning(target);
  }

  @Test
  void approveCurrentPendingWithNoMridFallsBackToNearestIntervalStart() {
    // Arrange: no mrid on the command (fixed commands/{verb}/event_active/none topic shape has no
    // slot for one) — resolve whichever still-undecided event is nearest its interval.start
    DerEvent nearest = undecided("mrid-1");
    given(repository.findFirstByApprovedIsNullOrderByIntervalStartAsc())
        .willReturn(Optional.of(nearest));
    given(repository.save(nearest)).willReturn(nearest);

    // Act
    service().approveCurrentPending(null);

    // Assert
    assertThat(nearest.getApproved()).isTrue();
    verify(posture).publishGoverning(nearest);
  }

  @Test
  void decideCurrentPendingIsANoOpWhenNothingResolves() {
    // Arrange: no mrid given, nothing currently undecided
    given(repository.findFirstByApprovedIsNullOrderByIntervalStartAsc())
        .willReturn(Optional.empty());

    // Act
    service().approveCurrentPending(null);

    // Assert
    verify(posture, never()).publishGoverning(any());
  }
}
