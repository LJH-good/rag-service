package com.init.worker.repo;

import com.init.worker.domain.RagDocument;
import com.init.worker.domain.QRagCategory;
import com.init.worker.domain.enums.UserType;
import com.init.worker.domain.QRagDocument;
import com.init.worker.domain.QRagDocumentFile;
import com.init.worker.dto.common.DataSourceSearchField;
import com.init.worker.repo.query.QuerydslPageSupport;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RagDocumentRepositoryImpl implements RagDocumentRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<RagDocument> findByFilter(
            UUID userNo,
            UUID categoryId,
            UserType userType,
            String search,
            DataSourceSearchField searchField,
            boolean portalScope,
            Boolean uncategorizedOnly,
            Pageable pageable
    ) {
        QRagDocument d = QRagDocument.ragDocument;
        QRagDocumentFile f = QRagDocumentFile.ragDocumentFile;
        QRagCategory c = QRagCategory.ragCategory;
        BooleanBuilder where = new BooleanBuilder().and(d.isDeleted.isFalse());
        if (userNo != null) {
            where.and(d.userNo.eq(userNo));
        }
        if (Boolean.TRUE.equals(uncategorizedOnly)) {
            where.and(d.categoryId.isNull());
        } else if (categoryId != null) {
            where.and(d.categoryId.eq(categoryId));
        }
        if (userType != null) {
            where.and(d.userType.eq(userType));
        }
        if (portalScope) {
            where.and(d.categoryId.isNull().or(c.active.isTrue()));
        }
        BooleanExpression searchExpr = searchExpression(f, c, search, searchField);
        if (searchExpr != null) {
            where.and(searchExpr);
        }
        return QuerydslPageSupport.fetchPage(
                queryFactory,
                d,
                qf -> qf.selectFrom(d)
                        .join(f).on(f.id.eq(d.fileId))
                        .leftJoin(c).on(c.id.eq(d.categoryId).and(c.isDeleted.isFalse()))
                        .where(where)
                        .orderBy(d.createdAt.desc()),
                qf -> qf.select(d.count())
                        .from(d)
                        .join(f).on(f.id.eq(d.fileId))
                        .leftJoin(c).on(c.id.eq(d.categoryId).and(c.isDeleted.isFalse()))
                        .where(where),
                pageable);
    }

    @Override
    public List<RagDocument> findCandidatesForPurge(OffsetDateTime cutoff, Pageable pageable) {
        QRagDocument d = QRagDocument.ragDocument;
        return queryFactory
                .selectFrom(d)
                .where(
                        d.isDeleted.isTrue(),
                        d.deletedAt.lt(cutoff),
                        d.purgedAt.isNull()
                )
                .orderBy(d.deletedAt.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }

    private static BooleanExpression searchExpression(
            QRagDocumentFile f,
            QRagCategory c,
            String search,
            DataSourceSearchField searchField
    ) {
        if (search == null || search.isBlank()) {
            return null;
        }
        DataSourceSearchField field = searchField != null ? searchField : DataSourceSearchField.ALL;
        String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        // UI 표시와 동일: title이 없으면 originalFileName을 제목으로 사용
        BooleanExpression titleMatch = Expressions.stringTemplate(
                "LOWER(COALESCE(NULLIF({0}, ''), {1}))", f.title, f.originalFileName
        ).like(term);
        BooleanExpression fileNameMatch = f.originalFileName.lower().like(term);
        BooleanExpression categoryNameMatch = c.name.lower().like(term);
        return switch (field) {
            case TITLE -> titleMatch;
            case ORIGINAL_FILE_NAME -> fileNameMatch;
            case CATEGORY_NAME -> categoryNameMatch;
            case ALL -> titleMatch.or(fileNameMatch).or(categoryNameMatch);
        };
    }
}
