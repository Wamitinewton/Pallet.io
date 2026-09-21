package io.pallet.orgteam;

import io.pallet.orgteam.config.InviteSigningProperties;
import io.pallet.orgteam.config.OrgTeamProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({OrgTeamProperties.class, InviteSigningProperties.class})
public class OrgTeamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrgTeamServiceApplication.class, args);
    }
}
