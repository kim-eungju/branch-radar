// Branch Radar - Flyway 관리 화면
(function () {
    'use strict';

    function el(id) { return document.getElementById(id); }

    // ---------------------------------------------------------------- 기준 브랜치 Flyway 목록 검색
    var listSearch = el('fwListSearch');
    if (listSearch) {
        listSearch.addEventListener('input', function () {
            var q = listSearch.value.trim().toLowerCase();
            var shown = 0;
            document.querySelectorAll('tr.fw-row').forEach(function (row) {
                var ok = !q || row.dataset.search.toLowerCase().indexOf(q) >= 0;
                row.classList.toggle('d-none', !ok);
                if (ok) shown++;
            });
            el('fwShown').textContent = shown;
            el('fwNoRows').classList.toggle('d-none', shown > 0);
        });
    }

    // ---------------------------------------------------------------- 작성 중인 Flyway
    var editor = el('fwEditor');
    if (!editor) return;

    var cfg = editor.dataset;
    var sqlInput = el('fwSql');
    var workerSel = el('fwWorker');
    var descInput = el('fwDescription');
    var branchBox = el('fwBranches');
    var saveBtn = el('fwSaveBtn');
    var reviewBtn = el('fwReviewBtn');
    var commitBtn = el('fwCommitBtn');
    var confirmEl = el('fwConfirm');
    var confirmModal = bootstrap.Modal.getOrCreateInstance(confirmEl);

    var revision = Number(cfg.revision) || 0;   // 화면이 마지막으로 본 임시저장
    var dirty = false;                          // 임시저장하지 않은 변경이 있는지
    var busy = false;
    var committed = false;                      // 커밋 결과를 보여 주는 중 (모달을 닫으면 새로고침)
    var canCommit = !reviewBtn.disabled;

    function requestJson(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(function (res) {
            return res.json().catch(function () { return {}; }).then(function (data) {
                if (!res.ok) {
                    var err = new Error(data.error || ('HTTP ' + res.status));
                    err.status = res.status;
                    err.plan = data.plan;
                    throw err;
                }
                return data;
            });
        });
    }

    function selectedBranches() {
        return Array.prototype.map.call(branchBox.querySelectorAll('input:checked'), function (c) { return c.value; });
    }

    function payload() {
        return {
            sql: sqlInput.value,
            description: descInput.value,
            worker: workerSel.value,
            branches: selectedBranches(),
            revision: revision
        };
    }

    function showFormError(msg) {
        var box = el('fwFormError');
        box.textContent = msg || '';
        box.classList.toggle('d-none', !msg);
    }

    function setBusy(on) {
        busy = on;
        saveBtn.disabled = on;
        reviewBtn.disabled = on || !canCommit;
    }

    function markDirty() {
        if (dirty) return;
        dirty = true;
        el('fwSaved').textContent = '저장하지 않은 변경 있음';
    }

    function applyDraft(draft) {
        revision = draft.revision || 0;
        dirty = false;
        el('fwSaved').textContent = draft.exists
            ? '임시저장됨 ' + draft.updatedAt + (draft.worker ? ' · ' + draft.worker : '')
            : '임시저장 없음';
    }

    function visibleBoxes(card) {
        return Array.prototype.filter.call(card.querySelectorAll('.fw-branch'), function (item) {
            return !item.classList.contains('d-none');
        }).map(function (item) { return item.querySelector('input'); });
    }

    // 전체 선택 수와 상태 카드별 선택 수, 카드의 전체 선택/해제 버튼 글자를 맞춘다
    function updateBranchCount() {
        el('fwBranchCount').textContent = selectedBranches().length;
        branchBox.querySelectorAll('.fw-branch-card').forEach(function (card) {
            card.querySelector('.fw-group-selected').textContent = card.querySelectorAll('input:checked').length;
            var toggle = card.querySelector('.fw-group-toggle');
            if (!toggle) return;
            var boxes = visibleBoxes(card);
            var allOn = boxes.length > 0 && boxes.every(function (c) { return c.checked; });
            toggle.textContent = allOn ? '전체 해제' : '전체 선택';
        });
    }

    // ---------------------------------------------------------------- 보기 모드 ↔ 수정 모드
    // 보기: 문법 강조된 읽기 전용 화면. 수정: textarea. 내용이 없으면 볼 것이 없으므로 수정 모드로 시작한다.
    var sqlView = el('fwSqlView');
    var editBtn = el('fwEditBtn');
    var viewBtn = el('fwViewBtn');

    function setMode(editing) {
        if (!editing) window.BranchRadar.renderSql(sqlView, sqlInput.value);
        sqlView.classList.toggle('d-none', editing);
        sqlInput.classList.toggle('d-none', !editing);
        editBtn.classList.toggle('d-none', editing);
        viewBtn.classList.toggle('d-none', !editing);
        el('fwModeLabel').textContent = editing ? '수정 중' : '보기';
        el('fwModeLabel').classList.toggle('chip-accent', editing);
        if (editing) sqlInput.focus();
    }

    editBtn.addEventListener('click', function () { setMode(true); });
    viewBtn.addEventListener('click', function () {
        if (!sqlInput.value.trim()) return;          // 빈 내용은 보기 모드로 갈 이유가 없다
        setMode(false);
    });
    sqlView.addEventListener('dblclick', function () { setMode(true); });

    // ---------------------------------------------------------------- 임시저장
    saveBtn.addEventListener('click', function () {
        if (busy) return;
        showFormError('');
        setBusy(true);
        saveBtn.textContent = '저장 중…';
        requestJson(cfg.apiDraft, payload())
            .then(function (draft) {
                applyDraft(draft);
                setMode(!sqlInput.value.trim());     // 저장한 내용을 강조된 보기로 보여 준다
                if (draft.exists) {
                    toast(true, '임시저장되었습니다',
                        draft.updatedAt + ' · ' + draft.lines + '줄' + (draft.worker ? ' · ' + draft.worker : '')
                        + ' · 대시보드에 작성 중인 Flyway 로 표시됩니다.');
                } else {
                    toast(true, '임시저장을 지웠습니다', 'SQL 과 설명이 모두 비어 있어 작성 중인 Flyway 를 없앴습니다.');
                }
            })
            .catch(function (err) {
                showFormError('임시저장하지 못했습니다: ' + err.message);
                toast(false, '임시저장하지 못했습니다', err.message);
            })
            .finally(function () {
                saveBtn.textContent = '임시저장';
                setBusy(false);
            });
    });

    // 화면 위쪽 가운데에 크게 뜨는 알림. 성공은 잠시 뒤 사라지고, 실패는 눌러서 닫을 때까지 남는다.
    var toastEl = el('fwToast');
    var toastTimer = null;
    function toast(ok, title, text) {
        clearTimeout(toastTimer);
        toastEl.classList.toggle('ok', ok);
        toastEl.classList.toggle('fail', !ok);
        el('fwToastOk').classList.toggle('d-none', !ok);
        el('fwToastFail').classList.toggle('d-none', ok);
        el('fwToastTitle').textContent = title;
        el('fwToastText').textContent = text || '';
        toastEl.classList.add('show');
        if (ok) toastTimer = setTimeout(function () { toastEl.classList.remove('show'); }, 4500);
    }
    toastEl.addEventListener('click', function () {
        clearTimeout(toastTimer);
        toastEl.classList.remove('show');
    });

    // ---------------------------------------------------------------- 검증 결과 표시
    function issue(cls, label, text) {
        var div = document.createElement('div');
        div.className = 'notice ' + cls;
        var strong = document.createElement('b');
        strong.textContent = label;
        var span = document.createElement('span');
        span.textContent = text;
        div.appendChild(strong);
        div.appendChild(span);
        return div;
    }

    function addIssues(box, items, cls, label) {
        (items || []).forEach(function (text) { box.appendChild(issue(cls, label, text)); });
    }

    function branchHeader(name, cls, label) {
        var head = document.createElement('div');
        head.className = 'fw-branch-result';
        var pill = document.createElement('span');
        pill.className = 'branch-pill';
        pill.textContent = name;
        var st = document.createElement('span');
        st.className = 'st ' + cls;
        st.textContent = label;
        head.appendChild(pill);
        head.appendChild(st);
        return head;
    }

    function renderPlan(plan) {
        el('fwConfirmTitle').textContent = '커밋 확인';
        el('fwcPath').textContent = plan.filePath;
        el('fwcSize').textContent = plan.sqlLines + '줄 · ' + plan.sqlBytes.toLocaleString() + ' bytes';
        el('fwcMessage').textContent = plan.commitMessage;
        window.BranchRadar.renderSql(el('fwcSql'), sqlInput.value);
        el('fwcSql').classList.remove('d-none');

        var issues = el('fwcIssues');
        issues.textContent = '';
        addIssues(issues, plan.errors, 'notice-danger', '오류');
        addIssues(issues, plan.warnings, 'notice-warn', '경고');
        addIssues(issues, plan.notes, 'notice-info', '참고');

        var warned = plan.warnings.length > 0;
        var box = el('fwcBranches');
        box.textContent = '';
        plan.branches.forEach(function (b) {
            var blocked = b.errors.length > 0;
            if (b.warnings.length) warned = true;
            box.appendChild(branchHeader(b.branch,
                blocked ? 'st-danger' : b.warnings.length ? 'st-warn' : 'st-ok',
                blocked ? '커밋 불가' : b.warnings.length ? '경고' : '커밋 가능'));
            addIssues(box, b.errors, 'notice-danger', '오류');
            addIssues(box, b.warnings, 'notice-warn', '경고');
        });

        commitBtn.classList.remove('d-none');
        el('fwDashboardBtn').classList.add('d-none');
        commitBtn.disabled = !plan.ok;
        commitBtn.textContent = !plan.ok ? '커밋' : (warned ? '경고를 확인했고 ' : '') + plan.branches.length + '개 브랜치에 커밋';
        el('fwCloseBtn').textContent = '취소';
        el('fwcStatus').textContent = plan.ok
            ? '선택한 브랜치에 바로 커밋됩니다.'
            : '오류가 있어 어느 브랜치에도 커밋하지 않습니다. 고친 뒤 다시 시도하세요.';
    }

    function renderResult(done) {
        var anySucceeded = done.results.some(function (r) { return r.success; });
        // 하나도 커밋되지 않았으면 서버의 임시저장은 그대로다. 화면의 입력 내용을 잃지 않게 새로고침하지 않는다.
        committed = anySucceeded;
        if (anySucceeded) dirty = false;
        el('fwConfirmTitle').textContent = done.allSucceeded ? '커밋 완료'
            : anySucceeded ? '일부 브랜치 커밋 실패' : '커밋 실패';
        el('fwcSql').classList.add('d-none');

        var issues = el('fwcIssues');
        issues.textContent = '';
        if (done.localFile) issues.appendChild(issue('notice-info', '로컬 저장', done.localFile));
        if (done.localError) issues.appendChild(issue('notice-warn', '로컬 저장 실패', done.localError));
        if (!done.allSucceeded && anySucceeded) {
            issues.appendChild(issue('notice-warn', '안내',
                '실패한 브랜치는 임시저장에 남겨 두었습니다. 원인을 해결한 뒤 같은 내용으로 다시 커밋하면 같은 파일명으로 올라갑니다.'));
        }

        var box = el('fwcBranches');
        box.textContent = '';
        done.results.forEach(function (r) {
            var head = branchHeader(r.branch, r.success ? 'st-ok' : 'st-danger', r.success ? '커밋됨' : '실패');
            if (r.success && r.sha) {
                var link = document.createElement('a');
                link.className = 'sha';
                link.target = '_blank';
                link.rel = 'noopener noreferrer';
                link.textContent = r.shortSha || r.sha.substring(0, 8);
                link.href = r.webUrl
                    || (cfg.projectWebUrl ? cfg.projectWebUrl.replace(/\/+$/, '') + '/-/commit/' + r.sha : '#');
                head.appendChild(link);
            }
            box.appendChild(head);
            if (!r.success) box.appendChild(issue('notice-danger', '사유', r.error || ''));
        });

        commitBtn.classList.add('d-none');
        el('fwDashboardBtn').classList.toggle('d-none', !anySucceeded);   // 새로 조회한 대시보드로 바로 이동
        el('fwCloseBtn').textContent = '닫기';
        el('fwcStatus').textContent = anySucceeded ? '닫으면 화면을 새로 불러옵니다.'
            : '어느 브랜치에도 커밋되지 않았습니다. 원인을 해결한 뒤 다시 커밋하세요.';

        // 커밋 결과를 크게 알린다 (모달 위에 뜬다)
        var okNames = done.results.filter(function (r) { return r.success; }).map(function (r) { return r.branch; });
        var failNames = done.results.filter(function (r) { return !r.success; }).map(function (r) { return r.branch; });
        if (done.allSucceeded) {
            toast(true, '커밋되었습니다', done.fileName + ' → ' + okNames.join(', '));
        } else if (okNames.length) {
            toast(false, '일부 브랜치만 커밋되었습니다',
                done.fileName + ' · 성공: ' + okNames.join(', ') + ' · 실패: ' + failNames.join(', '));
        } else {
            toast(false, '커밋되지 않았습니다', '실패: ' + failNames.join(', ') + ' · 사유는 아래 목록을 확인하세요.');
        }
    }

    // ---------------------------------------------------------------- 검증 → 확인 → 커밋
    reviewBtn.addEventListener('click', function () {
        if (busy) return;
        showFormError('');
        setBusy(true);
        requestJson(cfg.apiValidate, payload())
            .then(function (plan) {
                renderPlan(plan);
                confirmModal.show();
            })
            .catch(function (err) { showFormError('검증하지 못했습니다: ' + err.message); })
            .finally(function () { setBusy(false); });
    });

    commitBtn.addEventListener('click', function () {
        if (busy) return;
        setBusy(true);
        commitBtn.disabled = true;
        el('fwcStatus').textContent = '커밋하는 중…';
        requestJson(cfg.apiCommit, payload())
            .then(renderResult)
            .catch(function (err) {
                if (err.plan) {
                    renderPlan(err.plan);          // 그 사이 브랜치가 바뀌어 검증에 걸린 경우
                    return;
                }
                var issues = el('fwcIssues');
                issues.textContent = '';
                issues.appendChild(issue('notice-danger', '커밋하지 않음', err.message));
                el('fwcStatus').textContent = '커밋되지 않았습니다.';
                commitBtn.disabled = err.status === 409;   // 다른 사람이 먼저 저장한 경우: 새로고침이 필요
            })
            .finally(function () { setBusy(false); });
    });

    confirmEl.addEventListener('hidden.bs.modal', function () {
        if (committed) location.reload();
    });

    // ---------------------------------------------------------------- 입력 이벤트
    sqlInput.addEventListener('input', markDirty);
    descInput.addEventListener('input', markDirty);
    branchBox.addEventListener('change', function () {
        markDirty();
        updateBranchCount();
    });

    // ---------------------------------------------------------------- 커밋할 브랜치 검색
    var branchFilter = el('fwBranchFilter');

    // 이름에서 검색어와 일치한 부분을 mark 로 표시한다 (textContent/TextNode 로만 넣는다)
    function paintName(span, name, q) {
        span.textContent = '';
        var at = q ? name.toLowerCase().indexOf(q) : -1;
        if (at < 0) { span.textContent = name; return; }
        var mark = document.createElement('mark');
        mark.textContent = name.substring(at, at + q.length);
        span.appendChild(document.createTextNode(name.substring(0, at)));
        span.appendChild(mark);
        span.appendChild(document.createTextNode(name.substring(at + q.length)));
    }

    function applyBranchFilter() {
        var q = branchFilter.value.trim().toLowerCase();
        var matched = 0;
        branchBox.querySelectorAll('.fw-branch-card').forEach(function (card) {
            var visible = 0;
            card.querySelectorAll('.fw-branch').forEach(function (item) {
                var name = item.dataset.name;
                var hit = !q || name.toLowerCase().indexOf(q) >= 0;
                // 선택된 브랜치는 검색과 무관하게 계속 보여 준다 (선택한 것을 놓치지 않게)
                var ok = hit || item.querySelector('input').checked;
                item.classList.toggle('d-none', !ok);
                paintName(item.querySelector('.mono'), name, q);
                if (ok) visible++;
                if (q && hit) matched++;
            });
            var none = card.querySelector('.fw-branch-nomatch');
            if (none) none.classList.toggle('d-none', !q || visible > 0 || !card.querySelector('.fw-branch'));
        });
        var label = el('fwBranchMatch');
        label.textContent = matched + '개 일치';
        label.classList.toggle('d-none', !q);
        el('fwBranchFilterClear').classList.toggle('d-none', !q);
        updateBranchCount();
    }

    branchFilter.addEventListener('input', applyBranchFilter);
    branchFilter.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && branchFilter.value) {
            branchFilter.value = '';
            applyBranchFilter();
        }
    });
    el('fwBranchFilterClear').addEventListener('click', function () {
        branchFilter.value = '';
        applyBranchFilter();
        branchFilter.focus();
    });

    // 카드의 전체 선택/해제: 검색으로 보이는 브랜치에만 적용한다
    branchBox.addEventListener('click', function (e) {
        var toggle = e.target.closest('.fw-group-toggle');
        if (!toggle) return;
        var boxes = visibleBoxes(toggle.closest('.fw-branch-card'));
        var turnOn = !boxes.every(function (c) { return c.checked; });
        boxes.forEach(function (c) { c.checked = turnOn; });
        markDirty();
        updateBranchCount();
    });

    // Tab 키로 들여쓰기 (포커스 이동 대신). Esc 를 누른 직후의 Tab 은 원래대로 포커스를 옮긴다.
    var escaped = false;
    sqlInput.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') { escaped = true; return; }
        if (e.key === 'Tab' && !e.shiftKey && !e.ctrlKey && !e.altKey && !escaped) {
            e.preventDefault();
            sqlInput.setRangeText('    ', sqlInput.selectionStart, sqlInput.selectionEnd, 'end');
            markDirty();
        }
        escaped = false;
    });

    // 등록자: 이 브라우저에서 마지막으로 고른 사람을 기억한다 (없으면 임시저장한 사람)
    var remembered = '';
    try { remembered = localStorage.getItem('br.worker') || ''; } catch (e) { /* ignore */ }
    workerSel.value = remembered || workerSel.dataset.saved || '';
    if (workerSel.selectedIndex < 0) workerSel.value = '';
    workerSel.addEventListener('change', function () {
        try { localStorage.setItem('br.worker', workerSel.value); } catch (e) { /* ignore */ }
    });

    window.addEventListener('beforeunload', function (e) {
        if (dirty) {
            e.preventDefault();
            e.returnValue = '';
        }
    });

    updateBranchCount();
    setMode(!sqlInput.value.trim());
    if (document.activeElement === sqlInput) sqlInput.blur();   // 처음 열 때는 포커스를 가져가지 않는다
})();
