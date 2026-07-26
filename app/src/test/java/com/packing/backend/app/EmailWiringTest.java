package com.packing.backend.app;

import com.packing.backend.core.notification.port.out.EmailSender;
import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.infra.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.email.enabled=true",
        "app.email.brevo.api-key=test-key",
        "app.alerts.recipients=ops@example.com"
})
class EmailWiringTest {

    @Autowired
    private EmailSender emailSender;

    @Autowired
    private ErrorAlerter alerter;

    @Test
    void wiresTheBrevoSenderAndTheAlerterWhenMailIsEnabled() {
        assertThat(emailSender.getClass().getSimpleName()).isEqualTo("BrevoEmailSender");
        assertThat(alerter).isNotNull();
    }
}
