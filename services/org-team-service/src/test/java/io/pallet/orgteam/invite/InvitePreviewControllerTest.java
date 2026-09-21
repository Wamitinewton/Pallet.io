package io.pallet.orgteam.invite;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.ControllerTest;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.token.InvalidTokenException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ControllerTest(InvitePreviewController.class)
class InvitePreviewControllerTest {

    private static final String PATH = "/org-team/invites/{token}";
    private static final Instant EXPIRES_AT = Instant.parse("2026-03-04T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InviteService inviteService;

    @Test
    void aValidTokenReturnsTheMaskedPreviewAndNoIdentifiers() throws Exception {
        given(inviteService.preview("good.token.value"))
                .willReturn(
                        new InvitePreviewDto("Acme", Role.DEVELOPER, "Olivia Owner", "j***@example.com", EXPIRES_AT));

        mvc.perform(get(PATH, "good.token.value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgName").value("Acme"))
                .andExpect(jsonPath("$.data.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.data.inviterName").value("Olivia Owner"))
                .andExpect(jsonPath("$.data.maskedEmail").value("j***@example.com"))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(jsonPath("$.data.orgId").doesNotExist())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.email").doesNotExist())
                .andExpect(content().string(not(containsString("jane@example.com"))));
    }

    @Test
    void aBadTokenIsA400WithTheInvalidTokenCode() throws Exception {
        given(inviteService.preview("bad")).willThrow(new InvalidTokenException("Malformed action token"));

        mvc.perform(get(PATH, "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void aNonPendingInviteIsA410() throws Exception {
        given(inviteService.preview("revoked")).willThrow(new InviteNoLongerValidException());

        mvc.perform(get(PATH, "revoked"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("INVITE_NO_LONGER_VALID"));
    }
}
