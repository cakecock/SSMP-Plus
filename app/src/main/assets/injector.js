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

    function findVisMui(kws) {
        const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, .MuiInputLabel-root, label'));
        for (let kw of kws) {
            for (let t of labels.filter(l => l.innerText && l.innerText.trim().toLowerCase().includes(kw.toLowerCase()))) {
                const inp = t.closest('.MuiFormControl-root, .MuiGrid-item')?.querySelector('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])');
                if (inp && inp.offsetParent !== null) return inp;
            }
        }
        return null;
    }

    function scrapeCache() {
        const c = window.b103ScreenCache;
        const g = (k) => findVisMui([k])?.value?.trim();
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
            const fio = findVisMui(["фио"])?.value?.trim() || "";
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

window.applyAutofill = async function(jsonStr) {
    if (window.b103IsApplying) return;
    window.b103IsApplying = true;
    const fillBtn = document.getElementById('btn-fill-db');
    if (fillBtn) fillBtn.innerText = "⏳";

    try {
        const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;

        function normBp(v) {
            if (!v) return "";
            if (typeof v === 'object') return (v.sys || v.dis) ? `${v.sys||''}/${v.dis||''}` : "";
            const s = String(v).trim();
            if (s.startsWith("{")) { try { const o = JSON.parse(s); return `${o.sys||''}/${o.dis||''}`; } catch(e){ return ""; } }
            return s;
        }

        function setVal(inp, val, skipFocus = false) {
            if (!inp || val === undefined || val === null || inp.disabled) return;
            const str = String(val).trim();
            if (!str || inp.value.trim() === str) return;
            try {
                if (!skipFocus) inp.focus({ preventScroll: true });
                const proto = inp.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(inp, str); else inp.value = str;
                if (inp._valueTracker) inp._valueTracker.setValue('');
                inp.dispatchEvent(new Event('input', { bubbles: true }));
                inp.dispatchEvent(new Event('change', { bubbles: true }));
                if (!skipFocus) inp.blur();
            } catch (e) { inp.value = str; }
        }

        function findAll(kws, exact = false) {
            const res = [], labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, .MuiInputLabel-root, label'));
            for (let kw of kws) {
                for (let l of labels) {
                    const t = (l.innerText || '').trim().toLowerCase();
                    if (exact ? t === kw.toLowerCase() : t.includes(kw.toLowerCase())) {
                        const inp = l.closest('.MuiFormControl-root, .MuiGrid-item')?.querySelector('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])');
                        if (inp && inp.offsetParent !== null && !res.includes(inp)) res.push(inp);
                    }
                }
            }
            return res;
        }

        const setAll = (kws, val, ex = false) => { if (val) findAll(kws, ex).forEach(i => setVal(i, val, false)); };

        async function selDrop(kws, valStr, ex = false) {
            if (!valStr) return;
            const vals = String(valStr).split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
            const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, .MuiInputLabel-root, label'));
            const ctrls = [];
            for (let kw of kws) {
                for (let l of labels) {
                    const t = (l.innerText || '').trim().toLowerCase();
                    if (ex ? t === kw.toLowerCase() : t.includes(kw.toLowerCase())) {
                        const c = l.closest('.MuiFormControl-root, .MuiGrid-item');
                        if (c && c.offsetParent !== null && !ctrls.includes(c)) ctrls.push(c);
                    }
                }
            }
            for (let c of ctrls) {
                if (vals.every(v => (c.innerText || '').toLowerCase().includes(v))) continue;
                const btn = c.querySelector('.MuiSelect-root, [role="button"], .MuiAutocomplete-popupIndicator');
                if (btn && btn.offsetParent !== null) {
                    const multi = btn.classList.contains('MuiSelect-multiple') || vals.length > 1;
                    btn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                    await new Promise(r => setTimeout(r, 95));
                    const opts = Array.from(document.querySelectorAll('li[role="option"], .MuiMenuItem-root, .MuiAutocomplete-option'));
                    for (let v of vals) {
                        const m = opts.find(o => o.innerText?.trim().toLowerCase() === v) || opts.find(o => o.innerText?.trim().toLowerCase().includes(v));
                        if (m && (!multi || (!m.classList.contains('Mui-selected') && m.getAttribute('aria-selected') !== 'true'))) {
                            m.click(); await new Promise(r => setTimeout(r, 45));
                        }
                    }
                    document.querySelectorAll('.MuiBackdrop-root, .MuiPopover-root > div[aria-hidden="true"]').forEach(b => b.click());
                    document.querySelector('ul[role="listbox"]')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
                    await new Promise(r => setTimeout(r, 45));
                }
            }
        }

        function selBtn(txt) {
            if (!txt) return;
            document.querySelectorAll('button, div[role="button"]').forEach(b => {
                if (b.offsetParent !== null && b.innerText?.trim().toUpperCase() === txt.toUpperCase()) {
                    const s = window.getComputedStyle(b);
                    const on = (s.color.includes('255, 255, 255') && !s.backgroundColor.includes('0, 0, 0, 0') && !s.backgroundColor.includes('255, 255, 255')) || b.className.includes('MuiButton-contained');
                    if (!on) b.click();
                }
            });
        }

        function selYN(kws, ch) {
            if (!ch) return;
            const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, .MuiInputLabel-root, label'));
            for (let kw of kws) {
                labels.filter(l => l.offsetParent !== null && l.innerText?.trim().toLowerCase().includes(kw)).forEach(t => {
                    let p = t.closest('.MuiFormControl-root, .MuiGrid-item') || t.parentElement;
                    for (let i = 0; i < 3 && p && p !== document.body; i++) {
                        const d = Array.from(p.querySelectorAll('button, div[role="button"]')).find(b => b.innerText?.trim().toUpperCase() === ch.toUpperCase());
                        if (d) {
                            const s = window.getComputedStyle(d);
                            const on = (s.color.includes('255, 255, 255') && !s.backgroundColor.includes('0, 0, 0, 0') && !s.backgroundColor.includes('255, 255, 255')) || d.className.includes('MuiButton-contained');
                            if (!on) d.click(); break;
                        }
                        p = p.parentElement;
                    }
                });
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

        if (data.fio) setAll(["фио"], data.fio, true);
        if (data.age) setAll(["возраст"], data.age, true);
        await selDrop(["возраст (ед.)"], data.ageUnit || window.lastLoadedApiCard?.ageType?.type || "Лет", true);
        if (data.address) setAll(["место жительства (пребывания)"], data.address);

        setAll(["жалобы"], data.complaints); setAll(["анамнез заболевания"], data.anamnesis);
        setAll(["перенесенные заболевания"], data.pastDiseases); setAll(["предварительный диагноз"], data.diagnosis);
        setAll(["локальный статус"], data.localStatus);

        if (data.bpAdapted) fillBP(true, data.bpAdapted);
        if (data.bp) fillBP(false, data.bp);
        if (data.pulse) setAll(["пульс, ударов в минуту", "комментарий (пульс", "пульс (в минуту)"], data.pulse);
        if (data.rr) setAll(["частота дыхания (в минуту)"], data.rr);
        if (data.temp) setAll(["температура тела (°c)", "температура (°c)"], data.temp);
        if (data.saturation) setAll(["сатурация 1 (%)"], data.saturation);
        if (data.glucose) setAll(["гликемия 1 (ммоль/л)"], data.glucose);

        setAll(["экг до оказания"], data.ecgBefore); setAll(["экг после оказания"], data.ecgAfter);
        setAll(["шкала комы глазго (баллы)"], data.glasgowScore || "15");
        setAll(["тонус мышц"], "Норма"); setAll(["патологические рефлексы"], "Нет");
        setAll(["менингиальные знаки"], "Нет"); setAll(["плегии, парезы"], "Нет");
        setAll(["язык"], data.tongue || "Влажный, не обложен"); setAll(["слизистые"], data.mucous || "Чистые");

        if (data.gender) selBtn(data.gender);
        selBtn(data.position || "АКТИВНОЕ"); selBtn(data.behavior || "СПОКОЙНОЕ");
        if (data.pupils) selBtn(data.pupils);
        selBtn(data.speech || "СОХРАНЕНА"); selBtn(data.face || "СИММЕТРИЧНОЕ");
        selBtn(data.gait || "СВОБОДНАЯ"); selBtn(data.nystagmus || "НЕТ");
        selBtn(data.liver || "НЕ УВЕЛИЧЕНА"); selBtn(data.urination || "НЕ НАРУШЕНО");
        selBtn(data.attack || "КУПИРОВАН"); selBtn(data.therapyEffect || "УЛУЧШЕНИЕ");
        if (data.callResult) selBtn(data.callResult);

        selYN(["аллергия"], data.allergy || "НЕТ"); selYN(["реакция на свет"], data.lightReaction || "ДА");
        selYN(["высыпания"], "НЕТ"); selYN(["перистальтика"], data.peristalsis || "ДА");
        selYN(["симптомы раздражения брюшины"], "НЕТ"); selYN(["асцит"], "НЕТ");
        selYN(["периферические отеки"], "НЕТ"); selYN(["запах алкоголя"], "НЕТ"); selYN(["запах ацетона"], "НЕТ");

        await selDrop(["общее состояние"], data.generalState || "Удовлетворительное", true);
        await selDrop(["сознание"], data.consciousness || "Ясное", true);
        if (data.skin) await selDrop(["кожа"], data.skin, true);
        if (data.heartSounds) await selDrop(["тоны сердца"], data.heartSounds, true);
        if (data.lungs) await selDrop(["дыхание"], data.lungs, true);
        if (data.percussion) await selDrop(["перкуссия легких"], data.percussion, true);
        if (data.abdomen) await selDrop(["живот"], data.abdomen, true);
    } catch (e) { console.error(e); } finally {
        if (fillBtn) fillBtn.innerText = "⚡";
        setTimeout(() => { window.b103IsApplying = false; }, 200);
    }
};
