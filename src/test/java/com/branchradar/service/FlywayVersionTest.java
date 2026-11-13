package com.branchradar.service;

import com.branchradar.dto.FlywayVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayVersionTest {

    private static FlywayVersion v(String raw) {
        return FlywayVersion.parse(raw, true);
    }

    @Test
    void 파일명에서_버전과_설명을_추출한다() {
        var m = com.branchradar.TestData.FLYWAY.parse("db/migration/V250212.001__VADA.sql", "b1");
        assertThat(m.version().getRaw()).isEqualTo("250212.001");
        assertThat(m.version().getKey()).isEqualTo("250212.1");
        assertThat(m.description()).isEqualTo("VADA");
        assertThat(m.fileName()).isEqualTo("V250212.001__VADA.sql");
    }

    @Test
    void 언더스코어는_점으로_취급한다() {
        assertThat(v("20261105_143000")).isEqualTo(v("20261105.143000"));
    }

    @Test
    void 끝의_0은_무시한다_Flyway와_동일() {
        assertThat(v("1.0")).isEqualTo(v("1"));
        assertThat(v("250212.001")).isEqualTo(v("250212.1"));
        assertThat(v("1.0").compareTo(v("1"))).isZero();
    }

    @Test
    void 사전순이_아니라_숫자로_정렬한다() {
        List<FlywayVersion> list = new ArrayList<>(Stream.of("10", "2", "1.10", "1.9", "250212.010", "250212.002")
                .map(FlywayVersionTest::v).toList());
        list.sort(null);
        assertThat(list).extracting(FlywayVersion::getRaw)
                .containsExactly("1.9", "1.10", "2", "10", "250212.002", "250212.010");
    }

    @Test
    void 날짜형식이_섞여도_날짜_의미로_정렬한다() {
        // 숫자로만 보면 20250101 > 251231 이지만, 날짜로는 2025-12-31 이 더 최신
        assertThat(v("251231.001")).isGreaterThan(v("20250101.001"));
        assertThat(v("20261105_143000")).isGreaterThan(v("261105"));
        // 날짜가 아닌 버전은 그대로 숫자 비교
        assertThat(v("3")).isGreaterThan(v("2"));
        assertThat(v("250212").isDateLike()).isTrue();
        assertThat(v("999999").isDateLike()).isFalse();
    }

    @Test
    void 날짜_정렬을_끄면_Flyway_숫자_비교와_같다() {
        assertThat(FlywayVersion.parse("20250101", false)).isGreaterThan(FlywayVersion.parse("251231", false));
    }

    @Test
    void 숫자가_아닌_버전은_거부한다() {
        assertThatThrownBy(() -> v("1a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v("1..2")).isInstanceOf(IllegalArgumentException.class);
    }
}
