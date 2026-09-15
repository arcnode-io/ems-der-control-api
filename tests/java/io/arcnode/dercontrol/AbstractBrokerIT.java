package io.arcnode.dercontrol;

import org.springframework.test.annotation.DirtiesContext;
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
 *
 * <p>{@code @DirtiesContext(AFTER_CLASS)} (inherited by every subclass — verified {@code
 * DirtiesContext} carries {@code @Inherited}) forces Spring to close each {@code *IT} class's
 * {@code ApplicationContext}, including its {@code MqttClient} ({@code destroyMethod = "close"} in
 * {@code MqttConfig}), before the next class's context is built, rather than caching and reusing
 * it. Found the hard way, adding {@code DispatchCommandSubscriber}: without this, Spring's normal
 * context-cache reuse means an <em>earlier</em>-run {@code *IT} class's context can still be alive
 * and still subscribed to this broker while a <em>later</em> class's tests run. MQTT topics are
 * fan-out, not exclusive-consumer — every live subscriber gets every message — so a single command
 * published by one test was being delivered to that stale context's own {@code
 * DispatchCommandSubscriber} too, which independently resolved and published a <em>different</em>
 * leftover event's state to this same, fixed (not per-test) topic in the same window. Costs a full
 * context boot per class instead of reusing one across all four; worth it — a component with
 * always-on behavior driven by shared external state cannot be tested correctly if a sibling
 * class's instance of it is still alive and reacting during a different class's test.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
