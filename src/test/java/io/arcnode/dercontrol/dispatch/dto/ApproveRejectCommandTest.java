package io.arcnode.dercontrol.dispatch.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit — the approve/reject payload has to survive the envelope the rest of this bus publishes.
 * Every command carries {@code {ts, value}}, and the template types these two commands' payload as
 * {@code bool}, so the real body an operator's click produces has both fields and no mRID. A parse
 * failure is swallowed by DispatchCommandSubscriber's guard as a logged error, which would drop the
 * approval silently — hence a direct deserialization test rather than one that goes through the
 * subscriber's off-thread handoff. AAA.
 */
class ApproveRejectCommandTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void parsesTheEnvelopeTheHmiPublishesAndIgnoresItsFields() {
    // Arrange
    String envelope = "{\"ts\":\"2026-10-03T12:00:00Z\",\"value\":true}";

    // Act
    ApproveRejectCommand command = mapper.readValue(envelope, ApproveRejectCommand.class);

    // Assert: no mRID in the envelope, so the service falls back to the nearest undecided event
    assertThat(command.mrid()).isNull();
  }

  @Test
  void stillReadsAnMridWhenOneIsCarriedAlongsideTheEnvelope() {
    // Arrange
    String envelope = "{\"ts\":\"2026-10-03T12:00:00Z\",\"value\":true,\"mrid\":\"mrid-1\"}";

    // Act
    ApproveRejectCommand command = mapper.readValue(envelope, ApproveRejectCommand.class);

    // Assert
    assertThat(command.mrid()).isEqualTo("mrid-1");
  }

  @Test
  void acceptsABareEmptyBody() {
    // Act / Assert
    assertThatCode(() -> mapper.readValue("{}", ApproveRejectCommand.class))
        .doesNotThrowAnyException();
  }
}
