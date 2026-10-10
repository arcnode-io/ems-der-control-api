package io.arcnode.dercontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** Unit — {@code $ENV} block selection and {@link Config} constraints. AAA. */
class ConfigTest {

  private final Config.Loader loader = new Config.Loader();

  @Test
  void defaultsToLocalBlock() {
    // Arrange
    MockEnvironment env = new MockEnvironment();

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert
    assertThat(env.getProperty("app.port", Integer.class)).isEqualTo(8080);
    assertThat(env.getProperty("app.postgresHost")).isEqualTo("localhost");
    assertThat(env.getProperty("app.e2e", Boolean.class)).isFalse();
    assertThat(env.getProperty("app.mqttBrokerUrl")).isEqualTo("tcp://localhost:1883");
    assertThat(env.getProperty("app.mqttUsername")).isEqualTo("arcnode_der_control_api");
    assertThat(env.getProperty("app.siteId")).isEqualTo("local_site");
  }

  @Test
  void failsLoudlyWhenEnvNamesNoBlock() {
    // Arrange: silently falling back to local would run the wrong configuration and present as a
    // behaviour bug rather than a misconfiguration
    MockEnvironment env = new MockEnvironment().withProperty("ENV", "nope");

    // Act / Assert
    assertThatThrownBy(() -> loader.postProcessEnvironment(env, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void selectsBetaBlockWhenEnvIsBeta() {
    // Arrange
    MockEnvironment env = new MockEnvironment().withProperty("ENV", "beta");

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert
    assertThat(env.getProperty("app.postgresHost")).isEqualTo("postgres");
    assertThat(env.getProperty("app.e2e", Boolean.class)).isTrue();
    assertThat(env.getProperty("app.mqttBrokerUrl")).isEqualTo("tcp://hivemq:1883");
  }

  @Test
  void rejectsPortBelowEightyAndBlankHost() {
    // Arrange
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    Config bad =
        new Config(
            Config.LogLevel.INFO,
            20,
            "",
            false,
            "localhost",
            "tcp://localhost:1883",
            "u",
            "local_site",
            "http://localhost:8081",
            "http://localhost:8080",
            90);

    // Act
    var violations = validator.validate(bad);

    // Assert
    assertThat(violations).hasSize(2);
  }

  @Test
  void deviceDemoOverridesOnlySiteIdAndInheritsTheContainerHostnames() {
    // Arrange: device-demo is merged from beta — a localhost broker or Postgres inside the compose
    // stack is the failure this guards, and siteId keys every MQTT topic
    MockEnvironment env = new MockEnvironment().withProperty("ENV", "device-demo");

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert
    assertThat(env.getProperty("app.siteId")).isEqualTo("device_demo_site");
    assertThat(env.getProperty("app.mqttBrokerUrl")).isEqualTo("tcp://hivemq:1883");
    assertThat(env.getProperty("app.postgresHost")).isEqualTo("postgres");
    assertThat(env.getProperty("app.utilityMirrorUrl"))
        .isEqualTo("http://mock-derms-dispatch-api:8080");
    assertThat(env.getProperty("app.publicBaseUrl")).isEqualTo("http://der-control-api:8080");
  }
}
