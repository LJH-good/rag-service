package com.init.worker.storage;

import com.init.worker.config.RagProperties;
import org.springframework.stereotype.Component;

/**
 * 스토리지 오브젝트 키(경로) 생성 정책.
 *
 * pathPrefix 는 keyPrefix 를 포함한 전체 디렉터리 경로다.
 * 예: "rag/정책문서/20250527/연간보고서"
 *
 * 최종 경로 구조:
 *   원본: rag/{categoryName}/{YYYYMMDD}/{title}/{documentId}/{fileName}
 *   청크: rag/{categoryName}/{YYYYMMDD}/{title}/{documentId}/chunks/chunk_00000.txt
 *   임베딩: rag/{categoryName}/{YYYYMMDD}/{title}/{documentId}/embeddings/chunk_{chunkId}.jsonl
 */
@Component
public class PathPolicy {

    private final RagProperties props;

    public PathPolicy(RagProperties props) {
        this.props = props;
    }

    /** 설정된 keyPrefix (기본값: "rag") */
    public String keyPrefix() {
        String raw = (props.storage() != null) ? props.storage().keyPrefix() : null;
        return (raw == null || raw.isBlank()) ? "rag" : raw.trim().replaceAll("^/+|/+$", "");
    }

    /**
     * pathPrefix 생성.
     * 결과: "rag/{categoryName}/{YYYYMMDD}/{title}"
     */
    public String buildPathPrefix(String categoryName, String date, String title) {
        String cat = buildPathSegment(categoryName, "uncategorized");
        String ttl = buildPathSegment(title, "untitled");
        return keyPrefix() + "/" + cat + "/" + date + "/" + ttl;
    }

    /**
     * 원본 파일 저장 키.
     * 결과: "{pathPrefix}/{documentId}/{fileName}"
     */
    public String buildStorageKey(String pathPrefix, String documentId, String originalFileName) {
        return pathPrefix + "/" + segment(documentId) + "/" + sanitizeFileName(originalFileName);
    }

    /** 청크 텍스트 저장 키 */
    public String buildChunkKey(String pathPrefix, String documentId, int chunkIndex) {
        return String.format("%s/%s/chunks/chunk_%05d.txt",
                pathPrefix, segment(documentId), chunkIndex);
    }

    /**
     * 구형 경로 (category UUID 직하위). 과거 적재분 MinIO/DB 정리·fallback 용.
     * 예: rag/{categoryUuid}/{documentId}/chunks/chunk_00000.txt
     */
    public String legacyChunkStorageKey(String categoryId, String documentId, int chunkIndex) {
        if (categoryId == null || categoryId.isBlank()) {
            return null;
        }
        return String.format("%s/%s/%s/chunks/chunk_%05d.txt",
                keyPrefix(), segment(categoryId), segment(documentId), chunkIndex);
    }

    /**
     * {@link #buildChunkKey} 로 만든 키와 동일한지 검사한다.
     * rag_chunks.storage_key 가 현재 원본 file.storage_key 의 pathPrefix 와 맞는지 확인할 때 쓴다.
     */
    public boolean isCanonicalChunkStorageKey(String pathPrefix, String documentId, int chunkIndex, String chunkStorageKey) {
        if (chunkStorageKey == null || chunkStorageKey.isBlank()) {
            return false;
        }
        return buildChunkKey(pathPrefix, documentId, chunkIndex).equals(chunkStorageKey);
    }

    /** 파싱 결과 저장 키 */
    public String buildParsedKey(String pathPrefix, String documentId) {
        return pathPrefix + "/" + segment(documentId) + "/parsed.txt";
    }

    /** 클렌징 결과 저장 키 */
    public String buildCleanedKey(String pathPrefix, String documentId) {
        return pathPrefix + "/" + segment(documentId) + "/cleaned.txt";
    }

    /** 청크 단위 임베딩 JSONL 저장 키 */
    public String buildEmbeddingChunkKey(String pathPrefix, String documentId, String chunkId) {
        return String.format("%s/%s/embeddings/chunk_%s.jsonl",
                pathPrefix, segment(documentId), segment(chunkId));
    }

    /** 임베딩 파트 저장 키 */
    public String buildEmbeddingsPartKey(String pathPrefix, String documentId, int partNo) {
        return String.format("%s/%s/embeddings/embeddings-%06d.jsonl",
                pathPrefix, segment(documentId), partNo);
    }

    /**
     * storage_key에서 pathPrefix를 역추출.
     * storage_key 구조: {pathPrefix}/{documentId}/{filename}
     * → 마지막 두 세그먼트({documentId}, {filename})를 제거하면 pathPrefix가 남는다.
     */
    public String extractPathPrefix(String storageKey) {
        if (storageKey == null || storageKey.isBlank())
            throw new IllegalArgumentException("storageKey is blank");

        int lastSlash = storageKey.lastIndexOf('/');
        if (lastSlash <= 0)
            throw new IllegalArgumentException("storageKey has too few segments: " + storageKey);

        int secondLastSlash = storageKey.lastIndexOf('/', lastSlash - 1);
        if (secondLastSlash <= 0)
            throw new IllegalArgumentException("storageKey has too few segments: " + storageKey);

        return storageKey.substring(0, secondLastSlash);
    }

    /**
     * 사용자 입력값을 경로 세그먼트로 변환.
     * 공백·슬래시 등 경로에 부적절한 문자를 _로 치환한다.
     */
    public static String buildPathSegment(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim()
                .replace("/", "_")
                .replace("\\", "_")
                .replaceAll("[\\x00-\\x1F\\x7F]", "")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (v.contains("..")) return fallback;
        return v.isBlank() ? fallback : v;
    }

    // ── private ──────────────────────────────────────────────────────

    private String segment(String s) {
        if (s == null) throw new IllegalArgumentException("segment is null");
        String v = s.trim().replace("\\", "/");
        while (v.startsWith("/")) v = v.substring(1);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        while (v.contains("//")) v = v.replace("//", "/");
        if (v.contains("..")) throw new IllegalArgumentException("invalid segment: ..");
        if (v.isBlank()) throw new IllegalArgumentException("segment is blank");
        return v;
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "file";
        String v = fileName.trim()
                .replace("/", "_")
                .replace("\\", "_")
                .replaceAll("[\\x00-\\x1F\\x7F]", "");
        if (v.contains("..")) return "file";
        return v.isBlank() ? "file" : v;
    }
}
