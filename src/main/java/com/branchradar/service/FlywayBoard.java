package com.branchradar.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 이번 묶음: 다음 Flyway 파일에 들어갈 조각 목록 (로컬 폴더의 .board.json).
 * <p>
 * 작업자마다 SQL 을 조각 하나로 올리고, 커밋할 때 남아 있는 조각을 순서대로 합쳐 Flyway 파일 하나로 만든다.
 * 조각을 삭제해도 지우지 않고 deleted 로 표시하며, 등록·수정·삭제·복구·순서 변경은 모두 log 에 남긴다.
 * 커밋이 끝나면 history 폴더에 그대로 보관한다.
 * <p>
 * JSON 으로 그대로 저장하므로 필드는 public 으로 둔다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlywayBoard {

    /** 묶음이 바뀔 때마다 커지는 값. 커밋할 때 화면이 최신 조각 목록을 보고 있었는지 확인한다 */
    public long revision;
    public long nextId = 1;

    /** 일부 브랜치에만 커밋된 경우: 고정된 버전과 파일 내용. 남은 브랜치에 같은 파일을 올리기 위해 조각 수정을 막는다 */
    public String pinnedVersion;
    public String pinnedContent;
    /** 아직 커밋하지 못한 브랜치 (pinnedVersion 이 있을 때만) */
    public List<String> pendingBranches = new ArrayList<>();
    /** 이미 커밋된 브랜치 (pinnedVersion 이 있을 때만) */
    public List<String> committedBranches = new ArrayList<>();

    /** 등록 순서가 아니라 화면에서 정한 순서 = 파일 안의 실행 순서 */
    public List<Post> posts = new ArrayList<>();
    /** 오래된 것부터 */
    public List<LogEntry> log = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Post {
        public long id;
        public String author;
        public String description;
        public String sql;
        public String createdAt;
        public String updatedAt;
        public String updatedBy;
        public boolean deleted;
        public String deletedAt;
        public String deletedBy;
        /** 조각이 바뀔 때마다 커지는 값. 다른 사람의 수정을 모르고 덮어쓰지 않게 한다 */
        public long revision;

        @JsonIgnore
        public int getLines() {
            return sql == null || sql.isBlank() ? 0 : (int) sql.strip().lines().count();
        }

        /**
         * 끝의 공백과 줄 주석(--)을 빼고 ; 로 끝나는지 (합친 파일에서 다음 조각의 SQL 과 붙지 않게).
         * 따옴표 안의 -- 까지 구분하지는 않는 간단한 확인이라 경고로만 쓴다.
         */
        @JsonIgnore
        public boolean isTerminated() {
            if (sql == null) return false;
            List<String> lines = sql.strip().lines()
                    .map(l -> l.replaceFirst("--.*$", "").strip())
                    .filter(l -> !l.isEmpty()).toList();
            return !lines.isEmpty() && lines.get(lines.size() - 1).endsWith(";");
        }

        @JsonIgnore
        public boolean isEdited() {
            return updatedAt != null
                    && !(updatedAt.equals(createdAt) && java.util.Objects.equals(author, updatedBy));
        }
    }

    /**
     * 변경 이력 한 줄.
     *
     * @see Action
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LogEntry {
        public String at;
        public String who;
        public Action action;
        /** 조각과 관계없는 기록(커밋 등)은 0 */
        public long postId;
        public String detail;
        /** 수정·삭제 직전의 내용 (나중에 확인할 수 있게) */
        public String beforeDescription;
        public String beforeSql;

        @JsonIgnore
        public boolean hasBefore() {
            return beforeSql != null;
        }
    }

    public enum Action {
        CREATE("등록"), UPDATE("수정"), DELETE("삭제"), RESTORE("복구"), MOVE("순서 변경"),
        PARTIAL_COMMIT("일부 커밋"), COMMIT("커밋"), CLOSE("마감"), MIGRATE("옮김");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    // ---------------------------------------------------------------- 조회 도우미

    @JsonIgnore
    public List<Post> getActive() {
        return posts.stream().filter(p -> !p.deleted).toList();
    }

    @JsonIgnore
    public List<Post> getDeleted() {
        return posts.stream().filter(p -> p.deleted).toList();
    }

    /** 최신 기록부터 */
    @JsonIgnore
    public List<LogEntry> getRecentLog() {
        List<LogEntry> copy = new ArrayList<>(log);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** 남아 있는 조각의 등록자 (순서대로, 중복 없이) */
    @JsonIgnore
    public List<String> getAuthors() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        getActive().forEach(p -> set.add(p.author));
        return List.copyOf(set);
    }

    @JsonIgnore
    public boolean isLocked() {
        return pinnedVersion != null;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return getActive().isEmpty() && !isLocked();
    }

    @JsonIgnore
    public LogEntry getLastLog() {
        return log.isEmpty() ? null : log.get(log.size() - 1);
    }

    Post find(long id) {
        return posts.stream().filter(p -> p.id == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("조각 #" + id + " 을(를) 찾을 수 없습니다."));
    }

    /**
     * 커밋이 끝난 묶음 (history 폴더). 묶음 내용과 커밋 결과를 함께 보관한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Archive {
        public String fileName;
        public String filePath;
        public String closedAt;
        public String closedBy;
        /** 커밋된 브랜치 */
        public List<String> committedBranches = new ArrayList<>();
        /** 마감할 때 커밋하지 않고 남긴 브랜치 */
        public List<String> abandonedBranches = new ArrayList<>();
        /** 커밋한 파일 내용 그대로 */
        public String content;
        public FlywayBoard board;
    }
}
