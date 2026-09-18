package io.arcnode.dercontrol;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Entry point. Boots the context; {@link StartupLogger} echoes the resolved config once ready. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  /** Title / version for the springdoc-generated spec at {@code /v3/api-docs}. */
  @Bean
  public OpenAPI apiInfo() {
    return new OpenAPI()
        .info(
            new Info()
                .title("ems-der-control-api")
                .version("1.0.0-beta")
                .description("IEEE 2030.5 DERControl intake"));
  }

  /** Real wall clock — swapped for {@code Clock.fixed(...)} in tests. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /** Logs the bound {@link Config} at startup — the winston "Running with Config" line analog. */
  @Component
  static class StartupLogger {
    private static final Logger LOG = LoggerFactory.getLogger(Application.class);
    private final Config config;

    StartupLogger(Config config) {
      this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logConfig() {
      LOG.info("Running with {}", config);
    }
  }
}
