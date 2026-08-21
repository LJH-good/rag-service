package com.ragservice.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragservice.worker.config.RagProperties;
import com.ragservice.worker.domain.RagEntity;
import com.ragservice.worker.util.EntityNameNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Graph RAG entity:link 캐시(RAG Redis 소유). Pass1(EXTRACT_ENTITY)이 확정한 canonical 엔티티를
 * 검색측 엔티티 링킹이 재사용하도록 선워밍한다.
 *
 * <p>키: {@code rag:entity:link:{normalizedTerm}} — term 은 엔티티 이름 소문자·trim.
 * 값: {@code {entityId, name, type, documentId}} JSON. 검색측은 이 term→canonical 매핑으로
 * 표면형을 정규화한 뒤 rag_entity 를 조회한다.
 *
 * <p>비차단: Redis 장애가 인덱싱 파이프라인을 막지 않도록 모든 쓰기는 best-effort(예외 삼킴)다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.app.role", havingValue = "consumer")
public class EntityLinkCache {

    private static final String KEY_PREFIX = "rag:entity:link:";

    private final StringRedisTemplate redis;
    private final RagProperties props;
    private final ObjectMapper om;

    public EntityLinkCache(StringRedisTemplate redis, RagProperties props, ObjectMapper om) {
        this.redis = redis;
        this.props = props;
        this.om = om;
    }

    /** 엔티티의 정규화된 이름을 term 으로 canonical 정보를 캐싱한다(best-effort). */
    public void prewarm(RagEntity entity) {
        String term = normalize(entity.getName());
        if (term.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("entityId", entity.getEntityId().toString());
            value.put("name", entity.getName());
            value.put("type", entity.getType());
            value.put("documentId", entity.getDocumentId().toString());
            String json = om.writeValueAsString(value);

            long ttl = props.graph().entityLinkTtlSeconds();
            if (ttl > 0) {
                redis.opsForValue().set(KEY_PREFIX + term, json, Duration.ofSeconds(ttl));
            } else {
                redis.opsForValue().set(KEY_PREFIX + term, json);
            }
        } catch (Exception e) {
            log.warn("[ENTITY_LINK] prewarm failed (ignored). term={}, entityId={}, err={}",
                    term, entity.getEntityId(), e.toString());
        }
    }

    private static String normalize(String name) {
        return EntityNameNormalizer.normalize(name);
    }
}
