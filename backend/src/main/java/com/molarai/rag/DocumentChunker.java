package com.molarai.rag;

import com.molarai.model.DocumentChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentChunker {
    private final int chunkSize;
    private final int overlap;

    public DocumentChunker(
            @Value("${molarai.chunking.size:1200}") int chunkSize,
            @Value("${molarai.chunking.overlap:150}") int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("Chunk overlap must be non-negative and less than chunk size");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<DocumentChunk> chunk(String document) {
        if (document == null || document.isBlank()) {
            return List.of();
        }
        String text = document.strip();
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            if (end < text.length()) {
                end = preferWhitespaceBoundary(text, start, end);
            }
            String content = text.substring(start, end).strip();
            if (!content.isEmpty()) {
                chunks.add(new DocumentChunk(chunks.size(), content));
            }
            if (end >= text.length()) {
                break;
            }
            int nextStart = Math.max(start + 1, end - overlap);
            while (nextStart < text.length() && Character.isWhitespace(text.charAt(nextStart))) {
                nextStart++;
            }
            start = nextStart;
        }
        return List.copyOf(chunks);
    }

    private int preferWhitespaceBoundary(String text, int start, int maximumEnd) {
        int earliestPreferredBoundary = start + Math.max(1, chunkSize / 2);
        for (int boundary = maximumEnd; boundary > earliestPreferredBoundary; boundary--) {
            if (Character.isWhitespace(text.charAt(boundary - 1))) {
                return boundary;
            }
        }
        return maximumEnd;
    }
}
