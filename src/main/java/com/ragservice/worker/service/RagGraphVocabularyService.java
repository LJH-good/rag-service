package com.ragservice.worker.service;

import com.ragservice.worker.domain.RagGraphVocabEntry;
import com.ragservice.worker.dto.admin.GraphVocabularyItem;
import com.ragservice.worker.dto.admin.GraphVocabularyResponse;
import com.ragservice.worker.error.code.ErrorCodes;
import com.ragservice.worker.error.exception.AppException;
import com.ragservice.worker.repo.RagGraphVocabEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Graph RAG TYPE/RELATION 어휘 세트 관리.
 * api/consumer 공통으로 DB({@code rag_graph_vocab_entry})를 사용한다.
 * 테이블이 비어 있을 때만 builtin seed 를 넣는다.
 */
@Service
@RequiredArgsConstructor
public class RagGraphVocabularyService {

    static final String KIND_TYPE = "TYPE";
    static final String KIND_RELATION = "RELATION";

    /** 세트 밖/공백 type fallback */
    public static final String DEFAULT_ENTITY_TYPE = "CONCEPT";
    /** 세트 밖/공백 relation fallback */
    public static final String DEFAULT_RELATION_TYPE = "RELATED_TO";

    /** 빈 테이블·신규 builtin 보강용 TYPE (설계: 10~20개 권장) */
    static final List<String> BUILTIN_ENTITY_TYPES = List.of(
            "ORGANIZATION", "PERSON", "POLICY", "PRODUCT",
            "CONCEPT", "LOCATION", "EVENT", "TERM",
            "WORK"
    );

    /**
     * 빈 테이블·신규 builtin 보강용 RELATION (설계: 6~10개, TYPE보다 타이트).
     * 세트 밖은 {@link #DEFAULT_RELATION_TYPE} 으로 흡수.
     */
    static final List<String> BUILTIN_RELATION_TYPES = List.of(
            "HAS", "PART_OF", "EXCEPTION_OF", "CAUSES", "REQUIRES", "RELATED_TO",
            "LOCATED_IN", "CREATED_BY", "BASED_ON"
    );

    private final RagGraphVocabEntryRepository vocabRepo;

    /**
     * 앱 기동 시 builtin 이 없으면 추가한다.
     * 이미 있는 행(관리자가 soft-delete 한 경우 포함)은 건드리지 않는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureBuiltins() {
        ensureBuiltinKind(KIND_TYPE, BUILTIN_ENTITY_TYPES);
        ensureBuiltinKind(KIND_RELATION, BUILTIN_RELATION_TYPES);
    }

    private void ensureBuiltinKind(String kind, List<String> names) {
        int nextOrder = (int) vocabRepo.countByKind(kind);
        for (String name : names) {
            if (vocabRepo.findByKindAndName(kind, name).isPresent()) {
                continue;
            }
            vocabRepo.save(new RagGraphVocabEntry(kind, name, true, nextOrder++));
        }
    }

    /** 프롬프트 주입·정규화용 — 활성·미삭제만 */
    @Transactional(readOnly = true)
    public List<String> getEntityTypes() {
        return activeNames(KIND_TYPE);
    }

    /** 프롬프트 주입·정규화용 — 활성·미삭제만 */
    @Transactional(readOnly = true)
    public List<String> getRelationTypes() {
        return activeNames(KIND_RELATION);
    }

    /**
     * LLM/입력 type 을 DB 어휘에 맞게 정규화한다.
     * 공백·미등록 값은 {@link #DEFAULT_ENTITY_TYPE} 으로 fallback.
     */
    @Transactional(readOnly = true)
    public String normalizeEntityType(String raw) {
        return normalize(raw, getEntityTypes(), DEFAULT_ENTITY_TYPE);
    }

    /**
     * LLM/입력 relation 을 DB 어휘에 맞게 정규화한다.
     * 공백·미등록 값은 {@link #DEFAULT_RELATION_TYPE} 으로 fallback.
     */
    @Transactional(readOnly = true)
    public String normalizeRelationType(String raw) {
        return normalize(raw, getRelationTypes(), DEFAULT_RELATION_TYPE);
    }

    static String normalize(String raw, List<String> allowed, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String upper = raw.trim().toUpperCase();
        Set<String> set = allowed.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.trim().toUpperCase())
                .collect(Collectors.toSet());
        return set.contains(upper) ? upper : fallback;
    }

    @Transactional
    public void addType(String name) {
        addEntry(KIND_TYPE, name);
    }

    @Transactional
    public void deleteType(String name) {
        softDeleteEntry(KIND_TYPE, name);
    }

    @Transactional
    public void setTypeActive(String name, boolean active) {
        setActive(KIND_TYPE, name, active);
    }

    @Transactional
    public void addRelation(String name) {
        addEntry(KIND_RELATION, name);
    }

    @Transactional
    public void deleteRelation(String name) {
        softDeleteEntry(KIND_RELATION, name);
    }

    @Transactional
    public void setRelationActive(String name, boolean active) {
        setActive(KIND_RELATION, name, active);
    }

    @Transactional(readOnly = true)
    public GraphVocabularyResponse vocabulary() {
        return new GraphVocabularyResponse(
                toItems(KIND_TYPE),
                DEFAULT_ENTITY_TYPE,
                toItems(KIND_RELATION),
                DEFAULT_RELATION_TYPE,
                "db");
    }

    private List<String> activeNames(String kind) {
        return vocabRepo.findByKindAndDeletedFalseOrderBySortOrderAscNameAsc(kind).stream()
                .filter(RagGraphVocabEntry::isActive)
                .map(RagGraphVocabEntry::getName)
                .toList();
    }

    private List<GraphVocabularyItem> toItems(String kind) {
        return vocabRepo.findByKindAndDeletedFalseOrderBySortOrderAscNameAsc(kind).stream()
                .map(e -> new GraphVocabularyItem(e.getName(), e.isBuiltin(), e.isActive()))
                .toList();
    }

    private void addEntry(String kind, String name) {
        String upper = name.trim().toUpperCase();
        var existing = vocabRepo.findByKindAndName(kind, upper);
        if (existing.isPresent()) {
            RagGraphVocabEntry entry = existing.get();
            if (!entry.isDeleted()) {
                throw new AppException(ErrorCodes.Api.VOCAB_ENTRY_ALREADY_EXISTS,
                        Map.of("kind", kind, "name", upper));
            }
            entry.restore();
            return;
        }
        int nextOrder = (int) vocabRepo.countByKind(kind);
        vocabRepo.save(new RagGraphVocabEntry(kind, upper, false, nextOrder));
    }

    private void softDeleteEntry(String kind, String name) {
        String upper = name.trim().toUpperCase();
        RagGraphVocabEntry entry = vocabRepo.findByKindAndName(kind, upper)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new AppException(ErrorCodes.Api.VOCAB_ENTRY_NOT_FOUND,
                        Map.of("kind", kind, "name", upper)));
        entry.softDelete(OffsetDateTime.now());
    }

    private void setActive(String kind, String name, boolean active) {
        String upper = name.trim().toUpperCase();
        RagGraphVocabEntry entry = vocabRepo.findByKindAndName(kind, upper)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new AppException(ErrorCodes.Api.VOCAB_ENTRY_NOT_FOUND,
                        Map.of("kind", kind, "name", upper)));
        entry.setActive(active);
    }
}
