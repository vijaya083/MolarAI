package com.molarai.config;

import java.net.URI;
import java.util.Locale;

final class PostgresJdbcUrl {
    private static final String JDBC_PREFIX = "jdbc:postgresql://";

    private PostgresJdbcUrl() {
    }

    static String fromRenderOrJdbcUrl(String connectionUrl) {
        if (connectionUrl == null || connectionUrl.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL must be configured");
        }
        if (connectionUrl.regionMatches(true, 0, JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
            return connectionUrl;
        }

        int schemeEnd = connectionUrl.indexOf("://");
        if (schemeEnd < 0) {
            throw invalidUrl();
        }
        String scheme = connectionUrl.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!scheme.equals("postgres") && !scheme.equals("postgresql")) {
            throw invalidUrl();
        }

        URI uri;
        try {
            uri = URI.create(connectionUrl);
        } catch (IllegalArgumentException exception) {
            throw invalidUrl();
        }
        String authority = uri.getRawAuthority();
        if (uri.getHost() == null || authority == null || authority.isBlank() || uri.getRawFragment() != null) {
            throw invalidUrl();
        }

        int userInfoEnd = authority.lastIndexOf('@');
        if (userInfoEnd >= 0) {
            authority = authority.substring(userInfoEnd + 1);
        }
        StringBuilder jdbcUrl = new StringBuilder(JDBC_PREFIX)
                .append(authority)
                .append(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (uri.getRawQuery() != null) {
            jdbcUrl.append('?').append(uri.getRawQuery());
        }
        return jdbcUrl.toString();
    }

    private static IllegalArgumentException invalidUrl() {
        return new IllegalArgumentException(
                "DATABASE_URL must be a valid PostgreSQL URL using postgres://, postgresql://, or jdbc:postgresql://");
    }
}
