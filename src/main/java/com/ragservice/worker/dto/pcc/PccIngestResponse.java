package com.ragservice.worker.dto.pcc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * LangChain 통합 PCC 응답. 청크 본문과 (선택) 파싱·정제·청킹 단계별 소요 시간.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PccIngestResponse(
        List<PccChunkPayload> chunks,
        PccStageTimings timings
) {

    public record PccChunkPayload(
            String text,
            Integer index,
            String location
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PccStageTimings(
            Long parseMs,
            Long cleanMs,
            Long chunkMs
    ) {}
}
