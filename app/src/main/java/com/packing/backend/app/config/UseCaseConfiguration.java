package com.packing.backend.app.config;

import com.packing.backend.core.file.FileApplicationService;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.file.port.out.FileOwnerLookup;
import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.core.user.UserApplicationService;
import com.packing.backend.core.user.port.out.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Composition root for the application layer.
 *
 * <p>{@code :core} carries no {@code @Service} or {@code @Component}, so its services are
 * assembled here explicitly. That is the whole point: the use cases can be constructed in
 * a plain unit test with no Spring context, and the dependency direction stays visible in
 * one file instead of being implied by classpath scanning.
 *
 * <p>{@code @Transactional} still applies — Spring's transaction proxying is a bean
 * post-processor and does not care whether the bean came from a scan or from here.
 */
@Configuration(proxyBeanMethods = false)
public class UseCaseConfiguration {

    /**
     * Takes no Firebase dependency: every Firebase side effect is driven by a domain event
     * handled after commit, in {@code infra.firebase.FirebaseUserMirroringListener}.
     */
    @Bean
    public UserApplicationService userApplicationService(UserRepository users,
                                                         DomainEventPublisher eventPublisher,
                                                         Clock clock) {
        return new UserApplicationService(users, eventPublisher, clock);
    }

    @Bean
    public FileApplicationService fileApplicationService(FileRepository files,
                                                         BinaryStorage storage,
                                                         FileOwnerLookup ownerLookup,
                                                         DomainEventPublisher eventPublisher,
                                                         Clock clock) {
        return new FileApplicationService(files, storage, ownerLookup, eventPublisher, clock);
    }

    /**
     * Injected into application services rather than calling {@code Instant.now()}, so
     * time is controllable in tests.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
