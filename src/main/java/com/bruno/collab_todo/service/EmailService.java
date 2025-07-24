package com.bruno.collab_todo.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    public void sendEmail(String toEmail, String subject, String name, int code) throws MessagingException {

        String[] firstName = name.split(" ");

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);

        helper.setFrom("todollab@gmail.com");
        helper.setTo(toEmail);
        helper.setSubject(subject);

        String htmlContent = """
                <html>
                    <body style="font-family: Arial, sans-serif; background-color: #f4f4f4; padding: 20px;">
                        <div style="max-width: 600px; margin: auto; background-color: white; padding: 20px; border-radius: 10px;">
                            <h2 style="color: #333;">Confirmação de Cadastro</h2>
                            <p style="font-size: 16px;">Olá, %s! 👋</p>
                            <p style="font-size: 16px;">Seu código de ativação é:</p>
                            <h1 style="color: #007BFF; text-align: center;">%d</h1>
                            <p style="font-size: 14px; color: #555;">Insira este código no app para ativar sua conta.</p>
                        </div>
                    </body>
                </html>
                """.formatted(firstName[0], code);

        helper.setText(htmlContent, true);

        mailSender.send(message);

        System.out.println("Email enviado com sucesso!");
    }
}
