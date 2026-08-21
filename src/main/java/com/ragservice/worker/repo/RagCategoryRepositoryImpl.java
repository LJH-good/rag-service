package com.ragservice.worker.repo;

import com.ragservice.worker.domain.QRagCategory;
import com.ragservice.worker.domain.RagCategory;
import com.ragservice.worker.dto.admin.CategorySearchField;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RagCategoryRepositoryImpl implements RagCategoryRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<RagCategory> findForAdmin(
            UUID userNo, String search, CategorySearchField searchField, Boolean active
    ) {
        QRagCategory c = QRagCategory.ragCategory;
        BooleanExpression where = c.isDeleted.isFalse();
        if (userNo != null) {
            where = where.and(c.userNo.eq(userNo));
        }
        if (active != null) {
            where = where.and(c.active.eq(active));
        }
        BooleanExpression searchExpr = searchExpression(c, search, searchField);
        if (searchExpr != null) {
            where = where.and(searchExpr);
        }
        return queryFactory
                .selectFrom(c)
                .where(where)
                .orderBy(c.createdAt.desc())
                .fetch();
    }

    @Override
    public List<RagCategory> findForPortal(UUID userNo, String search, CategorySearchField searchField) {
        QRagCategory c = QRagCategory.ragCategory;
        BooleanExpression where = c.isDeleted.isFalse().and(c.active.isTrue());
        if (userNo != null) {
            where = where.and(c.userNo.eq(userNo));
        }
        BooleanExpression searchExpr = searchExpression(c, search, searchField);
        if (searchExpr != null) {
            where = where.and(searchExpr);
        }
        return queryFactory
                .selectFrom(c)
                .where(where)
                .orderBy(c.createdAt.desc())
                .fetch();
    }

    private static BooleanExpression searchExpression(
            QRagCategory c, String search, CategorySearchField searchField
    ) {
        if (search == null || search.isBlank()) {
            return null;
        }
        CategorySearchField field = searchField != null ? searchField : CategorySearchField.ALL;
        String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        BooleanExpression nameMatch = c.name.lower().like(term);
        BooleanExpression descMatch = Expressions.stringTemplate(
                "LOWER(COALESCE({0}, ''))", c.description
        ).like(term);
        return switch (field) {
            case NAME -> nameMatch;
            case DESCRIPTION -> descMatch;
            case ALL -> nameMatch.or(descMatch);
        };
    }
}
