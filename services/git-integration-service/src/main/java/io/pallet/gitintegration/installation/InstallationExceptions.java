package io.pallet.gitintegration.installation;

import io.pallet.common.error.AppException;
import org.springframework.http.HttpStatus;

public final class InstallationExceptions {

    private InstallationExceptions() {}

    /** The caller's GitHub user can't see the installation. Says nothing about whether it exists. */
    public static class InstallationNotAccessibleException extends AppException {

        public InstallationNotAccessibleException() {
            super(
                    HttpStatus.FORBIDDEN,
                    "INSTALLATION_NOT_ACCESSIBLE",
                    "Your GitHub account can't access this installation.",
                    "Installation not visible to the caller's GitHub user");
        }
    }

    public static class InstallationSuspendedException extends AppException {

        public InstallationSuspendedException() {
            super(
                    HttpStatus.CONFLICT,
                    "INSTALLATION_SUSPENDED",
                    "This GitHub installation is suspended. Unsuspend it on GitHub first.",
                    "Installation is suspended on GitHub");
        }
    }
}
