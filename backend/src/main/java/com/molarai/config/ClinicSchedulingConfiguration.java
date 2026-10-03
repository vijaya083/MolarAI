package com.molarai.config;

import com.molarai.service.ClinicSchedulingPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
public class ClinicSchedulingConfiguration {
    @Bean
    ClinicSchedulingPolicy clinicSchedulingPolicy(
            @Value("${molarai.clinic.weekday-open:09:00}") String weekdayOpen,
            @Value("${molarai.clinic.weekday-close:14:00}") String weekdayClose,
            @Value("${molarai.clinic.saturday-open:09:00}") String saturdayOpen,
            @Value("${molarai.clinic.saturday-close:14:00}") String saturdayClose,
            @Value("${molarai.clinic.slot-minutes:30}") int slotMinutes,
            @Value("${molarai.clinic.daily-slot-starts:09:00,09:30,10:00,11:00,12:00,13:30}") String dailySlotStarts,
            @Value("${molarai.clinic.booking-horizon-days:90}") int bookingHorizonDays,
            @Value("${molarai.clinic.minimum-advance-minutes:60}") int minimumAdvanceMinutes,
            @Value("${molarai.clinic.providers:Dr. Maya Chen,Dr. Jordan Lee}") String providers,
            @Value("${molarai.clinic.holidays:}") String holidays) {
        return new ClinicSchedulingPolicy(
                LocalTime.parse(weekdayOpen),
                LocalTime.parse(weekdayClose),
                LocalTime.parse(saturdayOpen),
                LocalTime.parse(saturdayClose),
                slotMinutes,
                split(dailySlotStarts).stream().map(LocalTime::parse).toList(),
                bookingHorizonDays,
                minimumAdvanceMinutes,
                split(providers),
                split(holidays).stream().map(LocalDate::parse).collect(Collectors.toUnmodifiableSet()));
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }
}
