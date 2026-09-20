package io.arcnode.dercontrol.derevent;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for the {@link DispatchSettings} singleton row. */
public interface DispatchSettingsRepository extends JpaRepository<DispatchSettings, Long> {}
