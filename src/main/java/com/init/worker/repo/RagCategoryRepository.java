package com.init.worker.repo;

import com.init.worker.domain.RagCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RagCategoryRepository extends JpaRepository<RagCategory, UUID>, RagCategoryRepositoryCustom {
}
