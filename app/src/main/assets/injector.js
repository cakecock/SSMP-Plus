(function() {
    if (!window.currentCardSnapshot) {
        window.currentCardSnapshot = {};
    }

    let isAutofilling = false;
    window.b103AuthHeader = null;

    // ==========================================
    // 1. СЕТЕВОЙ ПЕРЕХВАТЧИК API И ТОКЕНА
    // ==========================================
    function setupNetworkInterceptor() {
        const origFetch = window.fetch;
        window.fetch = async function(...args) {
            try {
                const req = args[0];
                const opts = args[1] || {};
                if (opts.headers) {
                    const auth = opts.headers['Authorization'] || opts.headers['authorization'] || (opts.headers.get && opts.headers.get('Authorization'));
                    if (auth) window.b103AuthHeader = auth;
                }
            } catch (e) {}

            const response = await origFetch.apply(this, args);
            try {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] ? args[0].url : '');
                if (url && url.includes('/api/card/') && !url.includes('/short')) {
                    const clone = response.clone();
                    clone.json().then(data => {
                        if (data && data.patient) {
                            window.lastLoadedApiCard = data;
                            // Сохраняем в архив только если в карте уже есть данные (не пустая новая)
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
        const origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
        const origSend = XMLHttpRequest.prototype.send;

        XMLHttpRequest.prototype.open = function(method, url) {
            this._url = url;
            return origOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
            if (header && header.toLowerCase() === 'authorization') {
                window.b103AuthHeader = value;
            }
            return origSetHeader.apply(this, arguments);
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

    // ==========================================
    // 2. ПОИСК КАРТЫ ПАЦИЕНТА НА СЕРВЕРЕ (ПО ВСЕМ ПОДСТАНЦИЯМ)
    // ==========================================
    window.fetchPatientFromServer = async function(fio) {
        try {
            const cleanFio = fio.trim();
            if (cleanFio.length < 3) return null;

            const headers = { 'Accept': 'application/json' };
            if (window.b103AuthHeader) headers['Authorization'] = window.b103AuthHeader;

            const searchUrl = `/ssmp_proxy_api/ssmp/api/card/short?size=10&page=0&search=${encodeURIComponent(cleanFio)}`;
            const res = await fetch(searchUrl, { headers });
            if (!res.ok) return null;

            const searchData = await res.json();
            const cardsList = searchData.content || searchData.items || (Array.isArray(searchData) ? searchData : []);
            if (cardsList.length === 0) return null;

            // Ищем прошлую карту (не текущую открытую), желательно со статусом подготовленной
            const currentId = window.lastLoadedApiCard ? window.lastLoadedApiCard.id : -1;
            const pastCards = cardsList.filter(c => c.id !== currentId);
            if (pastCards.length === 0) return null;

            // Проверяем до 3 прошлых карт, чтобы найти самую полную
            for (let i = 0; i < Math.min(pastCards.length, 3); i++) {
                const cardId = pastCards[i].id;
                const cardRes = await fetch(`/ssmp_proxy_api/ssmp/api/card/${cardId}`, { headers });
                if (cardRes.ok) {
                    const fullCard = await cardRes.json();
                    if ((fullCard.anamnesis && fullCard.anamnesis.length > 5) || fullCard.diagnosis) {
                        // Сохраняем найденную карту в локальный архив телефона
                        window.AndroidBridge?.saveApiCard(JSON.stringify(fullCard));
                        return fullCard;
                    }
                }
            }
        } catch (e) {
            console.error("Server search error:", e);
        }
        return null;
    };

    // Конвертация серверного JSON в формат автозаполнения
    function convertServerJsonToAutofill(raw) {
        const patient = raw.patient || {};
        const fio = patient.fullName || raw.initialFullName || "";
        
        // Конвертируем дату 1954-09-20T00:00:00 -> 20.09.1954 / 00:00
        let bDate = patient.birthDate || "";
        if (bDate.includes("T") && bDate.includes("-")) {
            const parts = bDate.split("T")[0].split("-");
            if (parts.length === 3) bDate = `${parts[2]}.${parts[1]}.${parts[0]} / 00:00`;
        }

        const extractTypes = (arr) => Array.isArray(arr) ? arr.map(x => x.type || x).filter(Boolean).join(", ") : "";

        return {
            fio: fio,
            birthDate: bDate,
            age: raw.age ? String(raw.age) : "",
            ageUnit: raw.ageType?.type || "",
            gender: patient.gender === true ? "МУЖЧИНА" : (patient.gender === false ? "ЖЕНЩИНА" : ""),
            address: patient.placeOfRegistration || patient.clarificationAddress || raw.address || "",
            apo: patient.territorialMedicalFacility?.type || "",
            callReason: raw.reasonToCall?.type || "",
            complaints: raw.complaints || "",
            anamnesis: raw.anamnesis || "",
            pastDiseases: raw.pastIllnesses || "",
            allergy: raw.allergy === true ? "ДА" : "НЕТ",
            generalState: raw.generalState?.type || "",
            consciousness: raw.consciousness?.type || "",
            position: raw.position?.type?.toUpperCase() || "АКТИВНОЕ",
            behavior: raw.behavior?.type?.toUpperCase() || "СПОКОЙНОЕ",
            glasgowScore: raw.comaScale ? String(raw.comaScale) : "15",
            bp: raw.arterialPressure || "",
            bpAdapted: raw.arterialPressureAdaptive || "",
            pulse: raw.pulse ? String(raw.pulse) : "",
            rr: raw.breathCount ? String(raw.breathCount) : "",
            temp: raw.skinTemp ? String(raw.skinTemp) : "",
            saturation: raw.saturation1 ? String(raw.saturation1) : "",
            glucose: raw.glycemia1 ? String(raw.glycemia1) : "",
            heartSounds: extractTypes(raw.heartSounds),
            lungs: extractTypes(raw.breaths),
            percussion: extractTypes(raw.percussionOfLungs),
            abdomen: extractTypes(raw.stomachs),
            skin: extractTypes(raw.skins),
            urination: extractTypes(raw.urinations)?.toUpperCase() || "НЕ НАРУШЕНО",
            pupils: raw.pupils?.type?.toUpperCase() || "МИОЗ",
            lightReaction: raw.pupilsLight === false ? "НЕТ" : "ДА",
            speech: raw.speech?.type?.toUpperCase() || "СОХРАНЕНА",
            gait: raw.gait?.type?.toUpperCase() || "СВОБОДНАЯ",
            face: raw.face?.type?.toUpperCase() || "СИММЕТРИЧНОЕ",
            nystagmus: raw.nystagmus?.type?.toUpperCase() || "НЕТ",
            tongue: raw.tongue || "Влажный, не обложен",
            mucous: raw.mucosa || "Чистые",
            liver: raw.liver?.type?.toUpperCase() || "НЕ УВЕЛИЧЕНА",
            peristalsis: raw.peristalsis === false ? "НЕТ" : "ДА",
            localStatus: raw.localStatus || "",
            ecgBefore: raw.electrocardiogramBefore || "",
            ecgAfter: raw.electrocardiogramAfter || "",
            diagnosis: raw.diagnosis || "",
            mkb: raw.icd ? `[${raw.icd.code}] ${raw.icd.name || ""}`.trim() : "",
            attack: raw.postAttack?.type?.toUpperCase() || "КУПИРОВАН",
            therapyEffect: raw.result?.type?.toUpperCase() || "УЛУЧШЕНИЕ",
            callResult: raw.resultDeparture?.type?.toUpperCase() || "ОСТАВЛЕН НА МЕСТЕ"
        };
    }

    // Поиск видимого поля на текущем открытом экране
    function findVisibleMuiField(keywords) {
        const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, label, span, p'));
        for (let kw of keywords) {
            const matchingLabels = labels.filter(l => l.innerText && l.innerText.trim().toLowerCase() === kw.toLowerCase() || (l.innerText && l.innerText.trim().toLowerCase().includes(kw.toLowerCase())));
            for (let target of matchingLabels) {
                const control = target.closest('.MuiFormControl-root') || target.closest('div');
                const inp = control?.querySelector('textarea, input:not([type="hidden"])');
                if (inp && inp.offsetParent !== null) return inp;
            }
        }
        return null;
    }

    function isInsideCard() {
        const text = document.body ? document.body.innerText : '';
        return (text.includes('О ВЫЗОВЕ') && text.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ')) || text.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ');
    }

    // ==========================================
    // 3. ПЛАВАЮЩИЕ КНОПКИ
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

        // Принудительное сохранение текущей карты
        document.getElementById('btn-save-db').onclick = function() {
            if (window.lastLoadedApiCard && window.AndroidBridge) {
                window.AndroidBridge.saveApiCardForced(JSON.stringify(window.lastLoadedApiCard));
            } else {
                window.AndroidBridge?.notifyUser("Откройте или обновите карту для сохранения");
            }
        };

        // Умный запуск Автозаполнения ⚡
        document.getElementById('btn-fill-db').onclick = async function() {
            const btn = document.getElementById('btn-fill-db');
            // Проверяем видимое поле ФИО на текущем экране
            let fioInp = findVisibleMuiField(["фио", "фио пациента"]);
            let fio = fioInp ? fioInp.value.trim() : "";

            // Если поле ФИО на экране стерто/пустое — сразу открываем выбор карты из архива!
            if (!fio || fio.length < 3) {
                window.AndroidBridge?.openAutofillPicker();
                return;
            }

            // Ищем в локальной базе или на сервере всех подстанций
            window.AndroidBridge?.requestAutofill(fio);
        };
    }

    // Функция, вызываемая из Android, если в локальной базе телефона пациента ещё нет
    window.searchServerAndAutofill = async function(fio) {
        const btn = document.getElementById('btn-fill-db');
        if (btn) btn.innerText = "⏳";
        const serverCard = await window.fetchPatientFromServer(fio);
        if (btn) btn.innerText = "⚡";

        if (serverCard) {
            const converted = convertServerJsonToAutofill(serverCard);
            await window.applyAutofill(converted);
            window.AndroidBridge?.notifyUser(`⚡ Найдена прошлая карта на сервере (${converted.fio}) и заполнена!`);
        } else {
            // Если даже на сервере нет — открываем ручной выбор образца/пациента
            window.AndroidBridge?.openAutofillPicker();
        }
    };

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
// 4. АВТОПИЛОТ MATERIAL-UI (ТЕКСТ + КНОПКИ + ВЫПАДАЮЩИЕ СПИСКИ)
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
                    const inp = control?.querySelector('textarea, input:not([type="hidden"]):not([disabled])');
                    if (inp) return inp;
                }
            }
            return null;
        }

        // Автовыбор выпадающих списков Material-UI (одиночных и множественных)
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

            // Если это Autocomplete с текстовым вводом (например МКБ или Нас. пункт)
            const autoInput = control.querySelector('input[type="text"]:not([disabled])');
            const selectBtn = control.querySelector('.MuiSelect-root, [role="button"], .MuiInputBase-root');

            if (selectBtn) {
                // В Material-UI меню открывается строго по событию mousedown (button: 0)
                selectBtn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                await new Promise(r => setTimeout(r, 90));

                const options = Array.from(document.querySelectorAll('li[role="option"], .MuiMenuItem-root, .MuiListItem-root'));
                if (options.length > 0) {
                    for (let val of valuesToSelect) {
                        const matchOpt = options.find(o => o.innerText && o.innerText.trim().toLowerCase() === val)
                                      || options.find(o => o.innerText && o.innerText.trim().toLowerCase().includes(val));
                        if (matchOpt) {
                            const isSelected = matchOpt.classList.contains('Mui-selected') || matchOpt.getAttribute('aria-selected') === 'true';
                            if (!isSelected) {
                                matchOpt.click();
                                await new Promise(r => setTimeout(r, 40));
                            }
                        }
                    }
                    // Закрываем выпадающее меню (клик по подложке или Escape)
                    const backdrop = document.querySelector('.MuiBackdrop-root, .MuiPopover-root [aria-hidden="true"]');
                    if (backdrop) backdrop.click();
                    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
                    await new Promise(r => setTimeout(r, 50));
                    return;
                }
            }

            if (autoInput) {
                setReactVal(autoInput, valueStr);
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
                        const inputs = Array.from(parent.querySelectorAll('input:not([type="hidden"])'));
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

        // 1. ПАСПОРТНЫЕ ДАННЫЕ (ФИО, Дата рождения, Возраст, Адрес)
        if (data.fio) setReactVal(findMuiInput(["фио"], true), data.fio);
        if (data.birthDate) {
            let formattedDate = data.birthDate;
            if (formattedDate.includes("T") && formattedDate.includes("-")) {
                const p = formattedDate.split("T")[0].split("-");
                if (p.length === 3) formattedDate = `${p[2]}.${p[1]}.${p[0]} / 00:00`;
            }
            setReactVal(findMuiInput(["дата рождения"]), formattedDate);
        }
        if (data.age) setReactVal(findMuiInput(["возраст"], true), data.age);
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

        // 6. ВЫПАДАЮЩИЕ СПИСКИ MATERIAL-UI (Состояние, Сознание, Тоны, Дыхание, Живот, Кожа, АПО)
        await selectMuiDropdown(["общее состояние"], data.generalState || "Удовлетворительное");
        await selectMuiDropdown(["сознание"], data.consciousness || "Ясное");
        if (data.skin) await selectMuiDropdown(["кожа"], data.skin);
        if (data.heartSounds) await selectMuiDropdown(["тоны сердца"], data.heartSounds);
        if (data.lungs) await selectMuiDropdown(["дыхание"], data.lungs);
        if (data.percussion) await selectMuiDropdown(["перкуссия легких"], data.percussion);
        if (data.abdomen) await selectMuiDropdown(["живот"], data.abdomen);
        if (data.apo) await selectMuiDropdown(["территориальная апо"], data.apo);

        if (fillBtn) fillBtn.innerText = "⚡";
    } catch (e) {
        console.error("Autofill error:", e);
        if (fillBtn) fillBtn.innerText = "⚡";
    }
};
