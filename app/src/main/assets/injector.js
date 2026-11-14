(function() {
    window.lastLoadedApiCard = window.lastLoadedApiCard || null;
    window.activeAutofillData = window.activeAutofillData || null;
    window.b103ScreenCache = window.b103ScreenCache || {};
    window.b103IsApplying = false;

    if (!window.b103NetHooked) {
        window.b103NetHooked = true;
        const origFetch = window.fetch;
        window.fetch = async function(...args) {
            try {
                const opts = args[1] || {};
                if (opts.body && typeof opts.body === 'string' && opts.body.includes('"patient"')) {
                    const s = JSON.parse(opts.body);
                    if (s && s.patient) window.lastLoadedApiCard = Object.assign(window.lastLoadedApiCard || {}, s);
                }
            } catch (e) {}
            const res = await origFetch.apply(this, args);
            try {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] ? args[0].url : '');
                if (url && url.includes('/api/card/') && !url.includes('/short')) {
                    res.clone().json().then(d => {
                        if (d && d.patient) {
                            if (window.lastLoadedApiCard && window.lastLoadedApiCard.id !== d.id) {
                                window.activeAutofillData = null; window.b103ScreenCache = {};
                            }
                            window.lastLoadedApiCard = d;
                        }
                    }).catch(() => {});
                }
            } catch (e) {}
            return res;
        };

        const origOpen = XMLHttpRequest.prototype.open;
        const origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(m, u) { this._url = u; return origOpen.apply(this, arguments); };
        XMLHttpRequest.prototype.send = function(body) {
            try {
                if (body && typeof body === 'string' && body.includes('"patient"')) {
                    const s = JSON.parse(body);
                    if (s && s.patient) window.lastLoadedApiCard = Object.assign(window.lastLoadedApiCard || {}, s);
                }
            } catch (e) {}
            this.addEventListener('load', function() {
                try {
                    if (this._url && this._url.includes('/api/card/') && !this._url.includes('/short')) {
                        const d = JSON.parse(this.responseText);
                        if (d && d.patient) {
                            if (window.lastLoadedApiCard && window.lastLoadedApiCard.id !== d.id) {
                                window.activeAutofillData = null; window.b103ScreenCache = {};
                            }
                            window.lastLoadedApiCard = d;
                        }
                    }
                } catch (e) {}
            });
            return origSend.apply(this, arguments);
        };
    }

    // ===== ВИДИМОСТЬ ЭЛЕМЕНТОВ =====
    // Карта вызова построена на react-swipeable-views: ВСЕ вкладки постоянно
    // находятся в DOM, неактивные лишь сдвинуты по горизонтали и помечены
    // aria-hidden="true". Поэтому offsetParent !== null ещё не значит, что поле
    // принадлежит активной вкладке (из-за этого раньше заполнялось «Заключение»).
    function isElementOnScreen(el) {
        if (!el || el.offsetParent === null) return false;
        // Поле внутри неактивного слайда (вкладки) — не трогаем.
        if (el.closest && el.closest('[data-swipeable="true"][aria-hidden="true"]')) return false;
        const r = el.getBoundingClientRect();
        if (!r || r.width === 0 || r.height === 0) return false;
        const vw = window.innerWidth || document.documentElement.clientWidth || 0;
        // Соседние слайды сдвинуты на целую ширину экрана, поэтому их центр
        // за пределами viewport. Проверяем центр, а не края: поле может быть
        // чуть шире экрана, но всё равно принадлежать активной вкладке.
        const cx = (r.left + r.right) / 2;
        if (vw > 0 && (cx < 0 || cx > vw)) return false;
        return true;
    }
    window.b103IsElementOnScreen = isElementOnScreen;

    // Активна ли вкладка «Заключение» (её автозаполнение запрещено).
    window.b103IsConclusionTab = function() {
        const tabs = Array.from(document.querySelectorAll('[role="tab"]'));
        const cur = tabs.find(t => t.getAttribute('aria-selected') === 'true');
        return !!(cur && (cur.innerText || '').toUpperCase().includes('ЗАКЛЮЧЕНИЕ'));
    };

    // Универсальный поиск видимого поля по ключевым словам (охватывает label, p, span, div)
    function findInputByKw(keywords, exact = false) {
        const all = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
        for (let kw of keywords) {
            const kwL = kw.toLowerCase();
            const target = all.find(el => {
                if (!el.innerText) return false;
                const t = el.innerText.trim().toLowerCase();
                if (!((exact ? t === kwL : (t === kwL || t.includes(kwL))) && t.length < kwL.length + 30)) return false;
                return isElementOnScreen(el);
            });
            if (target) {
                let p = target.closest('.MuiFormControl-root, .MuiGrid-item') || target.parentElement;
                for (let i = 0; i < 4 && p && p !== document.body; i++) {
                    const inp = p.querySelector('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])');
                    if (inp && isElementOnScreen(inp)) return inp;
                    p = p.parentElement;
                }
            }
        }
        return null;
    }

    function scrapeCache() {
        const c = window.b103ScreenCache;
        const g = (k) => findInputByKw([k])?.value?.trim();
        const put = (kw, prop) => { const v = g(kw); if (v) c[prop] = v; };
        put("экг до оказания", "electrocardiogramBefore");
        put("экг после оказания", "electrocardiogramAfter");
        put("предварительный диагноз", "diagnosis");
        put("жалобы", "complaints");
        put("анамнез заболевания", "anamnesis");
        put("локальный статус", "localStatus");

        // XPath быстрее, чем перебор всего DOM через querySelectorAll('*').
        let qtyNodes = [];
        try {
            const xp = document.evaluate("//*[contains(text(),'Количество:')]", document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
            for (let i = 0; i < xp.snapshotLength; i++) qtyNodes.push(xp.snapshotItem(i));
        } catch (err) {
            qtyNodes = Array.from(document.querySelectorAll('*')).filter(n => n.children.length === 0 && n.textContent && n.textContent.includes("Количество:"));
        }
        qtyNodes = qtyNodes.filter(n => n.children.length === 0 && isElementOnScreen(n));
        if (qtyNodes.length > 0) {
            const meds = [];
            for (let q of qtyNodes) {
                let card = q.parentElement;
                while (card && card !== document.body) {
                    const lines = (card.innerText || '').split('\n').map(s => s.trim()).filter(Boolean);
                    const qi = lines.findIndex(l => l.includes("Количество:"));
                    if (qi > 0) {
                        const nm = lines[0], qt = lines[qi].replace(/Количество:\s*/i, '').trim();
                        const rt = (lines.find(l => l.includes("Путь введения:")) || "").replace(/Путь введения:\s*/i, '').trim();
                        const rtStr = (rt && !rt.includes("не определен")) ? ` [${rt}]` : '';
                        if (nm && !nm.includes("Лекарственные") && !nm.includes("ДОБАВИТЬ")) meds.push(`${nm} — ${qt}${rtStr}`);
                        break;
                    }
                    card = card.parentElement;
                }
            }
            if (meds.length > 0) c._domMedications = Array.from(new Set(meds)).join(";\n• ");
        }
    }

    function isInsideCard() {
        // Дешёвая проверка по разметке карты вызова (swipeable-контейнер есть
        // только в карте), чтобы не читать document.body.innerText каждые 300 мс.
        if (document.querySelector('.react-swipeable-view-container')) return true;
        const tabs = document.querySelectorAll('[role="tab"]');
        for (let i = 0; i < tabs.length; i++) {
            const tx = (tabs[i].innerText || '').toUpperCase();
            if (tx.includes('ОБЪЕКТИВНЫЕ') || tx.includes('УТОЧНЕННЫЕ') || tx.includes('МЕДИЦИНСКАЯ')) return true;
        }
        const t = document.body ? document.body.innerText : '';
        return (t.includes('О ВЫЗОВЕ') && t.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ')) || t.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ');
    }

    function ensureToolbar() {
        if (!document.body || document.getElementById('b103-toolbar')) return;
        const bar = document.createElement('div');
        bar.id = 'b103-toolbar';
        bar.style.cssText = 'position:fixed;bottom:88px;right:16px;z-index:99999;display:flex;flex-direction:column;align-items:flex-end;gap:12px;';
        const rs = 'width:52px;height:52px;border-radius:50%;border:none;box-shadow:0 4px 14px rgba(0,0,0,0.32);font-size:22px;display:none;align-items:center;justify-content:center;cursor:pointer;user-select:none;';
        bar.innerHTML = `
            <button id="btn-main-menu" style="background:#008577;color:white;${rs}">☰</button>
            <button id="btn-save-db" style="background:#00796B;color:white;${rs}">💾</button>
            <button id="btn-fill-db" style="background:#0288D1;color:white;${rs}">⚡</button>
        `;
        document.body.appendChild(bar);

        document.getElementById('btn-main-menu').onclick = () => window.AndroidBridge?.openMainMenu();
        document.getElementById('btn-save-db').onclick = () => {
            scrapeCache();
            if (window.lastLoadedApiCard && window.AndroidBridge) {
                window.AndroidBridge.saveApiCardForced(JSON.stringify(Object.assign({}, window.lastLoadedApiCard, window.b103ScreenCache)));
            } else {
                window.AndroidBridge?.notifyUser("Откройте карту для сохранения");
            }
        };
        document.getElementById('btn-fill-db').onclick = () => {
            if (window.b103IsConclusionTab && window.b103IsConclusionTab()) {
                window.AndroidBridge?.notifyUser("Вкладка «Заключение» не заполняется автоматически — заполните её вручную");
                return;
            }
            // ФИО берём из карты вызова, а не с экрана: на вкладке «Заключение»
            // поле «ФИО» — это уже принимающий медработник.
            const apiFio = window.lastLoadedApiCard?.patient?.fullName || window.lastLoadedApiCard?.initialFullName || "";
            const fio = apiFio || findInputByKw(["фио"])?.value?.trim() || "";
            window.AndroidBridge?.requestAutofill(fio);
        };
    }

    let lastScrapeAt = 0;
    function updateVisibility() {
        ensureToolbar();
        const inCard = isInsideCard();
        const now = Date.now();
        // Раньше полный обход DOM выполнялся каждые 300 мс; на большой карте это
        // заметно подтормаживало WebView. Теперь — не чаще раза в 2,5 с.
        if (inCard && !window.b103IsApplying && !document.hidden && now - lastScrapeAt > 2500) {
            lastScrapeAt = now;
            scrapeCache();
        }
        const m = document.getElementById('btn-main-menu');
        const s = document.getElementById('btn-save-db');
        const f = document.getElementById('btn-fill-db');
        if (m && s && f) {
            m.style.display = inCard ? 'none' : 'flex';
            s.style.display = inCard ? 'flex' : 'none';
            f.style.display = inCard ? 'flex' : 'none';
        }
        if (!inCard) { window.activeAutofillData = null; window.b103ScreenCache = {}; }
    }

    if (!window.b103ClickHooked) {
        window.b103ClickHooked = true;
        document.addEventListener('click', function(e) {
            if (!e.isTrusted || window.b103IsApplying || !window.activeAutofillData) return;
            const tab = e.target && e.target.closest ? e.target.closest('button, [role="tab"]') : null;
            if (!tab) return;
            const rect = tab.getBoundingClientRect();
            if (rect.top < 0 || rect.top > 130) return;
            const t = (tab.innerText || '').toUpperCase();
            if (t.includes('ЗАКЛЮЧЕНИЕ')) return; // эту вкладку заполняем только вручную
            if (t.includes('УТОЧНЕННЫЕ') || t.includes('ОБЪЕКТИВНЫЕ') || t.includes('МЕДИЦИНСКАЯ')) {
                // Ждём окончания анимации SwipeableViews (0,35 с), иначе поля
                // соседних вкладок ещё частично видны и могут быть заполнены.
                setTimeout(() => window.applyAutofill(window.activeAutofillData), 450);
            }
        }, true);
    }

    ensureToolbar();
    updateVisibility();
    if (!window.b103VisTimer) window.b103VisTimer = setInterval(updateVisibility, 300);
})();

window.setAndApplyAutofill = function(jsonStr) {
    try {
        window.activeAutofillData = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
        window.applyAutofill(window.activeAutofillData);
    } catch (e) {}
};

// ==========================================
// ТОЧНЫЙ АВТОПИЛОТ (БЕЗ ДЕРГАНИЯ ЭКРАНА)
// ==========================================
window.applyAutofill = async function(jsonStr) {
    if (window.b103IsApplying) return;
    // «Заключение» заполняем только вручную: автопилот туда больше не лезет.
    if (window.b103IsConclusionTab && window.b103IsConclusionTab()) {
        window.AndroidBridge?.notifyUser("Вкладка «Заключение» не заполняется автоматически — заполните её вручную");
        return;
    }
    window.b103IsApplying = true;
    const fillBtn = document.getElementById('btn-fill-db');
    if (fillBtn) fillBtn.innerText = "⏳";

    let hideStyle = document.getElementById('b103-hide-menus');
    if (!hideStyle) {
        hideStyle = document.createElement('style');
        hideStyle.id = 'b103-hide-menus';
        hideStyle.textContent = '.MuiPopover-root, .MuiModal-root { opacity: 0 !important; pointer-events: auto !important; }';
        document.head.appendChild(hideStyle);
    }

    const onScreen = (el) => (window.b103IsElementOnScreen ? window.b103IsElementOnScreen(el) : true);
    const sleep = (ms) => new Promise(r => setTimeout(r, ms));

    // Самое «свежее» открытое меню (последнее в DOM).
    function getOpenPopover() {
        const pops = Array.from(document.querySelectorAll('.MuiPopover-root'))
            .filter(p => p.getAttribute('aria-hidden') !== 'true');
        return pops.length ? pops[pops.length - 1] : null;
    }

    // Закрытие меню Material-UI. ВАЖНО: MUI ловит Escape на корне поповера
    // (onKeyDown у Modal), поэтому событие нужно отправлять ВНУТРИ него, а не
    // на document.body — иначе меню остаётся открытым.
    async function closeMenus(rounds = 3, wait = 120) {
        for (let i = 0; i < rounds; i++) {
            const pops = Array.from(document.querySelectorAll('.MuiPopover-root, .MuiModal-root'))
                .filter(p => p.isConnected);
            if (pops.length === 0) return;
            for (const p of pops) {
                const target = p.querySelector('[role="listbox"], [role="menu"], .MuiPaper-root') || p;
                target.dispatchEvent(new KeyboardEvent('keydown', {
                    key: 'Escape', code: 'Escape', keyCode: 27, which: 27,
                    bubbles: true, cancelable: true
                }));
            }
            await sleep(wait);
        }
    }

    try {
        const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;

        // На случай, если меню осталось открытым с прошлого раза.
        await closeMenus(1, 60);

        function normBp(v) {
            if (!v) return "";
            if (typeof v === 'object') return (v.sys || v.dis) ? `${v.sys||''}/${v.dis||''}` : "";
            const s = String(v).trim();
            if (s.startsWith("{")) { try { const o = JSON.parse(s); return `${o.sys||''}/${o.dis||''}`; } catch(e){ return ""; } }
            return s;
        }

        // Вставка текста без focus()/blur() — экран стоит на месте!
        function setVal(inp, val) {
            if (!inp || val === undefined || val === null || inp.disabled) return;
            const str = String(val).trim();
            if (!str || inp.value.trim() === str) return;
            try {
                const proto = inp.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(inp, str); else inp.value = str;
                if (inp._valueTracker) inp._valueTracker.setValue('');
                inp.dispatchEvent(new Event('input', { bubbles: true }));
                inp.dispatchEvent(new Event('change', { bubbles: true }));
            } catch (e) { inp.value = str; }
        }

        // Поиск всех подходящих видимых инпутов (включая p, span, label, div)
        function findInputs(keywords, exact = false) {
            const res = [];
            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
            for (let kw of keywords) {
                const kwL = kw.toLowerCase();
                for (let l of labels) {
                    if (!l.innerText || !onScreen(l)) continue;
                    const t = l.innerText.trim().toLowerCase();
                    const isMatch = exact ? t === kwL : (t === kwL || t.includes(kwL));
                    if (isMatch && t.length < kwL.length + 30) {
                        let p = l.closest('.MuiFormControl-root, .MuiGrid-item') || l.parentElement;
                        for (let i = 0; i < 4 && p && p !== document.body; i++) {
                            const inp = p.querySelector('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])');
                            if (inp && onScreen(inp) && !res.includes(inp)) {
                                res.push(inp);
                                break;
                            }
                            p = p.parentElement;
                        }
                    }
                }
            }
            return res;
        }

        const setAll = (kws, val, ex = false) => { if (val) findInputs(kws, ex).forEach(i => setVal(i, val)); };

        function formatBirthDateAndCalcAge(rawDateStr) {
            if (!rawDateStr) return { dateStr: "", ageStr: "" };
            let clean = String(rawDateStr).trim();
            if (clean.startsWith("__")) return { dateStr: "", ageStr: "" };

            let datePart = "", timePart = "00:00";
            if (clean.includes("-")) {
                const p = clean.split("T"); const d = p[0].split("-");
                if (d.length === 3) datePart = `${d[2]}.${d[1]}.${d[0]}`;
                if (p[1] && /^\d{2}:\d{2}/.test(p[1].trim())) timePart = p[1].trim().substring(0, 5);
            } else if (clean.includes(".")) {
                const p = clean.split("/"); datePart = p[0].trim();
                if (p[1] && /^\d{2}:\d{2}/.test(p[1].trim())) timePart = p[1].trim().substring(0, 5);
            }
            if (!datePart || datePart.includes("_")) return { dateStr: "", ageStr: "" };

            const now = new Date();
            const todayStr = `${String(now.getDate()).padStart(2,'0')}.${String(now.getMonth()+1).padStart(2,'0')}.${now.getFullYear()}`;
            let calcAge = "";
            const dp = datePart.split(".");
            if (dp.length === 3) {
                const by = parseInt(dp[2], 10);
                if (by > 1900 && by < now.getFullYear()) {
                    let a = now.getFullYear() - by;
                    if ((now.getMonth()+1) < parseInt(dp[1],10) || ((now.getMonth()+1) === parseInt(dp[1],10) && now.getDate() < parseInt(dp[0],10))) a--;
                    if (a >= 0) calcAge = String(a);
                }
            }
            return { dateStr: (datePart !== todayStr) ? `${datePart} / 00:00` : `${datePart} / ${timePart}`, ageStr: calcAge };
        }

        // Выпадающие списки Material-UI: для одиночных кликаем пункт БЕЗ Escape (меню закрывается само и выбор не сбрасывается!)
        async function selectDropdown(keywords, valStr) {
            if (!valStr) return;
            const vals = String(valStr).split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
            if (vals.length === 0) return;

            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
            const ctrls = [];
            for (let kw of keywords) {
                const kwL = kw.toLowerCase();
                for (let l of labels) {
                    if (!l.innerText || !onScreen(l)) continue;
                    const t = l.innerText.trim().toLowerCase();
                    if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 30) {
                        const c = l.closest('.MuiFormControl-root, .MuiGrid-item') || l.parentElement;
                        if (c && onScreen(c) && !ctrls.includes(c)) ctrls.push(c);
                    }
                }
            }

            for (let c of ctrls) {
                const btn = c.querySelector('.MuiSelect-root, [role="button"], .MuiAutocomplete-popupIndicator');
                if (!btn || !onScreen(btn)) continue;

                let isMulti = btn.classList.contains('MuiSelect-multiple') || vals.length > 1;
                const curText = (btn.innerText || '').trim().toLowerCase();

                if (!isMulti && curText === vals[0]) continue;
                if (isMulti && vals.every(v => curText.includes(v))) continue;

                // Меню от предыдущего поля должно быть закрыто, иначе Escape/клик
                // попадут не в тот список и часть меню останется висеть.
                await closeMenus(1, 60);

                // Открываем меню через mousedown (у MUI Select это onMouseDown).
                btn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                btn.click();
                await sleep(110);

                const popover = getOpenPopover();
                if (!popover) continue;
                const opts = Array.from(popover.querySelectorAll('li[role="option"], .MuiMenuItem-root, .MuiAutocomplete-option'));
                if (opts.length === 0) { await closeMenus(1, 80); continue; }

                const txt = (o) => (o.innerText || '').trim().toLowerCase();
                const findOpt = (v) => opts.find(o => {
                    const ot = txt(o);
                    return ot !== "не выбрано" && (ot === v || ot.includes(v));
                });
                const isSelected = (o) => o.getAttribute('aria-selected') === 'true' ||
                    o.classList.contains('Mui-selected') ||
                    !!o.querySelector('input[type="checkbox"]:checked');

                if (!isMulti) {
                    // ОДИНОЧНЫЙ СПИСОК (Состояние, Сознание, Возраст ед.): строго игнорируем "не выбрано"!
                    const match = findOpt(vals[0]);
                    if (match) {
                        if (!isSelected(match)) {
                            match.click(); // в одиночном списке клик сам закрывает меню
                            await sleep(80);
                        }
                    }
                    // Если меню всё ещё раскрыто — это мультиселект с одним значением
                    // в шаблоне (например, «Дыхание: Везикулярное»).
                    isMulti = btn.getAttribute('aria-expanded') === 'true';
                    if (!isMulti) { await closeMenus(1, 80); continue; }
                }

                // МУЛЬТИСЕЛЕКТ (Тоны, Дыхание, Перкуссия, Живот, Кожа):
                const wanted = new Set();
                for (let v of vals) {
                    const m = findOpt(v);
                    if (m) wanted.add(m);
                }
                for (let o of opts) {
                    if (!o.isConnected) continue;
                    const shouldBe = wanted.has(o);
                    if (shouldBe !== isSelected(o)) {
                        o.click();
                        await sleep(40);
                    }
                }
                // Закрываем именно это меню (Escape внутри поповера).
                await closeMenus(2, 120);
            }
        }

        // ПРЯМОЙ НАДЕЖНЫЙ КЛИК ПО УНИКАЛЬНЫМ КНОПКАМ (без лишних проверок родителей)
        function clickUniqueButton(targetText) {
            if (!targetText) return;
            const targetUp = targetText.trim().toUpperCase();
            const btns = Array.from(document.querySelectorAll('button, div[role="button"]'));
            const btn = btns.find(b => {
                if (!onScreen(b)) return false;
                const bt = (b.innerText || '').trim().toUpperCase();
                return bt === targetUp || (targetUp.includes("СИММЕТР") && targetUp.startsWith("А") && bt.startsWith("А") && bt.includes("СИМ"));
            });
            if (btn) {
                const s = window.getComputedStyle(btn);
                const isWhite = s.color.includes('255, 255, 255');
                const isColored = !s.backgroundColor.includes('rgba(0, 0, 0, 0)') && !s.backgroundColor.includes('255, 255, 255');
                const isAlreadyOn = (isWhite && isColored) || btn.className.includes('MuiButton-contained');
                if (!isAlreadyOn) btn.click();
            }
        }

        // ЛОКАЛЬНЫЙ КЛИК ДЛЯ КНОПОК "ДА / НЕТ"
        function clickYesNoInRow(keywords, choice) {
            if (!choice) return;
            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
            for (let kw of keywords) {
                const kwL = kw.toLowerCase();
                const target = labels.find(el => {
                    if (!el.innerText || !onScreen(el)) return false;
                    const t = el.innerText.trim().toLowerCase();
                    return (t === kwL || t.includes(kwL)) && t.length < kwL.length + 30;
                });
                if (target) {
                    let p = target.closest('.MuiFormControl-root, .MuiGrid-item') || target.parentElement;
                    for (let i = 0; i < 4 && p && p !== document.body; i++) {
                        const btns = Array.from(p.querySelectorAll('button, div[role="button"]'));
                        const desired = btns.find(b => onScreen(b) && (b.innerText || '').trim().toUpperCase() === choice.toUpperCase());
                        if (desired) {
                            const s = window.getComputedStyle(desired);
                            const isWhite = s.color.includes('255, 255, 255');
                            const isColored = !s.backgroundColor.includes('rgba(0, 0, 0, 0)') && !s.backgroundColor.includes('255, 255, 255');
                            const isAlreadyOn = (isWhite && isColored) || desired.className.includes('MuiButton-contained');
                            if (!isAlreadyOn) desired.click();
                            return;
                        }
                        p = p.parentElement;
                    }
                }
            }
        }

        // Заполнение АД строго по бокам от знака "/"
        function fillBP(isAd, raw) {
            const str = normBp(raw); if (!str.includes('/')) return;
            const p = str.split('/');
            Array.from(document.querySelectorAll('*')).filter(e => onScreen(e) && e.children.length === 0 && e.textContent.trim() === '/').forEach(sl => {
                let pr = sl.parentElement;
                for (let i = 0; i < 3 && pr && pr !== document.body; i++) {
                    const inps = Array.from(pr.querySelectorAll('input:not([type="hidden"]):not([disabled])'));
                    if (inps.length === 2) {
                        const tx = (pr.innerText || pr.parentElement?.innerText || '').toLowerCase();
                        if (isAd ? tx.includes("адаптированное") : (tx.includes("ад") && !tx.includes("адаптированное"))) {
                            setVal(inps[0], p[0].trim()); setVal(inps[1], p[1].trim()); break;
                        }
                    }
                    pr = pr.parentElement;
                }
            });
        }

        // 1. ПАСПОРТНЫЕ ДАННЫЕ, ДАТА РОЖДЕНИЯ (00:00) И ВОЗРАСТ
        if (data.fio) setAll(["фио"], data.fio, true);

        const bDateInps = findInputs(["дата рождения"]);
        if (bDateInps.length > 0) {
            const now = new Date();
            const todayIso = `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
            const todayDot = `${String(now.getDate()).padStart(2,'0')}.${String(now.getMonth()+1).padStart(2,'0')}.${now.getFullYear()}`;
            const regDateIso = (window.lastLoadedApiCard?.dateOfRegistration || "").split("T")[0];

            let servB = window.lastLoadedApiCard?.patient?.birthDate || "";
            if (servB && (servB.startsWith(todayIso) || (regDateIso && servB.startsWith(regDateIso)))) servB = "";
            let archB = data.birthDate || "";
            if (archB && (archB.includes(todayIso) || archB.includes(todayDot))) archB = "";
            let scrB = bDateInps[0].value || "";
            let scrDateOnly = scrB.split("/")[0].trim();
            if (scrDateOnly.includes("_") || scrDateOnly === todayDot) scrB = "";

            const rawB = servB || archB || scrB;
            if (rawB) {
                const { dateStr, ageStr } = formatBirthDateAndCalcAge(rawB);
                if (dateStr) setVal(bDateInps[0], dateStr);
                const finAge = data.age || (window.lastLoadedApiCard?.age ? String(window.lastLoadedApiCard.age) : "") || ageStr;
                if (finAge) setAll(["возраст"], finAge, true);
            } else if (data.age) {
                setAll(["возраст"], data.age, true);
            }
        }

        // Выбираем "Возраст (ед.)" (Лет)
        const unitVal = data.ageUnit || window.lastLoadedApiCard?.ageType?.type || "Лет";
        await selectDropdown(["возраст (ед.)"], unitVal);

        if (data.address) setAll(["место жительства (пребывания)"], data.address);

        // 2. ЖАЛОБЫ, АНАМНЕЗ, ДИАГНОЗ
        setAll(["жалобы"], data.complaints);
        setAll(["анамнез заболевания"], data.anamnesis);
        setAll(["перенесенные заболевания"], data.pastDiseases);
        setAll(["предварительный диагноз"], data.diagnosis);
        setAll(["локальный статус"], data.localStatus);

        // 3. АД, ПУЛЬС, ЧДД, ТЕМПЕРАТУРА
        if (data.bpAdapted) fillBP(true, data.bpAdapted);
        if (data.bp) fillBP(false, data.bp);
        if (data.pulse) setAll(["комментарий (пульс", "пульс (в минуту)"], data.pulse);
        if (data.rr) setAll(["частота дыхания (в минуту)"], data.rr);
        if (data.temp) setAll(["температура тела (°c)", "температура (°c)"], data.temp);
        if (data.saturation) setAll(["сатурация 1 (%)"], data.saturation);
        if (data.glucose) setAll(["гликемия 1 (ммоль/л)"], data.glucose);

        // 4. ЭКГ И НЕВРОЛОГИЯ
        setAll(["экг до оказания"], data.ecgBefore);
        setAll(["экг после оказания"], data.ecgAfter);
        setAll(["шкала комы глазго (баллы)"], data.glasgowScore || "15");
        setAll(["тонус мышц"], "Норма");
        setAll(["патологические рефлексы"], "Нет");
        setAll(["менингиальные знаки"], "Нет");
        setAll(["плегии, парезы"], "Нет");
        setAll(["язык"], data.tongue || "Влажный, не обложен");
        setAll(["слизистые"], data.mucous || "Чистые");

        // 5. ПРЯМЫЕ КЛИКИ ПО ВСЕМ УНИКАЛЬНЫМ КНОПКАМ
        if (data.gender) clickUniqueButton(data.gender);
        clickUniqueButton(data.position || "АКТИВНОЕ");
        clickUniqueButton(data.behavior || "СПОКОЙНОЕ");
        if (data.pupils) clickUniqueButton(data.pupils);
        clickUniqueButton(data.speech || "СОХРАНЕНА");
        clickUniqueButton(data.face || "СИММЕТРИЧНОЕ");
        clickUniqueButton(data.gait || "СВОБОДНАЯ");
        clickUniqueButton(data.nystagmus || "НЕТ");
        clickUniqueButton(data.liver || "НЕ УВЕЛИЧЕНА");
        clickUniqueButton(data.urination || "НЕ НАРУШЕНО");
        clickUniqueButton(data.stool || "НЕ НАРУШЕН");

        clickUniqueButton(data.attack || "КУПИРОВАН");
        clickUniqueButton(data.therapyEffect || "УЛУЧШЕНИЕ");
        const dyn = (data.therapyEffect === "БЕЗ ЭФФЕКТА") ? "БЕЗ ДИНАМИКИ" : (data.therapyEffect || "УЛУЧШЕНИЕ");
        clickUniqueButton(dyn);
        if (data.callResult) clickUniqueButton(data.callResult);

        // КЛИКИ ДЛЯ КНОПОК "ДА / НЕТ"
        clickYesNoInRow(["аллергия"], data.allergy || "НЕТ");
        clickYesNoInRow(["реакция на свет"], data.lightReaction || "ДА");
        clickYesNoInRow(["высыпания"], data.rash || "НЕТ");
        clickYesNoInRow(["пульс, ударов в минуту"], data.pulseExists || (data.pulse === "0" ? "НЕТ" : "ДА"));
        clickYesNoInRow(["асцит"], data.ascites || "НЕТ");
        clickYesNoInRow(["периферические отеки"], data.edema || "НЕТ");
        clickYesNoInRow(["запах алкоголя"], data.alcohol || "НЕТ");
        clickYesNoInRow(["запах ацетона"], data.acetone || "НЕТ");
        clickYesNoInRow(["симптомы раздражения брюшины"], data.peritoneal || "НЕТ");
        clickYesNoInRow(["перистальтика"], data.peristalsis || "ДА");

        // 6. ВЫПАДАЮЩИЕ СПИСКИ MATERIAL-UI
        await selectDropdown(["общее состояние"], data.generalState || "Удовлетворительное");
        await selectDropdown(["сознание"], data.consciousness || "Ясное");
        if (data.skin) await selectDropdown(["кожа"], data.skin);
        if (data.heartSounds) await selectDropdown(["тоны сердца"], data.heartSounds);
        if (data.lungs) await selectDropdown(["дыхание"], data.lungs);
        if (data.percussion) await selectDropdown(["перкуссия легких"], data.percussion);
        if (data.abdomen) await selectDropdown(["живот"], data.abdomen);
    } catch (e) { console.error(e); } finally {
        // Закрываем все меню ДО снятия маски, иначе пользователь увидит
        // оставшиеся открытыми списки (Перкуссия, Дыхание и т.д.).
        await closeMenus(4, 120);
        document.getElementById('b103-hide-menus')?.remove();
        if (fillBtn) fillBtn.innerText = "⚡";
        setTimeout(() => { window.b103IsApplying = false; }, 200);
    }
};
