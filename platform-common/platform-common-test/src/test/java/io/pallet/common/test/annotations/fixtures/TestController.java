package io.pallet.common.test.annotations.fixtures;

import io.pallet.common.error.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Throwaway controller proving {@code @ControllerTest} renders {@code AppException} through
 * {@code platform-common-exception}'s {@code GlobalExceptionHandler} with no extra {@code @Import}.
 */
@RestController
public class TestController {

    @GetMapping("/test-entities/missing")
    public void alwaysThrowsNotFound() {
        throw new NotFoundException("TestEntity", "missing");
    }
}
