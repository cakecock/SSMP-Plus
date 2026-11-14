```javascript
(function() {
    window.lastLoadedApiCard = window.lastLoadedApiCard || null;
    window.activeAutofillData = window.activeAutofillData || null;
    window.b103ScreenCache = window.b103ScreenCache || {};
    window.b103IsApplying = false;

    function setupNetworkInterceptor() {
        const origFetch = window.fetch;
        window.fetch = async function(...args) {
            try {
                const opts = args[1] || {};
                if (opts.body && typeof opts.body === 'string' && opts.body.includes('"patient"')) {
                    const sentData = JSON.parse(opts.body);
                    if (sentData && sentData.patient) {
                        window.lastLoadedApiCard = Object.assign(window.lastLoadedApiCard || {}, sentData);
                    }
                }
            } catch (e) {}

            const response = await origFetch.apply(this, args);
            try {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] ? args[0].url : '');
                if (url && url.includes('/api/card/') && !url.includes('/short')) {
                    const clone = response.clone();
                    clone.json().then(data => {
                        if (data && data.patient) {
                            if (window.lastLoadedApiCard && window.lastLoadedApiCard.id !== data.id) {
                                window.activeAutofillData = null;
                                window.b103ScreenCache = {};
                            }
                            window.lastLoadedApiCard = data;
                        }
                    }).catch(() => {});
                }
            } catch (e) {}
            return response;
        };

        const origOpen = XMLHttpRequest.prototype.open;
        const origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(method, url) {
            this._url = url;
            return origOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function(body) {
            try {
                if (body && typeof body === 'string' && body.includes('"patient"')) {
                    const sentData = JSON.parse(body);
                    if (sentData && sentData.patient) {
                        window.lastLoadedApiCard = Object.assign(window.lastLoadedApiCard || {}, sentData);
                    }
                }
            } catch (e) {}

            this.addEventListener('load', function() {
                try {
                    if (this._url && this._url.includes('/api/card/') && !this._url.includes('/short')) {
                        const data = JSON.parse(this.responseText);
                        if (data && data.patient) {
                            if (window.lastLoadedApiCard && window.lastLoadedApiCard.id !== data.id) {
                                window.activeAutofillData = null;
                                window.b103ScreenCache = {};
                            }
                            window.lastLoadedApiCard = data;
                        }
                    }
                } catch (e) {}
            });
            return origSend.apply(this, arguments);
        };
    }
    setupNetworkInterceptor();

    function findVisibleEditableMuiField(keywords, exactOnly = false) {
        const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, .MuiInputLabel-root, label'));
        for (let kw of keywords) {
            const kwLower = kw.toLowerCase();
            const matchingLabels = labels.filter(l => {
                if (!l.innerText) return false;
                const t = l.innerText.trim().toLowerCase();
                return exactOnly ? t === kwLower : (t === kwLower || t.includes(kwLower));
            });
            for (let target of matchingLabels) {
                const control = target.closest('.MuiFormControl-root, .MuiGrid-item');
                const inp = control?.querySelector('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])');
                if (inp) {
                    const rect = inp.getBoundingClientRect();
                    if (rect.width > 0 && rect.height > 0 && inp.offsetParent !== null) {
                        return inp;
                    }
                }
            }
        }
        return null;
    }

    function scrapeVisibleScreenToCache() {
        const cache = window.b103ScreenCache;
        const ecgB = findVisibleEditableMuiField(["экг до оказания"])?.value?.trim();
        if (ecgB) cache.electrocardiogramBefore = ecgB;
        const ecgA = findVisibleEditableMuiField(["экг после оказания"])?.value?.trim();
        if (ecgA) cache.electrocardiogramAfter = ecgA;
        const diag = findVisibleEditableMuiField(["предварительный диагноз"])?.value?.trim();
        if (diag) cache.diagnosis = diag;
        const compl = findVisibleEditableMuiField(["жалобы"])?.value?.trim();
        if (compl) cache.complaints = compl;
        const anamn = findVisibleEditableMuiField(["анамнез заболевания"])?.value?.trim();
        if (anamn) cache.anamnesis = anamn;
        const locSt = findVisibleEditableMuiField(["локальный статус"])?.value?.trim();
        if (locSt) cache.localStatus = locSt;

        const allNodes = Array.from(document.querySelectorAll('*'));
        const qtyNodes = allNodes.filter(el => el.children.length === 0 && el.textContent && el.textContent.includes("Количество:"));
        if (qtyNodes.length > 0) {
            const medItems = [];
            for (let qNode of qtyNodes) {
                let card = qNode.parentElement;
                while (card && card !== document.body) {
                    const lines = (card.innerText || '').split('\n').map(s => s.trim()).filter(Boolean);
                    const qIdx = lines.findIndex(l => l.includes("Количество:"));
                    if (qIdx > 0) {
                        const drugName = lines[0];
                        const qtyStr = lines[qIdx].replace(/Количество:\s*/i, '').trim();
                        const routeLine = lines.find(l => l.includes("Путь введения:"));
                        let routeStr = '';
                        if (routeLine && !routeLine.includes("не определен")) {
                            routeStr = ` [${routeLine.replace(/Путь введения:\s*/i, '').trim()}]`;
                        }
                        if (drugName && !drugName.includes("Лекарственные") && !drugName.includes("ДОБАВИТЬ")) {
                            medItems.push(`${drugName} — ${qtyStr}${routeStr}`);
                        }
                        break;
                    }
                    card = card.parentElement;
                }
            }
            if (medItems.length > 0) {
                cache._domMedications = Array.from(new Set(medItems)).join(";\n• ");
            }
        }
    }

    function isInsideCard() {
        const text = document.body ? document.body.innerText : '';
        return (text.includes('О ВЫЗОВЕ') && text.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ')) || text.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ');
    }

    let bar = document.getElementById('b103-toolbar');
    if (!bar) {
        bar = document.createElement('div');
        bar.id = 'b103-toolbar';
        bar.style.position = 'fixed';
        bar.style.bottom = '88px';
        bar.style.right = '16px';
        bar.style.zIndex = '99999';
        bar.style.display = 'flex';
        bar.style.flexDirection = 'column';
        bar.style.alignItems = 'flex-end';
        bar.style.gap = '12px';

        const roundStyle = `
            width:52px;
            height:52px;
            border-radius:50%;
            border:none;
            box-shadow:0 4px 14px rgba(0,0,0,0.32);
            font-size:22px;
            display:none;
            align-items:center;
            justify-content:center;
            cursor:pointer;
            opacity:0;
            transform:scale(0.7) translateY(8px);
            transition:opacity 0.2s ease, transform 0.22s cubic-bezier(0.34, 1.56, 0.64, 1);
            user-select:none;
        `;

        bar.innerHTML = `
            <button id="btn-main-menu" title="Меню SSMP Plus" style="background:#008577;color:white;${roundStyle}">☰</button>
            <button id="btn-save-db" title="Сохранить в мой архив" style="background:#00796B;color:white;${roundStyle}">💾</button>
            <button id="btn-fill-db" title="Автозаполнение" style="background:#0288D1;color:white;${roundStyle}">⚡</button>
        `;
        document.body.appendChild(bar);

        document.getElementById('btn-main-menu').onclick = () => window.AndroidBridge?.openMainMenu();

        document.getElementById('btn-save-db').onclick = function() {
            scrapeVisibleScreenToCache();
            if (window.lastLoadedApiCard && window.AndroidBridge) {
                const merged = Object.assign({}, window.lastLoadedApiCard, window.b103ScreenCache);
                window.AndroidBridge.saveApiCardForced(JSON.stringify(merged));
            } else {
                window.AndroidBridge?.notifyUser("Откройте или обновите карту для сохранения в архив");
            }
        };

        document.getElementById('btn-fill-db').onclick = function() {
            let fioInp = findVisibleEditableMuiField(["фио"]);
            let fio = fioInp ? fioInp.value.trim() : "";
            window.AndroidBridge?.requestAutofill(fio);
        };
    }

    function setBtnAnimated(btn, show) {
        if (!btn) return;
        if (show) {
            btn.style.display = 'flex';
            requestAnimationFrame(() => {
                btn.style.opacity = '1';
                btn.style.transform = 'scale(1) translateY(0)';
                btn.style.pointerEvents = 'auto';
            });
        } else {
            btn.style.opacity = '0';
            btn.style.transform = 'scale(0.7) translateY(8px)';
            btn.style.pointerEvents = 'none';
            setTimeout(() => {
                if (btn.style.opacity === '0') btn.style.display = 'none';
            }, 220);
        }
    }

    let lastCardState = null;
    function updateVisibility() {
        const inCard = isInsideCard();
        if (inCard) scrapeVisibleScreenToCache();
        if (inCard !== lastCardState) {
            lastCardState = inCard;
            setBtnAnimated(document.getElementById('btn-main-menu'), !inCard);
            setBtnAnimated(document.getElementById('btn-save-db'), inCard);
            setBtnAnimated(document.getElementById('btn-fill-db'), inCard);
            if (!inCard) {
                window.activeAutofillData = null;
                window.b103ScreenCache = {};
            }
        }
    }

    // ЖЕСТКАЯ ЗАЩИТА: срабатывает ТОЛЬКО при реальном нажатии пальцем (e.isTrusted) строго на верхнюю панель вкладок!
    document.addEventListener('click', function(e) {
        if (!e.isTrusted || window.b103IsApplying || !window.activeAutofillData) return;
        const tab = e.target && e.target.closest ? e.target.closest('button, [role="tab"]') : null;
        if (!tab) return;

        const rect = tab.getBoundingClientRect();
        // Игнорируем всё, что ниже верхней панели навигации (и все выпадающие меню!)
        if (rect.top < 0 || rect.top > 130) return;

        const t = (tab.innerText || '').toUpperCase();
        if (t.includes('УТОЧНЕННЫЕ') || t.includes('ОБЪЕКТИВНЫЕ') || t.includes('МЕДИЦИНСКАЯ')) {
            setTimeout(() => {
                window.applyAutofill(window.activeAutofillData);
            }, 180);
        }
    }, true);

    setInterval(updateVisibility, 300);
})();

window.setAndApplyAutofill = function(jsonStr) {
    try {
        window.activeAutofillData = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
        window.applyAutofill(window.activeAutofillData);
    } catch (e) {
        console.error(e);
    }
};

window.applyAutofill = async function(jsonStr) {
    if (window.b103IsApplying) return;
    window.b103IsApplying = true;

    const fillBtn = document.getElementById('btn-fill-db');
    if (fillBtn) fillBtn.innerText = "⏳";

    try {
        const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;

        function normalizeBp(bpVal) {
            if (!bpVal) return "";
            if (typeof bpVal === 'object') {
                const s = bpVal.sys ?? bpVal.systolic ?? "";
                const d = bpVal.dis ?? bpVal.dia ?? bpVal.diastolic ?? "";
                return (s !== "" || d !== "") ? `${s}/${d}` : "";
            }
            const str = String(bpVal).trim();
            if (str.startsWith("{")) {
                try {
                    const o = JSON.parse(str);
                    const s = o.sys ?? o.systolic ?? "";
                    const d = o.dis ?? o.dia ?? o.diastolic ?? "";
                    return (s !== "" || d !== "") ? `${s}/${d}` : "";
                } catch (e) { return ""; }
            }
            return str;
        }

        // Обновление React-поля через сброс внутреннего _valueTracker (БЕЗ дергания focus/blur для дат)
        function setReactValDirect(input, val, skipFocusBlur = false) {
            if (!input || val === undefined || val === null || input.disabled) return;
            const str = String(val).trim();
            if (str === "" || input.value.trim() === str) return;

            try {
                if (!skipFocusBlur) input.focus({ preventScroll: true });
                const proto = input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(input, str); else input.value = str;

                // Сбрасываем внутренний трекер React, чтобы React 100% увидел изменение значения!
                if (input._valueTracker) {
                    input._valueTracker.setValue('');
                }

                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                if (!skipFocusBlur) input.blur();
            } catch (e) {
                input.value = str;
            }
        }

        function findAllMuiInputs(keywords, exactOnly = false) {
            const results = [];
            const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, .MuiInputLabel-root, label'));
            for (let kw of keywords) {
                const kwLower = kw.toLowerCase();
                for (let l of labels) {
                    if (!l.innerText) continue;
                    const t = l.innerText.trim().toLowerCase();
                    if (exactOnly ? t === kwLower : t.includes(kwLower)) {
                        const control = l.closest('.MuiFormControl-root, .MuiGrid-item');
                        const inp = control?.querySelector('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])');
                        if (inp && inp.offsetParent !== null && !results.includes(inp)) {
                            results.push(inp);
                        }
                    }
                }
            }
            return results;
        }

        function setAllMuiInputs(keywords, val, exactOnly = false) {
            if (!val) return;
            const inputs = findAllMuiInputs(keywords, exactOnly);
            inputs.forEach(inp => setReactValDirect(inp, val, false));
        }

        function formatBirthDateAndCalcAge(rawDateStr) {
            if (!rawDateStr) return { dateStr: "", ageStr: "" };
            let clean = String(rawDateStr).trim();
            if (clean.startsWith("__")) return { dateStr: "", ageStr: "" };

            let datePart = "";
            let timePart = "00:00";

            if (clean.includes("-")) {
                const parts = clean.split("T");
                const d = parts[0].split("-");
                if (d.length === 3) datePart = `${d[2]}.${d[1]}.${d[0]}`;
                if (parts[1] && /^\d{2}:\d{2}/.test(parts[1].trim())) {
                    timePart = parts[1].trim().substring(0, 5);
                }
            } else if (clean.includes(".")) {
                const parts = clean.split("/");
                datePart = parts[0].trim();
                if (parts[1] && /^\d{2}:\d{2}/.test(parts[1].trim())) {
                    timePart = parts[1].trim().substring(0, 5);
                }
            }

            if (!datePart || datePart.includes("_")) return { dateStr: "", ageStr: "" };

            const now = new Date();
            const dd = String(now.getDate()).padStart(2, '0');
            const mm = String(now.getMonth() + 1).padStart(2, '0');
            const yyyy = now.getFullYear();
            const todayStr = `${dd}.${mm}.${yyyy}`;

            let calcAge = "";
            const dParts = datePart.split(".");
            if (dParts.length === 3) {
                const birthYear = parseInt(dParts[2], 10);
                if (birthYear > 1900 && birthYear < yyyy) {
                    let age = yyyy - birthYear;
                    const birthMonth = parseInt(dParts[1], 10);
                    const birthDay = parseInt(dParts[0], 10);
                    if ((now.getMonth() + 1) < birthMonth || ((now.getMonth() + 1) === birthMonth && now.getDate() < birthDay)) {
                        age--;
                    }
                    if (age >= 0) calcAge = String(age);
                }
            }

            const finalDate = (datePart !== todayStr) ? `${datePart} / 00:00` : `${datePart} / ${timePart}`;
            return { dateStr: finalDate, ageStr: calcAge };
        }

        async function closeMuiMenuQuietly() {
            const backdrops = Array.from(document.querySelectorAll('.MuiBackdrop-root, .MuiPopover-root > div[aria-hidden="true"]'));
            if (backdrops.length > 0) {
                backdrops.forEach(b => b.click());
                await new Promise(r => setTimeout(r, 40));
            }
            const pop = document.querySelector('ul[role="listbox"], .MuiMenu-list');
            if (pop) {
                pop.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
                await new Promise(r => setTimeout(r, 40));
            }
        }

        async function selectAllMuiDropdowns(keywords, valueStr, exactOnly = false) {
            if (!valueStr) return;
            const valuesToSelect = String(valueStr).split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
            if (valuesToSelect.length === 0) return;

            const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, .MuiInputLabel-root, label'));
            const controls = [];
            for (let kw of keywords) {
                const kwLower = kw.toLowerCase();
                for (let l of labels) {
                    if (!l.innerText) continue;
                    const t = l.innerText.trim().toLowerCase();
                    if (exactOnly ? t === kwLower : t.includes(kwLower)) {
                        const ctrl = l.closest('.MuiFormControl-root, .MuiGrid-item');
                        if (ctrl && ctrl.offsetParent !== null && !controls.includes(ctrl)) {
                            controls.push(ctrl);
                        }
                    }
                }
            }

            for (let control of controls) {
                const currentText = (control.innerText || '').toLowerCase();
                if (valuesToSelect.every(v => currentText.includes(v))) continue;

                const selectBtn = control.querySelector('.MuiSelect-root, [role="button"], .MuiAutocomplete-popupIndicator');
                if (selectBtn && selectBtn.offsetParent !== null) {
                    const isMulti = selectBtn.classList.contains('MuiSelect-multiple') || valuesToSelect.length > 1;
                    selectBtn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                    await new Promise(r => setTimeout(r, 95));

                    const options = Array.from(document.querySelectorAll('li[role="option"], .MuiMenuItem-root, .MuiAutocomplete-option'));
                    if (options.length > 0) {
                        for (let val of valuesToSelect) {
                            const matchOpt = options.find(o => o.innerText && o.innerText.trim().toLowerCase() === val)
                                          || options.find(o => o.innerText && o.innerText.trim().toLowerCase().includes(val));
                            if (matchOpt) {
                                const isAlreadySelected = matchOpt.classList.contains('Mui-selected') || matchOpt.getAttribute('aria-selected') === 'true';
                                if (!isMulti || !isAlreadySelected) {
                                    matchOpt.click();
                                    await new Promise(r => setTimeout(r, 45));
                                }
                            }
                        }
                        await closeMuiMenuQuietly();
                    }
                } else {
                    const textInp = control.querySelector('input[type="text"]:not([disabled])');
                    if (textInp) setReactValDirect(textInp, valueStr, false);
                }
            }
        }

        function selectAllMuiBtns(targetText) {
            if (!targetText) return;
            const buttons = Array.from(document.querySelectorAll('button, div[role="button"]')).filter(b => b.offsetParent !== null && b.innerText && b.innerText.trim().toUpperCase() === targetText.toUpperCase());
            for (let btn of buttons) {
                const s = window.getComputedStyle(btn);
                const isWhite = s.color.includes('255, 255, 255');
                const isColored = !s.backgroundColor.includes('rgba(0, 0, 0, 0)') && !s.backgroundColor.includes('255, 255, 255');
                const isAlreadyOn = (isWhite && isColored) || btn.className.includes('MuiButton-contained');
                if (!isAlreadyOn) btn.click();
            }
        }

        function selectMuiYN(labelKeywords, choice) {
            if (!choice) return;
            const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, .MuiInputLabel-root, label'));
            for (let kw of labelKeywords) {
                const matching = labels.filter(l => l.offsetParent !== null && l.innerText && l.innerText.trim().toLowerCase().includes(kw.toLowerCase()));
                for (let target of matching) {
                    let parent = target.closest('.MuiFormControl-root, .MuiGrid-item') || target.parentElement;
                    for (let i = 0; i < 3 && parent && parent !== document.body; i++) {
                        const btns = Array.from(parent.querySelectorAll('button, div[role="button"]'));
                        const desired = btns.find(b => b.innerText && b.innerText.trim().toUpperCase() === choice.toUpperCase());
                        if (desired) {
                            const s = window.getComputedStyle(desired);
                            const isWhite = s.color.includes('255, 255, 255');
                            const isColored = !s.backgroundColor.includes('rgba(0, 0, 0, 0)') && !s.backgroundColor.includes('255, 255, 255');
                            const isAlreadyOn = (isWhite && isColored) || desired.className.includes('MuiButton-contained');
                            if (!isAlreadyOn) desired.click();
                            break;
                        }
                        parent = parent.parentElement;
                    }
                }
            }
        }

        function fillAllDualBPs(isAdapted, rawBp) {
            const bpStr = normalizeBp(rawBp);
            if (!bpStr || !bpStr.includes('/')) return;
            const parts = bpStr.split('/');
            const slashes = Array.from(document.querySelectorAll('*')).filter(el => el.offsetParent !== null && el.children.length === 0 && el.textContent.trim() === '/');
            for (let slash of slashes) {
                let parent = slash.parentElement;
                for (let i = 0; i < 3 && parent && parent !== document.body; i++) {
                    const inputs = Array.from(parent.querySelectorAll('input:not([type="hidden"]):not([disabled])'));
                    if (inputs.length === 2) {
                        const text = (parent.innerText || parent.parentElement?.innerText || '').toLowerCase();
                        const isMatch = isAdapted ? text.includes("адаптированное") : (text.includes("ад") && !text.includes("адаптированное"));
                        if (isMatch) {
                            setReactValDirect(inputs[0], parts[0].trim(), false);
                            setReactValDirect(inputs[1], parts[1].trim(), false);
                            break;
                        }
                    }
                    parent = parent.parentElement;
                }
            }
        }

        // 1. ПАСПОРТНЫЕ ДАННЫЕ, ДАТА РОЖДЕНИЯ (00:00) И ВОЗРАСТ
        if (data.fio) setAllMuiInputs(["фио"], data.fio, true);

        const bDateInputs = findAllMuiInputs(["дата рождения"]);
        if (bDateInputs.length > 0) {
            const now = new Date();
            const todayIso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
            const todayDot = `${String(now.getDate()).padStart(2, '0')}.${String(now.getMonth() + 1).padStart(2, '0')}.${now.getFullYear()}`;
            const callRegDateIso = (window.lastLoadedApiCard?.dateOfRegistration || "").split("T")[0];

            let servBDate = window.lastLoadedApiCard?.patient?.birthDate || "";
            if (servBDate && (servBDate.startsWith(todayIso) || (callRegDateIso && servBDate.startsWith(callRegDateIso)))) {
                servBDate = "";
            }
            let archBDate = data.birthDate || "";
            if (archBDate && (archBDate.includes(todayIso) || archBDate.includes(todayDot))) {
                archBDate = "";
            }
            // Также проверяем дату на экране, если она введена вручную (не сегодняшняя и без времени __:__)
            let screenVal = bDateInputs[0].value || "";
            let screenDateOnly = screenVal.split("/")[0].trim();
            if (screenDateOnly.includes("_") || screenDateOnly === todayDot) {
                screenVal = "";
            }

            const rawBDate = servBDate || archBDate || screenVal;
            if (rawBDate) {
                const { dateStr, ageStr } = formatBirthDateAndCalcAge(rawBDate);
                // Обновляем БЕЗ focus/blur (skipFocusBlur = true), чтобы маска календаря не сбрасывала на 14.11.2026!
                if (dateStr) setReactValDirect(bDateInputs[0], dateStr, true);
                const finalAge = data.age || (window.lastLoadedApiCard?.age ? String(window.lastLoadedApiCard.age) : "") || ageStr;
                if (finalAge) setAllMuiInputs(["возраст"], finalAge, true);
            } else if (data.age) {
                setAllMuiInputs(["возраст"], data.age, true);
            }
        }

        const ageUnitToSelect = data.ageUnit || window.lastLoadedApiCard?.ageType?.type || "Лет";
        await selectAllMuiDropdowns(["возраст (ед.)"], ageUnitToSelect, true);

        if (data.address) setAllMuiInputs(["место жительства (пребывания)"], data.address);

        // 2. ЖАЛОБЫ, АНАМНЕЗ, ДИАГНОЗ
        setAllMuiInputs(["жалобы"], data.complaints);
        setAllMuiInputs(["анамнез заболевания"], data.anamnesis);
        setAllMuiInputs(["перенесенные заболевания"], data.pastDiseases);
        setAllMuiInputs(["предварительный диагноз"], data.diagnosis);
        setAllMuiInputs(["локальный статус"], data.localStatus);

        // 3. АД, ПУЛЬС, ЧДД, ТЕМПЕРАТУРА
        if (data.bpAdapted) fillAllDualBPs(true, data.bpAdapted);
        if (data.bp) fillAllDualBPs(false, data.bp);
        if (data.pulse) setAllMuiInputs(["пульс, ударов в минуту", "комментарий (пульс", "пульс (в минуту)"], data.pulse);
        if (data.rr) setAllMuiInputs(["частота дыхания (в минуту)"], data.rr);
        if (data.temp) setAllMuiInputs(["температура тела (°c)", "температура (°c)"], data.temp);
        if (data.saturation) setAllMuiInputs(["сатурация 1 (%)"], data.saturation);
        if (data.glucose) setAllMuiInputs(["гликемия 1 (ммоль/л)"], data.glucose);

        // 4. ЭКГ И НЕВРОЛОГИЧЕСКИЙ СТАТУС
        setAllMuiInputs(["экг до оказания"], data.ecgBefore);
        setAllMuiInputs(["экг после оказания"], data.ecgAfter);
        setAllMuiInputs(["шкала комы глазго (баллы)"], data.glasgowScore || "15");
        setAllMuiInputs(["тонус мышц"], "Норма");
        setAllMuiInputs(["патологические рефлексы"], "Нет");
        setAllMuiInputs(["менингиальные знаки"], "Нет");
        setAllMuiInputs(["плегии, парезы"], "Нет");
        setAllMuiInputs(["язык"], data.tongue || "Влажный, не обложен");
        setAllMuiInputs(["слизистые"], data.mucous || "Чистые");

        // 5. КНОПКИ-ПЕРЕКЛЮЧАТЕЛИ
        if (data.gender) selectAllMuiBtns(data.gender);
        selectAllMuiBtns(data.position || "АКТИВНОЕ");
        selectAllMuiBtns(data.behavior || "СПОКОЙНОЕ");
        if (data.pupils) selectAllMuiBtns(data.pupils);
        selectAllMuiBtns(data.speech || "СОХРАНЕНА");
        selectAllMuiBtns(data.face || "СИММЕТРИЧНОЕ");
        selectAllMuiBtns(data.gait || "СВОБОДНАЯ");
        selectAllMuiBtns(data.nystagmus || "НЕТ");
        selectAllMuiBtns(data.liver || "НЕ УВЕЛИЧЕНА");
        selectAllMuiBtns(data.urination || "НЕ НАРУШЕНО");

        selectAllMuiBtns(data.attack || "КУПИРОВАН");
        selectAllMuiBtns(data.therapyEffect || "УЛУЧШЕНИЕ");
        if (data.callResult) selectAllMuiBtns(data.callResult);

        selectMuiYN(["аллергия"], data.allergy || "НЕТ");
        selectMuiYN(["реакция на свет"], data.lightReaction || "ДА");
        selectMuiYN(["высыпания"], "НЕТ");
        selectMuiYN(["перистальтика"], data.peristalsis || "ДА");
        selectMuiYN(["симптомы раздражения брюшины"], "НЕТ");
        selectMuiYN(["асцит"], "НЕТ");
        selectMuiYN(["периферические отеки"], "НЕТ");
        selectMuiYN(["запах алкоголя"], "НЕТ");
        selectMuiYN(["запах ацетона"], "НЕТ");

        // 6. ВЫПАДАЮЩИЕ СПИСКИ MATERIAL-UI
        await selectAllMuiDropdowns(["общее состояние"], data.generalState || "Удовлетворительное", true);
        await selectAllMuiDropdowns(["сознание"], data.consciousness || "Ясное", true);
        if (data.skin) await selectAllMuiDropdowns(["кожа"], data.skin, true);
        if (data.heartSounds) await selectAllMuiDropdowns(["тоны сердца"], data.heartSounds, true);
        if (data.lungs) await selectAllMuiDropdowns(["дыхание"], data.lungs, true);
        if (data.percussion) await selectAllMuiDropdowns(["перкуссия легких"], data.percussion, true);
        if (data.abdomen) await selectAllMuiDropdowns(["живот"], data.abdomen, true);

        if (fillBtn) fillBtn.innerText = "⚡";
    } catch (e) {
        console.error("Autofill error:", e);
        if (fillBtn) fillBtn.innerText = "⚡";
    } finally {
        setTimeout(() => { window.b103IsApplying = false; }, 200);
    }
};
