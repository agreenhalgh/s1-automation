package com.newbrid.epr_automation.pdsreview.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link CapabilityInterceptor} across the UI.
 *
 * <p>Registered for everything rather than for a list of protected prefixes, with the exclusions
 * held inside the interceptor. A capability check you have to remember to add to each new
 * controller is a capability check that will eventually be forgotten on the one that matters.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CapabilityInterceptor capability;
    private final PdsReviewProperties properties;

    public WebConfig(CapabilityInterceptor capability, PdsReviewProperties properties) {
        this.capability = capability;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(capability)
                .addPathPatterns("/**")
                // The gateway's intake route has no reviewer and no role to select; it is
                // authenticated as a service on its own filter chain.
                .excludePathPatterns(properties.intake().path() + "/**",
                        properties.intake().path());
    }
}
