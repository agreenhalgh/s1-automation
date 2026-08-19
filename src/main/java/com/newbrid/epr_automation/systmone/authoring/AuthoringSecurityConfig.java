package com.newbrid.epr_automation.systmone.authoring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The capture tool is a <b>developer</b> surface, and it puts live clinical screenshots on the
 * screen — so it is authenticated, and only to the roles that author bots (NFR-SEC-01). It holds
 * no machine surface of its own: workers never call it, it calls them.
 *
 * <p>CSRF protection stays on, including for the tool's own JSON endpoints, because those
 * endpoints capture screens and write to the registry. The page sends the token as a header.</p>
 */
@Configuration
@EnableWebSecurity
@Profile("authoring")
public class AuthoringSecurityConfig {

    @Bean
    public SecurityFilterChain authoringSecurity(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/info", "/css/**", "/js/**", "/error")
                        .permitAll()
                        .anyRequest().hasAnyRole("DEVELOPER", "ADMIN"))
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public PasswordEncoder authoringPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Development users only, matching the control plane's. In production these roles arrive via
     * OAuth2/SSO claims; do not enable this bean against live patient data.
     */
    @Bean
    @Profile("!prod")
    public UserDetailsService authoringDevUsers(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("developer").password(encoder.encode("developer")).roles("DEVELOPER").build(),
                User.withUsername("admin").password(encoder.encode("admin")).roles("ADMIN").build()
        );
    }
}
