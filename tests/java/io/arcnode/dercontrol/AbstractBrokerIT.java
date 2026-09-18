package io.arcnode.dercontrol;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared HiveMQ broker for every {@code *IT} — {@code MqttConfig} connects on boot, so every
 * {@code @SpringBootTest} needs one reachable, and Spring's context cache means several {@code *IT}
 * classes reuse the SAME {@code ApplicationContext} (and its {@code MqttClient} bean) across a run.
 *
 * <p>Reason: this is a Testcontainers "singleton container" (start once in a static initializer,
 * never call {@code stop()} — Ryuk reaps it at JVM exit), not the more usual {@code @Container} /
 * {@code @Testcontainers} JUnit5-managed field. A {@code @Container} field gets independently
 * started <em>and stopped</em> by every subclass's own {@code @Testcontainers} extension instance —
 * for one test class that's fine, but here it killed the broker out from under a still-cached
 * context after the first `*IT` class finished, orphaning the app's `MqttClient` mid-suite.
 * Postgres doesn't hit this because {@code @ServiceConnection} containers are
 * Spring-context-lifecycled, not JUnit5-class-lifecycled.
 *
 * <p>{@code hivemq-ce} has no {@code @ServiceConnection} support in Boot, so the broker URL is fed
 * in manually via {@code @DynamicPropertySource}. Image left unpinned (matches the Testcontainers
 * module's own docs example) — no known-good CE tag to pin to; revisit if that bites us.
 */
public abstract class AbstractBrokerIT {

  static final HiveMQContainer HIVEMQ;

  static {
    HIVEMQ = new HiveMQContainer(DockerImageName.parse("hivemq/hivemq-ce"));
    // Reason: mirror @Testcontainers(disabledWithoutDocker = true) on the concrete *IT classes —
    // without this guard a Docker-less box gets a hard class-load crash instead of a clean skip.
    if (DockerClientFactory.instance().isDockerAvailable()) {
      HIVEMQ.start();
    }
  }

  @DynamicPropertySource
  static void mqttProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "app.mqttBrokerUrl", () -> "tcp://" + HIVEMQ.getHost() + ":" + HIVEMQ.getMqttPort());
  }
}
