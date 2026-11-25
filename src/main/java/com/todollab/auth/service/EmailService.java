package com.todollab.auth.service;

import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from:no-reply@todollab.local}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
        } catch (MessagingException | MailException ex) {
            log.error("Failed to send email to {} with subject '{}'", toEmail, subject, ex);
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCode.INTERNAL_ERROR,
                    "Não foi possível enviar o e-mail no momento. Tente novamente mais tarde."
            );
        }
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
