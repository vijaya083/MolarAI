ALTER TABLE document_chunks
    ALTER COLUMN embedding TYPE VECTOR(${embedding-dimension})
    USING embedding::VECTOR(${embedding-dimension});
