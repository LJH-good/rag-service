package com.ragservice.worker.repo;

import com.ragservice.worker.domain.QRagChunk;
import com.ragservice.worker.domain.QRagEmbeddingPart;
import com.ragservice.worker.domain.RagEmbeddingPart;
import com.ragservice.worker.domain.enums.RagEmbeddingPartStatus;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RagEmbeddingPartRepositoryImpl implements RagEmbeddingPartRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<RagEmbeddingPart> findFirstByDocAndStatus(UUID documentId, RagEmbeddingPartStatus status) {
        QRagEmbeddingPart ep = QRagEmbeddingPart.ragEmbeddingPart;
        QRagChunk c = QRagChunk.ragChunk;

        RagEmbeddingPart part = queryFactory
                .selectFrom(ep)
                .join(c).on(ep.chunkId.eq(c.id))
                .where(
                        c.documentId.eq(documentId),
                        ep.status.eq(status)
                )
                .orderBy(c.chunkIndex.asc())
                .fetchFirst();

        return Optional.ofNullable(part);
    }

    @Override
    public long countByDocumentIdAndStatus(UUID documentId, RagEmbeddingPartStatus status) {
        QRagEmbeddingPart ep = QRagEmbeddingPart.ragEmbeddingPart;
        QRagChunk c = QRagChunk.ragChunk;

        Long count = queryFactory
                .select(ep.count())
                .from(ep)
                .join(c).on(ep.chunkId.eq(c.id))
                .where(
                        c.documentId.eq(documentId),
                        ep.status.eq(status)
                )
                .fetchOne();

        return count != null ? count : 0L;
    }

    @Override
    public int deleteByDocumentId(UUID documentId) {
        QRagEmbeddingPart ep = QRagEmbeddingPart.ragEmbeddingPart;
        QRagChunk c = QRagChunk.ragChunk;

        Long deleted = queryFactory
                .delete(ep)
                .where(ep.chunkId.in(
                        JPAExpressions.select(c.id)
                                .from(c)
                                .where(c.documentId.eq(documentId))
                ))
                .execute();

        return deleted != null ? deleted.intValue() : 0;
    }

    @Override
    public int deletePartsWithMissingChunk() {
        QRagEmbeddingPart ep = QRagEmbeddingPart.ragEmbeddingPart;
        QRagChunk c = QRagChunk.ragChunk;

        Long deleted = queryFactory
                .delete(ep)
                .where(ep.chunkId.notIn(
                        JPAExpressions.select(c.id).from(c)
                ))
                .execute();

        return deleted != null ? deleted.intValue() : 0;
    }

    @Override
    public int deleteByDocumentIdAndStatus(UUID documentId, RagEmbeddingPartStatus status) {
        QRagEmbeddingPart ep = QRagEmbeddingPart.ragEmbeddingPart;
        QRagChunk c = QRagChunk.ragChunk;

        Long deleted = queryFactory
                .delete(ep)
                .where(
                        ep.chunkId.in(
                                JPAExpressions.select(c.id)
                                        .from(c)
                                        .where(c.documentId.eq(documentId))
                        ),
                        ep.status.eq(status)
                )
                .execute();

        return deleted != null ? deleted.intValue() : 0;
    }

    @Override
    public List<RagEmbeddingPart> findPartsByDocumentId(UUID documentId) {
        QRagEmbeddingPart ep = QRagEmbeddingPart.ragEmbeddingPart;
        QRagChunk c = QRagChunk.ragChunk;

        return queryFactory
                .selectFrom(ep)
                .join(c).on(ep.chunkId.eq(c.id))
                .where(c.documentId.eq(documentId))
                .orderBy(c.chunkIndex.asc())
                .fetch();
    }
}
