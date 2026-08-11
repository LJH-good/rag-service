package com.init.worker.repo;

import com.init.worker.domain.QRagDocument;
import com.init.worker.domain.QRagDocumentJob;
import com.init.worker.domain.RagDocumentJob;
import com.init.worker.domain.enums.RagJobStatus;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.init.worker.domain.enums.RagJobStep;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RagDocumentJobRepositoryImpl implements RagDocumentJobRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public boolean existsActiveJobByUserNo(UUID userNo) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;
        QRagDocument d = QRagDocument.ragDocument;

        UUID found = queryFactory
                .select(j.id)
                .from(j)
                .join(d).on(j.documentId.eq(d.id))
                .where(
                        d.userNo.eq(userNo),
                        j.status.in(RagJobStatus.PENDING, RagJobStatus.RUNNING)
                )
                .fetchFirst();

        return found != null;
    }

    @Override
    public boolean existsActiveJobByDocumentId(UUID documentId) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;
        UUID found = queryFactory
                .select(j.id)
                .from(j)
                .where(
                        j.documentId.eq(documentId),
                        j.status.in(RagJobStatus.PENDING, RagJobStatus.RUNNING)
                )
                .fetchFirst();
        return found != null;
    }

    @Override
    public List<RagDocumentJob> findNextStepQueueForUpdate(RagJobStep step, Pageable pageable) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;

        return queryFactory
                .selectFrom(j)
                .where(
                        j.currentStep.eq(step),
                        j.status.eq(RagJobStatus.PENDING)
                )
                .orderBy(j.startedAt.asc().nullsFirst(), j.createdAt.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint("jakarta.persistence.lock.timeout", "-2")
                .fetch();
    }

    @Override
    public List<RagDocumentJob> findNextPendingForUpdate(Pageable pageable) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;

        return queryFactory
                .selectFrom(j)
                .where(
                        j.status.eq(RagJobStatus.PENDING),
                        j.currentStep.isNull()
                )
                .orderBy(j.createdAt.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint("jakarta.persistence.lock.timeout", "-2")
                .fetch();
    }

    @Override
    public List<RagDocumentJob> findByDocumentId(UUID documentId) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;

        return queryFactory
                .selectFrom(j)
                .where(j.documentId.eq(documentId))
                .orderBy(j.createdAt.desc())
                .fetch();
    }

    @Override
    public Page<RagDocumentJob> findByDocumentIdPaged(UUID documentId, Pageable pageable) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;

        List<RagDocumentJob> content = queryFactory
                .selectFrom(j)
                .where(j.documentId.eq(documentId))
                .orderBy(j.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(j.count())
                .from(j)
                .where(j.documentId.eq(documentId))
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }

    @Override
    public java.util.Optional<RagDocumentJob> findLatestByTransactionId(UUID transactionId) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;

        RagDocumentJob job = queryFactory
                .selectFrom(j)
                .where(j.transactionId.eq(transactionId))
                .orderBy(j.createdAt.desc())
                .fetchFirst();
        return java.util.Optional.ofNullable(job);
    }

    @Override
    public long countByStatusAndStep(RagJobStatus status, RagJobStep step) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;
        Long count = queryFactory
                .select(j.count())
                .from(j)
                .where(
                        j.status.eq(status),
                        step == null ? j.currentStep.isNull() : j.currentStep.eq(step)
                )
                .fetchOne();
        return count != null ? count : 0L;
    }

    @Override
    public List<RagDocumentJob> findStuckRunningJobsOlderThan(OffsetDateTime cutoff, Pageable pageable) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;

        return queryFactory
                .selectFrom(j)
                .where(
                        j.status.eq(RagJobStatus.RUNNING),
                        j.startedAt.isNotNull(),
                        j.startedAt.before(cutoff)
                )
                .orderBy(j.startedAt.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }

    @Override
    public boolean transitionRunningToNextStep(UUID jobId, RagJobStep nextStep) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;
        long updated = queryFactory.update(j)
                .set(j.status, RagJobStatus.PENDING)
                .set(j.currentStep, nextStep.toDbStep())
                .set(j.errorCode, (String) null)
                .set(j.errorMessage, (String) null)
                .set(j.endedAt, (OffsetDateTime) null)
                .where(j.id.eq(jobId), j.status.eq(RagJobStatus.RUNNING))
                .execute();
        return updated > 0;
    }

    @Override
    public boolean transitionRunningToSucceeded(UUID jobId) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;
        OffsetDateTime now = OffsetDateTime.now();
        long updated = queryFactory.update(j)
                .set(j.status, RagJobStatus.SUCCEEDED)
                .set(j.currentStep, (RagJobStep) null)
                .set(j.errorCode, (String) null)
                .set(j.errorMessage, (String) null)
                .set(j.endedAt, now)
                .where(j.id.eq(jobId), j.status.eq(RagJobStatus.RUNNING))
                .execute();
        return updated > 0;
    }

    @Override
    public boolean transitionRunningToFailed(UUID jobId, String errorCode, String errorMessage) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;
        OffsetDateTime now = OffsetDateTime.now();
        long updated = queryFactory.update(j)
                .set(j.status, RagJobStatus.FAILED)
                .set(j.currentStep, (RagJobStep) null)
                .set(j.errorCode, errorCode)
                .set(j.errorMessage, errorMessage)
                .set(j.endedAt, now)
                .where(j.id.eq(jobId), j.status.eq(RagJobStatus.RUNNING))
                .execute();
        return updated > 0;
    }

    @Override
    public boolean transitionRunningToRequeue(UUID jobId) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;
        long updated = queryFactory.update(j)
                .set(j.status, RagJobStatus.PENDING)
                .set(j.errorCode, (String) null)
                .set(j.errorMessage, (String) null)
                // 다시 claim() 될 때 startedAt 이 새로 찍히도록 초기화한다.
                // (초기화하지 않으면 오래된 startedAt 때문에 재선택 직후 다시 stuck 으로 오탐된다.)
                .set(j.startedAt, (OffsetDateTime) null)
                .set(j.endedAt, (OffsetDateTime) null)
                .where(j.id.eq(jobId), j.status.eq(RagJobStatus.RUNNING))
                .execute();
        return updated > 0;
    }

    @Override
    public boolean transitionRunningToPccReset(UUID jobId) {
        QRagDocumentJob j = QRagDocumentJob.ragDocumentJob;
        long updated = queryFactory.update(j)
                .set(j.status, RagJobStatus.PENDING)
                .set(j.currentStep, (RagJobStep) null)
                .set(j.errorCode, (String) null)
                .set(j.errorMessage, (String) null)
                .set(j.startedAt, (OffsetDateTime) null)
                .set(j.endedAt, (OffsetDateTime) null)
                .where(j.id.eq(jobId), j.status.eq(RagJobStatus.RUNNING))
                .execute();
        return updated > 0;
    }
}
