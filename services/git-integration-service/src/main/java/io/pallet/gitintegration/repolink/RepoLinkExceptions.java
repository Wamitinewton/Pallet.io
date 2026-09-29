package io.pallet.gitintegration.repolink;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class RepoLinkExceptions {

    private RepoLinkExceptions() {}

    public static class RepoLinkExistsException extends AppException {

        public RepoLinkExistsException() {
            super(
                    HttpStatus.CONFLICT,
                    "REPO_LINK_EXISTS",
                    "This app is already linked to a repository. Disconnect it first.",
                    "App already has an active repo link");
        }
    }

    public static class RepoLinkNotFoundException extends AppException {

        public RepoLinkNotFoundException() {
            super(
                    HttpStatus.NOT_FOUND,
                    "REPO_LINK_NOT_FOUND",
                    "This app is not linked to a repository.",
                    "App has no repo link in the required state");
        }
    }

    /** The rule broken is safe to name: it is about the caller's own input. */
    public static class InvalidRootDirectoryException extends AppException {

        public InvalidRootDirectoryException(String rule) {
            super(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ROOT_DIRECTORY",
                    "rootDirectory " + rule + ".",
                    "Invalid root directory: " + rule);
        }
    }
}
