package com.molarai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppointmentAvailabilityIntentRouterTest {
    private final AppointmentAvailabilityIntentRouter router = new AppointmentAvailabilityIntentRouter();

    @Test
    void routesExplicitDatedOrTimedAvailabilityRequestsToNativeToolPath() {
        assertTrue(router.requiresLiveAvailability("Do you have appointments available on June 10, 2030?"));
        assertTrue(router.requiresLiveAvailability("Are there any appointments on June 10?"));
        assertTrue(router.requiresLiveAvailability("Is any slot open tomorrow afternoon?"));
        assertTrue(router.requiresLiveAvailability("Can I get an appointment at 2 PM?"));
    }

    @Test
    void doesNotTreatOrdinaryAppointmentWordingAsLiveAvailability() {
        assertFalse(router.requiresLiveAvailability("Do you have pediatric appointments?"));
        assertFalse(router.requiresLiveAvailability("Do you have appointments?"));
        assertFalse(router.requiresLiveAvailability("How do new patients book a first visit?"));
    }

    @Test
    void keepsClinicKnowledgeAndAppointmentPolicyQuestionsOnFaqPath() {
        assertFalse(router.requiresLiveAvailability("Do you accept Aetna insurance?"));
        assertFalse(router.requiresLiveAvailability("What are your clinic hours?"));
        assertFalse(router.requiresLiveAvailability("What is your appointment cancellation policy?"));
        assertFalse(router.requiresLiveAvailability("What are your appointment cancellation fees for October 2?"));
        assertFalse(router.requiresLiveAvailability("How do I schedule an appointment?"));
    }

    @Test
    void handlesNullAndBlankQuestionsAsFaq() {
        assertFalse(router.requiresLiveAvailability(null));
        assertFalse(router.requiresLiveAvailability("  "));
    }

    @Test
    void keepsSlotLookupDistinctFromHoursExactTimeAndWeekdayQuestions() {
        assertEquals(QuestionIntent.APPOINTMENT_AVAILABILITY,
                router.classify("Is slot available on September 29, 2026?"));
        assertEquals(QuestionIntent.APPOINTMENT_AVAILABILITY,
                router.classify("Is a slot available on September 29, 2026 outside opening hours?"));
        assertEquals(QuestionIntent.EXACT_SLOT_AVAILABILITY,
                router.classify("Is 10 AM available on September 29?"));
        assertEquals(QuestionIntent.CLINIC_HOURS, router.classify("What are your opening hours?"));
        assertEquals(QuestionIntent.DATE_OR_WEEKDAY_QUERY, router.classify("What day is September 29, 2026?"));
        assertFalse(router.requiresLiveAvailability("What are your opening hours?"));
        assertFalse(router.requiresLiveAvailability("What day is September 29, 2026?"));
    }
}
