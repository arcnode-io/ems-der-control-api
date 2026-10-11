package io.arcnode.dercontrol.utility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsServer;
import io.arcnode.dercontrol.ClientIdentity;
import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.TestTls;
import io.arcnode.dercontrol.mirror.DerDispatchIdentity;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class UtilityTlsTest {

  @TempDir Path dir;

  private static Config config(Path dir) {
    return new Config(
        Config.LogLevel.INFO,
        8080,
        "localhost",
        false,
        "localhost",
        "tcp://localhost:1883",
        "u",
        "test_site",
        "http://localhost:8081",
        "http://localhost:8080",
        90,
        dir.resolve("client.pem").toString(),
        dir.resolve("client-key.pem").toString(),
        dir.resolve("ca.pem").toString());
  }

  @Test
  void absentFilesMeanNoClientIdentityAndTheEmbeddedLfdi() {
    // Arrange
    UtilityTls tls = new UtilityTls(config(dir));

    // Act / Assert
    assertThat(tls.requestFactory()).isNotNull();
    assertThat(tls.lfdi()).isEqualTo(DerDispatchIdentity.LFDI);
  }

  @Test
  void aPartialSetRefusesToBoot() throws IOException {
    // Arrange — the cert is there, the key and the CA bundle are not
    Files.writeString(dir.resolve("client.pem"), "not even pem");

    // Act / Assert
    assertThatThrownBy(() -> new UtilityTls(config(dir)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("client-key.pem")
        .hasMessageContaining("ca.pem");
  }

  @Test
  void aFullSetIsAcceptedByAUtilityThatRequiresOurCertificate()
      throws IOException, GeneralSecurityException, InterruptedException {
    // Arrange — the utility's own cert doubles as the CA bundle, which a PEM truststore allows
    String loop = InetAddress.getLoopbackAddress().getHostAddress();
    TestTls.Identity utility =
        TestTls.selfSigned(dir, "utility", "CN=" + loop + ",O=test", "ip:" + loop);
    TestTls.Identity us = TestTls.selfSigned(dir, "der-control", "CN=der-control-api,O=test", null);
    Files.writeString(dir.resolve("client.pem"), us.certificatePem());
    Files.writeString(dir.resolve("client-key.pem"), us.privateKeyPem());
    Files.writeString(dir.resolve("ca.pem"), utility.certificatePem());
    HttpsServer server = TestTls.utilityRequiringClientCert(utility, us.certificate());
    try {
      String url = "https://" + loop + ":" + server.getAddress().getPort() + "/ping";
      UtilityTls tls = new UtilityTls(config(dir));

      // Act
      String body =
          RestClient.builder()
              .requestFactory(tls.requestFactory())
              .build()
              .get()
              .uri(url)
              .retrieve()
              .body(String.class);

      // Assert
      assertThat(body).isEqualTo("pong");
      assertThat(tls.lfdi()).isEqualTo(ClientIdentity.fromPem(us.certificatePem()).lfdi());
      // and the same utility refuses a der-control-api that has no files to present
      UtilityTls anonymous = new UtilityTls(config(Files.createTempDirectory(dir, "empty")));
      RestClient bare = RestClient.builder().requestFactory(anonymous.requestFactory()).build();
      assertThatThrownBy(() -> bare.get().uri(url).retrieve().body(String.class))
          .isInstanceOf(ResourceAccessException.class);
    } finally {
      server.stop(0);
    }
  }
}
