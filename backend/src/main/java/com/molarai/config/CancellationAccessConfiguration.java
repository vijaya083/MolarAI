package com.molarai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class CancellationAccessConfiguration implements WebMvcConfigurer {
    private final HandlerInterceptor cancellationGuard;

    public CancellationAccessConfiguration(
            @Value("${molarai.cancellation.enabled:true}") boolean cancellationEnabled) {
        this.cancellationGuard = new CancellationAccessInterceptor(cancellationEnabled);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(cancellationGuard).addPathPatterns("/api/appointments/**");
    }
}
