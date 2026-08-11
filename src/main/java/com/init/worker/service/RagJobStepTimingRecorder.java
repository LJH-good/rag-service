package com.init.worker.service;

import com.init.worker.domain.RagJobStepTiming;
import com.init.worker.domain.enums.RagJobStep;
import com.init.worker.domain.enums.RagStepTimingStatus;
import com.init.worker.dto.pcc.PccIngestResponse.PccStageTimings;
import com.init.worker.repo.RagJobStepTimingRepository;
import com.init.worker.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Job 파이프라인 단계별 소요 시간 기록기.
 *
 * <ul>
 *   <li>{@link #begin} — 단계 pick(claim) 직후 호출. 새 타이밍 행(RUNNING)을 남긴다.</li>
 *   <li>{@link #end}   — 단계 상태 전이(성공·실패·재대기) 시 호출. 열린 행을 닫고 소요 시간을 확정한다.</li>
 *   <li>{@link #completeParseCleanChunk} — PCC 응답의 하위 단계(ms)로 PARSE/CLEAN/CHUNK 를 확정한다.</li>
 * </ul>
 *
 * <p>타이밍 기록 실패가 실제 색인 파이프라인을 절대 중단시키지 않도록 모든 작업은 best-effort(try/catch)로 수행한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagJobStepTimingRecorder {

    private final RagJobStepTimingRepository timingRepo;

    /**
     * 단계 시작 기록. 새 트랜잭션에서 즉시 커밋해 {@link #end} 가 별도 트랜잭션에서 조회할 수 있게 한다.
     * 이전 시도가 비정상 종료돼 남은 열린 행이 있으면 ABANDONED 로 정리한다.
     * <p>PCC·EXTRACT_ENTITY 등 라우팅 전용 step 은 {@link RagJobStep#toDbStep()} 로 매핑해 DB CHECK 를 준수한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void begin(UUID jobId, UUID documentId, RagJobStep step) {
        try {
            RagJobStep dbStep = step.toDbStep();
            List<RagJobStepTiming> dangling = timingRepo.findByJobIdAndEndedAtIsNullOrderByStartedAtDesc(jobId);
            for (RagJobStepTiming stale : dangling) {
                stale.close(RagStepTimingStatus.ABANDONED, null);
            }
            long attempt = nextAttempt(jobId, dbStep);
            RagJobStepTiming timing = new RagJobStepTiming(
                    IdGenerator.newId(), jobId, documentId, dbStep, (int) attempt);
            if (!dangling.isEmpty()) {
                timingRepo.saveAll(dangling);
            }
            timingRepo.save(timing);
        } catch (Exception e) {
            log.warn("[STEP_TIMING] begin failed (ignored). jobId={}, step={}", jobId, step, e);
        }
    }

    /**
     * 단계 종료 기록. 호출 측(상태 머신)의 트랜잭션에 참여한다.
     * 열린 행(정상적으로는 1건)을 닫고, 혹시 여러 건이면 최신 1건만 해당 상태로, 나머지는 ABANDONED 로 닫는다.
     */
    public void end(UUID jobId, RagStepTimingStatus status, String errorCode) {
        try {
            List<RagJobStepTiming> open = timingRepo.findByJobIdAndEndedAtIsNullOrderByStartedAtDesc(jobId);
            if (open.isEmpty()) {
                return;
            }
            open.get(0).close(status, errorCode);
            for (int i = 1; i < open.size(); i++) {
                open.get(i).close(RagStepTimingStatus.ABANDONED, null);
            }
            timingRepo.saveAll(open);
        } catch (Exception e) {
            log.warn("[STEP_TIMING] end failed (ignored). jobId={}, status={}", jobId, status, e);
        }
    }

    /**
     * LangChain PCC/Pass1 하위 단계 시간으로 PARSE / CLEAN / CHUNK 타이밍을 확정한다.
     * <p>{@link #begin} 이 남긴 PARSE placeholder(EXTRACT_ENTITY/PCC → PARSE)가 있으면 abandon+재생성하지 않고
     * 같은 행·같은 attempt 를 파싱 시간으로 덮어써서 "2회차"로 오인되지 않게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeParseCleanChunk(UUID jobId, UUID documentId, PccStageTimings timings) {
        try {
            List<RagJobStepTiming> open = timingRepo.findByJobIdAndEndedAtIsNullOrderByStartedAtDesc(jobId);
            boolean hasStages = timings != null
                    && (timings.parseMs() != null || timings.cleanMs() != null || timings.chunkMs() != null);

            if (!hasStages) {
                if (open.isEmpty()) {
                    return;
                }
                open.get(0).close(RagStepTimingStatus.SUCCEEDED, null);
                for (int i = 1; i < open.size(); i++) {
                    open.get(i).close(RagStepTimingStatus.ABANDONED, null);
                }
                timingRepo.saveAll(open);
                return;
            }

            long parseMs = timings.parseMs() != null ? Math.max(0, timings.parseMs()) : 0L;
            long cleanMs = timings.cleanMs() != null ? Math.max(0, timings.cleanMs()) : 0L;
            long chunkMs = timings.chunkMs() != null ? Math.max(0, timings.chunkMs()) : 0L;

            OffsetDateTime chunkEnd = OffsetDateTime.now();
            OffsetDateTime chunkStart = chunkEnd.minusNanos(chunkMs * 1_000_000L);
            OffsetDateTime cleanEnd = chunkStart;
            OffsetDateTime cleanStart = cleanEnd.minusNanos(cleanMs * 1_000_000L);
            OffsetDateTime parseEnd = cleanStart;
            OffsetDateTime parseStart = parseEnd.minusNanos(parseMs * 1_000_000L);

            RagJobStepTiming parsePlaceholder = null;
            List<RagJobStepTiming> toAbandon = new ArrayList<>();
            for (RagJobStepTiming row : open) {
                if (parsePlaceholder == null && row.getStep() == RagJobStep.PARSE) {
                    parsePlaceholder = row;
                } else {
                    toAbandon.add(row);
                }
            }
            for (RagJobStepTiming stale : toAbandon) {
                stale.close(RagStepTimingStatus.ABANDONED, null);
            }
            if (!toAbandon.isEmpty()) {
                timingRepo.saveAll(toAbandon);
            }

            List<RagJobStepTiming> rows = new ArrayList<>(3);
            if (parsePlaceholder != null) {
                parsePlaceholder.applyWindow(parseStart, parseEnd, parseMs);
                rows.add(parsePlaceholder);
            } else {
                rows.add(completedRow(jobId, documentId, RagJobStep.PARSE, parseStart, parseEnd, parseMs));
            }
            rows.add(completedRow(jobId, documentId, RagJobStep.CLEAN, cleanStart, cleanEnd, cleanMs));
            rows.add(completedRow(jobId, documentId, RagJobStep.CHUNK, chunkStart, chunkEnd, chunkMs));
            timingRepo.saveAll(rows);

            log.info("[STEP_TIMING] PCC stages recorded. jobId={} parseMs={} cleanMs={} chunkMs={} reusedParse={}",
                    jobId, parseMs, cleanMs, chunkMs, parsePlaceholder != null);
        } catch (Exception e) {
            log.warn("[STEP_TIMING] completeParseCleanChunk failed (ignored). jobId={}", jobId, e);
        }
    }

    /**
     * 이미 종료된 단계 1건을 durationMs 로 확정 기록한다(Pass1 엔티티 LLM 등).
     * step 은 그대로 저장한다({@link RagJobStep#toDbStep()} 미적용).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCompleted(UUID jobId, UUID documentId, RagJobStep step, long durationMs) {
        try {
            long safeMs = Math.max(0, durationMs);
            OffsetDateTime endedAt = OffsetDateTime.now();
            OffsetDateTime startedAt = endedAt.minusNanos(safeMs * 1_000_000L);
            timingRepo.save(completedRow(jobId, documentId, step, startedAt, endedAt, safeMs));
            log.info("[STEP_TIMING] completed recorded. jobId={} step={} durationMs={}", jobId, step, safeMs);
        } catch (Exception e) {
            log.warn("[STEP_TIMING] recordCompleted failed (ignored). jobId={} step={}", jobId, step, e);
        }
    }

    private RagJobStepTiming completedRow(
            UUID jobId,
            UUID documentId,
            RagJobStep step,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            long durationMs
    ) {
        long attempt = nextAttempt(jobId, step);
        RagJobStepTiming timing = new RagJobStepTiming(
                IdGenerator.newId(), jobId, documentId, step, (int) attempt);
        timing.applyWindow(startedAt, endedAt, durationMs);
        return timing;
    }

    /** ABANDONED(placeholder 정리 행)는 재시도 횟수에 포함하지 않는다. */
    private long nextAttempt(UUID jobId, RagJobStep step) {
        return timingRepo.countByJobIdAndStepAndStatusNot(jobId, step, RagStepTimingStatus.ABANDONED) + 1;
    }
}
