package io.arcnode.dercontrol.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.arcnode.dercontrol.Config;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Unit — real RestClient wiring against MockRestServiceServer, no Spring context needed. AAA. */
class TopologyClientTest {

  private static final String BASE_URL = "http://device-api-test";

  private static Config config() {
    return new Config(
        Config.LogLevel.INFO,
        8080,
        "localhost",
        false,
        "localhost",
        "tcp://localhost:1883",
        "arcnode_der_control_api",
        "site_001",
        Config.DispatchMode.AUTO,
        BASE_URL);
  }

  private record Fixture(TopologyClient client, MockRestServiceServer server) {}

  private static Fixture client() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    return new Fixture(new TopologyClient(builder, config()), server);
  }

  @Test
  void resolvesFirstBessModuleDeviceId() {
    // Arrange
    Fixture fixture = client();
    fixture
        .server()
        .expect(requestTo(BASE_URL + "/topology"))
        .andRespond(
            withSuccess(
                """
                {
                  "devices": {
                    "operating_envelope": { "template": "operating_envelope" },
                    "bess_module_1": { "template": "bess_module" }
                  }
                }
                """,
                MediaType.APPLICATION_JSON));

    // Act / Assert
    assertThat(fixture.client().findBessModuleDeviceId()).contains("bess_module_1");
  }

  @Test
  void emptyWhenNoBessModuleInTopology() {
    // Arrange
    Fixture fixture = client();
    fixture
        .server()
        .expect(requestTo(BASE_URL + "/topology"))
        .andRespond(
            withSuccess(
                """
                { "devices": { "operating_envelope": { "template": "operating_envelope" } } }
                """,
                MediaType.APPLICATION_JSON));

    // Act / Assert
    assertThat(fixture.client().findBessModuleDeviceId()).isEmpty();
  }

  @Test
  void emptyWhenNoDtmHasBeenSubmittedYet() {
    // Arrange: device-api 404s until a DTM is submitted
    Fixture fixture = client();
    fixture
        .server()
        .expect(requestTo(BASE_URL + "/topology"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    // Act / Assert
    assertThat(fixture.client().findBessModuleDeviceId()).isEmpty();
  }
}
