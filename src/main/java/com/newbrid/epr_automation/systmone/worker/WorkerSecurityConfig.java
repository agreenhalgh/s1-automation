package com.newbrid.epr_automation.systmone.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A worker's HTTP listener exists only to be scraped, so everything that is not a liveness
 * check is refused outright. The scrape endpoint itself is handled ahead of this chain by
 * {@link com.newbrid.epr_automation.systmone.config.MetricsSecurityConfig}, which requires
 * credentials; without this chain the listener would fall through to Spring Boot's default
 * of "authenticate everything against a generated password", which on a process with no
 * user store means an endpoint nobody — including a health check — can reach.
 *
 * <p>Deny rather than permit for the rest: a worker drives a live clinical desktop, and the
 * only safe answer to an unexpected request arriving at one is no answer.
 */
@Configuration
@EnableWebSecurity
@Profile("worker")
public class WorkerSecurityConfig {

    @Bean
    @Order(3)
    public SecurityFilterChain workerSecurity(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Spring Security filters the ERROR dispatch too, so without this the
                        // container's forward to /error is itself denied — and a 401 challenge
                        // from the scrape endpoint comes back as an empty 403, which reads like
                        // wrong credentials rather than missing ones.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
