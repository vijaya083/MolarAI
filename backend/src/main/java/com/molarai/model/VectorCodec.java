package com.molarai.model;

import java.util.List;
import java.util.stream.Collectors;

public final class VectorCodec {
    private VectorCodec() {
    }

    public static String encode(List<Double> values) {
        return values.stream().map(VectorCodec::format).collect(Collectors.joining(",", "[", "]"));
    }

    public static List<Double> decode(String value) {
        if (value == null || value.length() < 2) {
            throw new IllegalArgumentException("Invalid pgvector value");
        }
        String body = value.substring(1, value.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(body.split(","))
                .map(String::trim)
                .map(Double::valueOf)
                .toList();
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Vector components must be finite numbers");
        }
        return Double.toString(value);
    }
}
