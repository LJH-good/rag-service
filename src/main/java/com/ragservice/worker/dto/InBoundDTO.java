package com.ragservice.worker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Gateway 공통 요청 DTO (클라이언트 → 게이트웨이)
 * 클라이언트는 최소한의 정보만 제공합니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InBoundDTO {

    /** 사용할 모델 코드 (선택, 없으면 aiGateway 라우팅 결과 사용) */
    private String modelCode;

    /** 사용자 프롬프트 (필수) */
    private String content;

}
