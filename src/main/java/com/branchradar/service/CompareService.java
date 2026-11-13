package com.branchradar.service;

import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.dto.BranchSnapshot;
import com.branchradar.dto.CompareResult;
import com.branchradar.dto.CompareResult.CommitCompare;
import com.branchradar.dto.CompareResult.CommitItem;
import com.branchradar.dto.CompareResult.Conflict;
import com.branchradar.dto.CompareResult.FlywayCompare;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A/B 브랜치 비교.
 */
@Service
public class CompareService {

    /** 충돌 후보마다 원문 2개를 받아 checksum 을 계산한다. 과도한 호출을 막기 위한 상한 */
    private static final int MAX_CHECKSUM_CONFLICTS = 20;
    private static final Pattern CHERRY_PICK = Pattern.compile("cherry picked from commit ([0-9a-f]{7,40})");

    private final GitLabClient client;
    private final FlywayService flywayService;
    private final BranchService branchService;

    public CompareService(GitLabClient client, FlywayService flywayService, BranchService branchService) {
        this.client = client;
        this.flywayService = flywayService;
        this.branchService = branchService;
    }

    /** 브랜치 조회 실패 시 GitLabException. 커밋 비교만 실패하면 Flyway 결과는 그대로 보여준다. */
    public CompareResult compare(String branchA, String branchB) {
        GitLabClient.Branch ga = client.getBranch(branchA);
        GitLabClient.Branch gb = client.getBranch(branchB);
        BranchSnapshot a = branchService.snapshot(ga, flywayService.loadMigrations(ga.commit().id()));
        BranchSnapshot b = branchService.snapshot(gb, flywayService.loadMigrations(gb.commit().id()));

        FlywayCompare flyway = flywayService.compare(a, b);
        flyway = withChecksums(flyway, a, b);
        return new CompareResult(a, b, flyway, compareCommits(a, b));
    }

    /**
     * 내용이 다른 충돌 후보에 대해 Flyway 방식 checksum 을 계산한다.
     * 같은 값이면 줄바꿈/BOM 차이일 뿐 Flyway 가 보기에는 같은 내용이다.
     */
    private FlywayCompare withChecksums(FlywayCompare f, BranchSnapshot a, BranchSnapshot b) {
        List<Conflict> enriched = new ArrayList<>();
        int done = 0;
        for (Conflict c : f.conflicts()) {
            if (Objects.equals(c.a().blobId(), c.b().blobId())) {
                enriched.add(c.withChecksums(null, null, "SQL 내용(blob) 동일"));
                continue;
            }
            if (done >= MAX_CHECKSUM_CONFLICTS) {
                enriched.add(c.withChecksums(null, null, "충돌 후보가 많아 checksum 계산 생략"));
                continue;
            }
            done++;
            try {
                int ca = FlywayService.flywayChecksum(client.getRawFile(c.a().path(), a.commitSha()));
                int cb = FlywayService.flywayChecksum(client.getRawFile(c.b().path(), b.commitSha()));
                String note = ca == cb
                        ? "Flyway checksum 동일 — 줄바꿈/BOM 만 다름 (Flyway 는 같은 내용으로 봄)"
                        : "Flyway checksum 다름 — 한쪽이 이미 적용된 DB에서 다른 쪽을 배포하면 validate 실패 가능";
                enriched.add(c.withChecksums(ca, cb, note));
            } catch (GitLabException e) {
                enriched.add(c.withChecksums(null, null, "원문 조회 실패: " + e.getMessage()));
            }
        }
        return new FlywayCompare(f.onlyInA(), f.onlyInB(), f.same(), enriched,
                f.duplicatesInA(), f.duplicatesInB(), f.repeatableDiffs());
    }

    CommitCompare compareCommits(BranchSnapshot a, BranchSnapshot b) {
        try {
            // A 에 있고 B 에 없는 커밋 = git log B..A = Compare(from=B, to=A)
            GitLabClient.CompareResponse aNotB = client.compare(b.commitSha(), a.commitSha());
            // B 에 있고 A 에 없는 커밋 = git log A..B = Compare(from=A, to=B)
            GitLabClient.CompareResponse bNotA = client.compare(a.commitSha(), b.commitSha());
            List<GitLabClient.Commit> ca = nullSafe(aNotB.commits());
            List<GitLabClient.Commit> cb = nullSafe(bNotA.commits());
            return new CommitCompare(toItems(ca, cb), toItems(cb, ca),
                    aNotB.compareTimeout() || bNotA.compareTimeout(), null, aNotB.webUrl());
        } catch (GitLabException e) {
            return new CommitCompare(List.of(), List.of(), true, e.getMessage(), null);
        }
    }

    /**
     * 커밋 목록을 화면용으로 변환하고 cherry-pick 가능성 힌트를 붙인다.
     * 힌트는 판정이 아니다. 패치 동등성(patch-id)은 검증하지 않으므로 모든 항목은 "이력상 누락 후보" 이다.
     */
    static List<CommitItem> toItems(List<GitLabClient.Commit> mine, List<GitLabClient.Commit> other) {
        List<CommitItem> items = new ArrayList<>();
        for (GitLabClient.Commit c : mine) {
            List<String> hints = new ArrayList<>();
            boolean merge = c.parentIds() != null && c.parentIds().size() > 1;
            for (GitLabClient.Commit o : other) {
                String om = o.message() == null ? "" : o.message();
                Matcher m = CHERRY_PICK.matcher(om);
                while (m.find()) {
                    if (c.id().startsWith(m.group(1))) {
                        hints.add("상대 브랜치의 " + o.shortId() + " 에 이 커밋의 cherry-pick 기록(-x)이 있음 → 반영되었을 가능성 높음");
                    }
                }
                String cm = c.message() == null ? "" : c.message();
                Matcher m2 = CHERRY_PICK.matcher(cm);
                while (m2.find()) {
                    if (o.id().startsWith(m2.group(1))) {
                        hints.add("이 커밋은 상대 브랜치의 " + o.shortId() + " 를 cherry-pick 한 것");
                    }
                }
                if (hints.isEmpty() && !merge && Objects.equals(c.title(), o.title())
                        && Objects.equals(c.authorEmail(), o.authorEmail())) {
                    hints.add("상대 브랜치에 제목·작성자가 같은 커밋 " + o.shortId() + " 존재 (cherry-pick 가능성, 미검증)");
                }
            }
            items.add(new CommitItem(c.id(), c.shortId(), c.title(), c.authorName(), c.committedDate(),
                    c.webUrl(), merge, hints.isEmpty() ? null : String.join(" / ", hints.stream().distinct().toList())));
        }
        items.sort(Comparator.comparing(CommitItem::date, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
