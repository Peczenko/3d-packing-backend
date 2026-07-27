package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.port.out.EmailSender;
import com.packing.backend.infra.shared.http.ExternalApi;
import com.packing.backend.infra.shared.http.ExternalApiClients;
import com.packing.backend.infra.shared.http.ExternalApiSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class EmailConfig {

    private static final String MAIL_SERVICE = "brevo";

    @Bean
    @ConditionalOnProperty(prefix = "app.email", name = "enabled", havingValue = "true")
    public EmailSender brevoEmailSender(EmailProperties properties, ExternalApiClients apiClients) {
        EmailProperties.Brevo brevo = properties.brevo();

        if (brevo.apiKey() == null || brevo.apiKey().isBlank()) {
            log.warn("app.email.enabled=true but app.email.brevo.api-key is not set; mail will "
                    + "be logged instead of sent. Set the key, or set app.email.enabled=false "
                    + "to make this deliberate.");

            return new LoggingEmailSender();
        }

        ExternalApi api = apiClients.create(
                new ExternalApiSpec(MAIL_SERVICE, brevo.baseUrl(), brevo.connectTimeout(), brevo.readTimeout()),
                builder -> builder.defaultHeader("api-key", brevo.apiKey()));

        return new BrevoEmailSender(api, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.email", name = "enabled", havingValue = "false", matchIfMissing = true)
    public EmailSender loggingEmailSender() {
        return new LoggingEmailSender();
    }

    @Bean
    public EmailErrorAlerter emailErrorAlerter(EmailSender emailSender,
                                               AlertProperties properties,
                                               ErrorEmailRenderer renderer,
                                               Clock clock) {
        if (!properties.alertingEnabled()) {
            log.warn("No app.alerts.recipients configured: 5xx failures will be logged but "
                    + "nobody will be emailed about them.");
        }
        AlertThrottle throttle =
                new AlertThrottle(clock, properties.cooldown(), properties.maxPerHour());
        return new EmailErrorAlerter(
                emailSender, properties, throttle, renderer, alertExecutor(), MAIL_SERVICE);
    }

    private ExecutorService alertExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                runnable -> {
                    Thread thread = new Thread(runnable, "alert-mail");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, pool) -> log.warn("Alert queue is full; dropping an alert email. "
                        + "The failure it described is in the logs."));
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }
}
