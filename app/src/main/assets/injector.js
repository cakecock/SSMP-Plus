(function() {
    if (!window.currentCardSnapshot) {
        window.currentCardSnapshot = {};
    }

    // ==========================================
    // 1. СЕТЕВОЙ ПЕРЕХВАТЧИК API КАРТЫ ВЫЗОВА
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