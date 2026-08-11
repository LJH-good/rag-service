package com.init.worker.service;

import com.init.worker.domain.RagDocument;
import com.init.worker.domain.enums.UserType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 문서 청크 임베딩(EMBED) 시 aiGateway {@code X-API-Key} 결정.
 * <ul>
 *   <li>{@code rag_documents.user_no} → {@code user_access_key} 에서 NORMAL UAK 조회</li>
 *   <li>{@code userType=admin} 이고 UAK 없음 → {@code __admin_service__} fallback</li>
 *   <li>{@code userType=user} 이고 UAK 없음 → 실패</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingApiKeyResolver {

    public static final String CODE_UAK_MISSING = "EMBED_UAK_MISSING";
    public static final String CODE_ADMIN_UAK_UNAVAILABLE = "EMBED_ADMIN_UAK_UNAVAILABLE";

    private final UserUakProvider userUakProvider;
    private final AdminServiceKeyProvider adminServiceKeyProvider;

    public String resolve(RagDocument doc) {
        return userUakProvider.findUakIdByUserNo(doc.getUserNo())
                .orElseGet(() -> resolveWhenUserHasNoUak(doc));
    }

    private String resolveWhenUserHasNoUak(RagDocument doc) {
        if (doc.getUserType() == UserType.admin) {
            String adminUak = adminServiceKeyProvider.getUakId();
            if (adminUak == null || adminUak.isBlank()) {
                throw new EmbeddingApiKeyResolveException(
                        CODE_ADMIN_UAK_UNAVAILABLE,
                        "__admin_service__ UAK is not configured (admin user has no NORMAL UAK)");
            }
            log.info(
                    "[EMBED] admin user has no UAK — using __admin_service__ fallback. docId={} userNo={}",
                    doc.getId(),
                    doc.getUserNo());
            return adminUak.trim();
        }
        throw new EmbeddingApiKeyResolveException(
                CODE_UAK_MISSING,
                "no NORMAL UAK for user_no=" + doc.getUserNo() + "; cannot bill document embedding");
    }

    public static final class EmbeddingApiKeyResolveException extends RuntimeException {
        private final String code;

        public EmbeddingApiKeyResolveException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
