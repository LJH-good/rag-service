package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagDocument;
import com.ragservice.worker.domain.enums.UserType;
import com.ragservice.worker.dto.common.DataSourceSearchField;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface RagDocumentRepositoryCustom {

    Page<RagDocument> findByFilter(
            UUID userNo,
            UUID categoryId,
            UserType userType,
            String search,
            DataSourceSearchField searchField,
            boolean portalScope,
            Boolean uncategorizedOnly,
            Pageable pageable
    );

    List<RagDocument> findCandidatesForPurge(OffsetDateTime cutoff, Pageable pageable);
}
