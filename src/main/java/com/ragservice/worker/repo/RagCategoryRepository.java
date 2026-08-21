package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RagCategoryRepository extends JpaRepository<RagCategory, UUID>, RagCategoryRepositoryCustom {
}
