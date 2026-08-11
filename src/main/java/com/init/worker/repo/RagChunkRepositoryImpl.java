package com.init.worker.repo;

import com.init.worker.domain.QRagChunk;
import com.init.worker.domain.RagChunk;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RagChunkRepositoryImpl implements RagChunkRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public int deleteByDoc(UUID documentId) {
        QRagChunk c = QRagChunk.ragChunk;
        return (int) queryFactory
                .delete(c)
                .where(c.documentId.eq(documentId))
                .execute();
    }

    @Override
    public List<RagChunk> findPageByDoc(UUID documentId, int fromIndex, Pageable pageable) {
        QRagChunk c = QRagChunk.ragChunk;
        return queryFactory
                .selectFrom(c)
                .where(
                        c.documentId.eq(documentId),
                        c.chunkIndex.goe(fromIndex)
                )
                .orderBy(c.chunkIndex.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }

    @Override
    public long countByDoc(UUID documentId) {
        QRagChunk c = QRagChunk.ragChunk;
        Long count = queryFactory
                .select(c.count())
                .from(c)
                .where(c.documentId.eq(documentId))
                .fetchOne();
        return count != null ? count : 0L;
    }
}
