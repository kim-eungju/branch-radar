package com.branchradar.dto;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flyway 버전.
 * <p>
 * 동일성(equals/key): Flyway 규칙과 같다. '_' 는 '.' 으로 바뀌고, 각 부분은 정수로 비교하며, 끝의 0 은 무시한다.
 * 즉 1.0 == 1, 250212.001 == 250212.1 이다. (Flyway 도 이 둘을 같은 버전으로 보고 중복 오류를 낸다)
 * <p>
 * 정렬(compareTo): 기본은 Flyway 와 같은 숫자 비교다. dateAware=true 이면 첫 부분이 날짜 형식
 * (yyMMdd, yyyyMMdd, yyyyMMddHHmm, yyyyMMddHHmmss)일 때 yyyyMMddHHmmss 로 정규화해서 먼저 비교한다.
 * 모든 파일이 같은 날짜 형식을 쓰면 결과는 Flyway 숫자 비교와 같고, 형식이 섞여 있을 때만 차이가 난다.
 * 문자열 사전순 비교는 사용하지 않는다.
 */
public final class FlywayVersion implements Comparable<FlywayVersion> {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);

    private final String raw;
    private final List<BigInteger> parts;
    private final BigInteger orderHead;
    private final boolean dateLike;
    private final String key;

    private FlywayVersion(String raw, List<BigInteger> parts, BigInteger orderHead, boolean dateLike) {
        this.raw = raw;
        this.parts = parts;
        this.orderHead = orderHead;
        this.dateLike = dateLike;
        this.key = String.join(".", parts.stream().map(BigInteger::toString).toList());
    }

    /**
     * @param raw 파일명에서 접두어와 구분자 사이의 문자열 (예: "250212.001", "20261105_143000")
     * @throws IllegalArgumentException 숫자/구분자 이외의 문자가 있으면 (Flyway 도 실패하는 이름)
     */
    public static FlywayVersion parse(String raw, boolean dateAware) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("버전이 비어 있음");
        }
        String[] tokens = raw.replace('_', '.').split("\\.", -1);
        List<BigInteger> parts = new ArrayList<>();
        for (String t : tokens) {
            if (t.isEmpty() || !t.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("숫자가 아닌 버전: " + raw);
            }
            parts.add(new BigInteger(t));
        }
        while (parts.size() > 1 && parts.get(parts.size() - 1).signum() == 0) {
            parts.remove(parts.size() - 1);
        }
        String dateHead = dateAware ? normalizeDate(tokens[0]) : null;
        BigInteger head = dateHead != null ? new BigInteger(dateHead) : parts.get(0);
        return new FlywayVersion(raw, Collections.unmodifiableList(parts), head, dateHead != null);
    }

    /** 날짜로 해석 가능하면 yyyyMMddHHmmss 문자열, 아니면 null */
    static String normalizeDate(String digits) {
        String full = switch (digits.length()) {
            case 6 -> "20" + digits + "000000";
            case 8 -> digits + "000000";
            case 12 -> digits + "00";
            case 14 -> digits;
            default -> null;
        };
        if (full == null) return null;
        try {
            LocalDateTime.parse(full, DATE_TIME);
            return full;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Override
    public int compareTo(FlywayVersion o) {
        int c = orderHead.compareTo(o.orderHead);
        if (c != 0) return c;
        c = compareParts(parts, o.parts, 1);
        if (c != 0) return c;
        // 날짜 정규화로 같아진 경우(V250212 vs V20250212)에도 순서를 결정적으로 만든다
        return compareParts(parts, o.parts, 0);
    }

    private static int compareParts(List<BigInteger> a, List<BigInteger> b, int from) {
        int n = Math.max(a.size(), b.size());
        for (int i = from; i < n; i++) {
            BigInteger x = i < a.size() ? a.get(i) : BigInteger.ZERO;
            BigInteger y = i < b.size() ? b.get(i) : BigInteger.ZERO;
            int c = x.compareTo(y);
            if (c != 0) return c;
        }
        return 0;
    }

    /** 파일명에 적힌 그대로의 버전 문자열 */
    public String getRaw() {
        return raw;
    }

    /** Flyway 기준 정규화된 버전 (동일성 판단용) */
    public String getKey() {
        return key;
    }

    public boolean isDateLike() {
        return dateLike;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FlywayVersion v && v.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }
}
