package com.ragservice.worker.prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Graph RAG 추출용 내장 프롬프트 템플릿.
 * Pass1(엔티티 사전)·Pass2(엔티티+관계) 프롬프트 본문·스키마는 RAG 내부 소유이며,
 * TYPE/RELATION 어휘는 호출자가 DB에서 가져와 파라미터로 전달한다.
 */
public final class GraphPromptTexts {

    private GraphPromptTexts() {}

    public static final String ENTITY_ROLE =
            "당신은 문서 단위 지식그래프 전처리기다. 아래 문서 전체에 대해 두 작업을 동시에 수행하라.\n"
                    + "1) 핵심 엔티티를 표준화(canonical)해 사전으로 추출한다. 동일 개념의 표면형(동의어·약어·표기 변형)은 하나의 표준 이름으로 병합한다.\n"
                    + "2) 문서를 '무손실'로 재정리한다. 내용·사실·수치·인용은 절대 요약·삭제·창작하지 말고, 아래 항목만 정리한다.\n"
                    + "   - 페이지 머리말/꼬리말/페이지번호/목차 등 파싱 노이즈 제거\n"
                    + "   - 줄바꿈으로 끊긴 문장 잇기, 깨진 표를 읽을 수 있는 형태로 복원\n"
                    + "   - 대명사·약어·지시어(예: '그 회사','본 시스템','당사')를 1)에서 정한 표준 엔티티 이름으로 치환\n"
                    + "   - 주제/엔티티 단위로 문단 경계만 정리(문장 순서를 크게 뒤섞지 말 것)\n"
                    + "   ※ 시험·문제지 형식의 보기/선택지(①②③④⑤ 또는 (1)(2)(3)(4)(5) 등 번호 붙은 항목)는\n"
                    + "     번호와 해당 값을 한 항목도 빠뜨리지 말고 원문 순서 그대로 모두 출력한다.\n"
                    + "   ※ 수식·루트·분수·수학 기호·특수문자는 입력된 형태 그대로 보존한다(변환·생략·재해석 금지).\n"
                    + "반드시 아래 JSON 스키마로만 응답하고, 그 외 설명/마크다운/코드펜스는 출력하지 마라.\n\n";

    public static final String ENTITY_JSON_SCHEMA = """
            {
              "entities": [{"name": "표준화된 이름", "type": "타입"}],
              "cleaned_document": "무손실로 재정리한 문서 전문"
            }
            """;

    /**
     * Pass1 프롬프트를 조립한다. TYPE 목록은 호출자가 DB 어휘에서 전달한다.
     * 업로드 용량 상한·문서 크기 기반 모델 라우팅으로 컨텍스트를 보장하므로 원문을 절단 없이 전량 전달한다.
     */
    public static String buildEntityDictionaryPrompt(String documentText, List<String> entityTypes) {
        String body = documentText == null ? "" : documentText;
        String typeList = joinCsv(entityTypes);
        StringBuilder sb = new StringBuilder();
        sb.append(ENTITY_ROLE);
        sb.append(ENTITY_JSON_SCHEMA).append('\n');
        sb.append("- type 은 다음 중 하나: ").append(typeList).append('\n');
        sb.append("- entities 는 문서에서 실제로 다루는 핵심 개념만, 중복 없이 표준 이름으로 통일한다.\n");
        sb.append("- cleaned_document 는 원문의 내용·사실·수치를 하나도 빠뜨리지 말고 보존한다(요약·삭제·창작 금지).\n");
        sb.append("- 보기/선택지(①②③④⑤ 등)가 있으면 모든 번호와 값을 원문 그대로 빠짐없이 출력한다. 단 하나도 생략 불가.\n");
        sb.append("- 수식·수학 기호·특수문자는 원문 형태 그대로 출력한다(임의 변환·생략 금지).\n\n");
        sb.append("문서 전체:\n");
        sb.append(body);
        return sb.toString();
    }

    public static final String EXTRACTION_ROLE =
            "당신은 지식그래프 추출기다. 아래 문서 청크들에서 핵심 엔티티와 엔티티 간 관계를 추출하라.\n"
                    + "반드시 아래 JSON 스키마로만 응답하고, 그 외 설명/마크다운/코드펜스는 출력하지 마라.\n\n";

    public static final String EXTRACTION_JSON_SCHEMA = """
            {
              "entities": [{"name": "표준화된 이름", "type": "타입", "chunks": [등장한 청크번호]}],
              "relations": [{"src": "엔티티이름", "dst": "엔티티이름", "relation": "관계", "label": "원문근거", "confidence": 0.0~1.0}]
            }
            """;

    public static final String EXTRACTION_CONSTRAINTS_FOOTER =
            "- src/dst 는 entities.name 과 정확히 일치해야 한다. chunks 번호는 아래 [번호] 를 사용한다.\n\n"
                    + "문서 청크:\n";

    /**
     * Pass2 엔티티+관계 추출 프롬프트를 조립한다. TYPE/RELATION 목록은 호출자가 DB 어휘에서 전달한다.
     */
    public static String buildExtractionPrompt(
            List<String> chunkTexts,
            int maxCharsPerChunk,
            List<String> entityTypes,
            List<String> relationTypes,
            String relationTypeDefault) {
        String typeList = joinCsv(entityTypes);
        String relList = joinCsv(relationTypes);
        String relDefault = (relationTypeDefault == null || relationTypeDefault.isBlank())
                ? "RELATED_TO"
                : relationTypeDefault;
        StringBuilder sb = new StringBuilder();
        sb.append(EXTRACTION_ROLE);
        sb.append(EXTRACTION_JSON_SCHEMA).append('\n');
        sb.append("- type 은 다음 중 하나: ").append(typeList).append('\n');
        sb.append("- relation 은 다음 중 하나(불명확하면 ").append(relDefault).append("): ").append(relList).append('\n');
        sb.append(EXTRACTION_CONSTRAINTS_FOOTER);
        for (int i = 0; i < chunkTexts.size(); i++) {
            String t = chunkTexts.get(i);
            if (t.length() > maxCharsPerChunk) {
                t = t.substring(0, maxCharsPerChunk);
            }
            sb.append('[').append(i).append("] ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static String joinCsv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream().collect(Collectors.joining(", "));
    }
}
