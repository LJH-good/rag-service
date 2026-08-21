package com.ragservice.worker.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 엔티티 이름 매칭용 정규화 유틸. 인덱싱(Pass1 dedup·entity:link)과 검색(seed 링킹)이
 * 동일 규칙을 공유해야 표기 흔들림(띄어쓰기·전각/반각·대소문자)을 같은 canonical 로 수렴시킬 수 있다.
 *
 * <p>규칙:
 * <ul>
 *   <li>유니코드 NFKC 정규화(전각/반각·호환문자 통일)</li>
 *   <li>소문자화(영문에만 영향)</li>
 *   <li>공백: 앞뒤가 <b>둘 다 한글</b>이면 제거, 그 외(영문·숫자 경계 포함)는 한 칸으로 유지</li>
 * </ul>
 * 예) {@code "가상 네트워크 관리" → "가상네트워크관리"}, {@code "AI Gateway" → "ai gateway"},
 * {@code "AI 관리" → "ai 관리"}(한글-영문 전환 공백은 보수적으로 유지).
 *
 * <p>표기 흔들림만 다루며, 동의어·음차·오타(예: {@code 뉴욕 ↔ New York})는 대상이 아니다.
 */
public final class EntityNameNormalizer {

    private EntityNameNormalizer() {}

    public static String normalize(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String lower = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        int i = 0;
        int n = lower.length();
        while (i < n) {
            char c = lower.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
                i++;
                continue;
            }
            int j = i;
            while (j < n && Character.isWhitespace(lower.charAt(j))) {
                j++;
            }
            boolean hasPrev = sb.length() > 0;
            boolean hasNext = j < n;
            if (hasPrev && hasNext) {
                char prev = sb.charAt(sb.length() - 1);
                char next = lower.charAt(j);
                if (!(isHangul(prev) && isHangul(next))) {
                    sb.append(' '); // 한글-한글이 아니면 한 칸 유지
                }
                // 한글-한글 경계면 아무것도 안 붙임(제거)
            }
            // 앞/뒤가 비면(선행·후행 공백) 그냥 버림
            i = j;
        }
        return sb.toString();
    }

    private static boolean isHangul(char c) {
        return (c >= 0xAC00 && c <= 0xD7A3)   // 한글 음절
                || (c >= 0x1100 && c <= 0x11FF)   // 한글 자모
                || (c >= 0x3130 && c <= 0x318F);  // 호환용 자모
    }
}
