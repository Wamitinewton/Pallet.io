package io.pallet.common.test.annotations;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.fixtures.TestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression test for the {@code @Import(PalletErrorHandlingAutoConfiguration.class)} on
 * {@code @ControllerTest}: an {@code AppException} thrown from the controller under test
 * renders in the documented {@code ErrorResponse} shape. Without that explicit import,
 * {@code @WebMvcTest}'s {@code @OverrideAutoConfiguration(enabled = false)} means the exception
 * advice never activates and this test fails with the raw exception instead.
 */
@ControllerTest(TestController.class)
class ControllerTestAnnotationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void appExceptionFromTheControllerRendersTheDocumentedErrorResponseShape() throws Exception {
        mvc.perform(get("/test-entities/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.message").value("TestEntity not found"));
    }
}
