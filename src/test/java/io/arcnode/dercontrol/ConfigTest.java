package io.arcnode.dercontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
  void customerFileOverridesTheBlockKeyByKey(@TempDir Path dir) throws IOException {
    // Arrange: what platform writes per deployment — the site, the real DERMS, our public URL
    Path customer = dir.resolve("cfg.customer.yml");
    Files.writeString(
        customer,
        """
        siteId: brookside_dc_1
        utilityMirrorUrl: https://derms.utility.invalid
        publicBaseUrl: https://203.0.113.10:8443
        """);
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("ENV", "beta")
            .withProperty("CFG_CUSTOMER_PATH", customer.toString());

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert: the three keys are the customer's, everything else is still the beta block
    assertThat(env.getProperty("app.siteId")).isEqualTo("brookside_dc_1");
    assertThat(env.getProperty("app.utilityMirrorUrl")).isEqualTo("https://derms.utility.invalid");
    assertThat(env.getProperty("app.publicBaseUrl")).isEqualTo("https://203.0.113.10:8443");
    assertThat(env.getProperty("app.mqttBrokerUrl")).isEqualTo("tcp://hivemq:1883");
    assertThat(env.getProperty("app.eventRetentionDays", Integer.class)).isEqualTo(90);
  }

  @Test
  void anAbsentCustomerFileLeavesTheBlockAlone(@TempDir Path dir) {
    // Arrange: same rule as the gateway's loader — the path is set everywhere, the file only where
    // a deployment wrote one
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("ENV", "beta")
            .withProperty("CFG_CUSTOMER_PATH", dir.resolve("missing.yml").toString());

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert
    assertThat(env.getProperty("app.siteId")).isEqualTo("beta_site");
  }

  @Test
  void aCustomerKeyThatIsNotACfgKeyFailsBoot(@TempDir Path dir) throws IOException {
    // Arrange: a typo'd key would otherwise be dropped silently and the site would run as beta_site
    Path customer = dir.resolve("cfg.customer.yml");
    Files.writeString(customer, "siteID: brookside_dc_1\n");
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("ENV", "beta")
            .withProperty("CFG_CUSTOMER_PATH", customer.toString());

    // Act / Assert
    assertThatThrownBy(() -> loader.postProcessEnvironment(env, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("siteID");
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
