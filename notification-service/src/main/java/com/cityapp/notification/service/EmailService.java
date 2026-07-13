package com.cityapp.notification.service;

import com.cityapp.common.event.OrderCreatedEvent;
import com.cityapp.notification.repository.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Sends HTML emails using Thymeleaf templates.
 *
 * WHY THYMELEAF FOR EMAILS:
 *   Plain text email: "Your order 1001 for Rs 378 is confirmed."
 *   HTML email: formatted receipt with logo, itemised bill, store info,
 *               payment method, delivery address, branded footer.
 *
 *   Thymeleaf: Java's server-side template engine.
 *   Same engine used for web pages, now used for email HTML.
 *   Templates live in src/main/resources/templates/email/.
 *   Each template is an HTML file with ${variable} placeholders.
 *   Context: Java object → Thymeleaf renders → final HTML string.
 *   HTML string → JavaMailSender sends as HTML email.
 *
 * @Async ON EVERY METHOD:
 *   Email sending is slow (SMTP round trip: 50-500ms).
 *   Calling this synchronously inside a Kafka consumer:
 *   Consumer thread is blocked for 500ms per email.
 *   Consumer lag builds up.
 *   @Async: Spring creates a thread pool. Email is sent in background.
 *   Kafka consumer returns immediately. Processes next message.
 *
 *   REQUIREMENT: @EnableAsync in CityAppApplication (we have @EnableScheduling,
 *   add @EnableAsync alongside it).
 *
 * WHY NOT SendGrid/Mailgun:
 *   Spring Mail + SMTP works for any email provider.
 *   Change MAIL_HOST/PORT → works with AWS SES, Mailgun, Gmail.
 *   No SDK lock-in. Just standard SMTP.
 *   In development: MailHog at localhost:1025 catches all emails.
 *   In production: AWS SES at port 465 with TLS.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender javaMailSender;
    private final TemplateEngine templateEngine;
    private final UserRepository userRepository;

    @Value("${app.mail.from:noreply@cityapp.com}")
    private String fromEmail;

    @Value("${app.mail.from-name:City App}")
    private String fromName;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")
                    .withLocale(Locale.ENGLISH)
                    .withZone(ZoneId.of("Asia/Kolkata"));

    // ── Order Confirmation ────────────────────────────────────────────────────

    @Async
    public void sendOrderConfirmation(Long userId, Long orderId,
                                      BigDecimal totalAmount) {
        userRepository.findById(userId).ifPresent(user -> {
            Context ctx = new Context();
            ctx.setVariable("userName",    user.getName());
            ctx.setVariable("orderId",     orderId);
            ctx.setVariable("totalAmount", totalAmount);
            ctx.setVariable("supportEmail","support@cityapp.com");
            ctx.setVariable("year",        java.time.Year.now().getValue());

            sendHtmlEmail(
                    user.getEmail(),
                    "Order Confirmed — #" + orderId,
                    "email/order-confirmation",
                    ctx
            );
        });
    }

    @Async
    public void sendOrderConfirmationFull(OrderCreatedEvent event) {
        userRepository.findById(event.getUserId()).ifPresent(user -> {
            Context ctx = new Context();
            ctx.setVariable("userName",        user.getName());
            ctx.setVariable("orderId",         event.getOrderId());
            ctx.setVariable("storeName",       event.getStoreName());
            ctx.setVariable("orderType",       event.getOrderType());
            ctx.setVariable("deliveryAddress", event.getDeliveryAddress());
            ctx.setVariable("items",           event.getItems());
            ctx.setVariable("totalAmount",     event.getTotalAmount());
            ctx.setVariable("orderDate",
                    DATE_FORMATTER.format(java.time.Instant.now()));
            ctx.setVariable("year",
                    java.time.Year.now().getValue());

            sendHtmlEmail(
                    user.getEmail(),
                    "Order Confirmed — #" + event.getOrderId() +
                            " — ₹" + event.getTotalAmount(),
                    "email/order-confirmation-full",
                    ctx
            );
        });
    }

    // ── Welcome Email ─────────────────────────────────────────────────────────

    @Async
    public void sendWelcomeEmail(String email, String name) {
        Context ctx = new Context();
        ctx.setVariable("userName", name);
        ctx.setVariable("year",     java.time.Year.now().getValue());

        sendHtmlEmail(email, "Welcome to City App! 🎉",
                "email/welcome", ctx);
    }

    // ── Seller Onboarding ─────────────────────────────────────────────────────

    @Async
    public void sendSellerOnboardingEmail(String email, String name) {
        Context ctx = new Context();
        ctx.setVariable("sellerName", name);
        ctx.setVariable("year",       java.time.Year.now().getValue());

        sendHtmlEmail(email, "Set Up Your City App Store 🏪",
                "email/seller-onboarding", ctx);
    }

    // ── Low Stock Alert ───────────────────────────────────────────────────────

    @Async
    public void sendLowStockEmail(Long sellerId, String productName,
                                  Integer currentQuantity) {
        userRepository.findById(sellerId).ifPresent(seller -> {
            Context ctx = new Context();
            ctx.setVariable("sellerName",      seller.getName());
            ctx.setVariable("productName",     productName);
            ctx.setVariable("currentQuantity", currentQuantity);
            ctx.setVariable("year",            java.time.Year.now().getValue());

            sendHtmlEmail(
                    seller.getEmail(),
                    "⚠️ Low Stock Alert: " + productName,
                    "email/low-stock-alert",
                    ctx
            );
        });
    }

    // ── OTP Email ─────────────────────────────────────────────────────────────

    @Async
    public void sendOtpEmail(String email, String name, String otp) {
        Context ctx = new Context();
        ctx.setVariable("userName", name);
        ctx.setVariable("otp",      otp);
        ctx.setVariable("expiry",   "5 minutes");
        ctx.setVariable("year",     java.time.Year.now().getValue());

        sendHtmlEmail(email, "Your City App verification code: " + otp,
                "email/otp", ctx);
    }

    // ── Core Send Method ──────────────────────────────────────────────────────

    private void sendHtmlEmail(String to, String subject,
                               String templateName, Context context) {
        try {
            // Process Thymeleaf template → HTML string
            String htmlContent = templateEngine.process(templateName, context);

            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, true, "UTF-8");

            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);   // true = isHtml
            // Second parameter: true means the second arg (htmlContent) is HTML.
            // Without it: HTML tags would appear as plain text.

            javaMailSender.send(message);
            log.info("Email sent: to={} subject='{}'", to, subject);

        } catch (MessagingException e) {
            log.error("Failed to send email: to={} subject='{}' error={}",
                    to, subject, e.getMessage());
            // Don't rethrow: email failure is not critical.
            // The order is confirmed. The in-app notification exists.
            // Email is supplementary.
        } catch (Exception e) {
            log.error("Unexpected email error: to={} error={}", to, e.getMessage());
        }
    }
}