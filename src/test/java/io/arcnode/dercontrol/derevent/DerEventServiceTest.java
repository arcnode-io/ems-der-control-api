package io.arcnode.dercontrol.derevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.TestCerts;
import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import io.arcnode.dercontrol.derevent.dto.DerEventResponse;
import io.arcnode.dercontrol.dispatch.DispatchPublisher;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit — mocked repository + publisher, real JsonMapper (pure serializer, not worth mocking). AAA.
 */
@ExtendWith(MockitoExtension.class)
class DerEventServiceTest {

  private static final Instant START = Instant.parse("2026-09-08T14:00:00Z");

  @Mock private DerEventRepository repository;
  @Mock private DispatchPublisher publisher;
  private final JsonMapper mapper = JsonMapper.builder().build();

  private DerEventService service() {
    return new DerEventService(repository, publisher, mapper);
  }

  private static DerControlRequest request(String mrid, DerControlStatus status) {
    return new DerControlRequest(
        mrid,
        status,
        new DerControlRequest.Interval(START, 3600L),
        new DerControlRequest.ControlBase(-1_000_000.0, true));
  }

  private static DerEvent withId(long id, DerEvent event) {
    ReflectionTestUtils.setField(event, "id", id);
    return event;
  }

  @Test
  void ingestSavesNewEventAndPublishes() {
    // Arrange
    given(repository.findByMrid("mrid-1")).willReturn(Optional.empty());
    given(repository.save(any(DerEvent.class))).willAnswer(inv -> withId(1, inv.getArgument(0)));

    // Act
    DerEventResponse result =
        service().ingest(request("mrid-1", DerControlStatus.ACTIVE), TestCerts.HEADER_VALUE);

    // Assert
    assertThat(result.mrid()).isEqualTo("mrid-1");
    assertThat(result.status()).isEqualTo(DerControlStatus.ACTIVE);
    assertThat(result.targetActivePowerW()).isEqualTo(-1_000_000.0);
    assertThat(result.submittedByLfdi()).isEqualTo(TestCerts.LFDI);
    ArgumentCaptor<DerEvent> published = ArgumentCaptor.forClass(DerEvent.class);
    verify(publisher).publish(published.capture());
    assertThat(published.getValue().getMrid()).isEqualTo("mrid-1");
  }

  @Test
  void ingestUpdatesExistingEventOnRetransmit() {
    // Arrange: same mRID re-sent as Cancelled — updates the existing row, doesn't duplicate it
    DerEvent existing =
        withId(
            1,
            new DerEvent(
                "mrid-1",
                DerControlStatus.ACTIVE,
                START,
                3600L,
                -1_000_000.0,
                true,
                "{}",
                "old-lfdi"));
    given(repository.findByMrid("mrid-1")).willReturn(Optional.of(existing));
    given(repository.save(any(DerEvent.class))).willAnswer(inv -> inv.getArgument(0));

    // Act
    DerEventResponse result =
        service().ingest(request("mrid-1", DerControlStatus.CANCELLED), TestCerts.HEADER_VALUE);

    // Assert
    assertThat(result.status()).isEqualTo(DerControlStatus.CANCELLED);
    assertThat(result.submittedByLfdi()).isEqualTo(TestCerts.LFDI);
    verify(repository).save(existing);
  }

  @Test
  void findByMridReturnsResponseWhenPresent() {
    // Arrange
    DerEvent event =
        withId(
            1,
            new DerEvent(
                "mrid-1", DerControlStatus.ACTIVE, START, 3600L, 500.0, null, "{}", "lfdi-1"));
    given(repository.findByMrid("mrid-1")).willReturn(Optional.of(event));

    // Act
    Optional<DerEventResponse> result = service().findByMrid("mrid-1");

    // Assert
    assertThat(result).isPresent();
    assertThat(result.orElseThrow().mrid()).isEqualTo("mrid-1");
  }

  @Test
  void findByMridIsEmptyWhenMissing() {
    // Arrange
    given(repository.findByMrid("missing")).willReturn(Optional.empty());

    // Act
    Optional<DerEventResponse> result = service().findByMrid("missing");

    // Assert
    assertThat(result).isEmpty();
    verify(publisher, never()).publish(any());
  }

  @Test
  void findByStatusReturnsMatchingEvents() {
    // Arrange
    DerEvent a =
        withId(
            1, new DerEvent("a", DerControlStatus.ACTIVE, START, 60L, null, null, "{}", "lfdi-a"));
    DerEvent b =
        withId(
            2, new DerEvent("b", DerControlStatus.ACTIVE, START, 60L, null, null, "{}", "lfdi-b"));
    given(repository.findByStatus(DerControlStatus.ACTIVE)).willReturn(List.of(a, b));

    // Act
    List<DerEventResponse> result = service().findByStatus(DerControlStatus.ACTIVE);

    // Assert
    assertThat(result).extracting(DerEventResponse::mrid).containsExactly("a", "b");
  }
}
