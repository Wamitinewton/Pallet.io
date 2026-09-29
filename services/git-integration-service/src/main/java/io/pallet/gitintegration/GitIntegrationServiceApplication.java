package io.pallet.gitintegration;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(GitIntegrationProperties.class)
@EnableScheduling
public class GitIntegrationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitIntegrationServiceApplication.class, args);
    }
}
