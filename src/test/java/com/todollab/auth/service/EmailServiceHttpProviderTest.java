package com.todollab.auth.service;

import com.todollab.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EmailServiceHttpProviderTest {

    @Test
    void brevoProviderPostsExpectedPayloadAndApiKeyHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EmailService service = new EmailService(
                Mockito.mock(JavaMailSender.class),
                builder.build(),
                "brevo",
                "sender@example.com",
                "Todollab",
                "xkeysib-test-key",
                ""
        );

        server.expect(requestTo(EmailService.BREVO_API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "xkeysib-test-key"))
                .andExpect(jsonPath("$.sender.email").value("sender@example.com"))
                .andExpect(jsonPath("$.sender.name").value("Todollab"))
                .andExpect(jsonPath("$.to[0].email").value("user@example.com"))
                .andExpect(jsonPath("$.subject").value("Todollab — Confirme seu e-mail"))
                .andExpect(jsonPath("$.htmlContent").exists())
                .andRespond(withSuccess("{\"messageId\":\"123\"}", MediaType.APPLICATION_JSON));

        service.sendVerificationCode("user@example.com", "Ana Silva", "123456");

        server.verify();
    }

    @Test
    void resendProviderPostsExpectedPayloadAndBearerHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EmailService service = new EmailService(
                Mockito.mock(JavaMailSender.class),
                builder.build(),
                "resend",
                "sender@example.com",
                "Todollab",
                "",
                "re_test_key"
        );

        server.expect(requestTo(EmailService.RESEND_API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer re_test_key"))
                .andExpect(jsonPath("$.from").value("Todollab <sender@example.com>"))
                .andExpect(jsonPath("$.to[0]").value("user@example.com"))
                .andExpect(jsonPath("$.subject").value("Todollab — Redefinição de senha"))
                .andExpect(jsonPath("$.html").exists())
                .andRespond(withSuccess("{\"id\":\"abc\"}", MediaType.APPLICATION_JSON));

        service.sendPasswordResetCode("user@example.com", "Ana Silva", "654321");

        server.verify();
    }

    @Test
    void brevoProviderFailsFastWhenApiKeyMissing() {
        EmailService service = new EmailService(
                Mockito.mock(JavaMailSender.class),
                Mockito.mock(RestClient.class),
                "brevo",
                "sender@example.com",
                "Todollab",
                "",
                ""
        );

        assertThatThrownBy(() -> service.sendVerificationCode("user@example.com", "Ana", "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Não foi possível enviar o e-mail");
    }
}
