package com.molarai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class AppointmentTimeConfiguration {
    @Bean
    Clock appointmentClock(@Value("${molarai.appointment.time-zone:America/Los_Angeles}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
