package io.pallet.common.error;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

/**
 * The advice is never {@code @Import}ed here — a full {@code @SpringBootTest} exercises the
 * {@code AutoConfiguration.imports} entry, which is the thing under test. The rest is regression
 * cover for the mapping table through a real {@code DispatcherServlet}.
 */
@SpringBootTest(classes = ErrorHandlingAutoConfigurationIntegrationTest.TestApp.class)
@AutoConfigureMockMvc
class ErrorHandlingAutoConfigurationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void autoConfigurationRegistersTheAdviceWithoutAnImport() {
        assertThatCode(() -> context.getBean(GlobalExceptionHandler.class)).doesNotThrowAnyException();
    }

    @Test
    void appExceptionRendersErrorResponseShape() throws Exception {
        mvc.perform(get("/things/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.path").value("/things/99"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void unexpectedExceptionFallsBackWithoutLeaking() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred. Please try again later."));
    }

    @Test
    void beanValidationFailureMapsToValidationError() throws Exception {
        mvc.perform(post("/things").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("name"));
    }

    @Test
    void unknownRouteMapsToRouteNotFound() throws Exception {
        mvc.perform(get("/no/such/path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ROUTE_NOT_FOUND"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @RestController
        static class TestController {

            @GetMapping("/things/{id}")
            String thing(@PathVariable String id) {
                throw new NotFoundException("Thing", id);
            }

            @GetMapping("/boom")
            String boom() {
                throw new IllegalStateException("secret internal detail");
            }

            @PostMapping("/things")
            String create(@RequestBody @Valid Payload payload) {
                return payload.name();
            }
        }

        record Payload(@NotBlank String name) {}
    }
}
