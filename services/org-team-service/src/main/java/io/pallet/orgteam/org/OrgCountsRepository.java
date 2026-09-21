package io.pallet.orgteam.org;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OrgCountsRepository {

    private final JdbcClient jdbc;

    OrgCountsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    OrgDto.Counts countsFor(String orgId) {
        return jdbc.sql("""
                        SELECT
                            (SELECT count(*) FROM org_team.memberships WHERE org_id = :orgId AND status = 'ACTIVE') AS members,
                            (SELECT count(*) FROM org_team.teams WHERE org_id = :orgId) AS teams,
                            (SELECT count(*) FROM org_team.apps WHERE org_id = :orgId AND status = 'ACTIVE') AS apps
                        """)
                .param("orgId", orgId)
                .query((rs, rowNum) ->
                        new OrgDto.Counts(rs.getLong("members"), rs.getLong("teams"), rs.getLong("apps")))
                .single();
    }
}
