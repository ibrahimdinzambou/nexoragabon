package com.iptv.saas.service;

import jakarta.mail.Session;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationIntegrationTests {
    @Test
    void sendsSmtpTestWithConfiguredSender() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        TransactionalMailService mail = new TransactionalMailService(
                sender,
                new ObjectMapper(),
                "smtp.example.test",
                587,
                "mailer@example.test",
                "noreply@example.test",
                "Nexora",
                true,
                true,
                false,
                "",
                "https://api.sendgrid.com/v3/mail/send",
                15000
        );

        TransactionalMailService.DeliveryResult result = mail.test("admin@example.test");

        assertTrue(result.success(), result.message());
        assertEquals("Test email Nexora", message.getSubject());
        assertEquals("admin@example.test", message.getAllRecipients()[0].toString());
        verify(sender).send(message);
    }

    @Test
    void sendsHtmlAsMultipartAlternative() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        TransactionalMailService mail = new TransactionalMailService(
                sender,
                new ObjectMapper(),
                "smtp.example.test",
                587,
                "mailer@example.test",
                "noreply@example.test",
                "Nexora",
                true,
                true,
                false,
                "",
                "https://api.sendgrid.com/v3/mail/send",
                15000
        );

        TransactionalMailService.DeliveryResult result = mail.deliverHtml(
                "admin@example.test",
                "Modele HTML",
                "<html><body><strong>Bonjour</strong></body></html>"
        );

        assertTrue(result.success(), result.message());
        assertTrue(message.getContent() instanceof Multipart);
        verify(sender).send(message);
    }

    @Test
    void sendsViaSendGridApiWhenConfigured() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/mail/send", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v3/mail/send";
            TransactionalMailService mail = new TransactionalMailService(
                    mock(JavaMailSender.class),
                    mapper,
                    "smtp.example.test",
                    587,
                    "mailer@example.test",
                    "noreply@nexoragabon.com",
                    "Nexora",
                    true,
                    true,
                    false,
                    "SG.test-key",
                    endpoint,
                    15000
            );

            TransactionalMailService.DeliveryResult result = mail.deliverHtml(
                    "admin@example.test",
                    "Modele HTML",
                    "<html><body><strong>Bonjour</strong></body></html>"
            );

            assertTrue(result.success(), result.message());
            assertEquals("Bearer SG.test-key", authorization.get());
            assertNotNull(requestBody.get());
            var payload = mapper.readTree(requestBody.get());
            assertEquals("noreply@nexoragabon.com", payload.get("from").get("email").asText());
            assertEquals("Nexora", payload.get("from").get("name").asText());
            assertEquals("admin@example.test", payload.get("personalizations").get(0).get("to").get(0).get("email").asText());
            assertEquals("text/html", payload.get("content").get(1).get("type").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsTelegramAsDisabledWithoutExposingSecrets() {
        TelegramAlertService telegram = new TelegramAlertService(false, "secret-token", "-100123456", new ObjectMapper());

        TelegramAlertService.DeliveryResult result = telegram.test();

        assertFalse(result.success());
        assertFalse((Boolean) telegram.status().get("configured"));
        assertEquals("***3456", telegram.status().get("chatId"));
        assertEquals("***configure***", telegram.status().get("botToken"));
    }
}
