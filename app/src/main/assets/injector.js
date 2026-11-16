(function() {
    window.lastLoadedApiCard = window.lastLoadedApiCard || null;
    window.activeAutofillData = window.activeAutofillData || null;
    window.b103ScreenCache = window.b103ScreenCache || {};
    window.b103IsApplying = false;

    // Глобальная проверка границы вкладки "ЗАКЛЮЧЕНИЕ" (для защиты от случайной перезаписи)
    function isInConclusion(el) {
        if (!el) return false;
        const anchor = Array.from(document.querySelectorAll('p, span, div, label')).find(e => {
            if (!e.innerText || e.children.length > 2) return false;
            const t = e.innerText.trim();
            return t === 'Результат оказания мед. помощи' || t === 'Результат выезда' || t === 'Дата и время начала транспортировки';
        });
        if (!anchor) return false;
        return Boolean(anchor.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING);
    }
    window.b103IsInConclusion = isInConclusion;

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

    function getRealInputs(container) {
        return Array.from(container.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])'))
            .filter(el => el.offsetParent !== null && el.getAttribute('aria-hidden') !== 'true' && el.tabIndex !== -1 && !isInConclusion(el));
    }

    function findInputByKw(keywords, exact = false) {
        const all = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
        for (let kw of keywords) {
            const kwL = kw.toLowerCase();
            const target = all.find(el => {
                if (!el.innerText || el.offsetParent === null || el.children.length > 3 || isInConclusion(el)) return false;
                const t = el.innerText.trim().toLowerCase();
                return (exact ? t === kwL : (t === kwL || t.includes(kwL))) && t.length < kwL.length + 35;
            });
            if (target) {
                const fc = target.closest('.MuiFormControl-root');
                if (fc && !isInConclusion(fc)) {
                    const inps = getRealInputs(fc);
                    if (inps.length > 0) return inps[0];
                }
                let p = target.parentElement;
                for (let i = 0; i < 3 && p && p !== document.body && !isInConclusion(p); i++) {
                    const inps = getRealInputs(p);
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
// ТОЧНЫЙ АВТОПИЛОТ С ФИКСАЦИЕЙ ЭКРАНА И ВКЛАДКИ
// ==========================================
window.applyAutofill = async function(jsonStr) {
    if (window.b103IsApplying) return;

    const savedScrollWinY = window.scrollY || document.documentElement.scrollTop || 0;
    const savedScrollContainers = Array.from(document.querySelectorAll('main, [class*="content"], [class*="scroll"], div'))
        .filter(el => el.scrollTop > 0)
        .map(el => ({ el, top: el.scrollTop }));

    const activeTabBefore = Array.from(document.querySelectorAll('[role="tab"], button'))
        .find(b => b.getBoundingClientRect().top < 140 && (b.getAttribute('aria-selected') === 'true' || b.className.includes('Mui-selected')));

    if (activeTabBefore && (activeTabBefore.innerText || '').toUpperCase().includes('ЗАКЛЮЧЕНИЕ')) {
        return;
    }

    function restoreScreenPosition() {
        window.scrollTo(0, savedScrollWinY);
        document.documentElement.scrollTop = savedScrollWinY;
        for (let item of savedScrollContainers) {
            item.el.scrollTop = item.top;
        }
    }

    window.b103IsApplying = true;
    const fillBtn = document.getElementById('btn-fill-db');
    if (fillBtn) fillBtn.innerText = "⏳";

    let hideStyle = document.getElementById('b103-hide-menus');
    if (!hideStyle) {
        hideStyle = document.createElement('style');
        hideStyle.id = 'b103-hide-menus';
        hideStyle.textContent = '.MuiPopover-root, .MuiModal-root, .MuiAutocomplete-popper { opacity: 0 !important; pointer-events: auto !important; transition: none !important; }';
        document.head.appendChild(hideStyle);
    }

    function getActivePopovers() {
        return Array.from(document.querySelectorAll('.MuiPopover-root, .MuiModal-root, .MuiAutocomplete-popper'))
            .filter(el => el.offsetParent !== null && el.getAttribute('aria-hidden') !== 'true');
    }

    async function closeAnyOpenMenu() {
        for (let attempt = 0; attempt < 5; attempt++) {
            const popovers = getActivePopovers();
            const expandedBtns = Array.from(document.querySelectorAll('[aria-expanded="true"]'));
            if (popovers.length === 0 && expandedBtns.length === 0) break;

            const escInit = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
            if (document.activeElement) {
                document.activeElement.dispatchEvent(new KeyboardEvent('keydown', escInit));
                document.activeElement.dispatchEvent(new KeyboardEvent('keyup', escInit));
            }

            for (let pop of popovers) {
                const list = pop.querySelector('[role="listbox"], [role="menu"], ul, .MuiMenu-list') || pop;
                list.dispatchEvent(new KeyboardEvent('keydown', escInit));
                list.dispatchEvent(new KeyboardEvent('keyup', escInit));

                const backdrop = pop.querySelector('.MuiBackdrop-root') || (pop.firstElementChild && pop.firstElementChild.classList.contains('MuiBackdrop-root') ? pop.firstElementChild : null);
                if (backdrop) {
                    backdrop.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                    backdrop.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window, button: 0 }));
                    backdrop.click();
                }
            }

            for (let b of expandedBtns) {
                b.dispatchEvent(new KeyboardEvent('keydown', escInit));
                if (attempt >= 2) {
                    b.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                    b.click();
                }
            }

            if (document.activeElement && document.activeElement !== document.body && document.activeElement.blur) {
                document.activeElement.blur();
            }
            document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
            document.body.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, button: 0 }));
            document.body.click();

            restoreScreenPosition();
            await new Promise(r => setTimeout(r, 55));
        }
    }

    try {
        const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
        const isInConclusion = window.b103IsInConclusion;

        function normBp(v) {
            if (!v) return "";
            if (typeof v === 'object') return (v.sys || v.dis || v.dia) ? `${v.sys||''}/${v.dis||v.dia||''}` : "";
            const s = String(v).trim();
            if (s.startsWith("{")) { try { const o = JSON.parse(s); return `${o.sys||''}/${o.dis||o.dia||''}`; } catch(e){ return ""; } }
            return s;
        }

        function setVal(inp, val) {
            if (!inp || val === undefined || val === null || inp.disabled || isInConclusion(inp)) return;
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

        function findInputs(keywords, exact = false) {
            const res = [];
            const searchingFio = keywords.some(k => k.toLowerCase() === 'фио');
            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));

            for (let kw of keywords) {
                const kwL = kw.toLowerCase();
                for (let l of labels) {
                    if (!l.innerText || l.offsetParent === null || l.children.length > 3 || isInConclusion(l)) continue;

                    const t = l.innerText.trim().toLowerCase();
                    const isMatch = exact ? t === kwL : (t === kwL || t.includes(kwL));
                    if (isMatch && t.length < kwL.length + 35) {
                        let foundInp = null;

                        const fc = l.closest('.MuiFormControl-root');
                        if (fc && !isInConclusion(fc)) {
                            const inps = getRealInputs(fc);
                            if (inps.length === 1) foundInp = inps[0];
                        }

                        if (!foundInp) {
                            let p = l.parentElement;
                            for (let i = 0; i < 3 && p && p !== document.body && !isInConclusion(p); i++) {
                                const inps = getRealInputs(p);
                                if (inps.length === 1) {
                                    foundInp = inps[0];
                                    break;
                                } else if (inps.length > 1) {
                                    break;
                                }
                                p = p.parentElement;
                            }
                        }

                        if (foundInp && !res.includes(foundInp)) {
                            const holderText = ((foundInp.closest('.MuiFormControl-root') || foundInp.parentElement)?.innerText || '').trim().toLowerCase();
                            if (!searchingFio && (holderText === 'фио' || holderText.startsWith('фио\n') || holderText.includes('фио пациента'))) continue;
                            if (searchingFio) {
                                const rowTxt = (foundInp.closest('.MuiGrid-container') || foundInp.parentElement?.parentElement?.parentElement)?.innerText || '';
                                if (rowTxt.includes('Должность') || rowTxt.includes('передачи мед.работнику')) continue;
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

        async function selectDropdown(keywords, valStr, forceMulti = false) {
            if (!valStr) return;
            const vals = String(valStr).split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
            if (vals.length === 0) return;

            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
            const ctrls = [];
            for (let kw of keywords) {
                const kwL = kw.toLowerCase();
                for (let l of labels) {
                    if (!l.innerText || l.offsetParent === null || l.children.length > 3 || isInConclusion(l)) continue;
                    const t = l.innerText.trim().toLowerCase();
                    if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 35) {
                        let p = l.closest('.MuiFormControl-root, .MuiGrid-item') || l.parentElement;
                        for (let i = 0; i < 3 && p && p !== document.body && !isInConclusion(p); i++) {
                            if (p.querySelector('.MuiSelect-root, [role="button"]')) {
                                if (!ctrls.includes(p)) ctrls.push(p);
                                break;
                            }
                            p = p.parentElement;
                        }
                    }
                }
            }

            for (let c of ctrls) {
                const btn = c.querySelector('.MuiSelect-root, [role="button"]');
                if (!btn || btn.offsetParent === null || btn.classList.contains('Mui-disabled') || isInConclusion(btn)) continue;

                const isMulti = forceMulti || btn.classList.contains('MuiSelect-multiple') || vals.length > 1;
                const curText = (btn.innerText || '').trim().toLowerCase();

                if (!isMulti && curText === vals[0]) continue;
                if (isMulti && vals.every(v => curText.includes(v))) continue;

                await closeAnyOpenMenu();

                btn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                btn.click();
                restoreScreenPosition();
                await new Promise(r => setTimeout(r, 105));

                const activePops = getActivePopovers();
                const popover = activePops.length > 0 ? activePops[activePops.length - 1] : document;
                const opts = Array.from(popover.querySelectorAll('li[role="option"], .MuiMenuItem-root'));

                if (opts.length > 0) {
                    const hasCheckboxes = opts.some(o => !!o.querySelector('input[type="checkbox"], .MuiCheckbox-root'));
                    const treatAsMulti = isMulti || hasCheckboxes;

                    if (!treatAsMulti) {
                        const match = opts.find(o => {
                            const ot = (o.innerText || '').trim().toLowerCase();
                            return ot !== "не выбрано" && (ot === vals[0] || ot.includes(vals[0]) || vals[0].includes(ot));
                        });
                        if (match) {
                            match.click();
                            restoreScreenPosition();
                            await new Promise(r => setTimeout(r, 85));
                            if (getActivePopovers().length > 0) await closeAnyOpenMenu();
                        } else {
                            await closeAnyOpenMenu();
                        }
                    } else {
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
                                restoreScreenPosition();
                                await new Promise(r => setTimeout(r, 35));
                            }
                        }
                        await new Promise(r => setTimeout(r, 35));
                        await closeAnyOpenMenu();
                    }
                } else {
                    await closeAnyOpenMenu();
                }
            }
        }

        async function fillMkbAutocomplete(mkbStr) {
            if (!mkbStr) return;
            const cleanMkb = String(mkbStr).trim();
            if (!cleanMkb) return;

            const codeMatch = cleanMkb.match(/\[?([A-ZА-Я]\d{2}(?:\.\d+)?)\]?/i);
            const searchCode = codeMatch ? codeMatch[1].toUpperCase() : cleanMkb;

            const mkbInputs = findInputs(["код по мкб"]);
            if (mkbInputs.length === 0) return;
            const inp = mkbInputs[0];

            if (inp.value && inp.value.toUpperCase().includes(searchCode)) return;

            await closeAnyOpenMenu();

            const autoRoot = inp.closest('.MuiAutocomplete-root') || inp.parentElement;
            const popupBtn = autoRoot ? autoRoot.querySelector('.MuiAutocomplete-popupIndicator, button') : null;

            try { inp.focus({ preventScroll: true }); } catch (e) { inp.focus(); }
            restoreScreenPosition();
            setVal(inp, searchCode);
            if (popupBtn) popupBtn.click();
            restoreScreenPosition();
            await new Promise(r => setTimeout(r, 210));

            const popper = document.querySelector('.MuiAutocomplete-popper, .MuiPopover-root');
            if (popper) {
                const opts = Array.from(popper.querySelectorAll('li[role="option"], .MuiAutocomplete-option, .MuiMenuItem-root'));
                const targetOpt = opts.find(o => {
                    const t = (o.innerText || '').toUpperCase();
                    return t.includes(`[${searchCode}]`) || t.startsWith(searchCode) || t.includes(searchCode);
                }) || opts[0];

                if (targetOpt) {
                    targetOpt.click();
                    restoreScreenPosition();
                    await new Promise(r => setTimeout(r, 75));
                }
            }
            if (inp.blur) inp.blur();
            await closeAnyOpenMenu();
        }

        function clickButtonInRow(rowKeywords, buttonChoices) {
            if (!buttonChoices) return;
            const choices = (Array.isArray(buttonChoices) ? buttonChoices : [buttonChoices])
                .map(c => String(c).trim().toUpperCase()).filter(Boolean);
            if (choices.length === 0) return;

            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
            for (let kw of rowKeywords) {
                const kwL = kw.toLowerCase();
                const targets = labels.filter(el => {
                    if (!el.innerText || el.offsetParent === null || el.children.length > 3 || isInConclusion(el)) return false;
                    const t = el.innerText.trim().toLowerCase();
                    return (t === kwL || t.includes(kwL)) && t.length < kwL.length + 35;
                });

                for (let target of targets) {
                    let p = target.closest('.MuiFormControl-root, .MuiGrid-item') || target.parentElement;
                    for (let i = 0; i < 4 && p && p !== document.body && !isInConclusion(p); i++) {
                        const btns = Array.from(p.querySelectorAll('button, div[role="button"]')).filter(b => b.offsetParent !== null && !b.disabled && !isInConclusion(b));
                        const desired = btns.find(b => {
                            const bt = (b.innerText || '').trim().toUpperCase();
                            return choices.some(ch => bt === ch || (ch.includes("СИММЕТР") && ch.startsWith("А") && bt.startsWith("А") && bt.includes("СИМ")));
                        });
                        if (desired) {
                            const s = window.getComputedStyle(desired);
                            const isWhite = s.color.includes('255, 255, 255');
                            const isColored = !s.backgroundColor.includes('rgba(0, 0, 0, 0)') && !s.backgroundColor.includes('255, 255, 255');
                            const isAlreadyOn = (isWhite && isColored) || desired.className.includes('MuiButton-contained');
                            if (!isAlreadyOn) desired.click();
                            break;
                        }
                        p = p.parentElement;
                    }
                }
            }
        }

        function fillBP(isAd, raw) {
            const str = normBp(raw); if (!str.includes('/')) return;
            const p = str.split('/');
            Array.from(document.querySelectorAll('*')).filter(e => e.offsetParent !== null && e.children.length === 0 && e.textContent.trim() === '/' && !isInConclusion(e)).forEach(sl => {
                let pr = sl.parentElement;
                for (let i = 0; i < 3 && pr && pr !== document.body; i++) {
                    const inps = getRealInputs(pr);
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

        // 1. ПАСПОРТНЫЕ ДАННЫЕ, ПОЛ, ДАТА РОЖДЕНИЯ, ВОЗРАСТ, АПО
        if (data.fio) setAll(["фио"], data.fio, true);

        if (data.gender) {
            const gUp = String(data.gender).trim().toUpperCase();
            const gTarget = (gUp.startsWith("М") || gUp === "TRUE") ? ["МУЖЧИНА", "МУЖСКОЙ"] : (gUp.startsWith("Ж") || gUp === "FALSE") ? ["ЖЕНЩИНА", "ЖЕНСКИЙ"] : [gUp];
            clickButtonInRow(["пол"], gTarget);
        }

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

        const unitVal = data.ageUnit || window.lastLoadedApiCard?.ageType?.type || "Лет";
        await selectDropdown(["возраст (ед.)"], unitVal, false);

        if (data.address) setAll(["место жительства (пребывания)"], data.address);
        if (data.apo) await selectDropdown(["территориальная апо", "апо по месту жительства"], data.apo, false);

        // 2. ЖАЛОБЫ, АНАМНЕЗ, ПЕРЕНЕСЕННЫЕ ЗАБОЛЕВАНИЯ, ПРЕДВАРИТЕЛЬНЫЙ ДИАГНОЗ, КОД ПО МКБ
        setAll(["жалобы"], data.complaints);
        setAll(["анамнез заболевания"], data.anamnesis);
        setAll(["перенесенные заболевания"], data.pastDiseases);
        setAll(["предварительный диагноз"], data.diagnosis);
        setAll(["локальный статус"], data.localStatus);
        if (data.mkb) await fillMkbAutocomplete(data.mkb);

        // 3. АД, ПУЛЬС, ЧДД, ТЕМПЕРАТУРА
        if (data.bpAdapted) fillBP(true, data.bpAdapted);
        if (data.bp) fillBP(false, data.bp);
        if (data.pulse) setAll(["комментарий (пульс", "пульс, ударов в минуту"], data.pulse);
        if (data.rr) setAll(["частота дыхания (в минуту)"], data.rr);
        if (data.temp) setAll(["температура тела (°c)"], String(data.temp).replace(',', '.'));
        if (data.saturation) setAll(["сатурация 1 (%)"], data.saturation);
        if (data.glucose) setAll(["гликемия 1 (ммоль/л)"], String(data.glucose).replace(',', '.'));

        // 4. ЭКГ И НЕВРОЛОГИЯ
        setAll(["экг до оказания"], data.ecgBefore);
        setAll(["экг после оказания"], data.ecgAfter);
        setAll(["шкала комы глазго (баллы)"], data.glasgowScore || "15");
        setAll(["тонус мышц"], data.muscleTone || "Нормальный");
        setAll(["патологические рефлексы"], data.pathologicalReflexes || "Отрицательные");
        setAll(["менингиальные знаки"], data.meningealSymptoms || "Отрицательные");
        setAll(["плегии, парезы"], data.plegia || "Нет");
        setAll(["язык"], data.tongue || "Чистые");
        setAll(["слизистые"], data.mucous || "Бледно-розовые");

        // 5. КНОПКИ СОСТОЯНИЯ И НЕВРОЛОГИИ + ДОП. КОММЕНТАРИИ
        clickButtonInRow(["положение"], data.position || "АКТИВНОЕ");
        if (data.positionDescription) setAll(["комментарий (вынужденное)"], data.positionDescription);

        clickButtonInRow(["поведение"], data.behavior || "СПОКОЙНОЕ");
        if (data.pupils) clickButtonInRow(["зрачки"], [data.pupils, data.pupils.replace("АНИЗОКОРИЯ", "АНИЗОКАРИЯ")]);
        clickButtonInRow(["речь"], data.speech || "СОХРАНЕНА");
        clickButtonInRow(["лицо"], data.face || "СИММЕТРИЧНОЕ");
        clickButtonInRow(["походка"], data.gait || "СВОБОДНАЯ");
        clickButtonInRow(["нистагм"], data.nystagmus || "НЕТ");
        clickButtonInRow(["печень"], data.liver || "НЕ УВЕЛИЧЕНА");
        clickButtonInRow(["мочеиспускание"], data.urination || "НЕ НАРУШЕНО");
        clickButtonInRow(["стул"], data.stool || "НЕ НАРУШЕН");

        // 6. КНОПКИ "ДА / НЕТ"
        clickButtonInRow(["аллергия"], data.allergy || "НЕТ");
        clickButtonInRow(["реакция на свет"], data.lightReaction || "ДА");
        clickButtonInRow(["высыпания"], data.rash || "НЕТ");
        clickButtonInRow(["пульс, ударов в минуту"], data.pulseExists || (data.pulse === "0" ? "НЕТ" : "ДА"));
        clickButtonInRow(["асцит"], data.ascites || "НЕТ");
        clickButtonInRow(["периферические отеки"], data.edema || "НЕТ");
        if (data.edemaDescription) setAll(["комментарий (периферические отеки)"], data.edemaDescription);

        clickButtonInRow(["запах алкоголя"], data.alcohol || "НЕТ");
        clickButtonInRow(["запах ацетона"], data.acetone || "НЕТ");
        clickButtonInRow(["симптомы раздражения брюшины"], data.peritoneal || "НЕТ");
        clickButtonInRow(["перистальтика"], data.peristalsis || "ДА");
        clickButtonInRow(["получено устное согласие на простое медицинское вмешательство", "получено устное согласие"], "ДА");

        // 7. ВЫПАДАЮЩИЕ СПИСКИ MATERIAL-UI
        await selectDropdown(["общее состояние"], data.generalState || "Удовлетворительное", false);
        await selectDropdown(["сознание"], data.consciousness || "Ясное", false);

        if (data.skin) await selectDropdown(["кожа"], data.skin, true);
        if (data.heartSounds) await selectDropdown(["тоны сердца"], data.heartSounds, true);
        if (data.lungs) await selectDropdown(["дыхание"], data.lungs, true);
        if (data.percussion) await selectDropdown(["перкуссия легких"], data.percussion, true);
        if (data.abdomen) await selectDropdown(["живот"], data.abdomen, true);

        await closeAnyOpenMenu();
        restoreScreenPosition();
    } catch (e) { console.error(e); } finally {
        await new Promise(r => setTimeout(r, 70));
        await closeAnyOpenMenu();
        if (activeTabBefore) {
            const curActive = Array.from(document.querySelectorAll('[role="tab"], button'))
                .find(b => b.getBoundingClientRect().top < 140 && (b.getAttribute('aria-selected') === 'true' || b.className.includes('Mui-selected')));
            if (curActive !== activeTabBefore) activeTabBefore.click();
        }
        restoreScreenPosition();
        setTimeout(restoreScreenPosition, 50);

        document.getElementById('b103-hide-menus')?.remove();
        if (fillBtn) fillBtn.innerText = "⚡";
        setTimeout(() => { window.b103IsApplying = false; }, 200);
    }
};

// ==========================================
// ТОЧЕЧНАЯ ВСТАВКА ИЗ КОНСТРУКТОРОВ (ЭКГ, СЛР, ФРАЗЫ) С ЖЕСТКОЙ ЗАЩИТОЙ ВКЛАДКИ ЗАКЛЮЧЕНИЕ
// ==========================================
window.insertIntoCardField = function(fieldKeyword, textToInsert, appendMode) {
    try {
        const kwL = String(fieldKeyword).toLowerCase();
        const isInConclusion = window.b103IsInConclusion;
        const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
        
        for (let l of labels) {
            // Защита: полностью игнорируем любые надписи внутри вкладки Заключение
            if (!l.innerText || l.offsetParent === null || l.children.length > 3 || isInConclusion(l)) continue;
            
            const t = l.innerText.trim().toLowerCase();
            if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 35) {
                const fc = l.closest('.MuiFormControl-root') || l.parentElement?.parentElement;
                if (!fc || isInConclusion(fc)) continue;

                const inps = Array.from(fc.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])'))
                    .filter(el => el.offsetParent !== null && el.getAttribute('aria-hidden') !== 'true' && el.tabIndex !== -1 && !isInConclusion(el));

                if (inps.length > 0) {
                    const inp = inps[0];
                    const cur = (inp.value || '').trim();
                    const finalVal = (appendMode && cur) ? (cur + "\n" + textToInsert) : textToInsert;
                    const proto = inp.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                    if (setter) setter.call(inp, finalVal); else inp.value = finalVal;
                    if (inp._valueTracker) inp._valueTracker.setValue('');
                    inp.dispatchEvent(new Event('input', { bubbles: true }));
                    inp.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                }
            }
        }
    } catch (e) {}
    return false;
};
