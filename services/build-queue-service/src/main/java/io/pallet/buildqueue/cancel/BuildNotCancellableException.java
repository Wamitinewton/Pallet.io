package io.pallet.buildqueue.cancel;

import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.common.error.AppException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** The build already left the queue; {@code meta.status} says where it went. */
public class BuildNotCancellableException extends AppException {

    public BuildNotCancellableException(BuildStatus status) {
        super(
                HttpStatus.CONFLICT,
                "BUILD_NOT_CANCELLABLE",
                "Only a build waiting in the queue can be cancelled.",
                "Build is " + status + ", not QUEUED");
        withMeta(Map.of("status", status.name()));
    }
}
