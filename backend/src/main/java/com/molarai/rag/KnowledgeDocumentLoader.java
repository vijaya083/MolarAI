package com.molarai.rag;

import com.molarai.model.KnowledgeDocument;

import java.util.List;

public interface KnowledgeDocumentLoader {
    List<KnowledgeDocument> loadDocuments();
}
