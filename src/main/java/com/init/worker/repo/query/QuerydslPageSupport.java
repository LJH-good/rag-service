package com.init.worker.repo.query;

import com.querydsl.core.types.dsl.EntityPathBase;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.function.Function;

public final class QuerydslPageSupport {

    private QuerydslPageSupport() {}

    public static <T> Page<T> fetchPage(
            JPAQueryFactory queryFactory,
            EntityPathBase<T> entityPath,
            Function<JPAQueryFactory, JPAQuery<T>> contentQuery,
            Function<JPAQueryFactory, JPAQuery<Long>> countQuery,
            Pageable pageable) {
        JPAQuery<T> query = contentQuery.apply(queryFactory)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize());
        List<T> content = query.fetch();
        Long total = countQuery.apply(queryFactory).fetchOne();
        long totalElements = total != null ? total : 0L;
        return new PageImpl<>(content, pageable, totalElements);
    }
}
