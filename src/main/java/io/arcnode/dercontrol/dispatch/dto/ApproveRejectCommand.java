package io.arcnode.dercontrol.dispatch.dto;

import org.jspecify.annotations.Nullable;

/**
 * Payload for der_dispatch's {@code approve_dispatch}/{@code reject_dispatch} commands. The verb
 * (enable/disable) already carries the decision — this only exists to optionally disambiguate
 * *which* event, since the fixed {@code commands/{verb}/event_active/none} topic shape has no mRID
 * slot of its own. Absent entirely (an empty {@code {}} body) is valid — it just means "the nearest
 * still-undecided event."
 *
 * @param mrid the event to target, or {@code null} to use the fallback heuristic
 */
public record ApproveRejectCommand(@Nullable String mrid) {}
