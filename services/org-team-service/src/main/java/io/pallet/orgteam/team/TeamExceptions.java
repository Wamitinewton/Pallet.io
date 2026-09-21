package io.pallet.orgteam.team;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class TeamExceptions {

    private TeamExceptions() {}

    public static class TeamNotFoundException extends AppException {

        public TeamNotFoundException() {
            super(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team not found.", "No such team in the organization");
        }
    }

    public static class SlugTakenException extends AppException {

        public SlugTakenException() {
            super(
                    HttpStatus.CONFLICT,
                    "SLUG_TAKEN",
                    "That slug is already in use in this organization.",
                    "Slug is taken within the organization");
        }
    }

    public static class AlreadyInTeamException extends AppException {

        public AlreadyInTeamException() {
            super(
                    HttpStatus.CONFLICT,
                    "ALREADY_IN_TEAM",
                    "That member is already in this team.",
                    "Team assignment already exists");
        }
    }
}
