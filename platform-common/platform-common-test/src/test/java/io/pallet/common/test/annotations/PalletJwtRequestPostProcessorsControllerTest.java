package io.pallet.common.test.annotations;

import static io.pallet.common.test.security.PalletJwtRequestPostProcessors.orgJwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.fixtures.SecuredTestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves {@code PalletJwtRequestPostProcessors.orgJwt(...)} lets a {@code @ControllerTest} for a
 * secured endpoint run with no real Keycloak token and no {@code SecurityFilterChain} bean
 * imported into the slice: an unauthenticated request is rejected by Spring Security's own
 * classpath-triggered default, and a request stamped with the mock JWT reaches the controller
 * with the given {@code org_id} claim and {@code ROLE_*} authorities intact.
 *
 * <p>{@code @AutoConfigureMockMvc(addFilters = true)} here re-enables the servlet filters
 * (Spring Security's included) that {@code @ControllerTest} disables by default. See that
 * annotation's Javadoc for why. A service testing one of its own secured controllers copies
 * this same override.
 */
@ControllerTest(SecuredTestController.class)
@AutoConfigureMockMvc(addFilters = true)
class PalletJwtRequestPostProcessorsControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void anUnauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/secured/whoami")).andExpect(status().isUnauthorized());
    }

    @Test
    void aRequestStampedWithTheMockJwtCarriesTheOrgIdAndMappedRoles() throws Exception {
        mvc.perform(get("/secured/whoami").with(orgJwt("org-1", "viewer", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value("org-1"))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder("ROLE_viewer", "ROLE_admin")));
    }
}
