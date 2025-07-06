package com.bruno.collab_todo.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    public void sendEmail(String toEmail, String subject, String body) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom("bcgmeireles@gmail.com");
        email.setTo(toEmail);
        email.setSubject(subject);
        email.setText(body);

        mailSender.send(email);

        System.out.println("Email enviado com sucesso");
    }
}
