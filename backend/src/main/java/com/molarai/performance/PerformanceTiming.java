package com.molarai.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class PerformanceTiming {
    private static final Logger log = LoggerFactory.getLogger(PerformanceTiming.class);
    private static final ThreadLocal<RequestTrace> CURRENT = new ThreadLocal<>();

    public void beginRequest(String requestId) {
        CURRENT.set(new RequestTrace(requestId));
        log.info("perf request_id={} event=http_request_received", requestId);
    }

    public Stage stage(String name) {
        RequestTrace trace = CURRENT.get();
        if (trace != null) {
            log.info("perf request_id={} event=stage_start stage={}", trace.requestId, name);
        }
        return new Stage(trace, name);
    }

    public void event(String name) {
        RequestTrace trace = CURRENT.get();
        if (trace != null) log.info("perf request_id={} event={}", trace.requestId, name);
    }

    public void event(String name, String field, String value) {
        RequestTrace trace = CURRENT.get();
        if (trace != null) {
            log.info("perf request_id={} event={} {}={}", trace.requestId, name, field, value);
        }
    }

    public void increment(String counter) {
        RequestTrace trace = CURRENT.get();
        if (trace != null) trace.counts.merge(counter, 1, Integer::sum);
    }

    public void markSerializationStart() {
        RequestTrace trace = CURRENT.get();
        if (trace != null) {
            trace.serializationStartNanos = System.nanoTime();
            log.info("perf request_id={} event=response_serialization_start", trace.requestId);
        }
    }

    public void completeRequest(int status, boolean failed) {
        RequestTrace trace = CURRENT.get();
        if (trace == null) return;
        long totalMs = elapsedMillis(trace.startNanos);
        long serializationMs = trace.serializationStartNanos == 0
                ? 0 : elapsedMillis(trace.serializationStartNanos);
        log.info("perf request_id={} event=response_serialization_end elapsed_ms={} status={}",
                trace.requestId, serializationMs, status);
        // llm_calls counts chat completions only. tool_calls counts local
        // AppointmentAvailabilityService lookups, not LLM-native tool rounds.
        log.info("perf request_id={} event=request_complete total_ms={} status={} failed={} "
                        + "embedding_calls={} llm_calls={} tool_calls={}",
                trace.requestId, totalMs, status, failed,
                trace.counts.getOrDefault("embedding_calls", 0),
                trace.counts.getOrDefault("llm_calls", 0),
                trace.counts.getOrDefault("tool_calls", 0));
        CURRENT.remove();
    }

    private static long elapsedMillis(long sinceNanos) {
        return (System.nanoTime() - sinceNanos) / 1_000_000;
    }

    public static final class Stage implements AutoCloseable {
        private final RequestTrace trace;
        private final String name;
        private final long startNanos = System.nanoTime();

        private Stage(RequestTrace trace, String name) {
            this.trace = trace;
            this.name = name;
        }

        @Override
        public void close() {
            if (trace != null) {
                log.info("perf request_id={} event=stage_end stage={} elapsed_ms={}",
                        trace.requestId, name, elapsedMillis(startNanos));
            }
        }
    }

    private static final class RequestTrace {
        private final String requestId;
        private final long startNanos = System.nanoTime();
        private final Map<String, Integer> counts = new HashMap<>();
        private long serializationStartNanos;

        private RequestTrace(String requestId) {
            this.requestId = requestId;
        }
    }
}
