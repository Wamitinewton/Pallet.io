package io.pallet.buildqueue;

import io.pallet.buildqueue.config.BuildQueueProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BuildQueueProperties.class)
public class BuildQueueServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuildQueueServiceApplication.class, args);
    }
}
