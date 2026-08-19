package com.newbrid.epr_automation.systmone.controlplane.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two security perimeters (NFR-SEC-01):
 * <ol>
 *   <li><b>Machine surface</b> {@code /api/**}: stateless, authenticated by a worker token
 *       (workers POST results here) — {@link WorkerTokenFilter}.</li>
 *   <li><b>Operator UI</b>: session-based. OAuth2 login is activated automatically when a
 *       client registration is configured; otherwise form login with role-based dev users
 *       (DEVELOPER / OPERATOR / AUDITOR / ADMIN) keeps the app runnable locally.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("controlplane")
public class SecurityConfig {

    @Bean
    public WorkerTokenFilter workerTokenFilter(@Value("${systmone.worker-token:changeit}") String token) {
        return new WorkerTokenFilter(token);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurity(HttpSecurity http, WorkerTokenFilter workerTokenFilter) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("WORKER"))
                .addFilterBefore(workerTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain uiSecurity(HttpSecurity http,
                                          ObjectProvider<ClientRegistrationRepository> oauthRepos) throws Exception {
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/info", "/css/**", "/error").permitAll()
                .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults());

        // Activate OAuth2 login only when a provider is configured (NFR-SEC-01).
        if (oauthRepos.getIfAvailable() != null) {
            http.oauth2Login(Customizer.withDefaults());
        }
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Development users only. In production these roles are asserted via OAuth2/SSO claims;
     * do not enable this bean against live data.
     */
    @Bean
    @Profile("!prod")
    public UserDetailsService devUsers(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("developer").password(encoder.encode("developer")).roles("DEVELOPER").build(),
                User.withUsername("operator").password(encoder.encode("operator")).roles("OPERATOR").build(),
                User.withUsername("auditor").password(encoder.encode("auditor")).roles("AUDITOR").build(),
                User.withUsername("admin").password(encoder.encode("admin")).roles("ADMIN").build()
        );
    }
}
