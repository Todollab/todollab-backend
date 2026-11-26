package com.todollab.auth.service;

import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    static final String BREVO_API_URL = "https://api.brevo.com/v3/smtp/email";
    static final String RESEND_API_URL = "https://api.resend.com/emails";

    private final JavaMailSender mailSender;
    private final RestClient restClient;
    private final String provider;
    private final String fromAddress;
    private final String fromName;
    private final String brevoApiKey;
    private final String resendApiKey;

    public EmailService(JavaMailSender mailSender, String fromAddress) {
        this(mailSender, "smtp", fromAddress, "Todollab", "", "");
    }

    @Autowired
    public EmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.provider:smtp}") String provider,
            @Value("${app.mail.from:no-reply@todollab.local}") String fromAddress,
            @Value("${app.mail.from-name:Todollab}") String fromName,
            @Value("${app.mail.brevo.api-key:}") String brevoApiKey,
            @Value("${app.mail.resend.api-key:}") String resendApiKey
    ) {
        this(mailSender, defaultRestClient(), provider, fromAddress, fromName, brevoApiKey, resendApiKey);
    }

    EmailService(
            JavaMailSender mailSender,
            RestClient restClient,
            String provider,
            String fromAddress,
            String fromName,
            String brevoApiKey,
            String resendApiKey
    ) {
        this.mailSender = mailSender;
        this.restClient = restClient;
        this.provider = (provider == null || provider.isBlank()) ? "smtp" : provider.trim().toLowerCase(Locale.ROOT);
        this.fromAddress = fromAddress;
        this.fromName = (fromName == null || fromName.isBlank()) ? "Todollab" : fromName.trim();
        this.brevoApiKey = brevoApiKey == null ? "" : brevoApiKey.trim();
        this.resendApiKey = resendApiKey == null ? "" : resendApiKey.trim();
    }

    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public void sendVerificationCode(String toEmail, String name, String sixDigitCode) {
        String safeFirstName = HtmlUtils.htmlEscape(extractFirstName(name));
        String safeCode = HtmlUtils.htmlEscape(sixDigitCode);

        String body = """
                <h2 style="margin: 0 0 12px; color: #0C2439; font-size: 22px;">Confirme seu e-mail</h2>
                <p style="margin: 0 0 16px; color: #475569; font-size: 15px; line-height: 1.6;">
                    Olá, <strong>%s</strong>! Use o código abaixo para ativar sua conta no Todollab.
                    Ele expira em 15 minutos.
                </p>
                <div style="margin: 24px 0; padding: 18px; background-color: #F8FAFC; border: 1px solid #E2E8F0; border-radius: 10px; text-align: center;">
                    <span style="font-size: 32px; font-weight: 700; letter-spacing: 8px; color: #0C2439;">%s</span>
                </div>
                <p style="margin: 0; color: #475569; font-size: 13px; line-height: 1.5;">
                    Se você não criou uma conta no Todollab, pode ignorar este e-mail com segurança.
                </p>
                """.formatted(safeFirstName, safeCode);

        sendHtmlEmail(toEmail, "Todollab — Confirme seu e-mail", wrapLayout(body));
    }

    public void sendPasswordResetCode(String toEmail, String name, String sixDigitCode) {
        String safeFirstName = HtmlUtils.htmlEscape(extractFirstName(name));
        String safeCode = HtmlUtils.htmlEscape(sixDigitCode);

        String body = """
                <h2 style="margin: 0 0 12px; color: #0C2439; font-size: 22px;">Redefinição de senha</h2>
                <p style="margin: 0 0 16px; color: #475569; font-size: 15px; line-height: 1.6;">
                    Olá, <strong>%s</strong>! Recebemos uma solicitação para redefinir sua senha no Todollab.
                    Utilize o código abaixo nos próximos 30 minutos:
                </p>
                <div style="margin: 24px 0; padding: 18px; background-color: #F8FAFC; border: 1px solid #E2E8F0; border-radius: 10px; text-align: center;">
                    <span style="font-size: 32px; font-weight: 700; letter-spacing: 8px; color: #FF4B4B;">%s</span>
                </div>
                <p style="margin: 0; color: #475569; font-size: 13px; line-height: 1.5;">
                    Se você não solicitou a alteração da senha, ignore este e-mail. Sua senha atual permanecerá ativa.
                </p>
                """.formatted(safeFirstName, safeCode);

        sendHtmlEmail(toEmail, "Todollab — Redefinição de senha", wrapLayout(body));
    }

    public void sendBoardInvite(
            String toEmail,
            String inviterName,
            String boardName,
            String roleLabel,
            String inviteUrl
    ) {
        String safeInviter = HtmlUtils.htmlEscape(inviterName);
        String safeBoard = HtmlUtils.htmlEscape(boardName);
        String safeRole = HtmlUtils.htmlEscape(roleLabel);
        String safeUrl = HtmlUtils.htmlEscape(inviteUrl);

        String body = """
                <h2 style="margin: 0 0 12px; color: #0C2439; font-size: 22px;">Você recebeu um convite</h2>
                <p style="margin: 0 0 16px; color: #475569; font-size: 15px; line-height: 1.6;">
                    <strong>%s</strong> convidou você para colaborar no quadro <strong>%s</strong>
                    com o papel de <strong>%s</strong>.
                </p>
                <div style="margin: 24px 0; text-align: center;">
                    <a href="%s" style="display: inline-block; padding: 12px 24px; background-color: #FF4B4B; color: #FFFFFF; text-decoration: none; font-weight: 600; border-radius: 8px; font-size: 15px;">
                        Aceitar convite
                    </a>
                </div>
                <p style="margin: 0; color: #475569; font-size: 13px; line-height: 1.5;">
                    Caso o botão não funcione, copie e cole este link no navegador:<br/>
                    <a href="%s" style="color: #0C2439;">%s</a>
                </p>
                """.formatted(safeInviter, safeBoard, safeRole, safeUrl, safeUrl, safeUrl);

        sendHtmlEmail(toEmail, "Todollab — Convite para o quadro " + sanitizeHeaderValue(boardName), wrapLayout(body));
    }

    private void sendHtmlEmail(String toEmail, String subject, String htmlContent) {
        switch (provider) {
            case "brevo" -> sendViaBrevo(toEmail, subject, htmlContent);
            case "resend" -> sendViaResend(toEmail, subject, htmlContent);
            default -> sendViaSmtp(toEmail, subject, htmlContent);
        }
    }

    private void sendViaSmtp(String toEmail, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
        } catch (MessagingException | MailException ex) {
            log.error("Failed to send SMTP email to {} with subject '{}'", toEmail, subject, ex);
            throw mailFailureException();
        }
    }

    private void sendViaBrevo(String toEmail, String subject, String htmlContent) {
        if (brevoApiKey.isBlank()) {
            log.error("MAIL_PROVIDER=brevo is configured, but BREVO_API_KEY is blank.");
            throw mailFailureException();
        }
        try {
            Map<String, Object> payload = Map.of(
                    "sender", Map.of("name", fromName, "email", fromAddress),
                    "to", List.of(Map.of("email", toEmail)),
                    "subject", subject,
                    "htmlContent", htmlContent
            );
            restClient.post()
                    .uri(BREVO_API_URL)
                    .header("api-key", brevoApiKey)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.error("Failed to send Brevo API email to {} with subject '{}'", toEmail, subject, ex);
            throw mailFailureException();
        }
    }

    private void sendViaResend(String toEmail, String subject, String htmlContent) {
        if (resendApiKey.isBlank()) {
            log.error("MAIL_PROVIDER=resend is configured, but RESEND_API_KEY is blank.");
            throw mailFailureException();
        }
        try {
            Map<String, Object> payload = Map.of(
                    "from", fromName + " <" + fromAddress + ">",
                    "to", List.of(toEmail),
                    "subject", subject,
                    "html", htmlContent
            );
            restClient.post()
                    .uri(RESEND_API_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + resendApiKey)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.error("Failed to send Resend API email to {} with subject '{}'", toEmail, subject, ex);
            throw mailFailureException();
        }
    }

    private ApiException mailFailureException() {
        return new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                "Não foi possível enviar o e-mail no momento. Tente novamente mais tarde."
        );
    }

    private String wrapLayout(String innerHtml) {
        return """
                <!DOCTYPE html>
                <html lang="pt-BR">
                <body style="margin: 0; padding: 24px; background-color: #F8FAFC; font-family: Arial, Helvetica, sans-serif;">
                    <div style="max-width: 560px; margin: 0 auto; background-color: #FFFFFF; border: 1px solid #E2E8F0; border-radius: 12px; overflow: hidden;">
                        <div style="padding: 18px 24px; background-color: #0C2439;">
                            <span style="color: #FFFFFF; font-size: 18px; font-weight: 700;">Todollab</span>
                            <span style="color: #FF4B4B; font-size: 20px; font-weight: 700;">.</span>
                        </div>
                        <div style="padding: 28px 24px;">
                            %s
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(innerHtml);
    }

    private String sanitizeHeaderValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "").replace("\n", "");
    }

    private String extractFirstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "usuário";
        }
        return fullName.trim().split("\\s+")[0];
    }
}
