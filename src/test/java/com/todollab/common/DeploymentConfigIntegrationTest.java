package com.todollab.common;

import com.todollab.auth.repository.RefreshTokenRepository;
import com.todollab.auth.service.RefreshTokenService;
import com.todollab.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseCookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeploymentConfigIntegrationTest extends AbstractIntegrationTest {

    @Test
    void healthEndpointIsPublicAndReturnsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void refreshCookieSupportsCrossSiteSameSiteNoneWithSecure() {
        RefreshTokenRepository repository = Mockito.mock(RefreshTokenRepository.class);
        RefreshTokenService prodService = new RefreshTokenService(repository, true, "None");

        ResponseCookie cookie = prodService.buildRefreshCookie("sample-token");
        assertThat(cookie.toString())
                .contains("refresh_token=sample-token")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=None")
                .contains("Path=/api/v1/auth");
    }
}
