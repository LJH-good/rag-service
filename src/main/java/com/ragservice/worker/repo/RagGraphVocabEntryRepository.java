package com.ragservice.worker.repo;

import com.ragservice.worker.domain.RagGraphVocabEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagGraphVocabEntryRepository extends JpaRepository<RagGraphVocabEntry, Long> {

    List<RagGraphVocabEntry> findByKindAndDeletedFalseOrderBySortOrderAscNameAsc(String kind);

    Optional<RagGraphVocabEntry> findByKindAndName(String kind, String name);

    long countByKind(String kind);
}
