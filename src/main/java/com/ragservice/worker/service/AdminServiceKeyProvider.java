package com.ragservice.worker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code __admin_service__} 계정 UAK ID를 DB에서 로드해 캐싱한다.
 * <p>
 * EMBED 시 <b>admin 사용자에게 NORMAL UAK가 없을 때만</b> fallback 으로 사용한다.
 * 일반(user) 과금 UAK는 {@link UserUakProvider} 가 {@code rag_documents.user_no} 로 조회한다.
 *
 * rag_user 에 아래 권한이 필요하다:
 *   GRANT SELECT ON public.user_account TO rag_user;
 *   GRANT SELECT ON public.user_access_key TO rag_user;
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminServiceKeyProvider {

    private static final String QUERY =
            "SELECT k.uak_id::text " +
            "FROM public.user_account u " +
            "JOIN public.user_access_key k ON k.user_no = u.user_no " +
            "WHERE u.user_id = '__admin_service__' " +
            "  AND k.is_deleted = false " +
            "  AND k.key_status = 'NORMAL' " +
            "LIMIT 1";

    private final JdbcTemplate jdbcTemplate;

    private String uakId;

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        try {
            uakId = jdbcTemplate.queryForObject(QUERY, String.class);
            log.info("[admin-service-key] 로드 완료. uakId={}", uakId);
        } catch (EmptyResultDataAccessException e) {
            log.warn("[admin-service-key] '__admin_service__' 계정 또는 NORMAL UAK가 존재하지 않습니다. " +
                     "admin_service_account.sql 을 실행했는지 확인하세요.");
        } catch (DataAccessException e) {
            // public.user_account/user_access_key 는 다른 서비스(계정/Gateway) 소유 테이블이라
            // 로컬 등 해당 스키마가 없는 환경에서는 조회 자체가 실패할 수 있다.
            // admin fallback UAK 는 opt-in 기능이므로 여기서 앱을 죽이지 않고 null 로 남긴다.
            log.warn("[admin-service-key] public.user_account/user_access_key 조회 실패 — admin fallback UAK 비활성화됨. reason={}",
                    e.getMessage());
        }
    }

    /** X-API-Key 헤더 값으로 사용할 UAK ID. 계정이 없으면 null. */
    public String getUakId() {
        return uakId;
    }
}
