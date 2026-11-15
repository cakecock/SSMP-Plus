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

    // Точный поиск видимого поля без захвата соседних полей (таких как ФИО)
    function findInputByKw(keywords, exact = false) {
        const all = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
        for (let kw of keywords) {
            const kwL = kw.toLowerCase();
            const target = all.find(el => {
                if (!el.innerText || el.offsetParent === null) return false;
                const t = el.innerText.trim().toLowerCase();
                return (exact ? t === kwL : (t === kwL || t.includes(kwL))) && t.length < kwL.length + 25;
            });
            if (target) {
                const fc = target.closest('.MuiFormControl-root');
                if (fc) {
                    const inp = fc.querySelector('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])');
                    if (inp && inp.offsetParent !== null) return inp;
                }
                let p = target.parentElement;
                for (let i = 0; i < 3 && p && p !== document.body; i++) {
                    const inps = Array.from(p.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])')).filter(el => el.offsetParent !== null);
                    if (inps.length === 1) return inps[0];
                    p = p.parentElement;
                }
            }
        }
        return null;
    }

    function scrapeCache() {
        const c = window.b103ScreenCache;
        const g = (k) => findInputByKw([k])?.value?.trim();
        if (g("экг до оказания")) c.electrocardiogramBefore = g("экг до оказания");
        if (g("экг после оказания")) c.electrocardiogramAfter = g("экг после оказания");
        if (g("предварительный диагноз")) c.diagnosis = g("предварительный диагноз");
        if (g("жалобы")) c.complaints = g("жалобы");
        if (g("анамнез заболевания")) c.anamnesis = g("анамнез заболевания");
        if (g("локальный статус")) c.localStatus = g("локальный статус");

        const qtyNodes = Array.from(document.querySelectorAll('*')).filter(e => e.children.length === 0 && e.textContent && e.textContent.includes("Количество:"));
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
        const t = document.body ? document.body.innerText : '';
        return (t.includes('О ВЫЗОВЕ') && t.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ')) || t.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ');
    }

    // Проверка: открыта ли сейчас вкладка "ЗАКЛЮЧЕНИЕ"
    function isConclusionTabActive() {
        const activeTabs = Array.from(document.querySelectorAll('[role="tab"][aria-selected="true"], button.Mui-selected'));
        for (let tab of activeTabs) {
            if ((tab.innerText || '').toUpperCase().includes('ЗАКЛЮЧЕНИЕ')) return true;
        }
        return false;
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
            const fio = findInputByKw(["фио"], true)?.value?.trim() || "";
            window.AndroidBridge?.requestAutofill(fio);
        };
    }

    function updateVisibility() {
        ensureToolbar();
        const inCard = isInsideCard();
        if (inCard) scrapeCache();
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
            // ИСКЛЮЧЕНА ВКЛАДКА "ЗАКЛЮЧЕНИЕ"
            if (t.includes('УТОЧНЕННЫЕ') || t.includes('ОБЪЕКТИВНЫЕ') || t.includes('МЕДИЦИНСКАЯ')) {
                setTimeout(() => window.applyAutofill(window.activeAutofillData), 180);
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

    // Если пользователь находится на вкладке "ЗАКЛЮЧЕНИЕ", ничего не заполняем!
    const activeTabs = Array.from(document.querySelectorAll('[role="tab"][aria-selected="true"], button.Mui-selected'));
    if (activeTabs.some(t => (t.innerText || '').toUpperCase().includes('ЗАКЛЮЧЕНИЕ'))) {
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

    // Надежная функция принудительного закрытия любых открытых меню Material-UI
    async function closeAnyOpenMenu() {
        const popovers = Array.from(document.querySelectorAll('.MuiPopover-root, .MuiModal-root, [role="presentation"]')).filter(el => el.offsetParent !== null);
        if (popovers.length === 0) return;

        for (let pop of popovers) {
            const list = pop.querySelector('[role="listbox"], ul, .MuiMenu-list') || pop;
            list.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true }));
            const backdrop = pop.querySelector('.MuiBackdrop-root') || pop.firstElementChild;
            if (backdrop) {
                backdrop.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                backdrop.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, button: 0 }));
                backdrop.click();
            }
        }
        if (document.activeElement && document.activeElement !== document.body) {
            document.activeElement.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true }));
        }
        document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true }));
        await new Promise(r => setTimeout(r, 55));
    }

    try {
        const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;

        function normBp(v) {
            if (!v) return "";
            if (typeof v === 'object') return (v.sys || v.dis) ? `${v.sys||''}/${v.dis||''}` : "";
            const s = String(v).trim();
            if (s.startsWith("{")) { try { const o = JSON.parse(s); return `${o.sys||''}/${o.dis||''}`; } catch(e){ return ""; } }
            return s;
        }

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

        // Строгий поиск инпутов: исключает захват соседнего поля ФИО при поиске "Перенесенные заболевания"
        function findInputs(keywords, exact = false) {
            const res = [];
            const searchingFio = keywords.some(k => k.toLowerCase() === 'фио');
            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));

            for (let kw of keywords) {
                const kwL = kw.toLowerCase();
                for (let l of labels) {
                    if (!l.innerText || l.offsetParent === null) continue;
                    // Пропускаем крупные контейнеры, у которых много дочерних элементов
                    if (l.children.length > 2) continue;

                    const t = l.innerText.trim().toLowerCase();
                    const isMatch = exact ? t === kwL : (t === kwL || t.includes(kwL));
                    if (isMatch && t.length < kwL.length + 25) {
                        let foundInp = null;

                        // 1. Сначала ищем строго внутри собственного .MuiFormControl-root
                        const fc = l.closest('.MuiFormControl-root');
                        if (fc) {
                            const inps = Array.from(fc.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])')).filter(el => el.offsetParent !== null);
                            if (inps.length === 1) foundInp = inps[0];
                        }

                        // 2. Если поле не обернуто в MuiFormControl-root, поднимаемся аккуратно вверх, но только пока внутри ровно 1 инпут!
                        if (!foundInp) {
                            let p = l.parentElement;
                            for (let i = 0; i < 3 && p && p !== document.body; i++) {
                                const inps = Array.from(p.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])')).filter(el => el.offsetParent !== null);
                                if (inps.length === 1) {
                                    foundInp = inps[0];
                                    break;
                                } else if (inps.length > 1) {
                                    break; // Останавливаемся, чтобы не захватить чужой инпут из общей сетки!
                                }
                                p = p.parentElement;
                            }
                        }

                        if (foundInp && !res.includes(foundInp)) {
                            // Жесткий предохранитель: если ищем НЕ "фио", проверяем, не принадлежит ли инпут полю ФИО
                            if (!searchingFio) {
                                const inpHolderText = ((foundInp.closest('.MuiFormControl-root') || foundInp.parentElement)?.innerText || '').trim().toLowerCase();
                                if (inpHolderText === 'фио' || inpHolderText.startsWith('фио\n')) continue;
                            }
                            res.push(foundInp);
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

        // Выпадающие списки Material-UI с гарантированным закрытием мультиселектов
        async function selectDropdown(keywords, valStr, forceMulti = false) {
            if (!valStr) return;
            const vals = String(valStr).split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
            if (vals.length === 0) return;

            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
            const ctrls = [];
            for (let kw of keywords) {
                const kwL = kw.toLowerCase();
                for (let l of labels) {
                    if (!l.innerText || l.offsetParent === null || l.children.length > 2) continue;
                    const t = l.innerText.trim().toLowerCase();
                    if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 25) {
                        const c = l.closest('.MuiFormControl-root, .MuiGrid-item') || l.parentElement;
                        if (c && c.offsetParent !== null && !ctrls.includes(c)) ctrls.push(c);
                    }
                }
            }

            for (let c of ctrls) {
                const btn = c.querySelector('.MuiSelect-root, [role="button"], .MuiAutocomplete-popupIndicator');
                if (!btn || btn.offsetParent === null) continue;

                const isMulti = forceMulti || btn.classList.contains('MuiSelect-multiple') || vals.length > 1;
                const curText = (btn.innerText || '').trim().toLowerCase();

                if (!isMulti && curText === vals[0]) continue;
                if (isMulti && vals.every(v => curText.includes(v))) continue;

                // Перед открытием нового списка убеждаемся, что предыдущий закрыт
                await closeAnyOpenMenu();

                btn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                btn.click();
                await new Promise(r => setTimeout(r, 95));

                const popover = document.querySelector('.MuiPopover-root:not([aria-hidden="true"])') || document.querySelector('.MuiPopover-root') || document;
                const opts = Array.from(popover.querySelectorAll('li[role="option"], .MuiMenuItem-root, .MuiAutocomplete-option'));

                if (opts.length > 0) {
                    // Дополнительная проверка: если внутри пунктов есть чекбоксы — это 100% мультиселект!
                    const hasCheckboxes = opts.some(o => !!o.querySelector('input[type="checkbox"], .MuiCheckbox-root'));
                    const treatAsMulti = isMulti || hasCheckboxes;

                    if (!treatAsMulti) {
                        // ОДИНОЧНЫЙ СПИСОК (Общее состояние, Сознание, Возраст ед.):
                        const match = opts.find(o => {
                            const ot = (o.innerText || '').trim().toLowerCase();
                            return ot !== "не выбрано" && (ot === vals[0] || ot.includes(vals[0]));
                        });
                        if (match) {
                            match.click();
                            await new Promise(r => setTimeout(r, 85));
                        } else {
                            await closeAnyOpenMenu();
                        }
                    } else {
                        // МУЛЬТИСЕЛЕКТ (Кожа, Тоны сердца, Дыхание, Перкуссия легких, Живот):
                        const wanted = new Set();
                        for (let v of vals) {
                            const m = opts.find(o => {
                                const ot = (o.innerText || '').trim().toLowerCase();
                                return ot !== "не выбрано" && (ot === v || ot.includes(v));
                            });
                            if (m) wanted.add(m);
                        }
                        for (let o of opts) {
                            const shouldBe = wanted.has(o);
                            const isSel = o.classList.contains('Mui-selected') || o.getAttribute('aria-selected') === 'true' || !!o.querySelector('input[type="checkbox"]:checked');
                            if (shouldBe !== isSel) {
                                o.click();
                                await new Promise(r => setTimeout(r, 35));
                            }
                        }
                        // Принудительно закрываем мультиселект!
                        await closeAnyOpenMenu();
                    }
                } else {
                    await closeAnyOpenMenu();
                }
            }
        }

        function clickUniqueButton(targetText) {
            if (!targetText) return;
            const targetUp = targetText.trim().toUpperCase();
            const btns = Array.from(document.querySelectorAll('button, div[role="button"]'));
            const btn = btns.find(b => {
                if (b.offsetParent === null) return false;
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

        function clickYesNoInRow(keywords, choice) {
            if (!choice) return;
            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
            for (let kw of keywords) {
                const kwL = kw.toLowerCase();
                const target = labels.find(el => {
                    if (!el.innerText || el.offsetParent === null || el.children.length > 2) return false;
                    const t = el.innerText.trim().toLowerCase();
                    return (t === kwL || t.includes(kwL)) && t.length < kwL.length + 25;
                });
                if (target) {
                    let p = target.closest('.MuiFormControl-root, .MuiGrid-item') || target.parentElement;
                    for (let i = 0; i < 4 && p && p !== document.body; i++) {
                        const btns = Array.from(p.querySelectorAll('button, div[role="button"]'));
                        const desired = btns.find(b => (b.innerText || '').trim().toUpperCase() === choice.toUpperCase());
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

        function fillBP(isAd, raw) {
            const str = normBp(raw); if (!str.includes('/')) return;
            const p = str.split('/');
            Array.from(document.querySelectorAll('*')).filter(e => e.offsetParent !== null && e.children.length === 0 && e.textContent.trim() === '/').forEach(sl => {
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

        // Выбираем "Возраст (ед.)" (Лет) - одиночный список
        const unitVal = data.ageUnit || window.lastLoadedApiCard?.ageType?.type || "Лет";
        await selectDropdown(["возраст (ед.)"], unitVal, false);

        if (data.address) setAll(["место жительства (пребывания)"], data.address);

        // 2. ЖАЛОБЫ, АНАМНЕЗ, ПРЕДВАРИТЕЛЬНЫЙ ДИАГНОЗ (без вкладки Заключение)
        setAll(["жалобы"], data.complaints);
        setAll(["анамнез заболевания"], data.anamnesis);
        setAll(["перенесенные заболевания"], data.pastDiseases, true);
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

        // 5. ПРЯМЫЕ КЛИКИ ПО УНИКАЛЬНЫМ КНОПКАМ (Кнопки вкладки "Заключение" исключены!)
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
        // Одиночные списки (forceMulti = false)
        await selectDropdown(["общее состояние"], data.generalState || "Удовлетворительное", false);
        await selectDropdown(["сознание"], data.consciousness || "Ясное", false);

        // Мультиселекты (forceMulti = true — гарантирует принудительное закрытие даже при 1 значении!)
        if (data.skin) await selectDropdown(["кожа"], data.skin, true);
        if (data.heartSounds) await selectDropdown(["тоны сердца"], data.heartSounds, true);
        if (data.lungs) await selectDropdown(["дыхание"], data.lungs, true);
        if (data.percussion) await selectDropdown(["перкуссия легких"], data.percussion, true);
        if (data.abdomen) await selectDropdown(["живот"], data.abdomen, true);

        // Контрольная зачистка любых оставшихся открытых меню в самом конце
        await closeAnyOpenMenu();
    } catch (e) { console.error(e); } finally {
        await closeAnyOpenMenu();
        document.getElementById('b103-hide-menus')?.remove();
        if (fillBtn) fillBtn.innerText = "⚡";
        setTimeout(() => { window.b103IsApplying = false; }, 200);
    }
};
