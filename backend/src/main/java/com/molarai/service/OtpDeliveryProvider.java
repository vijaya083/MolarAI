package com.molarai.service;

public interface OtpDeliveryProvider {
    DeliveryResult send(String destination, String otp);

    record DeliveryResult(String developmentCode) {
    }
}
