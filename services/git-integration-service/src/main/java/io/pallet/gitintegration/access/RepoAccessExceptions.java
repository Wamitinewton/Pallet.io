package io.pallet.gitintegration.access;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class RepoAccessExceptions {

    private RepoAccessExceptions() {}

    /**
     * One response for "the installation can't reach it" and "you can't", so no answer reveals which private
     * repositories an installation holds.
     */
    public static class RepositoryNotAccessibleException extends AppException {

        public RepositoryNotAccessibleException() {
            super(
                    HttpStatus.FORBIDDEN,
                    "REPOSITORY_NOT_ACCESSIBLE",
                    "This repository can't be reached through this installation with your GitHub account.",
                    "Repository not reachable by the caller or the installation");
        }
    }

    /** Safe to say: the caller already sees the repository. */
    public static class RepositoryPermissionTooLowException extends AppException {

        public RepositoryPermissionTooLowException(RepoPermission floor) {
            super(
                    HttpStatus.FORBIDDEN,
                    "REPOSITORY_PERMISSION_TOO_LOW",
                    "Linking this repository needs at least " + floor.wireName() + " access on GitHub.",
                    "Caller's repository permission is below " + floor.wireName());
        }
    }

    public static class RepositoryArchivedException extends AppException {

        public RepositoryArchivedException() {
            super(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "REPOSITORY_ARCHIVED",
                    "Archived repositories can't be linked.",
                    "Repository is archived on GitHub");
        }
    }

    public static class BranchNotFoundException extends AppException {

        public BranchNotFoundException() {
            super(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BRANCH_NOT_FOUND",
                    "The branch doesn't exist on GitHub.",
                    "Branch not found on GitHub");
        }
    }
}
