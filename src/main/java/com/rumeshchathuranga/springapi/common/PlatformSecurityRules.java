package com.rumeshchathuranga.springapi.common;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/**
 * Cross-cutting platform routes that belong to no feature slice.
 * <p>
 * {@code /error} — Spring Security filters the ERROR dispatch by default
 * ({@code spring.security.filter.dispatcher-types} includes {@code error}), so
 * without this every exception not handled by {@code GlobalExceptionHandler} or a
 * controller-local {@code @ExceptionHandler} is re-authorized as {@code /error},
 * denied, and surfaces to the client as 401 instead of its real status.
 * <p>
 * {@code /actuator/health**} and {@code /actuator/info} — kubelet probes and the
 * docker-compose healthcheck are unauthenticated by construction.
 * <p>
 * This class is the seed of the {@code common-security} module extracted in Phase 3.
 */
@Component
public class PlatformSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry.requestMatchers("/error").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/actuator/info").permitAll();
    }
}
