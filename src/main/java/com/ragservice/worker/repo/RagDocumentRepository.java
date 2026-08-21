package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RagDocumentRepository extends JpaRepository<RagDocument, UUID>, RagDocumentRepositoryCustom {

    List<RagDocument> findByCategoryId(UUID categoryId);
}
