(function() {
    window.lastLoadedApiCard = window.lastLoadedApiCard || null;
    window.activeAutofillData = window.activeAutofillData || null;
    window.b103ScreenCache = window.b103ScreenCache || {};
    window.b103IsApplying = false;

    // ==========================================
    // 0. БАЗОВЫЕ УТИЛИТЫ И ГРАНИЦА «ЗАКЛЮЧЕНИЕ»
    // ==========================================
    function cleanVal(v) {
        if (v === undefined || v === null) return '';
        if (typeof v === 'string') {
            const s = v.trim();
            return (s === 'null' || s.startsWith('[object')) ? '' : s;
        }
        if (typeof v === 'number' || typeof v === 'boolean') return String(v);
        if (Array.isArray(v)) return v.map(cleanVal).filter(Boolean).join(', ');
        if (typeof v === 'object') return cleanVal(v.type || v.name || v.display || v.title || '');
        return '';
    }

    function cleanBpString(raw) {
        if (!raw) return '';
        if (typeof raw === 'object') {
            const s = String(raw.sys || raw.systolic || '').replace(/null/g, '').trim();
            const d = String(raw.dis || raw.dia || raw.diastolic || '').replace(/null/g, '').trim();
            return (s || d) ? `${s}/${d}` : '';
        }
        const str = String(raw).replace(/null/g, '').trim();
        if (!str) return '';
        if (str.startsWith('{')) {
            try {
                const o = JSON.parse(str);
                const s = String(o.sys || o.systolic || '').replace(/null/g, '').trim();
                const d = String(o.dis || o.dia || o.diastolic || '').replace(/null/g, '').trim();
                return (s || d) ? `${s}/${d}` : '';
            } catch (e) { return ''; }
        }
        return (str.includes('ул.') || /\d{2}\.\d{2}\.\d{4}/.test(str)) ? '' : str;
    }

    let cachedAnchor = null;
    let anchorTime = 0;
    function getConclusionAnchor() {
        const now = (window.performance && performance.now) ? performance.now() : Date.now();
        if (cachedAnchor && (now - anchorTime < 2500) && cachedAnchor.isConnected) {
            return cachedAnchor;
        }
        const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, h6, .MuiTypography-root'));
        cachedAnchor = labels.find(e => {
            if (!e.textContent || e.children.length > 2) return false;
            const t = e.textContent.trim();
            return t === 'Результат оказания мед. помощи' || t === 'Результат выезда';
        }) || null;
        anchorTime = now;
        return cachedAnchor;
    }

    function isInConclusion(el) {
        if (!el) return false;
        const anchor = getConclusionAnchor();
        if (!anchor) return false;
        return Boolean(anchor.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING);
    }
    window.b103IsInConclusion = isInConclusion;

    // ==========================================
    // 1. СЕТЕВОЙ ПЕРЕХВАТЧИК КАРТЫ СЕРВЕРА
    // ==========================================
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

    // ==========================================
    // 2. DOM-СБОР КЭША С ЭКРАНА (ПО КНОПКЕ 💾 И В ШАБЛОН)
    // ==========================================
    function getRealInputs(container) {
        return Array.from(container.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])'))
            .filter(el => !el.classList.contains('MuiSelect-nativeInput') && !el.classList.contains('MuiTextareaAutosize-shadow') && el.getAttribute('aria-hidden') !== 'true' && el.tabIndex !== -1 && !isInConclusion(el));
    }

    function findInputByKw(keywords, exact = false) {
        const all = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
        for (let kw of keywords) {
            const kwL = kw.toLowerCase();
            const target = all.find(el => {
                if (!el.textContent) return false;
                const t = el.textContent.trim().toLowerCase();
                return (exact ? t === kwL : (t === kwL || t.includes(kwL))) && t.length < kwL.length + 35;
            });
            if (target) {
                const fc = target.closest('.MuiFormControl-root');
                if (fc && !isInConclusion(fc)) {
                    const inps = getRealInputs(fc);
                    if (inps.length > 0) return inps[0];
                }
                let p = target.parentElement;
                for (let i = 0; i < 3 && p && p !== document.body; i++) {
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
        const setValIfFound = (kw, fieldName, exact = false) => {
            const v = findInputByKw(Array.isArray(kw) ? kw : [kw], exact)?.value?.trim();
            if (v && !v.startsWith('__')) c[fieldName] = v;
        };

        setValIfFound("гражданство", "citizenship", true);
        setValIfFound("нас. пункт", "settlement");
        setValIfFound("улица", "street");
        setValIfFound("дом", "building", true);
        setValIfFound("квартира", "flat", true);
        setValIfFound("уточнение места жительства", "clarificationAddress");
        setValIfFound("место жительства (пребывания)", "address");
        setValIfFound("экг до оказания", "electrocardiogramBefore");
        setValIfFound("экг после оказания", "electrocardiogramAfter");
        setValIfFound("предварительный диагноз", "diagnosis");
        setValIfFound("жалобы", "complaints");
        setValIfFound("анамнез заболевания", "anamnesis");
        setValIfFound("перенесенные заболевания", "pastIllnesses");
        setValIfFound("локальный статус", "localStatus");
        setValIfFound("возраст", "age", true);
        setValIfFound(["комментарий (пульс", "пульс, ударов в минуту"], "pulse");
        setValIfFound("частота дыхания (в минуту)", "breathCount");
        setValIfFound("температура тела (°c)", "skinTemp");
        setValIfFound("сатурация 1 (%)", "saturation1");
        setValIfFound("гликемия 1 (ммоль/л)", "glycemia1");
        setValIfFound("шкала комы глазго (баллы)", "comaScale");
        setValIfFound("тонус мышц", "muscleTone");
        setValIfFound("патологические рефлексы", "pathologicalReflexes");
        setValIfFound("менингиальные знаки", "meningealSymptoms");
        setValIfFound("плегии, парезы", "plegia");
        setValIfFound("язык", "tongue");
        setValIfFound("слизистые", "mucosa");
        setValIfFound("комментарий (вынужденное)", "positionDescription");
        setValIfFound("комментарий (периферические отеки)", "peripheralEdemaDescription");

        const mkbVal = findInputByKw(["код по мкб"])?.value?.trim();
        if (mkbVal) {
            const m = mkbVal.match(/^\[?([A-ZА-Я]\d{2}(?:\.\d+)?)\]?\s*(.*)$/i);
            if (m) c.icd = { code: m[1].toUpperCase(), name: m[2].trim() };
        }

        const slashNodes = Array.from(document.querySelectorAll('span, p, .MuiTypography-root'))
            .filter(e => e.children.length === 0 && e.textContent?.trim() === '/' && !isInConclusion(e));
        for (const sl of slashNodes) {
            let pr = sl.parentElement;
            for (let i = 0; i < 3 && pr && pr !== document.body; i++) {
                const inps = getRealInputs(pr);
                if (inps.length === 2) {
                    const tx = (pr.innerText || pr.parentElement?.innerText || '').toLowerCase();
                    const s = inps[0].value.trim(), d = inps[1].value.trim();
                    if (s || d) {
                        if (tx.includes("адаптированное")) c.arterialPressureAdaptive = { sys: s, dia: d };
                        else if (tx.includes("ад (мм") || tx.includes("артериальн")) c.arterialPressure = { sys: s, dia: d };
                    }
                    break;
                }
                pr = pr.parentElement;
            }
        }

        const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
        const getSelectText = (kw) => {
            const kwL = kw.toLowerCase();
            const l = labels.find(e => e.textContent && !isInConclusion(e) && (e.textContent.trim().toLowerCase() === kwL || e.textContent.trim().toLowerCase().includes(kwL)) && e.textContent.trim().length < kwL.length + 35);
            if (!l) return '';
            let p = l.closest('.MuiFormControl-root') || l.parentElement;
            for (let i = 0; i < 3 && p && p !== document.body; i++) {
                const b = p.querySelector('.MuiSelect-root, [role="button"]');
                if (b) {
                    const t = (b.innerText || '').trim();
                    return (t && t.toLowerCase() !== 'не выбрано') ? t : '';
                }
                p = p.parentElement;
            }
            return '';
        };
        const gs = getSelectText("общее состояние"); if (gs) c.generalState = { type: gs };
        const cs = getSelectText("сознание"); if (cs) c.consciousness = { type: cs };
        const au = getSelectText("возраст (ед.)"); if (au) c.ageType = { type: au };
        const ap = getSelectText("территориальная апо") || getSelectText("апо по месту жительства"); if (ap) c.apo = ap;
        const toArr = (kw, key) => {
            const v = getSelectText(kw);
            if (v) c[key] = v.split(',').map(s => ({ type: s.trim() })).filter(x => x.type);
        };
        toArr("кожа", "skins");
        toArr("тоны сердца", "heartSounds");
        toArr("дыхание", "breaths");
        toArr("перкуссия легких", "percussionOfLungs");
        toArr("живот", "stomachs");

        const getActiveBtnText = (kw) => {
            const kwL = kw.toLowerCase();
            const l = labels.find(e => e.textContent && !isInConclusion(e) && (e.textContent.trim().toLowerCase() === kwL || e.textContent.trim().toLowerCase().includes(kwL)) && e.textContent.trim().length < kwL.length + 35);
            if (!l) return null;
            let p = l.closest('.MuiFormControl-root') || l.parentElement;
            for (let i = 0; i < 3 && p && p !== document.body; i++) {
                const btns = Array.from(p.querySelectorAll('button')).filter(b => !isInConclusion(b));
                if (btns.length >= 2 && btns.length <= 6) {
                    const act = btns.find(b => b.className.includes('MuiButton-contained'));
                    return act ? (act.innerText || act.textContent || '').trim().toUpperCase() : '';
                }
                p = p.parentElement;
            }
            return null;
        };
        const pup = getActiveBtnText("зрачки"); if (pup !== null) c.pupils = pup ? { type: pup } : null;
        const pos = getActiveBtnText("положение"); if (pos) c.position = { type: pos };
        const beh = getActiveBtnText("поведение"); if (beh) c.behavior = { type: beh };
        const spc = getActiveBtnText("речь"); if (spc) c.speech = { type: spc };
        const fac = getActiveBtnText("лицо"); if (fac) c.face = { type: fac };
        const gat = getActiveBtnText("походка"); if (gat) c.gait = { type: gat };
        const nys = getActiveBtnText("нистагм"); if (nys) c.nystagmus = { type: nys };
        const stu = getActiveBtnText("стул"); if (stu) c.defecatio = { type: stu };
        const uri = getActiveBtnText("мочеиспускание"); if (uri) c.urinations = [{ type: uri }];
        const liv = getActiveBtnText("печень"); if (liv) c.liver = { type: liv };
        const alr = getActiveBtnText("аллергия"); if (alr) c.allergy = (alr === "ДА");
        const lgt = getActiveBtnText("реакция на свет"); if (lgt) c.pupilsLight = (lgt === "ДА");
        const rsh = getActiveBtnText("высыпания"); if (rsh) c.skinRashes = (rsh === "ДА");
        const asc = getActiveBtnText("асцит"); if (asc) c.ascites = (asc === "ДА");
        const edm = getActiveBtnText("периферические отеки"); if (edm) c.peripheralEdema = (edm === "ДА");
        const prt = getActiveBtnText("симптомы раздражения брюшины"); if (prt) c.stomachSymptoms = (prt === "ДА");
        const prs = getActiveBtnText("перистальтика"); if (prs) c.peristalsis = (prs === "ДА");

        const qtyNodes = Array.from(document.querySelectorAll('span, p, .MuiTypography-root'))
            .filter(e => e.children.length === 0 && e.textContent && e.textContent.includes("Количество:"));

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
        return Boolean(document.querySelector('.MuiTabs-root, [role="tablist"]'));
    }

    window.getCurrentCardRawJson = function() {
        try {
            if (!isInsideCard() || !window.lastLoadedApiCard) return '';
            scrapeCache();
            return JSON.stringify(Object.assign({}, window.lastLoadedApiCard, window.b103ScreenCache));
        } catch (e) {
            return '';
        }
    };

    // ==========================================
    // 2.5 КОМПАКТНАЯ ПЕРЕТАСКИВАЕМАЯ КАПСУЛА КНОПОК
    // ==========================================
    const DOCK_KEY = 'b103_dock_state_v1';
    function loadDockState() {
        try {
            const raw = localStorage.getItem(DOCK_KEY);
            if (raw) {
                const s = JSON.parse(raw);
                return {
                    side: s.side === 'left' ? 'left' : 'right',
                    topPct: (typeof s.topPct === 'number' && s.topPct >= 0.08 && s.topPct <= 0.88) ? s.topPct : 0.84,
                    collapsed: Boolean(s.collapsed)
                };
            }
        } catch (e) {}
        return { side: 'right', topPct: 0.84, collapsed: false };
    }
    function saveDockState(st) {
        try { localStorage.setItem(DOCK_KEY, JSON.stringify(st)); } catch (e) {}
    }

    window.b103DockState = window.b103DockState || loadDockState();
    let isTypingInField = false;
    let forceExpandWhileTyping = false;
    let suppressClickUntil = 0;
    let lastInCardState = null;

    function isEditableEl(el) {
        if (!el) return false;
        const tag = (el.tagName || '').toUpperCase();
        if (tag === 'TEXTAREA') return true;
        if (tag === 'INPUT') {
            const tp = (el.type || 'text').toLowerCase();
            return !['button', 'submit', 'checkbox', 'radio', 'file', 'hidden'].includes(tp);
        }
        return Boolean(el.isContentEditable);
    }

    function applyDockLayout() {
        const bar = document.getElementById('b103-toolbar');
        if (!bar) return;

        const inCard = isInsideCard();
        const st = window.b103DockState;
        const effectiveCollapsed = inCard && (st.collapsed || (isTypingInField && !forceExpandWhileTyping));

        const menuBtn = document.getElementById('btn-main-menu');
        const pill = document.getElementById('b103-card-pill');
        const tabBtn = document.getElementById('b103-edge-tab');
        const collapseBtn = document.getElementById('btn-collapse-dock');

        if (menuBtn) menuBtn.style.display = inCard ? 'none' : 'flex';
        if (pill) {
            pill.style.display = (inCard && !effectiveCollapsed) ? 'flex' : 'none';
            pill.style.flexDirection = st.side === 'left' ? 'row-reverse' : 'row';
        }
        if (collapseBtn) collapseBtn.innerText = st.side === 'left' ? '‹' : '›';
        if (tabBtn) {
            tabBtn.style.display = (inCard && effectiveCollapsed) ? 'flex' : 'none';
            tabBtn.style.borderRadius = st.side === 'left' ? '0 14px 14px 0' : '14px 0 0 14px';
            tabBtn.style.opacity = isTypingInField ? '0.52' : '0.84';
        }

        const vh = window.innerHeight || 700;
        const barH = bar.offsetHeight || 44;
        const topPx = Math.max(54, Math.min(vh - barH - 12, Math.round(vh * st.topPct)));

        bar.style.top = topPx + 'px';
        bar.style.bottom = 'auto';
        if (st.side === 'left') {
            bar.style.left = effectiveCollapsed ? '0px' : '8px';
            bar.style.right = 'auto';
        } else {
            bar.style.right = effectiveCollapsed ? '0px' : '8px';
            bar.style.left = 'auto';
        }
    }

    function attachDragBehavior(bar) {
        let dragging = false, moved = false;
        let startX = 0, startY = 0, startLeft = 0, startTop = 0;

        function onStart(clientX, clientY) {
            const rect = bar.getBoundingClientRect();
            dragging = true;
            moved = false;
            startX = clientX;
            startY = clientY;
            startLeft = rect.left;
            startTop = rect.top;
            bar.style.transition = 'none';
        }

        function onMove(clientX, clientY, e) {
            if (!dragging) return;
            const dx = clientX - startX;
            const dy = clientY - startY;
            if (!moved && Math.hypot(dx, dy) > 8) moved = true;
            if (moved) {
                if (e && e.cancelable) e.preventDefault();
                const vw = window.innerWidth || 360;
                const vh = window.innerHeight || 700;
                const w = bar.offsetWidth || 48;
                const h = bar.offsetHeight || 44;
                const nx = Math.max(0, Math.min(vw - w, startLeft + dx));
                const ny = Math.max(48, Math.min(vh - h - 8, startTop + dy));
                bar.style.left = nx + 'px';
                bar.style.right = 'auto';
                bar.style.top = ny + 'px';
                bar.style.bottom = 'auto';
            }
        }

        function onEnd() {
            if (!dragging) return;
            dragging = false;
            bar.style.transition = 'left 0.18s ease, right 0.18s ease, top 0.18s ease, opacity 0.2s ease';
            if (moved) {
                suppressClickUntil = Date.now() + 320;
                const rect = bar.getBoundingClientRect();
                const vw = window.innerWidth || 360;
                const vh = window.innerHeight || 700;
                window.b103DockState.side = (rect.left + rect.width / 2) < (vw / 2) ? 'left' : 'right';
                window.b103DockState.topPct = Math.max(0.08, Math.min(0.88, rect.top / vh));
                saveDockState(window.b103DockState);
                applyDockLayout();
            }
        }

        bar.addEventListener('touchstart', (e) => {
            if (e.touches.length === 1) onStart(e.touches[0].clientX, e.touches[0].clientY);
        }, { passive: true });
        bar.addEventListener('touchmove', (e) => {
            if (dragging && e.touches.length === 1) onMove(e.touches[0].clientX, e.touches[0].clientY, e);
        }, { passive: false });
        bar.addEventListener('touchend', onEnd, { passive: true });
        bar.addEventListener('touchcancel', onEnd, { passive: true });

        bar.addEventListener('mousedown', (e) => { if (e.button === 0) onStart(e.clientX, e.clientY); });
        window.addEventListener('mousemove', (e) => { if (dragging) onMove(e.clientX, e.clientY, e); });
        window.addEventListener('mouseup', onEnd);
    }

    function ensureToolbar() {
        if (!document.body || document.getElementById('b103-toolbar')) return;
        const bar = document.createElement('div');
        bar.id = 'b103-toolbar';
        bar.style.cssText = 'position:fixed;z-index:99999;touch-action:none;user-select:none;-webkit-user-select:none;transition:left 0.18s ease, right 0.18s ease, top 0.18s ease, opacity 0.2s ease;';
        const btnBase = 'width:34px;height:34px;border-radius:50%;border:none;color:#fff;font-size:16px;display:flex;align-items:center;justify-content:center;cursor:pointer;padding:0;box-shadow:0 2px 6px rgba(0,0,0,0.22);';

        bar.innerHTML = `
            <button id="btn-main-menu" style="width:46px;height:46px;border-radius:50%;border:none;background:#008577;color:#fff;font-size:20px;display:none;align-items:center;justify-content:center;cursor:pointer;box-shadow:0 4px 12px rgba(0,0,0,0.3);">☰</button>
            <div id="b103-card-pill" style="display:none;align-items:center;gap:6px;padding:3px 6px;border-radius:22px;background:rgba(30,43,51,0.88);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);box-shadow:0 4px 14px rgba(0,0,0,0.28);border:1px solid rgba(255,255,255,0.16);">
                <span style="color:rgba(255,255,255,0.55);font-size:13px;padding:0 2px;letter-spacing:-1px;cursor:grab;">⋮⋮</span>
                <button id="btn-save-db" title="Сохранить в архив" style="background:#00897B;${btnBase}">💾</button>
                <button id="btn-fill-db" title="Автозаполнение и Помощник" style="background:#0288D1;${btnBase}">⚡</button>
                <button id="btn-collapse-dock" title="Свернуть к краю" style="width:24px;height:32px;border-radius:12px;border:none;background:rgba(255,255,255,0.12);color:#fff;font-size:16px;font-weight:700;display:flex;align-items:center;justify-content:center;cursor:pointer;padding:0;">›</button>
            </div>
            <button id="b103-edge-tab" title="Развернуть панель SSMP Plus" style="display:none;width:26px;height:46px;border:none;background:rgba(2,136,209,0.92);color:#fff;font-size:15px;align-items:center;justify-content:center;cursor:pointer;box-shadow:0 3px 10px rgba(0,0,0,0.28);padding:0;">⚡</button>
        `;
        document.body.appendChild(bar);
        attachDragBehavior(bar);

        document.getElementById('btn-main-menu').onclick = () => {
            if (Date.now() < suppressClickUntil) return;
            window.AndroidBridge?.openMainMenu();
        };
        document.getElementById('btn-save-db').onclick = () => {
            if (Date.now() < suppressClickUntil) return;
            scrapeCache();
            if (window.lastLoadedApiCard && window.AndroidBridge) {
                window.AndroidBridge.saveApiCardForced(JSON.stringify(Object.assign({}, window.lastLoadedApiCard, window.b103ScreenCache)));
            } else {
                window.AndroidBridge?.notifyUser("Откройте карту для сохранения");
            }
        };
        document.getElementById('btn-fill-db').onclick = () => {
            if (Date.now() < suppressClickUntil) return;
            const fio = findInputByKw(["фио"], true)?.value?.trim() || "";
            window.AndroidBridge?.requestAutofill(fio);
        };
        document.getElementById('btn-collapse-dock').onclick = () => {
            if (Date.now() < suppressClickUntil) return;
            window.b103DockState.collapsed = true;
            forceExpandWhileTyping = false;
            saveDockState(window.b103DockState);
            applyDockLayout();
        };
        document.getElementById('b103-edge-tab').onclick = () => {
            if (Date.now() < suppressClickUntil) return;
            if (isTypingInField) {
                forceExpandWhileTyping = true;
            }
            window.b103DockState.collapsed = false;
            saveDockState(window.b103DockState);
            applyDockLayout();
        };

        if (!window.b103DockListenersAdded) {
            window.b103DockListenersAdded = true;
            document.addEventListener('focusin', (e) => {
                if (isEditableEl(e.target)) {
                    isTypingInField = true;
                    forceExpandWhileTyping = false;
                    applyDockLayout();
                }
            }, true);
            document.addEventListener('focusout', () => {
                setTimeout(() => {
                    if (!isEditableEl(document.activeElement)) {
                        isTypingInField = false;
                        forceExpandWhileTyping = false;
                        applyDockLayout();
                    }
                }, 140);
            }, true);
            window.addEventListener('resize', applyDockLayout);
        }

        applyDockLayout();
    }

    function updateVisibility() {
        ensureToolbar();
        const inCard = isInsideCard();
        if (inCard !== lastInCardState) {
            lastInCardState = inCard;
            applyDockLayout();
        }
        if (!inCard) {
            window.activeAutofillData = null;
            window.b103ScreenCache = {};
        }
    }

    ensureToolbar();
    updateVisibility();
    if (!window.b103VisTimer) window.b103VisTimer = setInterval(updateVisibility, 400);

    // ==========================================
    // 3. ТОЧНЫЙ АВТОПИЛОТ (ВНУТРИ ТОЙ ЖЕ ОБЛАСТИ ВИДИМОСТИ)
    // ==========================================
    window.setAndApplyAutofill = function(jsonStr) {
        try {
            window.activeAutofillData = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            window.applyAutofill(window.activeAutofillData);
        } catch (e) {}
    };

    window.applyAutofill = async function(jsonStr) {
        if (window.b103IsApplying) return;

        const savedScrollWinY = window.scrollY || document.documentElement.scrollTop || 0;
        const savedScrollContainers = Array.from(document.querySelectorAll('main, .MuiDialog-paper, [role="tabpanel"], .MuiContainer-root'))
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

        function isElemVisible(el) {
            if (!el || !el.isConnected) return false;
            if (el.getAttribute('aria-hidden') === 'true') return false;
            const st = window.getComputedStyle(el);
            if (st.display === 'none' || st.visibility === 'hidden') return false;
            const r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
        }

        function getActivePopovers() {
            return Array.from(document.querySelectorAll('.MuiPopover-root, .MuiModal-root, .MuiAutocomplete-popper'))
                .filter(isElemVisible);
        }

        async function closeAnyOpenMenu() {
            for (let attempt = 0; attempt < 5; attempt++) {
                const popovers = getActivePopovers();
                const expandedBtns = Array.from(document.querySelectorAll('[aria-expanded="true"]'));
                if (popovers.length === 0 && expandedBtns.length === 0) break;

                const escInit = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
                if (document.activeElement && document.activeElement !== document.body) {
                    document.activeElement.dispatchEvent(new KeyboardEvent('keydown', escInit));
                    document.activeElement.dispatchEvent(new KeyboardEvent('keyup', escInit));
                    if (document.activeElement.blur) document.activeElement.blur();
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

                restoreScreenPosition();
                await new Promise(r => setTimeout(r, 45));
            }
        }

        try {
            const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;

            function setVal(inp, val, allowEmpty = false) {
                if (!inp || val === undefined || val === null || inp.disabled || isInConclusion(inp)) return;
                const str = cleanVal(val);
                const prev = cleanVal(inp.value);
                if (!allowEmpty && !str) return;
                if (prev === str) return;
                try {
                    const proto = inp.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                    if (setter) setter.call(inp, str); else inp.value = str;
                    if (inp._valueTracker) inp._valueTracker.setValue(prev + '__force_diff');
                    inp.dispatchEvent(new Event('input', { bubbles: true }));
                    inp.dispatchEvent(new Event('change', { bubbles: true }));
                } catch (e) { inp.value = str; }
            }

            function getRealInputsAutofill(container) {
                return Array.from(container.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])'))
                    .filter(el => !el.classList.contains('MuiSelect-nativeInput') && !el.classList.contains('MuiTextareaAutosize-shadow') && el.getAttribute('aria-hidden') !== 'true' && el.tabIndex !== -1 && !isInConclusion(el));
            }

            function findInputs(keywords, exact = false) {
                const res = [];
                const searchingFio = keywords.some(k => k.toLowerCase() === 'фио');
                const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));

                for (let kw of keywords) {
                    const kwL = kw.toLowerCase();
                    for (let l of labels) {
                        if (!l.textContent || l.children.length > 3 || isInConclusion(l)) continue;

                        const t = l.textContent.trim().toLowerCase();
                        const isMatch = exact ? t === kwL : (t === kwL || t.includes(kwL));
                        if (isMatch && t.length < kwL.length + 35) {
                            let foundInp = null;

                            const fc = l.closest('.MuiFormControl-root');
                            if (fc && !isInConclusion(fc)) {
                                const inps = getRealInputsAutofill(fc);
                                if (inps.length === 1) foundInp = inps[0];
                            }

                            if (!foundInp) {
                                let p = l.parentElement;
                                for (let i = 0; i < 3 && p && p !== document.body && !isInConclusion(p); i++) {
                                    const inps = getRealInputsAutofill(p);
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
                                const holderText = cleanVal((foundInp.closest('.MuiFormControl-root') || foundInp.parentElement)?.innerText).toLowerCase();
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

            const setAll = (kws, val, ex = false, allowEmpty = false) => {
                if (val !== undefined && val !== null && (allowEmpty || cleanVal(val) !== '')) {
                    findInputs(kws, ex).forEach(i => setVal(i, val, allowEmpty));
                }
            };

            function formatBirthDateAndCalcAge(rawDateStr) {
                if (!rawDateStr) return { dateStr: "", ageStr: "", ageUnitGuess: "" };
                let clean = String(rawDateStr).trim();
                if (clean.startsWith("__")) return { dateStr: "", ageStr: "", ageUnitGuess: "" };

                let datePart = "", timePart = "00:00";
                if (clean.includes("-")) {
                    const p = clean.split("T"); const d = p[0].split("-");
                    if (d.length === 3) datePart = `${d[2]}.${d[1]}.${d[0]}`;
                    if (p[1] && /^\d{2}:\d{2}/.test(p[1].trim())) timePart = p[1].trim().substring(0, 5);
                } else if (clean.includes(".")) {
                    const p = clean.split("/"); datePart = p[0].trim();
                    if (p[1] && /^\d{2}:\d{2}/.test(p[1].trim())) timePart = p[1].trim().substring(0, 5);
                }
                if (!datePart || datePart.includes("_")) return { dateStr: "", ageStr: "", ageUnitGuess: "" };

                const now = new Date();
                const todayStr = `${String(now.getDate()).padStart(2,'0')}.${String(now.getMonth()+1).padStart(2,'0')}.${now.getFullYear()}`;
                let calcAge = "", ageUnitGuess = "";
                const dp = datePart.split(".");
                if (dp.length === 3) {
                    const bd = parseInt(dp[0], 10), bm = parseInt(dp[1], 10), by = parseInt(dp[2], 10);
                    const bObj = new Date(by, bm - 1, bd);
                    if (by > 1900 && bObj <= now) {
                        let yrs = now.getFullYear() - by;
                        if ((now.getMonth() + 1) < bm || ((now.getMonth() + 1) === bm && now.getDate() < bd)) yrs--;
                        if (yrs >= 1) {
                            calcAge = String(yrs); ageUnitGuess = "Лет";
                        } else {
                            let mos = (now.getFullYear() - by) * 12 + ((now.getMonth() + 1) - bm);
                            if (now.getDate() < bd) mos--;
                            if (mos >= 1) {
                                calcAge = String(mos); ageUnitGuess = "Месяцев";
                            } else {
                                const days = Math.floor((now - bObj) / 86400000);
                                calcAge = String(Math.max(1, days)); ageUnitGuess = "Дней";
                            }
                        }
                    }
                }
                return { dateStr: (datePart !== todayStr) ? `${datePart} / 00:00` : `${datePart} / ${timePart}`, ageStr: calcAge, ageUnitGuess };
            }

            async function selectDropdown(keywords, valStr, forceMulti = false, allowClearMulti = false) {
                const vals = cleanVal(valStr).split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
                if (!vals.length && !(forceMulti && allowClearMulti)) return;

                const matchVal = (ot, v) => {
                    if (!ot || ot === 'не выбрано') return false;
                    if (ot === v || ot.includes(v) || v.includes(ot)) return true;
                    if (v.startsWith('месяц') && ot.startsWith('месяц')) return true;
                    if ((v === 'лет' || v.startsWith('год')) && (ot === 'лет' || ot.startsWith('год'))) return true;
                    if ((v.startsWith('дн') || v === 'день') && (ot.startsWith('дн') || ot === 'день')) return true;
                    if (v.startsWith('час') && ot.startsWith('час')) return true;
                    return false;
                };

                const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
                const ctrls = [];
                for (let kw of keywords) {
                    const kwL = kw.toLowerCase();
                    for (let l of labels) {
                        if (!l.textContent || l.children.length > 3 || isInConclusion(l)) continue;
                        const t = l.textContent.trim().toLowerCase();
                        if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 35) {
                            let p = l.closest('.MuiFormControl-root') || l.parentElement;
                            for (let i = 0; i < 3 && p && p !== document.body && !isInConclusion(p); i++) {
                                const selBtns = p.querySelectorAll('.MuiSelect-root, [role="button"]');
                                if (selBtns.length === 1) {
                                    if (!ctrls.includes(p)) ctrls.push(p);
                                    break;
                                } else if (selBtns.length > 1) break;
                                p = p.parentElement;
                            }
                        }
                    }
                }

                for (let c of ctrls) {
                    const btn = c.querySelector('.MuiSelect-root, [role="button"]');
                    if (!btn || btn.classList.contains('Mui-disabled') || isInConclusion(btn)) continue;

                    const isMulti = forceMulti || btn.classList.contains('MuiSelect-multiple') || vals.length > 1;
                    const curText = cleanVal(btn.innerText).toLowerCase();
                    const curItems = (!curText || curText === 'не выбрано') ? [] : curText.split(',').map(s => s.trim()).filter(Boolean);

                    if (!isMulti && vals.length > 0 && matchVal(curText, vals[0])) continue;
                    if (isMulti && curItems.length === vals.length && vals.every(v => curItems.some(ci => matchVal(ci, v)))) continue;

                    await closeAnyOpenMenu();

                    btn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                    restoreScreenPosition();
                    await new Promise(r => setTimeout(r, 60));
                    if (getActivePopovers().length === 0) {
                        btn.click();
                        restoreScreenPosition();
                    }

                    let activePops = [];
                    for (let w = 0; w < 6; w++) {
                        activePops = getActivePopovers();
                        if (activePops.length > 0 && activePops[activePops.length - 1].querySelector('li[role="option"], .MuiMenuItem-root')) break;
                        await new Promise(r => setTimeout(r, 55));
                    }

                    const popover = activePops.length > 0 ? activePops[activePops.length - 1] : null;
                    const opts = popover ? Array.from(popover.querySelectorAll('li[role="option"], .MuiMenuItem-root')) : [];

                    if (opts.length > 0) {
                        const hasCheckboxes = opts.some(o => !!o.querySelector('input[type="checkbox"], .MuiCheckbox-root'));
                        const treatAsMulti = isMulti || hasCheckboxes;

                        if (!treatAsMulti) {
                            const match = opts.find(o => matchVal(cleanVal(o.innerText).toLowerCase(), vals[0]));
                            if (match) {
                                match.click();
                                restoreScreenPosition();
                                await new Promise(r => setTimeout(r, 80));
                            }
                            if (getActivePopovers().length > 0) await closeAnyOpenMenu();
                        } else {
                            const wanted = new Set();
                            for (let v of vals) {
                                const m = opts.find(o => matchVal(cleanVal(o.innerText).toLowerCase(), v));
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
                            await closeAnyOpenMenu();
                        }
                    } else {
                        await closeAnyOpenMenu();
                    }
                }
            }

            async function fillMkbAutocomplete(mkbStr) {
                if (!mkbStr) return;
                const codeMatch = String(mkbStr).trim().match(/\[?([A-ZА-Я]\d{2}(?:\.\d+)?)\]?/i);
                const searchCode = codeMatch ? codeMatch[1].toUpperCase() : String(mkbStr).trim();

                const mkbInputs = findInputs(["код по мкб"]);
                if (!mkbInputs.length) return;
                const inp = mkbInputs[0];

                if (inp.value && inp.value.toUpperCase().includes(`[${searchCode}]`)) return;

                await closeAnyOpenMenu();

                const autoRoot = inp.closest('.MuiAutocomplete-root') || inp.closest('.MuiFormControl-root') || inp.parentElement;
                const popupBtn = autoRoot ? autoRoot.querySelector('.MuiAutocomplete-popupIndicator') : null;

                try { inp.focus({ preventScroll: true }); } catch (e) { inp.focus(); }
                restoreScreenPosition();
                setVal(inp, searchCode);
                await new Promise(r => setTimeout(r, 120));
                if (!Array.from(document.querySelectorAll('.MuiAutocomplete-popper')).some(isElemVisible) && popupBtn) {
                    popupBtn.click();
                    restoreScreenPosition();
                }

                let targetOpt = null;
                for (let w = 0; w < 12; w++) {
                    const poppers = Array.from(document.querySelectorAll('.MuiAutocomplete-popper')).filter(isElemVisible);
                    const popper = poppers[poppers.length - 1];
                    if (popper) {
                        const opts = Array.from(popper.querySelectorAll('li[role="option"], .MuiAutocomplete-option, .MuiMenuItem-root'))
                            .filter(o => {
                                const tx = cleanVal(o.innerText).toLowerCase();
                                return tx && !tx.includes('загрузка') && !tx.includes('нет вариантов');
                            });
                        if (opts.length > 0) {
                            targetOpt = opts.find(o => cleanVal(o.innerText).toUpperCase().includes(`[${searchCode}]`))
                                || opts.find(o => cleanVal(o.innerText).toUpperCase().startsWith(searchCode))
                                || opts.find(o => cleanVal(o.innerText).toUpperCase().includes(searchCode))
                                || opts[0];
                            if (targetOpt) break;
                        }
                    }
                    await new Promise(r => setTimeout(r, 80));
                }

                if (targetOpt) {
                    targetOpt.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                    targetOpt.click();
                    restoreScreenPosition();
                    await new Promise(r => setTimeout(r, 80));
                }
                if (inp.blur) inp.blur();
                await closeAnyOpenMenu();
            }

            async function fillSimpleAutocomplete(keywords, targetVal, skipIfFilled = false) {
                const cleanTarget = cleanVal(targetVal);
                if (!cleanTarget) return;
                const inps = findInputs(keywords);
                if (!inps.length) return;
                const inp = inps[0];
                const cur = cleanVal(inp.value);
                const fullTargetL = cleanTarget.toLowerCase();
                const shortSearch = cleanTarget.split('(')[0].trim();
                const shortTargetL = shortSearch.toLowerCase();

                if (skipIfFilled && cur) return;
                if (cur && (cur.toLowerCase() === fullTargetL || cur.toLowerCase().startsWith(shortTargetL))) return;

                await closeAnyOpenMenu();
                const autoRoot = inp.closest('.MuiAutocomplete-root') || inp.closest('.MuiFormControl-root') || inp.parentElement;
                const clearBtn = autoRoot ? autoRoot.querySelector('.MuiAutocomplete-clearIndicator') : null;
                const popupBtn = autoRoot ? autoRoot.querySelector('.MuiAutocomplete-popupIndicator') : null;
                try { inp.focus({ preventScroll: true }); } catch (e) { inp.focus(); }
                if (cur && clearBtn) {
                    clearBtn.click();
                    await new Promise(r => setTimeout(r, 50));
                }
                restoreScreenPosition();
                setVal(inp, shortSearch, true);
                await new Promise(r => setTimeout(r, 140));
                if (!Array.from(document.querySelectorAll('.MuiAutocomplete-popper')).some(isElemVisible) && popupBtn) {
                    popupBtn.click();
                    restoreScreenPosition();
                }

                let targetOpt = null;
                for (let w = 0; w < 12; w++) {
                    const poppers = Array.from(document.querySelectorAll('.MuiAutocomplete-popper')).filter(isElemVisible);
                    const popper = poppers[poppers.length - 1];
                    if (popper) {
                        const opts = Array.from(popper.querySelectorAll('li[role="option"], .MuiAutocomplete-option, .MuiMenuItem-root'))
                            .filter(o => {
                                const tx = cleanVal(o.innerText).toLowerCase();
                                return tx && !tx.includes('загрузка') && !tx.includes('нет вариантов');
                            });
                        if (opts.length > 0) {
                            targetOpt = opts.find(o => cleanVal(o.innerText).toLowerCase() === fullTargetL)
                                || opts.find(o => cleanVal(o.innerText).toLowerCase().startsWith(shortTargetL))
                                || opts.find(o => cleanVal(o.innerText).toLowerCase().includes(shortTargetL))
                                || opts[0];
                            if (targetOpt) break;
                        }
                    }
                    await new Promise(r => setTimeout(r, 80));
                }
                if (targetOpt) {
                    targetOpt.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                    targetOpt.click();
                    restoreScreenPosition();
                    await new Promise(r => setTimeout(r, 80));
                }
                if (inp.blur) inp.blur();
                await closeAnyOpenMenu();
            }

            function clickButtonInRow(rowKeywords, buttonChoices) {
                if (!buttonChoices) return;
                const choices = (Array.isArray(buttonChoices) ? buttonChoices : [buttonChoices])
                    .map(c => cleanVal(c).toUpperCase()).filter(Boolean);
                if (!choices.length) return;

                const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span'));
                for (let kw of rowKeywords) {
                    const kwL = kw.toLowerCase();
                    const targets = labels.filter(el => {
                        if (!el.textContent || el.children.length > 2 || isInConclusion(el)) return false;
                        const t = el.textContent.trim().toLowerCase();
                        return (t === kwL || t.includes(kwL)) && t.length < kwL.length + 35;
                    });

                    for (let target of targets) {
                        let p = target.closest('.MuiFormControl-root') || target.parentElement;
                        for (let i = 0; i < 4 && p && p !== document.body && !isInConclusion(p); i++) {
                            const btns = Array.from(p.querySelectorAll('button, div[role="button"]')).filter(b => !b.disabled && !isInConclusion(b));
                            const desired = btns.find(b => {
                                const bt = cleanVal(b.innerText || b.textContent).toUpperCase();
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

            function fillBP(isAd, raw, allowEmpty = false) {
                const str = cleanBpString(raw);
                if (!str.includes('/') && !allowEmpty) return;
                const p = str.includes('/') ? str.split('/') : ['', ''];
                const slashNodes = Array.from(document.querySelectorAll('span, p, .MuiTypography-root'))
                    .filter(e => e.children.length === 0 && e.textContent?.trim() === '/' && !isInConclusion(e));

                for (let sl of slashNodes) {
                    let pr = sl.parentElement;
                    for (let i = 0; i < 3 && pr && pr !== document.body; i++) {
                        const inps = getRealInputsAutofill(pr);
                        if (inps.length === 2) {
                            const tx = (pr.innerText || pr.parentElement?.innerText || '').toLowerCase();
                            const isMatch = isAd
                                ? (tx.includes('адаптированное') && (tx.includes('ад') || tx.includes('давлен')))
                                : (!tx.includes('адаптированное') && (tx.includes('ад (мм') || tx.includes('артериальн')));
                            if (isMatch) {
                                setVal(inps[0], p[0].trim(), allowEmpty);
                                setVal(inps[1], p[1].trim(), allowEmpty);
                                return;
                            }
                        }
                        pr = pr.parentElement;
                    }
                }
            }

            if (data.fio) setAll(["фио"], data.fio, true);

            if (data.gender) {
                const gUp = cleanVal(data.gender).toUpperCase();
                const gTarget = (gUp.startsWith("М") || gUp === "TRUE") ? ["МУЖЧИНА", "МУЖСКОЙ"] : ["ЖЕНЩИНА", "ЖЕНСКИЙ"];
                clickButtonInRow(["пол"], gTarget);
            }

            const initAgeRaw = cleanVal(window.lastLoadedApiCard?.initialAge);
            const initAgeM = initAgeRaw.match(/^(\d+)\s+([А-Яа-яЁё]+)/);
            let finAge = cleanVal(data.age) || cleanVal(window.lastLoadedApiCard?.age) || (initAgeM ? initAgeM[1] : "");
            let unitGuess = "";

            const bDateInps = findInputs(["дата рождения"]);
            if (bDateInps.length > 0) {
                const now = new Date();
                const todayIso = `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
                const todayDot = `${String(now.getDate()).padStart(2,'0')}.${String(now.getMonth()+1).padStart(2,'0')}.${now.getFullYear()}`;
                const regDateIso = (window.lastLoadedApiCard?.dateOfRegistration || "").split("T")[0];

                let servB = cleanVal(window.lastLoadedApiCard?.patient?.birthDate);
                if (servB && (servB.startsWith(todayIso) || (regDateIso && servB.startsWith(regDateIso)))) servB = "";
                let archB = cleanVal(data.birthDate);
                if (archB && (archB.includes(todayIso) || archB.includes(todayDot))) archB = "";
                let scrB = cleanVal(bDateInps[0].value);
                let scrDateOnly = scrB.split("/")[0].trim();
                if (scrDateOnly.includes("_") || scrDateOnly === todayDot) scrB = "";

                const rawB = servB || archB || scrB;
                if (rawB) {
                    const parsed = formatBirthDateAndCalcAge(rawB);
                    if (parsed.dateStr) setVal(bDateInps[0], parsed.dateStr);
                    if (!finAge && parsed.ageStr) finAge = parsed.ageStr;
                    if (parsed.ageUnitGuess) unitGuess = parsed.ageUnitGuess;
                }
            }

            const unitVal = cleanVal(data.ageUnit) || cleanVal(window.lastLoadedApiCard?.ageType) || (initAgeM ? initAgeM[2] : "") || unitGuess || "Лет";
            await selectDropdown(["возраст (ед.)"], unitVal, false);
            if (finAge) setAll(["возраст"], finAge, true);

            const getInpVal = (kws, exact = false) => cleanVal(findInputs(kws, exact)[0]?.value);
            const savedSettl = cleanVal(data.settlement) || getInpVal(['нас. пункт']) || cleanVal(window.lastLoadedApiCard?.patient?.settlement) || cleanVal(window.lastLoadedApiCard?.settlement);
            const savedStreet = cleanVal(data.street) || getInpVal(['улица']) || cleanVal(window.lastLoadedApiCard?.patient?.street) || cleanVal(window.lastLoadedApiCard?.street);
            const savedBld = cleanVal(data.building) || getInpVal(['дом'], true) || cleanVal(window.lastLoadedApiCard?.patient?.building) || cleanVal(window.lastLoadedApiCard?.building);
            const savedFlat = cleanVal(data.flat) || getInpVal(['квартира'], true) || cleanVal(window.lastLoadedApiCard?.patient?.flat) || cleanVal(window.lastLoadedApiCard?.flat);
            const savedClar = cleanVal(data.clarificationAddress) || getInpVal(['уточнение места жительства']) || cleanVal(window.lastLoadedApiCard?.patient?.clarificationAddress);
            const savedAddr = cleanVal(data.address) || getInpVal(['место жительства (пребывания)']) || cleanVal(window.lastLoadedApiCard?.patient?.placeOfRegistration) || cleanVal(window.lastLoadedApiCard?.address);

            const ctzVal = cleanVal(data.citizenship) || cleanVal(window.lastLoadedApiCard?.patient?.countryOfRegistration) || '';
            if (ctzVal) await fillSimpleAutocomplete(["гражданство"], ctzVal, !data.citizenship);
            if (savedSettl) await fillSimpleAutocomplete(["нас. пункт"], savedSettl, false);
            if (savedStreet) await fillSimpleAutocomplete(["улица"], savedStreet, false);
            if (savedBld) setAll(["дом"], savedBld, true);
            if (savedFlat) setAll(["квартира"], savedFlat, true);
            if (savedClar) setAll(["уточнение места жительства"], savedClar);
            if (savedAddr) setAll(["место жительства (пребывания)"], savedAddr);

            if (data.apo) await selectDropdown(["территориальная апо", "апо по месту жительства"], data.apo, false);

            setAll(["жалобы"], data.complaints ?? "", false, true);
            setAll(["анамнез заболевания"], data.anamnesis ?? "", false, true);
            setAll(["перенесенные заболевания"], data.pastDiseases ?? "", false, true);
            setAll(["предварительный диагноз"], data.diagnosis ?? "", false, true);
            setAll(["локальный статус"], data.localStatus ?? "", false, true);
            if (data.mkb) await fillMkbAutocomplete(data.mkb);

            fillBP(true, data.bpAdapted ?? "", true);
            fillBP(false, data.bp ?? "", true);
            setAll(["комментарий (пульс", "пульс, ударов в минуту"], data.pulse ?? "", false, true);
            setAll(["частота дыхания (в минуту)"], data.rr ?? "", false, true);
            setAll(["температура тела (°c)"], data.temp !== undefined && data.temp !== null && String(data.temp) !== "" ? String(data.temp).replace(',', '.') : "", false, true);
            setAll(["сатурация 1 (%)"], data.saturation ?? "", false, true);
            setAll(["гликемия 1 (ммоль/л)"], data.glucose !== undefined && data.glucose !== null && String(data.glucose) !== "" ? String(data.glucose).replace(',', '.') : "", false, true);

            setAll(["экг до оказания"], data.ecgBefore ?? "", false, true);
            setAll(["экг после оказания"], data.ecgAfter ?? "", false, true);
            setAll(["шкала комы глазго (баллы)"], cleanVal(data.glasgowScore) || "15", false, true);
            setAll(["тонус мышц"], cleanVal(data.muscleTone) || "Нормальный", false, true);
            setAll(["патологические рефлексы"], cleanVal(data.pathologicalReflexes) || "Отрицательные", false, true);
            setAll(["менингиальные знаки"], cleanVal(data.meningealSymptoms) || "Отрицательные", false, true);
            setAll(["плегии, парезы"], cleanVal(data.plegia) || "Нет", false, true);
            setAll(["язык"], cleanVal(data.tongue) || "Чистые", false, true);
            setAll(["слизистые"], cleanVal(data.mucous) || "Бледно-розовые", false, true);

            clickButtonInRow(["положение"], data.position || "АКТИВНОЕ");
            clickButtonInRow(["поведение"], data.behavior || "СПОКОЙНОЕ");
            if (data.pupils) {
                clickButtonInRow(["зрачки"], [data.pupils, data.pupils.replace("АНИЗОКОРИЯ", "АНИЗОКАРИЯ")]);
            } else {
                const pupLbl = Array.from(document.querySelectorAll('label')).find(e => e.textContent && e.textContent.trim().toLowerCase() === 'зрачки' && !isInConclusion(e));
                if (pupLbl && pupLbl.parentElement) {
                    Array.from(pupLbl.parentElement.querySelectorAll('button'))
                        .filter(b => b.className.includes('MuiButton-contained') && !b.disabled)
                        .forEach(b => b.click());
                }
            }
            clickButtonInRow(["речь"], data.speech || "СОХРАНЕНА");
            clickButtonInRow(["лицо"], data.face || "СИММЕТРИЧНОЕ");
            clickButtonInRow(["походка"], data.gait || "СВОБОДНАЯ");
            clickButtonInRow(["нистагм"], data.nystagmus || "НЕТ");
            clickButtonInRow(["печень"], data.liver || "НЕ УВЕЛИЧЕНА");
            clickButtonInRow(["мочеиспускание"], data.urination || "НЕ НАРУШЕНО");
            clickButtonInRow(["стул"], data.stool || "НЕ НАРУШЕН");

            clickButtonInRow(["аллергия"], data.allergy || "НЕТ");
            clickButtonInRow(["реакция на свет"], data.lightReaction || "ДА");
            clickButtonInRow(["высыпания"], data.rash || "НЕТ");
            clickButtonInRow(["пульс, ударов в минуту"], data.pulseExists || (data.pulse === "0" ? "НЕТ" : "ДА"));
            clickButtonInRow(["асцит"], data.ascites || "НЕТ");
            clickButtonInRow(["периферические отеки"], data.edema || "НЕТ");

            clickButtonInRow(["запах алкоголя"], data.alcohol || "НЕТ");
            clickButtonInRow(["запах ацетона"], data.acetone || "НЕТ");
            clickButtonInRow(["симптомы раздражения брюшины"], data.peritoneal || "НЕТ");
            clickButtonInRow(["перистальтика"], data.peristalsis || "ДА");
            clickButtonInRow(["получено устное согласие на простое медицинское вмешательство", "получено устное согласие"], "ДА");

            await new Promise(r => setTimeout(r, 50));
            setAll(["комментарий (вынужденное)"], data.positionDescription ?? "", false, true);
            setAll(["комментарий (периферические отеки)"], data.edemaDescription ?? "", false, true);

            await selectDropdown(["общее состояние"], data.generalState || "Удовлетворительное", false);
            await selectDropdown(["сознание"], data.consciousness || "Ясное", false);

            await selectDropdown(["кожа"], data.skin ?? "", true, true);
            await selectDropdown(["тоны сердца"], data.heartSounds ?? "", true, true);
            await selectDropdown(["дыхание"], data.lungs ?? "", true, true);
            await selectDropdown(["перкуссия легких"], data.percussion ?? "", true, true);
            await selectDropdown(["живот"], data.abdomen ?? "", true, true);

            await closeAnyOpenMenu();
            restoreScreenPosition();
        } catch (e) { console.error(e); } finally {
            await new Promise(r => setTimeout(r, 60));
            await closeAnyOpenMenu();
            if (activeTabBefore) {
                const curActive = Array.from(document.querySelectorAll('[role="tab"], button'))
                    .find(b => b.getBoundingClientRect().top < 140 && (b.getAttribute('aria-selected') === 'true' || b.className.includes('Mui-selected')));
                if (curActive !== activeTabBefore) activeTabBefore.click();
            }
            restoreScreenPosition();
            setTimeout(restoreScreenPosition, 40);

            document.getElementById('b103-hide-menus')?.remove();
            if (fillBtn) fillBtn.innerText = "⚡";
            setTimeout(() => { window.b103IsApplying = false; }, 150);
        }
    };

    // ==========================================
    // 4. БЫСТРАЯ ТОЧЕЧНАЯ ВСТАВКА В ПОЛЯ КАРТЫ
    // ==========================================
    window.insertIntoCardField = function(fieldKeyword, textToInsert, appendMode) {
        try {
            const kwL = String(fieldKeyword).toLowerCase();
            const isInConclusion = window.b103IsInConclusion;
            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
            
            for (let l of labels) {
                if (!l.innerText || l.offsetParent === null || l.children.length > 3 || isInConclusion(l)) continue;
                
                const t = l.innerText.trim().toLowerCase();
                if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 35) {
                    const fc = l.closest('.MuiFormControl-root') || l.parentElement?.parentElement;
                    if (!fc || isInConclusion(fc)) continue;

                    const inps = Array.from(fc.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])'))
                        .filter(el => !el.classList.contains('MuiSelect-nativeInput') && !el.classList.contains('MuiTextareaAutosize-shadow') && el.getAttribute('aria-hidden') !== 'true' && el.tabIndex !== -1 && !isInConclusion(el));

                    if (inps.length > 0) {
                        const inp = inps[0];
                        const cur = (inp.value || '').trim();
                        const finalVal = (appendMode && cur) ? (cur + "\n" + textToInsert) : textToInsert;
                        const proto = inp.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                        const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                        if (setter) setter.call(inp, finalVal); else inp.value = finalVal;
                        if (inp._valueTracker) inp._valueTracker.setValue(cur + '__force_diff');
                        inp.dispatchEvent(new Event('input', { bubbles: true }));
                        inp.dispatchEvent(new Event('change', { bubbles: true }));
                        return true;
                    }
                }
            }
        } catch (e) {}
        return false;
    };
})();
