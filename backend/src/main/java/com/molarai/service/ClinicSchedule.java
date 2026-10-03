package com.molarai.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Verified fictional clinic hours from the knowledge base. Weekdays come from {@link LocalDate}, not a model.
 * Opening hours come from {@link ClinicSchedulingPolicy} so booking and hours answers share one configuration.
 */
@Component
public class ClinicSchedule {
    public static final String WEEKLY_HOURS =
            "The clinic is open Monday through Saturday from 9:00 AM to 2:00 PM. "
                    + "It is closed on Sunday. All times are Pacific Time.";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private final ClinicSchedulingPolicy policy;

    public ClinicSchedule() {
        this(ClinicSchedulingPolicy.defaults());
    }

    @Autowired
    public ClinicSchedule(ClinicSchedulingPolicy policy) {
        this.policy = policy;
    }

    public boolean isClosed(LocalDate date) {
        return policy.isClosed(date);
    }

    public String hoursClause(LocalDate date) {
        if (policy.isHoliday(date)) {
            return "The clinic is closed for a scheduled holiday";
        }
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SUNDAY) {
            return "The clinic is closed on Sunday";
        }
        return "The clinic is open from " + TIME.format(policy.opensAt(date)) + " to " + TIME.format(policy.closesAt(date));
    }
}
