package io.pallet.notification.docs;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@Import(KeycloakTestContainerConfiguration.class)
class OpenApiIntegrationTest {

    private static final String LIST = "$.paths['/api/v1/notification/notifications'].get";

    @Autowired
    private MockMvc mvc;

    @Test
    void theListTakesPageSizeAndSortAsQueryParameters() throws Exception {
        mvc.perform(get("/api/v1/notification/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(LIST + ".parameters[?(@.in == 'query')].name")
                        .value(hasItems("page", "size", "sort", "status")))
                .andExpect(
                        jsonPath(LIST + ".parameters[?(@.name == 'pageQuery')]").value(empty()));
    }
}
