package com.ragservice.worker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code user_no}로 aiGateway {@code X-API-Key}(UAK id) 조회.
 *
 * <p>rag_user 에 {@code SELECT ON public.user_access_key} 권한이 필요하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserUakProvider {

    private static final String QUERY_BY_USER_NO =
            "SELECT k.uak_id::text " +
            "FROM public.user_access_key k " +
            "WHERE k.user_no = ?::uuid " +
            "  AND k.is_deleted = false " +
            "  AND k.key_status = 'NORMAL' " +
            "ORDER BY k.created_at DESC " +
            "LIMIT 1";

    private final JdbcTemplate jdbcTemplate;

    public Optional<String> findUakIdByUserNo(UUID userNo) {
        if (userNo == null) {
            return Optional.empty();
        }
        try {
            String uakId = jdbcTemplate.queryForObject(QUERY_BY_USER_NO, String.class, userNo.toString());
            if (uakId == null || uakId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(uakId.trim());
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
