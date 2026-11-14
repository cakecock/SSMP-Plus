(function() {
    if (!window.currentCardSnapshot) {
        window.currentCardSnapshot = {};
    }

    // ==========================================
    // 1. СЕТЕВОЙ ПЕРЕХВАТЧИК API КАРТЫ ВЫЗОВА
    // ==========================================
    function setupNetworkInterceptor() {
        const origFetch = window.fetch;(function() {
    window.lastLoadedApiCard = window.lastLoadedApiCard || null;
    window.activeAutofillData = window.activeAutofillData || null;

    // ==========================================
    // 1. ПЕРЕХВАТ ОТКРЫТОЙ КАРТЫ ИЗ API В ПАМЯТЬ
    // ==========================================
    function setupNetworkInterceptor() {
        const origFetch = window.fetch;
        window.fetch = async function(...args) {
            const response = await origFetch.apply(this, args);
            try {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] ? args[0].url : '');
                if (url && url.includes('/api/card/') && !url.includes('/short')) {
                    const clone = response.clone();
                    clone.json().then(data => {
                        if (data && data.patient) {
                            if (window.lastLoadedApiCard && window.lastLoadedApiCard.id !== data.id) {
                                window.activeAutofillData = null;
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
        XMLHttpRequest.prototype.send = function() {
            this.addEventListener('load', function() {
                try {
                    if (this._url && this._url.includes('/api/card/') && !this._url.includes('/short')) {
                        const data = JSON.parse(this.responseText);
                        if (data && data.patient) {
                            if (window.lastLoadedApiCard && window.lastLoadedApiCard.id !== data.id) {
                                window.activeAutofillData = null;
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

    function findVisibleEditableMuiField(keywords) {
        const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, .MuiInputLabel-root, label'));
        for (let kw of keywords) {
            const matchingLabels = labels.filter(l => {
                if (!l.innerText) return false;
                const t = l.innerText.trim().toLowerCase();
                return t === kw.toLowerCase() || t.includes(kw.toLowerCase());
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

    function isInsideCard() {
        const text = document.body ? document.body.innerText : '';
        return (text.includes('О ВЫЗОВЕ') && text.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ')) || text.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ');
    }

    // ==========================================
    // 2. ЕДИНАЯ КНОПКА МЕНЮ (НА ГЛАВНОЙ) И КНОПКИ КАРТЫ
    // ==========================================
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
            <!-- Единая кнопка Меню на главном экране -->
            <button id="btn-main-menu" title="Меню SSMP Plus" style="background:#008577;color:white;${roundStyle}">☰</button>
            <!-- Кнопки внутри карты -->
            <button id="btn-save-db" title="Сохранить в мой архив" style="background:#00796B;color:white;${roundStyle}">💾</button>
            <button id="btn-fill-db" title="Автозаполнение" style="background:#0288D1;color:white;${roundStyle}">⚡</button>
        `;
        document.body.appendChild(bar);

        document.getElementById('btn-main-menu').onclick = () => window.AndroidBridge?.openMainMenu();

        // Сохранение карты только по нажатию кнопки 💾
        document.getElementById('btn-save-db').onclick = function() {
            if (window.lastLoadedApiCard && window.AndroidBridge) {
                window.AndroidBridge.saveApiCardForced(JSON.stringify(window.lastLoadedApiCard));
            } else {
                window.AndroidBridge?.notifyUser("Откройте или обновите карту для сохранения в архив");
            }
        };

        // Нажатие на молнию ⚡
        document.getElementById('btn-fill-db').onclick = function() {
            let fioInp = findVisibleEditableMuiField(["фио"]);
            let fio = fioInp ? fioInp.value.trim() : "";

            if (!fio || fio.length < 3) {
                window.AndroidBridge?.openAutofillPicker();
            } else {
                window.AndroidBridge?.requestAutofill(fio);
            }
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
        if (inCard !== lastCardState) {
            lastCardState = inCard;
            setBtnAnimated(document.getElementById('btn-main-menu'), !inCard);
            setBtnAnimated(document.getElementById('btn-save-db'), inCard);
            setBtnAnimated(document.getElementById('btn-fill-db'), inCard);
            if (!inCard) window.activeAutofillData = null;
        }
    }

    // При переключении пользователем верхних вкладок или кнопки "Транспортирован" — автоматически дозаполняем новую вкладку!
    document.addEventListener('click', function(e) {
        const t = e.target ? (e.target.innerText || '').toUpperCase() : '';
        if (window.activeAutofillData && (
            t.includes('УТОЧНЕННЫЕ') ||
            t.includes('ОБЪЕКТИВНЫЕ') ||
            t.includes('МЕДИЦИНСКАЯ') ||
            t.includes('ТРАНСПОРТИРОВАН')
        )) {
            setTimeout(() => {
                window.applyAutofill(window.activeAutofillData);
            }, 150);
        }
    }, true);

    setInterval(updateVisibility, 300);
})();

// Сохраняет выбранный шаблон/карту для текущего вызова и сразу заполняет открытую вкладку
window.setAndApplyAutofill = function(jsonStr) {
    try {
        window.activeAutofillData = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
        window.applyAutofill(window.activeAutofillData);
    } catch (e) {
        console.error(e);
    }
};

// ==========================================
// 3. ПЛАВНОЕ АВТОЗАПОЛНЕНИЕ (БЕЗ ДЕРГАНИЙ ЭКРАНА!)
// ==========================================
window.applyAutofill = async function(jsonStr) {
    const fillBtn = document.getElementById('btn-fill-db');
    if (fillBtn) fillBtn.innerText = "⏳";

    try {
        const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;

        // Установка текста БЕЗ прокрутки и дергания страницы (preventScroll: true)
        function setReactVal(input, val) {
            if (!input || val === undefined || val === null || input.disabled) return;
            const str = String(val).trim();
            if (str === "" || input.value.trim() === str) return;

            try {
                input.focus({ preventScroll: true });
                const proto = input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(input, str); else input.value = str;
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                input.blur();
            } catch (e) {
                input.value = str;
            }
        }

        // Находит ВСЕ подходящие поля на открытой вкладке (включая блоки транспортировки!)
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
                        if (inp && !results.includes(inp)) results.push(inp);
                    }
                }
            }
            return results;
        }

        function setAllMuiInputs(keywords, val, exactOnly = false) {
            if (!val) return;
            const inputs = findAllMuiInputs(keywords, exactOnly);
            inputs.forEach(inp => setReactVal(inp, val));
        }

        // Форматирование даты рождения (00:00) + вычисление возраста
        function formatBirthDateAndCalcAge(rawDateStr) {
            if (!rawDateStr) return { dateStr: "", ageStr: "" };
            let clean = String(rawDateStr).trim();
            let datePart = "";
            let timePart = "00:00";

            if (clean.includes("-")) {
                const parts = clean.split("T");
                const d = parts[0].split("-");
                if (d.length === 3) datePart = `${d[2]}.${d[1]}.${d[0]}`;
                if (parts[1] && parts[1].length >= 5) timePart = parts[1].substring(0, 5);
            } else if (clean.includes(".")) {
                const parts = clean.split("/");
                datePart = parts[0].trim();
                if (parts[1] && parts[1].trim().length >= 5 && !parts[1].includes("_")) {
                    timePart = parts[1].trim().substring(0, 5);
                }
            }

            if (!datePart) return { dateStr: clean, ageStr: "" };

            const now = new Date();
            const dd = String(now.getDate()).padStart(2, '0');
            const mm = String(now.getMonth() + 1).padStart(2, '0');
            const yyyy = now.getFullYear();
            const todayStr = `${dd}.${mm}.${yyyy}`;

            let calcAge = "";
            const dParts = datePart.split(".");
            if (dParts.length === 3) {
                const birthYear = parseInt(dParts[2], 10);
                if (birthYear > 1900 && birthYear <= yyyy) {
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

        // Открытие и выбор ВСЕХ подходящих выпадающих списков Material-UI на открытой вкладке
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
                        if (ctrl && !controls.includes(ctrl)) controls.push(ctrl);
                    }
                }
            }

            for (let control of controls) {
                // Ищем именно триггер списка MuiSelect-root / role="button"
                const selectBtn = control.querySelector('.MuiSelect-root, [role="button"], .MuiAutocomplete-popupIndicator');
                if (selectBtn) {
                    // В Material-UI открываем строго через mousedown (БЕЗ .click(), чтобы не закрыть обратно!)
                    selectBtn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                    await new Promise(r => setTimeout(r, 95));

                    const options = Array.from(document.querySelectorAll('li[role="option"], .MuiMenuItem-root, .MuiAutocomplete-option'));
                    if (options.length > 0) {
                        for (let val of valuesToSelect) {
                            const matchOpt = options.find(o => o.innerText && o.innerText.trim().toLowerCase() === val)
                                          || options.find(o => o.innerText && o.innerText.trim().toLowerCase().includes(val));
                            if (matchOpt) {
                                const isAlreadySelected = matchOpt.classList.contains('Mui-selected') || matchOpt.getAttribute('aria-selected') === 'true';
                                if (!isAlreadySelected) {
                                    matchOpt.click();
                                    await new Promise(r => setTimeout(r, 45));
                                }
                            }
                        }
                        // Закрываем меню, если оно осталось открытым (мультиселект)
                        const backdrop = document.querySelector('.MuiBackdrop-root, .MuiPopover-root [aria-hidden="true"]');
                        if (backdrop) backdrop.click();
                        document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
                        await new Promise(r => setTimeout(r, 45));
                    }
                } else {
                    const textInp = control.querySelector('input[type="text"]:not([disabled])');
                    if (textInp) setReactVal(textInp, valueStr);
                }
            }
        }

        function selectAllMuiBtns(targetText) {
            if (!targetText) return;
            const buttons = Array.from(document.querySelectorAll('button, div[role="button"]')).filter(b => b.innerText && b.innerText.trim().toUpperCase() === targetText.toUpperCase());
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
                const matching = labels.filter(l => l.innerText && l.innerText.trim().toLowerCase().includes(kw.toLowerCase()));
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

        // Заполнение ВСЕХ парных полей АД (включая АД после помощи и АД при транспортировке!)
        function fillAllDualBPs(isAdapted, bpStr) {
            if (!bpStr || !bpStr.includes('/')) return;
            const parts = bpStr.split('/');
            const slashes = Array.from(document.querySelectorAll('*')).filter(el => el.children.length === 0 && el.textContent.trim() === '/');
            for (let slash of slashes) {
                let parent = slash.parentElement;
                for (let i = 0; i < 4 && parent && parent !== document.body; i++) {
                    const text = parent.innerText.toLowerCase();
                    const isMatch = isAdapted ? text.includes("адаптированное") : (text.includes("ад") && !text.includes("адаптированное"));
                    if (isMatch) {
                        const inputs = Array.from(parent.querySelectorAll('input:not([type="hidden"]):not([disabled])'));
                        if (inputs.length >= 2) {
                            setReactVal(inputs[0], parts[0].trim());
                            setReactVal(inputs[1], parts[1].trim());
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
            const rawBDate = data.birthDate || bDateInputs[0].value;
            const { dateStr, ageStr } = formatBirthDateAndCalcAge(rawBDate);
            if (dateStr) setReactVal(bDateInputs[0], dateStr);
            const finalAge = data.age || ageStr;
            if (finalAge) setAllMuiInputs(["возраст"], finalAge, true);
        }

        if (data.address) setAllMuiInputs(["место жительства (пребывания)"], data.address);

        // 2. ЖАЛОБЫ, АНАМНЕЗ, ДИАГНОЗ
        setAllMuiInputs(["жалобы"], data.complaints);
        setAllMuiInputs(["анамнез заболевания"], data.anamnesis);
        setAllMuiInputs(["перенесенные заболевания"], data.pastDiseases);
        setAllMuiInputs(["предварительный диагноз"], data.diagnosis);
        setAllMuiInputs(["локальный статус"], data.localStatus);

        // 3. АД, ПУЛЬС, ЧДД, ТЕМПЕРАТУРА (Включая блоки после помощи и транспортировки!)
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

        // 5. КНОПКИ-ПЕРЕКЛЮЧАТЕЛИ (включая Динамику состояния при транспортировке!)
        if (data.gender) selectAllMuiBtns(data.gender);
        selectAllMuiBtns(data.position || "АКТИВНОЕ");
        selectAllMuiBtns(data.behavior || "СПОКОЙНОЕ");
        selectAllMuiBtns(data.pupils || "МИОЗ");
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

        // 6. ВЫПАДАЮЩИЕ СПИСКИ MATERIAL-UI (Включая блоки транспортировки!)
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
    }
};
        window.fetch = async function(...args) {
            const response = await origFetch.apply(this, args);
            try {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] ? args[0].url : '');
                if (url && url.includes('/api/card/') && !url.includes('/short')) {
                    const clone = response.clone();
                    clone.json().then(data => {
                        if (data && data.patient) {
                            window.lastLoadedApiCard = data;
                            // Автоматически сохраняем в архив только заполненные карты
                            if ((data.anamnesis && data.anamnesis.length > 5) || data.diagnosis) {
                                window.AndroidBridge?.saveApiCard(JSON.stringify(data));
                            }
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
        XMLHttpRequest.prototype.send = function() {
            this.addEventListener('load', function() {
                try {
                    if (this._url && this._url.includes('/api/card/') && !this._url.includes('/short')) {
                        const data = JSON.parse(this.responseText);
                        if (data && data.patient) {
                            window.lastLoadedApiCard = data;
                            if ((data.anamnesis && data.anamnesis.length > 5) || data.diagnosis) {
                                window.AndroidBridge?.saveApiCard(JSON.stringify(data));
                            }
                        }
                    }
                } catch (e) {}
            });
            return origSend.apply(this, arguments);
        };
    }
    setupNetworkInterceptor();

    // Поиск редактируемого и видимого на экране поля Material-UI
    function findVisibleEditableMuiField(keywords) {
        const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, label, span, p'));
        for (let kw of keywords) {
            const matchingLabels = labels.filter(l => {
                if (!l.innerText) return false;
                const t = l.innerText.trim().toLowerCase();
                return t === kw.toLowerCase() || t.includes(kw.toLowerCase());
            });
            for (let target of matchingLabels) {
                const control = target.closest('.MuiFormControl-root') || target.parentElement;
                const inp = control?.querySelector('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])');
                if (inp) {
                    const rect = inp.getBoundingClientRect();
                    // Проверяем, что поле реально видно на текущем экране
                    if (rect.width > 0 && rect.height > 0 && inp.offsetParent !== null) {
                        return inp;
                    }
                }
            }
        }
        return null;
    }

    function isInsideCard() {
        const text = document.body ? document.body.innerText : '';
        return (text.includes('О ВЫЗОВЕ') && text.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ')) || text.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ');
    }

    // ==========================================
    // 2. ПЛАВАЮЩИЕ КРУГЛЫЕ КНОПКИ (44x44 px)
    // ==========================================
    let bar = document.getElementById('b103-toolbar');
    if (!bar) {
        bar = document.createElement('div');
        bar.id = 'b103-toolbar';
        bar.style.position = 'fixed';
        bar.style.bottom = '85px';
        bar.style.right = '16px';
        bar.style.zIndex = '99999';
        bar.style.display = 'flex';
        bar.style.flexDirection = 'column';
        bar.style.alignItems = 'flex-end';
        bar.style.gap = '10px';

        const roundStyle = `
            width:44px;
            height:44px;
            border-radius:50%;
            border:none;
            box-shadow:0 4px 12px rgba(0,0,0,0.3);
            font-size:19px;
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
            <button id="btn-best-db" title="Образцы карт" style="background:linear-gradient(135deg, #00897B, #F57F17);color:white;${roundStyle}">⭐</button>
            <button id="btn-mkb-db" title="Справочник МКБ-10" style="background:linear-gradient(135deg, #00796B, #0288D1);color:white;${roundStyle}">📖</button>
            <button id="btn-search-db" title="Общий архив" style="background:linear-gradient(135deg, #00695C, #004D40);color:white;${roundStyle}">📂</button>
            <button id="btn-save-db" title="Запомнить карту" style="background:linear-gradient(135deg, #00796B, #004D40);color:white;${roundStyle}">💾</button>
            <button id="btn-fill-db" title="Автозаполнение" style="background:linear-gradient(135deg, #0288D1, #01579B);color:white;${roundStyle}">⚡</button>
        `;
        document.body.appendChild(bar);

        document.getElementById('btn-best-db').onclick = () => window.AndroidBridge?.openBestCardsDialog();
        document.getElementById('btn-mkb-db').onclick = () => window.AndroidBridge?.openMkbDialog();
        document.getElementById('btn-search-db').onclick = () => window.AndroidBridge?.openSearchDialog();

        // Ручное сохранение карты
        document.getElementById('btn-save-db').onclick = function() {
            if (window.lastLoadedApiCard && window.AndroidBridge) {
                window.AndroidBridge.saveApiCardForced(JSON.stringify(window.lastLoadedApiCard));
            } else {
                window.AndroidBridge?.notifyUser("Откройте карту для сохранения в архив");
            }
        };

        // Нажатие на молнию ⚡
        document.getElementById('btn-fill-db').onclick = function() {
            // Ищем только РЕДАКТИРУЕМОЕ и ВИДИМОЕ поле ФИО на текущем экране
            let fioInp = findVisibleEditableMuiField(["фио"]);
            let fio = fioInp ? fioInp.value.trim() : "";

            // Если поле ФИО пустое (или мы на вкладке без ФИО) — сразу открываем окно выбора карты!
            if (!fio || fio.length < 3) {
                window.AndroidBridge?.openAutofillPicker();
            } else {
                window.AndroidBridge?.requestAutofill(fio);
            }
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
        if (inCard === lastCardState) return;
        lastCardState = inCard;

        setBtnAnimated(document.getElementById('btn-best-db'), !inCard);
        setBtnAnimated(document.getElementById('btn-mkb-db'), !inCard);
        setBtnAnimated(document.getElementById('btn-search-db'), !inCard);
        setBtnAnimated(document.getElementById('btn-save-db'), inCard);
        setBtnAnimated(document.getElementById('btn-fill-db'), inCard);
    }

    if (window.b103Observer) window.b103Observer.disconnect();
    window.b103Observer = new MutationObserver(() => updateVisibility());
    window.b103Observer.observe(document.body, { childList: true, subtree: true });
    setInterval(updateVisibility, 300);
})();

// ==========================================
// 3. АВТОЗАПОЛНЕНИЕ (ТЕКСТ + ДАТА 00:00 + КНОПКИ + ВЫПАДАЮЩИЕ СПИСКИ)
// ==========================================
window.applyAutofill = async function(jsonStr) {
    const fillBtn = document.getElementById('btn-fill-db');
    if (fillBtn) fillBtn.innerText = "⏳";

    try {
        const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;

        function setReactVal(input, val) {
            if (!input || val === undefined || val === null || input.disabled) return;
            const str = String(val).trim();
            if (str === "" || input.value.trim() === str) return;

            try {
                input.focus();
                const proto = input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(input, str); else input.value = str;
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                input.blur();
            } catch (e) {
                input.value = str;
            }
        }

        function findMuiInput(keywords, exactFirst = false) {
            const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, label, span, p'));
            for (let kw of keywords) {
                const target = labels.find(l => {
                    if (!l.innerText) return false;
                    const t = l.innerText.trim().toLowerCase();
                    return exactFirst ? t === kw.toLowerCase() : t.includes(kw.toLowerCase());
                });
                if (target) {
                    const control = target.closest('.MuiFormControl-root') || target.parentElement;
                    const inp = control?.querySelector('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])');
                    if (inp) return inp;
                }
            }
            return null;
        }

        // Форматирование даты рождения: всегда ставит 00:00, кроме новорожденных в день вызова
        function formatBirthDateForInput(rawDateStr) {
            if (!rawDateStr) return "";
            let clean = String(rawDateStr).trim();
            let datePart = "";
            let timePart = "00:00";

            if (clean.includes("-")) {
                const parts = clean.split("T");
                const d = parts[0].split("-");
                if (d.length === 3) datePart = `${d[2]}.${d[1]}.${d[0]}`;
                if (parts[1] && parts[1].length >= 5) timePart = parts[1].substring(0, 5);
            } else if (clean.includes(".")) {
                const parts = clean.split("/");
                datePart = parts[0].trim();
                if (parts[1] && parts[1].trim().length >= 5) timePart = parts[1].trim().substring(0, 5);
            }

            if (!datePart) return clean;

            const now = new Date();
            const dd = String(now.getDate()).padStart(2, '0');
            const mm = String(now.getMonth() + 1).padStart(2, '0');
            const yyyy = String(now.getFullYear());
            const todayStr = `${dd}.${mm}.${yyyy}`;

            if (datePart !== todayStr) {
                return `${datePart} / 00:00`;
            } else {
                return `${datePart} / ${timePart}`;
            }
        }

        // Умное открытие и выбор пунктов в выпадающих списках Material-UI (Select и Autocomplete)
        async function selectMuiDropdown(keywords, valueStr) {
            if (!valueStr) return;
            const valuesToSelect = String(valueStr).split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
            if (valuesToSelect.length === 0) return;

            const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, label, span, p'));
            let targetLabel = null;
            for (let kw of keywords) {
                targetLabel = labels.find(l => l.innerText && l.innerText.trim().toLowerCase() === kw.toLowerCase())
                           || labels.find(l => l.innerText && l.innerText.trim().toLowerCase().includes(kw.toLowerCase()));
                if (targetLabel) break;
            }
            if (!targetLabel) return;

            const control = targetLabel.closest('.MuiFormControl-root') || targetLabel.parentElement;
            if (!control) return;

            // 1. Проверяем, есть ли кнопка открытия списка (MuiSelect или MuiAutocomplete)
            const selectTrigger = control.querySelector('.MuiSelect-root, [role="button"], .MuiAutocomplete-popupIndicator, .MuiInputBase-root');
            if (selectTrigger) {
                // Открываем выпадающий список через mousedown + click
                selectTrigger.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                selectTrigger.click();
                await new Promise(r => setTimeout(r, 110));

                const options = Array.from(document.querySelectorAll('li[role="option"], .MuiMenuItem-root, .MuiAutocomplete-option'));
                if (options.length > 0) {
                    let clickedAny = false;
                    for (let val of valuesToSelect) {
                        const matchOpt = options.find(o => o.innerText && o.innerText.trim().toLowerCase() === val)
                                      || options.find(o => o.innerText && o.innerText.trim().toLowerCase().includes(val));
                        if (matchOpt) {
                            const isAlreadySelected = matchOpt.classList.contains('Mui-selected') || matchOpt.getAttribute('aria-selected') === 'true';
                            if (!isAlreadySelected) {
                                matchOpt.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                                matchOpt.click();
                                clickedAny = true;
                                await new Promise(r => setTimeout(r, 50));
                            }
                        }
                    }

                    // Закрываем меню после выбора (для мультиселектов)
                    const backdrop = document.querySelector('.MuiBackdrop-root, .MuiPopover-root [aria-hidden="true"]');
                    if (backdrop) backdrop.click();
                    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
                    await new Promise(r => setTimeout(r, 50));
                    if (clickedAny) return;
                }
            }

            // 2. Если это текстовый Autocomplete (например МКБ) — вводим текст и выбираем всплывшую подсказку
            const textInp = control.querySelector('input[type="text"]:not([disabled])');
            if (textInp) {
                setReactVal(textInp, valueStr);
                await new Promise(r => setTimeout(r, 120));
                const firstOpt = document.querySelector('.MuiAutocomplete-option, li[role="option"]');
                if (firstOpt) firstOpt.click();
            }
        }

        function selectMuiBtn(targetText) {
            if (!targetText) return;
            const buttons = Array.from(document.querySelectorAll('button, div[role="button"]'));
            const btn = buttons.find(b => b.innerText.trim().toUpperCase() === targetText.toUpperCase());
            if (btn) {
                const s = window.getComputedStyle(btn);
                const isWhite = s.color.includes('255, 255, 255');
                const isColored = !s.backgroundColor.includes('rgba(0, 0, 0, 0)') && !s.backgroundColor.includes('255, 255, 255');
                const isAlreadyOn = (isWhite && isColored) || btn.className.includes('MuiButton-contained');
                if (!isAlreadyOn) btn.click();
            }
        }

        function selectMuiYN(labelKeywords, choice) {
            if (!choice) return;
            const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, label, span, p'));
            let target = null;
            for (let kw of labelKeywords) {
                target = labels.find(l => l.innerText && l.innerText.trim().toLowerCase().includes(kw));
                if (target) break;
            }
            if (!target) return;
            let parent = target.closest('.MuiFormControl-root') || target.parentElement;
            for (let i = 0; i < 4 && parent && parent !== document.body; i++) {
                const btns = Array.from(parent.querySelectorAll('button, div[role="button"]'));
                const desired = btns.find(b => b.innerText.trim().toUpperCase() === choice.toUpperCase());
                if (desired) {
                    const s = window.getComputedStyle(desired);
                    const isWhite = s.color.includes('255, 255, 255');
                    const isColored = !s.backgroundColor.includes('rgba(0, 0, 0, 0)') && !s.backgroundColor.includes('255, 255, 255');
                    const isAlreadyOn = (isWhite && isColored) || desired.className.includes('MuiButton-contained');
                    if (!isAlreadyOn) desired.click();
                    return;
                }
                parent = parent.parentElement;
            }
        }

        function fillDualBP(isAdapted, bpStr) {
            if (!bpStr || !bpStr.includes('/')) return;
            const parts = bpStr.split('/');
            const slashes = Array.from(document.querySelectorAll('*')).filter(el => el.children.length === 0 && el.textContent.trim() === '/');
            for (let slash of slashes) {
                let parent = slash.parentElement;
                for (let i = 0; i < 4 && parent && parent !== document.body; i++) {
                    const text = parent.innerText.toLowerCase();
                    const isMatch = isAdapted ? text.includes("адаптированное") : (text.includes("ад") && !text.includes("адаптированное") && !text.includes("после"));
                    if (isMatch) {
                        const inputs = Array.from(parent.querySelectorAll('input:not([type="hidden"]):not([disabled])'));
                        if (inputs.length >= 2) {
                            setReactVal(inputs[0], parts[0].trim());
                            setReactVal(inputs[1], parts[1].trim());
                            return;
                        }
                    }
                    parent = parent.parentElement;
                }
            }
        }

        // 1. ПАСПОРТНЫЕ ДАННЫЕ (ФИО, Дата рождения с 00:00, Возраст, Адрес)
        if (data.fio) setReactVal(findMuiInput(["фио"], true), data.fio);
        if (data.birthDate) {
            const finalBirthDate = formatBirthDateForInput(data.birthDate);
            setReactVal(findMuiInput(["дата рождения"]), finalBirthDate);
        }
        if (data.age) setReactVal(findMuiInput(["возраст"], true), data.age);
        if (data.ageUnit) await selectMuiDropdown(["возраст (ед.)"], data.ageUnit);
        if (data.address) setReactVal(findMuiInput(["место жительства (пребывания)"]), data.address);

        // 2. ЖАЛОБЫ, АНАМНЕЗ, ДИАГНОЗ
        setReactVal(findMuiInput(["жалобы"]), data.complaints);
        setReactVal(findMuiInput(["анамнез заболевания", "анамнез"]), data.anamnesis);
        setReactVal(findMuiInput(["перенесенные заболевания"]), data.pastDiseases);
        setReactVal(findMuiInput(["предварительный диагноз", "диагноз"]), data.diagnosis);
        setReactVal(findMuiInput(["локальный статус"]), data.localStatus);

        // 3. АД И ЭКГ
        if (data.bpAdapted) fillDualBP(true, data.bpAdapted);
        setReactVal(findMuiInput(["экг до оказания", "экг до"]), data.ecgBefore);
        setReactVal(findMuiInput(["экг после оказания", "экг после"]), data.ecgAfter);

        // 4. СТАТУС И НЕВРОЛОГИЯ
        setReactVal(findMuiInput(["шкала комы глазго (баллы)", "глазго (баллы)"]), data.glasgowScore || "15");
        setReactVal(findMuiInput(["тонус мышц"]), "Норма");
        setReactVal(findMuiInput(["патологические рефлексы"]), "Нет");
        setReactVal(findMuiInput(["менингиальные знаки"]), "Нет");
        setReactVal(findMuiInput(["плегии, парезы", "плегии"]), "Нет");
        setReactVal(findMuiInput(["язык"]), data.tongue || "Влажный, не обложен");
        setReactVal(findMuiInput(["слизистые"]), data.mucous || "Чистые");

        // 5. КНОПКИ-ПЕРЕКЛЮЧАТЕЛИ
        if (data.gender) selectMuiBtn(data.gender);
        selectMuiBtn(data.position || "АКТИВНОЕ");
        selectMuiBtn(data.behavior || "СПОКОЙНОЕ");
        selectMuiBtn(data.pupils || "МИОЗ");
        selectMuiBtn(data.speech || "СОХРАНЕНА");
        selectMuiBtn(data.face || "СИММЕТРИЧНОЕ");
        selectMuiBtn(data.gait || "СВОБОДНАЯ");
        selectMuiBtn(data.nystagmus || "НЕТ");
        selectMuiBtn(data.liver || "НЕ УВЕЛИЧЕНА");
        selectMuiBtn(data.urination || "НЕ НАРУШЕНО");

        selectMuiBtn(data.attack || "КУПИРОВАН");
        selectMuiBtn(data.therapyEffect || "УЛУЧШЕНИЕ");
        selectMuiBtn(data.callResult || "ОСТАВЛЕН НА МЕСТЕ");

        selectMuiYN(["аллергия"], data.allergy || "НЕТ");
        selectMuiYN(["реакция на свет"], data.lightReaction || "ДА");
        selectMuiYN(["высыпания"], "НЕТ");
        selectMuiYN(["перистальтика"], data.peristalsis || "ДА");
        selectMuiYN(["симптомы раздражения брюшины"], "НЕТ");
        selectMuiYN(["асцит"], "НЕТ");
        selectMuiYN(["периферические отеки", "отеки"], "НЕТ");
        selectMuiYN(["запах алкоголя"], "НЕТ");
        selectMuiYN(["запах ацетона"], "НЕТ");

        // 6. ВЫПАДАЮЩИЕ СПИСКИ MATERIAL-UI
        await selectMuiDropdown(["общее состояние"], data.generalState || "Удовлетворительное");
        await selectMuiDropdown(["сознание"], data.consciousness || "Ясное");
        if (data.skin) await selectMuiDropdown(["кожа"], data.skin);
        if (data.heartSounds) await selectMuiDropdown(["тоны сердца"], data.heartSounds);
        if (data.lungs) await selectMuiDropdown(["дыхание"], data.lungs);
        if (data.percussion) await selectMuiDropdown(["перкуссия легких"], data.percussion);
        if (data.abdomen) await selectMuiDropdown(["живот"], data.abdomen);
        if (data.apo) await selectMuiDropdown(["территориальная апо"], data.apo);
        if (data.mkb) await selectMuiDropdown(["код по мкб"], data.mkb);

        if (fillBtn) fillBtn.innerText = "⚡";
    } catch (e) {
        console.error("Autofill error:", e);
        if (fillBtn) fillBtn.innerText = "⚡";
    }
};
