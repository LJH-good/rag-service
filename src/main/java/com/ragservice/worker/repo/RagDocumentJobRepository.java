package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagDocumentJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * rag_document_jobs 테이블 접근 Repository.
 */
public interface RagDocumentJobRepository extends org.springframework.data.jpa.repository.JpaRepository<RagDocumentJob, UUID>,
        RagDocumentJobRepositoryCustom {
}
