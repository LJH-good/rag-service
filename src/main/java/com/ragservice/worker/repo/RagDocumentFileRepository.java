package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagDocumentFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RagDocumentFileRepository extends JpaRepository<RagDocumentFile, UUID> {
}
