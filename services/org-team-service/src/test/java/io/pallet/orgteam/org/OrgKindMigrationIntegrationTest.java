package io.pallet.orgteam.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.RepositoryTest;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@RepositoryTest
class OrgKindMigrationIntegrationTest {

    private static final String VERSION_BEFORE_KIND = "3";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void rowsThatPredateTheMigrationAreBackfilledAsPersonalAndLaterInsertsMustNameTheirKind() {
        String database = "org_kind_migration_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate server = new JdbcTemplate(dataSource(postgres.getJdbcUrl()));
        server.execute("CREATE DATABASE " + database);
        try {
            String url =
                    "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
            JdbcTemplate scratch = new JdbcTemplate(dataSource(url));

            flyway(url, MigrationVersion.fromVersion(VERSION_BEFORE_KIND)).migrate();
            scratch.update("""
                    INSERT INTO org_team.organizations (org_id, name, slug, owner_user_id, status)
                    VALUES ('org-active', 'Acme', 'acme', 'user-1', 'ACTIVE'),
                           ('org-deleted', 'Gone', 'gone', 'user-2', 'DELETED')
                    """);

            flyway(url, MigrationVersion.LATEST).migrate();

            assertThat(scratch.queryForList("SELECT org_id, kind FROM org_team.organizations ORDER BY org_id"))
                    .containsExactly(
                            Map.of("org_id", "org-active", "kind", "PERSONAL"),
                            Map.of("org_id", "org-deleted", "kind", "PERSONAL"));
            assertThat(scratch.queryForObject("""
                            SELECT column_default FROM information_schema.columns
                            WHERE table_schema = 'org_team' AND table_name = 'organizations' AND column_name = 'kind'
                            """, String.class)).isNull();
            assertThatThrownBy(() -> scratch.update("""
                            INSERT INTO org_team.organizations (org_id, name, slug, owner_user_id, status)
                            VALUES ('org-new', 'New', 'new', 'user-3', 'ACTIVE')
                            """)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            server.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    @Test
    void anOwnerCannotHoldASecondActivePersonalOrg() {
        org("org-a", "acme-a", "owner-1", "PERSONAL", "ACTIVE");

        assertThatThrownBy(() -> org("org-b", "acme-b", "owner-1", "PERSONAL", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_organizations_owner_personal");
    }

    @Test
    void anOwnerMayHoldAnyNumberOfTeamOrgsBesideTheirPersonalOne() {
        org("org-a", "acme-a", "owner-1", "PERSONAL", "ACTIVE");

        assertThatCode(() -> {
                    org("org-b", "acme-b", "owner-1", "TEAM", "ACTIVE");
                    org("org-c", "acme-c", "owner-1", "TEAM", "ACTIVE");
                })
                .doesNotThrowAnyException();
    }

    @Test
    void aDeletedPersonalOrgDoesNotBlockAnActiveOne() {
        org("org-a", "acme-a", "owner-1", "PERSONAL", "DELETED");

        assertThatCode(() -> org("org-b", "acme-b", "owner-1", "PERSONAL", "ACTIVE"))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnknownKindIsRejected() {
        assertThatThrownBy(() -> org("org-a", "acme-a", "owner-1", "SHARED", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void org(String orgId, String slug, String ownerUserId, String kind, String status) {
        jdbc.update("""
                        INSERT INTO org_team.organizations (org_id, name, slug, owner_user_id, kind, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, orgId, orgId, slug, ownerUserId, kind, status);
    }

    private Flyway flyway(String url, MigrationVersion target) {
        return Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .schemas("org_team")
                .defaultSchema("org_team")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private SimpleDriverDataSource dataSource(String url) {
        return new SimpleDriverDataSource(
                new org.postgresql.Driver(), url, postgres.getUsername(), postgres.getPassword());
    }
}
