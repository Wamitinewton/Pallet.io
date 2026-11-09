package io.pallet.build;

import io.pallet.build.config.BuildProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BuildProperties.class)
public class BuildServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuildServiceApplication.class, args);
    }
}
