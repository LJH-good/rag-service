package com.init.worker.service;

import com.init.worker.domain.RagChunk;
import com.init.worker.domain.RagDocument;
import com.init.worker.domain.RagDocumentFile;
import com.init.worker.repo.RagChunkRepository;
import com.init.worker.repo.RagDocumentFileRepository;
import com.init.worker.repo.RagDocumentRepository;
import com.init.worker.storage.PathPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * rag_documents + rag_document_files 를 함께 조회한다.
 */
@Component
@RequiredArgsConstructor
public class RagDocumentLoader {

    private final RagDocumentRepository docRepo;
    private final RagDocumentFileRepository fileRepo;
    private final RagChunkRepository chunkRepo;
    private final PathPolicy pathPolicy;

    public record WithFile(RagDocument doc, RagDocumentFile file) {}

    public WithFile loadWithFile(UUID documentId) {
        RagDocument doc = docRepo.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("document not found: " + documentId));
        RagDocumentFile file = fileRepo.findById(doc.getFileId())
                .orElseThrow(() -> new IllegalStateException("file not found: " + doc.getFileId()));
        return new WithFile(doc, file);
    }

    /**
     * storage 경로 prefix.
     * file.storageKey에서 역추출한다 — DB 컬럼 추가 없이 일관된 경로를 보장.
     */
    public String pathCategory(RagDocumentFile file) {
        return pathPolicy.extractPathPrefix(file.getStorageKey());
    }

    /** 현재 원본 file.storage_key 기준 canonical 청크 MinIO 키 */
    public String canonicalChunkStorageKey(RagDocumentFile file, UUID documentId, int chunkIndex) {
        return pathPolicy.buildChunkKey(pathCategory(file), documentId.toString(), chunkIndex);
    }

    public boolean isChunkStorageKeyAligned(RagDocumentFile file, UUID documentId, RagChunk chunk) {
        return pathPolicy.isCanonicalChunkStorageKey(
                pathCategory(file),
                documentId.toString(),
                chunk.getChunkIndex(),
                chunk.getStorageKey());
    }

    /**
     * document 의 모든 rag_chunks.storage_key 가 현재 원본 경로 prefix 와 일치하는지 검사한다.
     */
    public boolean allChunkStorageKeysAligned(UUID documentId, RagDocumentFile file) {
        return findFirstMisalignedChunk(documentId, file).isEmpty();
    }

    /** 정렬되지 않은 첫 청크 (없으면 empty). */
    public Optional<RagChunk> findFirstMisalignedChunk(UUID documentId, RagDocumentFile file) {
        int fromIndex = 0;
        int pageSize = 500;
        while (true) {
            List<RagChunk> page = chunkRepo.findPageByDoc(documentId, fromIndex, PageRequest.of(0, pageSize));
            if (page == null || page.isEmpty()) {
                return Optional.empty();
            }
            for (RagChunk chunk : page) {
                if (!isChunkStorageKeyAligned(file, documentId, chunk)) {
                    return Optional.of(chunk);
                }
            }
            fromIndex = page.get(page.size() - 1).getChunkIndex() + 1;
        }
    }
}
