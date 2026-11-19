// Branch Radar - Vanilla JS
(function () {
    'use strict';

    function el(id) { return document.getElementById(id); }

    // ---------------------------------------------------------------- 페이지 이동 시 상단 로딩 바
    // 대시보드는 GitLab 을 여러 번 호출하므로 몇 초 걸릴 수 있다
    var progress = el('topProgress');
    function startProgress() { if (progress) progress.classList.add('on'); }
    window.addEventListener('pageshow', function (e) {
        // 뒤로 가기로 브라우저가 보관해 둔 예전 화면이 복원되면 다시 조회한다 (커밋 직후 대시보드가 옛 상태로 보이지 않게)
        if (e.persisted) { location.reload(); return; }
        if (progress) progress.classList.remove('on');
    });

    document.addEventListener('click', function (e) {
        var a = e.target.closest('a[href]');
        if (!a || a.target === '_blank' || a.getAttribute('href').charAt(0) === '#') return;
        if (e.ctrlKey || e.metaKey || e.shiftKey || e.button !== 0) return;
        if (a.origin === location.origin) startProgress();
    });
    document.addEventListener('submit', startProgress);

    // ---------------------------------------------------------------- SQL 미리보기 모달
    var modalEl = el('sqlModal');
    var currentSql = '';
    var MAX_NUMBERED_LINES = 20000;

    var MAX_HIGHLIGHT_CHARS = 400000;

    // ---- SQL 문법 강조 (외부 라이브러리 없이, 사내망 오프라인에서도 동작)
    // 순서: 주석 | 문자열 | 따옴표 식별자 | 숫자 | 단어. 주석과 문자열은 여러 줄에 걸칠 수 있다.
    var SQL_TOKEN = /(--[^\n]*|\/\*[\s\S]*?(?:\*\/|$))|('(?:[^'\\]|\\[\s\S]|'')*'?)|("(?:[^"]|"")*"?|`[^`]*`?)|(\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|([A-Za-z_][A-Za-z0-9_$]*)/g;

    function wordSet(words) {
        var set = Object.create(null);
        words.split(' ').forEach(function (w) { set[w] = true; });
        return set;
    }

    var SQL_KEYWORDS = wordSet('ADD AFTER ALL ALTER ANALYZE AND ANY AS ASC AUTO_INCREMENT BEFORE BEGIN BETWEEN BY CALL CASCADE CASE ' +
        'CHARACTER CHARSET CHECK COLLATE COLUMN COMMENT COMMIT CONSTRAINT CREATE CROSS CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP ' +
        'CURRENT_USER DATABASE DECLARE DEFAULT DEFERRABLE DELETE DELIMITER DESC DISTINCT DO DROP EACH ELSE ELSIF END ENGINE EXCEPT ' +
        'EXEC EXECUTE EXISTS EXPLAIN FALSE FETCH FIRST FOR FOREIGN FROM FULL FUNCTION GENERATED GRANT GROUP HAVING IDENTITY IF ' +
        'IGNORE IN INDEX INNER INSERT INTERSECT INTO IS JOIN KEY LANGUAGE LAST LEFT LIKE LIMIT LOCK LOOP MATERIALIZED MERGE MODIFY ' +
        'NATURAL NEXT NO NOT NULL NULLS OF OFFSET ON ONLY OR ORDER OUTER OVER OWNER PARTITION PRIMARY PROCEDURE REFERENCES RENAME ' +
        'REPLACE RESTRICT RETURN RETURNING RETURNS REVOKE RIGHT ROLLBACK ROW ROWS SCHEMA SELECT SEQUENCE SET SHOW START TABLE ' +
        'TABLESPACE TEMPORARY THEN TO TOP TRANSACTION TRIGGER TRUE TRUNCATE UNION UNIQUE UNSIGNED UPDATE USE USING VALUES VIEW ' +
        'WHEN WHERE WHILE WINDOW WITH WITHOUT');
    var SQL_TYPES = wordSet('BIGINT BIGSERIAL BINARY BIT BLOB BOOL BOOLEAN BYTEA CHAR CLOB DATE DATETIME DATETIME2 DECIMAL DOUBLE ENUM ' +
        'FLOAT INT INT2 INT4 INT8 INTEGER INTERVAL JSON JSONB LONGBLOB LONGTEXT MEDIUMINT MEDIUMTEXT MONEY NCHAR NUMBER NUMERIC ' +
        'NVARCHAR NVARCHAR2 PRECISION REAL SERIAL SMALLINT TEXT TIME TIMESTAMP TIMESTAMPTZ TINYINT TINYTEXT UUID VARBINARY ' +
        'VARCHAR VARCHAR2 VARYING XML YEAR');

    /** @return [{cls, text}] — cls 가 없으면 일반 텍스트 */
    function tokenizeSql(text) {
        var tokens = [];
        var last = 0;
        var m;
        SQL_TOKEN.lastIndex = 0;
        while ((m = SQL_TOKEN.exec(text)) !== null) {
            if (m[0] === '') { SQL_TOKEN.lastIndex++; continue; }
            var cls = null;
            if (m[1]) cls = 'sql-com';
            else if (m[2]) cls = 'sql-str';
            else if (m[3]) cls = 'sql-id';
            else if (m[4]) cls = 'sql-num';
            else {
                var word = m[5].toUpperCase();
                if (SQL_KEYWORDS[word]) cls = 'sql-kw';
                else if (SQL_TYPES[word]) cls = 'sql-ty';
            }
            if (!cls) continue;                       // 일반 단어는 앞뒤 텍스트와 함께 그대로 둔다
            if (m.index > last) tokens.push({ cls: null, text: text.substring(last, m.index) });
            tokens.push({ cls: cls, text: m[0] });
            last = m.index + m[0].length;
        }
        if (last < text.length) tokens.push({ cls: null, text: text.substring(last) });
        return tokens;
    }

    /** pre 요소에 SQL 을 줄 번호 + 문법 강조로 그린다. 다른 화면 스크립트도 window.BranchRadar.renderSql 로 쓴다. */
    function renderSql(pre, text) {
        pre.textContent = '';
        var normalized = text.replace(/\r\n?/g, '\n');
        if (normalized.charAt(normalized.length - 1) === '\n') normalized = normalized.slice(0, -1);
        var lineCount = normalized.split('\n').length;
        if (lineCount > MAX_NUMBERED_LINES) {
            pre.classList.add('plain');
            pre.textContent = text;   // 너무 길면 줄 번호 없이
            return;
        }
        pre.classList.remove('plain');

        var tokens = normalized.length > MAX_HIGHLIGHT_CHARS
            ? [{ cls: null, text: normalized }]       // 아주 큰 파일은 강조 없이 줄 번호만
            : tokenizeSql(normalized);

        // 토큰을 줄 단위로 나눠 담는다. 모든 내용은 textContent/TextNode 로만 넣는다 (XSS 방지)
        var frag = document.createDocumentFragment();
        var line = null;
        function newLine() {
            if (line && !line.hasChildNodes()) line.textContent = ' ';   // 빈 줄도 높이를 갖게
            line = document.createElement('span');
            line.className = 'ln';
            frag.appendChild(line);
        }
        newLine();
        tokens.forEach(function (tok) {
            tok.text.split('\n').forEach(function (part, i) {
                if (i > 0) newLine();
                if (!part) return;
                if (tok.cls) {
                    var span = document.createElement('span');
                    span.className = tok.cls;
                    span.textContent = part;
                    line.appendChild(span);
                } else {
                    line.appendChild(document.createTextNode(part));
                }
            });
        });
        if (!line.hasChildNodes()) line.textContent = ' ';
        pre.appendChild(frag);
    }
    window.BranchRadar = { renderSql: renderSql };

    // 이번 묶음 조각의 SQL 을 문법 강조로 다시 그린다 (서버가 th:text 로 넣은 내용을 그대로 사용)
    document.querySelectorAll('pre.fw-post-sql').forEach(function (pre) {
        renderSql(pre, pre.textContent);
    });

    function openSql(link) {
        var ref = link.dataset.ref;
        var path = link.dataset.path;
        currentSql = '';
        el('sqlModalTitle').textContent = link.dataset.file || path;
        el('sqlModalMeta').textContent = path + ' @ ' + (ref || '').substring(0, 12);
        el('sqlModalGitLab').href = link.dataset.webUrl || '#';
        el('sqlModalGitLab').classList.remove('d-none');
        el('sqlModalError').classList.add('d-none');
        el('sqlModalContent').classList.add('d-none');
        el('sqlModalContent').textContent = '';
        el('sqlModalLoading').classList.remove('d-none');
        el('sqlCopyBtn').disabled = true;
        el('sqlCopyStatus').textContent = '';
        bootstrap.Modal.getOrCreateInstance(modalEl).show();

        var params = new URLSearchParams({ ref: ref, path: path });
        fetch((modalEl.dataset.api || '/api/sql') + '?' + params.toString(), { headers: { 'Accept': 'application/json' } })
            .then(function (res) {
                return res.json().catch(function () { return {}; }).then(function (body) {
                    if (!res.ok) throw new Error(body.error || ('HTTP ' + res.status));
                    return body;
                });
            })
            .then(function (body) {
                currentSql = body.content || '';
                if (!currentSql) {
                    showError('빈 파일입니다.');
                } else {
                    renderSql(el('sqlModalContent'), currentSql);
                    el('sqlModalContent').classList.remove('d-none');
                }
                el('sqlCopyBtn').disabled = !currentSql;
                if (body.truncated) {
                    showError('파일이 커서 앞부분(2MB)만 표시합니다. 전체 내용은 GitLab 원본에서 확인하세요.');
                }
            })
            .catch(function (err) {
                showError('SQL 을 불러오지 못했습니다: ' + err.message);
            })
            .finally(function () {
                el('sqlModalLoading').classList.add('d-none');
            });
    }

    // 페이지 안에 이미 있는 SQL(예: 작성 중인 Flyway)을 같은 모달로 띄운다. GitLab 원본 링크는 없음
    function openLocalSql(btn) {
        var source = el(btn.dataset.source);
        currentSql = source ? source.textContent : '';
        el('sqlModalTitle').textContent = btn.dataset.file || 'SQL';
        el('sqlModalMeta').textContent = btn.dataset.meta || '';
        el('sqlModalGitLab').classList.add('d-none');
        el('sqlModalError').classList.add('d-none');
        el('sqlModalLoading').classList.add('d-none');
        el('sqlCopyStatus').textContent = '';
        if (currentSql) {
            renderSql(el('sqlModalContent'), currentSql);
            el('sqlModalContent').classList.remove('d-none');
        } else {
            el('sqlModalContent').classList.add('d-none');
            showError('내용이 없습니다.');
        }
        el('sqlCopyBtn').disabled = !currentSql;
        bootstrap.Modal.getOrCreateInstance(modalEl).show();
    }

    function showError(msg) {
        var box = el('sqlModalError');
        box.textContent = msg;
        box.classList.remove('d-none');
    }

    function copyText(text) {
        // http(비보안 컨텍스트)에서는 navigator.clipboard 가 없을 수 있어 fallback 사용
        if (navigator.clipboard && window.isSecureContext) {
            return navigator.clipboard.writeText(text);
        }
        return new Promise(function (resolve, reject) {
            var ta = document.createElement('textarea');
            ta.value = text;
            ta.setAttribute('readonly', '');
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            try {
                document.execCommand('copy') ? resolve() : reject(new Error('copy failed'));
            } catch (e) {
                reject(e);
            } finally {
                document.body.removeChild(ta);
            }
        });
    }

    if (modalEl) {
        document.addEventListener('click', function (e) {
            var local = e.target.closest('.sql-local');
            if (local) {
                e.preventDefault();
                openLocalSql(local);
                return;
            }
            var link = e.target.closest('.sql-link');
            if (!link) return;
            e.preventDefault();
            openSql(link);
        });
        el('sqlCopyBtn').addEventListener('click', function () {
            copyText(currentSql).then(function () {
                el('sqlCopyStatus').textContent = '클립보드에 복사했습니다.';
            }, function () {
                el('sqlCopyStatus').textContent = '복사에 실패했습니다. 직접 선택해서 복사하세요.';
            });
        });
    }

    // ---------------------------------------------------------------- 대시보드 검색/필터
    var search = el('branchSearch');
    var statusFilter = el('statusFilter');
    var activityFilter = el('activityFilter');

    function matchActivity(row, af) {
        var act = row.dataset.activity;
        if (!af) return true;
        if (af === 'STALE_PLUS') return act === 'STALE' || act === 'VERY_STALE';
        return act === af;
    }

    function syncTiles() {
        document.querySelectorAll('[data-filter-target]').forEach(function (tile) {
            var select = el(tile.dataset.filterTarget);
            tile.classList.toggle('selected', !!select && select.value === tile.dataset.filterValue);
        });
    }

    function applyFilter() {
        var q = (search.value || '').trim().toLowerCase();
        var st = statusFilter.value;
        var af = activityFilter ? activityFilter.value : '';
        var shown = 0;
        document.querySelectorAll('tr.branch-row').forEach(function (row) {
            var okName = !q || row.dataset.name.toLowerCase().indexOf(q) >= 0;
            var okStatus = !st || (st === 'WARNING'
                ? (row.dataset.status === 'BEHIND' || row.dataset.status === 'CONFLICT')
                : row.dataset.status === st);
            var visible = okName && okStatus && matchActivity(row, af);
            row.classList.toggle('d-none', !visible);
            if (visible) shown++;
        });
        var counter = el('shownCount');
        if (counter) counter.textContent = shown;
        var noRows = el('noRows');
        if (noRows) noRows.classList.toggle('d-none', shown > 0);
        syncTiles();
    }

    if (search && statusFilter) {
        search.addEventListener('input', applyFilter);
        statusFilter.addEventListener('change', applyFilter);
        if (activityFilter) activityFilter.addEventListener('change', applyFilter);

        // 요약 타일 클릭 → 해당 상태만 표시 (다시 누르면 해제)
        document.querySelectorAll('[data-filter-target], [data-filter-reset]').forEach(function (tile) {
            function activate() {
                if (tile.hasAttribute('data-filter-reset')) {
                    search.value = '';
                    statusFilter.value = '';
                    if (activityFilter) activityFilter.value = '';
                } else {
                    var select = el(tile.dataset.filterTarget);
                    var v = tile.dataset.filterValue;
                    select.value = select.value === v ? '' : v;
                }
                applyFilter();
            }
            tile.addEventListener('click', activate);
            tile.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); activate(); }
            });
        });
    }

    // ---------------------------------------------------------------- 경과 일수 내림차순 정렬
    // 확인 불가(-1)는 맨 뒤. 다시 누르면 원래 순서로.
    var sortBtn = el('sortDaysBtn');
    var firstRow = document.querySelector('tr.branch-row');
    if (sortBtn && firstRow) {
        var tbody = firstRow.parentNode;
        var original = Array.prototype.slice.call(tbody.children);
        var sorted = false;
        sortBtn.addEventListener('click', function () {
            var rows = original.slice();
            if (!sorted) {
                rows.sort(function (a, b) {
                    return parseInt(b.dataset.days, 10) - parseInt(a.dataset.days, 10);
                });
            }
            rows.forEach(function (r) { tbody.appendChild(r); });
            sorted = !sorted;
            sortBtn.classList.toggle('on', sorted);
            sortBtn.title = sorted ? '원래 순서로' : '경과 일수 내림차순 정렬';
        });
    }

    // ---------------------------------------------------------------- 검색되는 select
    // select[data-searchable] 을 "누르면 검색칸 + 목록이 열리는" 선택 상자로 바꾼다.
    // 원래 select 는 숨겨서 그대로 두므로 form 전송과 값 변경(select._brRefresh())이 그대로 동작한다.
    function enhanceSelect(select) {
        var wrap = document.createElement('div');
        wrap.className = 'sselect';
        select.parentNode.insertBefore(wrap, select);
        wrap.appendChild(select);

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'form-select sselect-btn';
        if (select.id) {
            // label 을 누르면 이 버튼으로 오게 한다
            btn.id = select.id + 'Btn';
            var label = document.querySelector('label[for="' + select.id + '"]');
            if (label) label.htmlFor = btn.id;
        }
        btn.setAttribute('aria-haspopup', 'listbox');
        btn.setAttribute('aria-expanded', 'false');
        var valueEl = document.createElement('span');
        valueEl.className = 'sselect-value';
        btn.appendChild(valueEl);
        wrap.appendChild(btn);

        var pop = document.createElement('div');
        pop.className = 'sselect-pop';
        pop.hidden = true;
        var input = document.createElement('input');
        input.type = 'search';
        input.className = 'form-control form-control-sm';
        input.placeholder = '검색 (이름 일부만 입력)';
        input.setAttribute('autocomplete', 'off');
        var list = document.createElement('ul');
        list.className = 'sselect-list';
        list.setAttribute('role', 'listbox');
        var empty = document.createElement('div');
        empty.className = 'sselect-empty';
        empty.textContent = '일치하는 브랜치 없음';
        empty.hidden = true;
        pop.appendChild(input);
        pop.appendChild(list);
        pop.appendChild(empty);
        wrap.appendChild(pop);

        var shown = [];      // 지금 목록에 보이는 option
        var active = -1;

        function refresh() {
            var opt = select.options[select.selectedIndex];
            var placeholder = !opt || opt.value === '';
            valueEl.textContent = opt ? opt.text : '';
            valueEl.classList.toggle('is-placeholder', placeholder);
        }

        function paint(li, text, q) {
            var at = q ? text.toLowerCase().indexOf(q) : -1;
            if (at < 0) { li.textContent = text; return; }
            var mark = document.createElement('mark');
            mark.textContent = text.substring(at, at + q.length);
            li.appendChild(document.createTextNode(text.substring(0, at)));
            li.appendChild(mark);
            li.appendChild(document.createTextNode(text.substring(at + q.length)));
        }

        function render() {
            var q = input.value.trim().toLowerCase();
            list.textContent = '';
            shown = Array.prototype.filter.call(select.options, function (o) {
                return !q || (o.value !== '' && o.text.toLowerCase().indexOf(q) >= 0);
            });
            shown.forEach(function (o, i) {
                var li = document.createElement('li');
                li.setAttribute('role', 'option');
                li.dataset.index = i;
                if (o.value === '') li.classList.add('placeholder-opt');
                if (o.selected) li.classList.add('selected');
                paint(li, o.text, q);
                list.appendChild(li);
            });
            empty.hidden = shown.length > 0;
            var sel = shown.findIndex(function (o) { return o.selected; });
            setActive(q ? 0 : Math.max(0, sel));
        }

        function setActive(i) {
            var items = list.children;
            if (!items.length) { active = -1; return; }
            active = Math.max(0, Math.min(i, items.length - 1));
            Array.prototype.forEach.call(items, function (li, k) { li.classList.toggle('active', k === active); });
            items[active].scrollIntoView({ block: 'nearest' });
        }

        function open() {
            if (!pop.hidden) return;
            pop.hidden = false;
            btn.setAttribute('aria-expanded', 'true');
            input.value = '';
            render();
            input.focus();
        }

        function close(focusBtn) {
            if (pop.hidden) return;
            pop.hidden = true;
            btn.setAttribute('aria-expanded', 'false');
            if (focusBtn) btn.focus();
        }

        function pick(i) {
            var o = shown[i];
            if (!o) return;
            var changed = select.value !== o.value;
            select.value = o.value;
            refresh();
            close(true);
            if (changed) select.dispatchEvent(new Event('change', { bubbles: true }));
        }

        btn.addEventListener('click', function () { pop.hidden ? open() : close(true); });
        btn.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                open();
            } else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
                // 버튼에서 바로 타이핑하면 검색칸으로 이어서 입력
                e.preventDefault();
                open();
                input.value = e.key;
                render();
            }
        });
        input.addEventListener('input', render);
        input.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowDown') { e.preventDefault(); setActive(active + 1); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(active - 1); }
            else if (e.key === 'Enter') { e.preventDefault(); pick(active); }
            else if (e.key === 'Escape') { e.preventDefault(); close(true); }
            else if (e.key === 'Tab') { close(false); }
        });
        list.addEventListener('mousedown', function (e) { e.preventDefault(); });   // 검색칸 포커스 유지
        list.addEventListener('click', function (e) {
            var li = e.target.closest('li');
            if (li) pick(Number(li.dataset.index));
        });
        list.addEventListener('mousemove', function (e) {
            var li = e.target.closest('li');
            if (li && Number(li.dataset.index) !== active) setActive(Number(li.dataset.index));
        });
        document.addEventListener('mousedown', function (e) {
            if (!wrap.contains(e.target)) close(false);
        });

        select._brRefresh = refresh;
        refresh();
    }

    document.querySelectorAll('select[data-searchable]').forEach(enhanceSelect);

    // ---------------------------------------------------------------- 비교 화면 A/B 전환
    var swapBtn = el('swapBtn');
    if (swapBtn) {
        swapBtn.addEventListener('click', function () {
            var a = el('branchA');
            var b = el('branchB');
            var tmp = a.value;
            a.value = b.value;
            b.value = tmp;
            if (a._brRefresh) a._brRefresh();
            if (b._brRefresh) b._brRefresh();
            if (a.value && b.value) {
                startProgress();
                a.form.submit();
            }
        });
    }

    // ---------------------------------------------------------------- 비교 화면 작성자 필터
    var authorFilter = el('authorFilter');
    if (authorFilter) {
        var pane = el('tabCommits');
        authorFilter.addEventListener('change', function () {
            var want = authorFilter.value;
            pane.querySelectorAll('section.panel').forEach(function (sec) {
                var rows = sec.querySelectorAll('tr.commit-row');
                if (!rows.length) return;
                var shown = 0;
                rows.forEach(function (r) {
                    var ok = !want || r.getAttribute('data-author') === want;
                    r.hidden = !ok;
                    if (ok) shown++;
                });
                var table = sec.querySelector('table.br-table');
                var empty = sec.querySelector('.filter-empty');
                if (table) table.hidden = shown === 0;
                if (empty) empty.hidden = shown !== 0;
                var count = sec.querySelector('.count');
                if (count) count.textContent = want ? shown + ' / ' + rows.length : rows.length;
            });
        });
    }
})();
