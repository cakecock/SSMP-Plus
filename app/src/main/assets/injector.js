(function() {
    if (!window.currentCardSnapshot) {
        window.currentCardSnapshot = {};
    }

    let isAutofilling = false;

    // ==========================================
    // 1. СЕТЕВОЙ ПЕРЕХВАТЧИК СЕРВЕРНОГО API
    // ==========================================
    function setupNetworkInterceptor() {
        // Перехват fetch
        const origFetch = window.fetch;
        window.fetch = async function(...args) {
            const response = await origFetch.apply(this, args);
            try {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] ? args[0].url : '');
                if (url && url.includes('/api/card/') && !url.includes('/short')) {
                    const clone = response.clone();
                    clone.json().then(data => {
                        if (data && data.patient && window.AndroidBridge) {
                            window.AndroidBridge.saveApiCard(JSON.stringify(data));
                        }
                    }).catch(() => {});
                }
            } catch (e) {}
            return response;
        };

        // Перехват XMLHttpRequest
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
                        if (data && data.patient && window.AndroidBridge) {
                            window.AndroidBridge.saveApiCard(JSON.stringify(data));
                        }
                    }
                } catch (e) {}
            });
            return origSend.apply(this, arguments);
        };
    }
    setupNetworkInterceptor();

    // ==========================================
    // 2. РАБОТА С КНОПКАМИ И ПОЛЯМИ MATERIAL-UI
    // ==========================================
    function isButtonActive(btn) {
        if (!btn) return false;
        const s = window.getComputedStyle(btn);
        const isWhiteText = s.color.includes('255, 255, 255');
        const isColoredBg = !s.backgroundColor.includes('rgba(0, 0, 0, 0)') && !s.backgroundColor.includes('255, 255, 255');
        return (isWhiteText && isColoredBg) || btn.className.includes('MuiButton-contained') || btn.className.includes('active');
    }

    // Поиск поля Material-UI через .MuiFormControl-root
    function findMuiField(keywords) {
        const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, label, span, p'));
        for (let kw of keywords) {
            const target = labels.find(l => l.innerText && l.innerText.trim().toLowerCase().includes(kw));
            if (target) {
                const control = target.closest('.MuiFormControl-root') || target.closest('div');
                const inp = control?.querySelector('textarea, input:not([type="hidden"])');
                if (inp) return inp;
            }
        }
        return null;
    }

    // Безопасный выбор кнопки без повторных кликов
    function selectMuiOption(targetText) {
        if (!targetText) return;
        const buttons = Array.from(document.querySelectorAll('button, div[role="button"]'));
        const btn = buttons.find(b => b.innerText.trim().toUpperCase() === targetText.toUpperCase());
        if (btn && !isButtonActive(btn)) {
            btn.click();
        }
    }

    function selectMuiYesNo(labelKeywords, choice) {
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
            if (desired && !isButtonActive(desired)) {
                desired.click();
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
                        setReactInputValue(inputs[0], parts[0].trim());
                        setReactInputValue(inputs[1], parts[1].trim());
                        return;
                    }
                }
                parent = parent.parentElement;
            }
        }
    }

    function setReactInputValue(input, val) {
        if (!input || val === undefined || val === null) return;
        const str = String(val).trim();
        if (str === "" || input.value.trim() === str) return;

        try {
            input.focus();
            const proto = input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
            const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
            if (setter) {
                setter.call(input, str);
            } else {
                input.value = str;
            }
            input.dispatchEvent(new Event('input', { bubbles: true }));
            input.dispatchEvent(new Event('change', { bubbles: true }));
            input.blur();
        } catch (e) {
            input.value = str;
        }
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

        document.getElementById('btn-save-db').onclick = function() {
            window.AndroidBridge?.notifyUser("Карта автоматически перехвачена и сохранена из API!");
        };

        document.getElementById('btn-fill-db').onclick = function() {
            let fioInp = findMuiField(["фио пациента", "фио"]);
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
        if (inCard === lastCardState) return;
        lastCardState = inCard;

        const btnBest = document.getElementById('btn-best-db');
        const btnMkb = document.getElementById('btn-mkb-db');
        const btnSearch = document.getElementById('btn-search-db');
        const btnSave = document.getElementById('btn-save-db');
        const btnFill = document.getElementById('btn-fill-db');

        setBtnAnimated(btnBest, !inCard);
        setBtnAnimated(btnMkb, !inCard);
        setBtnAnimated(btnSearch, !inCard);
        setBtnAnimated(btnSave, inCard);
        setBtnAnimated(btnFill, inCard);
    }

    if (window.b103Observer) window.b103Observer.disconnect();
    window.b103Observer = new MutationObserver(() => updateVisibility());
    window.b103Observer.observe(document.body, { childList: true, subtree: true });
    setInterval(updateVisibility, 300);
})();

// ==========================================
// 4. ИНТЕЛЛЕКТУАЛЬНОЕ АВТОЗАПОЛНЕНИЕ MATERIAL-UI
// ==========================================
window.applyAutofill = function(jsonStr) {
    try {
        const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
        const fillBtn = document.getElementById('btn-fill-db');
        if (fillBtn) fillBtn.innerText = "⏳";

        function setReactVal(input, val) {
            if (!input || val === undefined || val === null) return;
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

        function findMui(keywords) {
            const labels = Array.from(document.querySelectorAll('.MuiFormLabel-root, label, span, p'));
            for (let kw of keywords) {
                const target = labels.find(l => l.innerText && l.innerText.trim().toLowerCase().includes(kw));
                if (target) {
                    const control = target.closest('.MuiFormControl-root') || target.closest('div');
                    const inp = control?.querySelector('textarea, input:not([type="hidden"])');
                    if (inp) return inp;
                }
            }
            return null;
        }

        function selectMui(targetText) {
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

        function fillDual(isAdapted, bpStr) {
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

        // Текстовые поля
        setReactVal(findMui(["жалобы"]), data.complaints);
        setReactVal(findMui(["анамнез заболевания", "анамнез"]), data.anamnesis);
        setReactVal(findMui(["перенесенные заболевания"]), data.pastDiseases);
        setReactVal(findMui(["предварительный диагноз", "диагноз"]), data.diagnosis);
        setReactVal(findMui(["код по мкб"]), data.mkb);
        setReactVal(findMui(["локальный статус"]), data.localStatus);

        // Давление
        if (data.bpAdapted) fillDual(true, data.bpAdapted);

        // ЭКГ
        setReactVal(findMui(["экг до оказания", "экг до"]), data.ecgBefore);
        setReactVal(findMui(["экг после оказания", "экг после"]), data.ecgAfter);

        // Объективный статус
        setReactVal(findMui(["шкала комы глазго (баллы)", "глазго (баллы)"]), data.glasgowScore || "15");
        setReactVal(findMui(["тонус мышц"]), "Норма");
        setReactVal(findMui(["патологические рефлексы"]), "Нет");
        setReactVal(findMui(["менингиальные знаки"]), "Нет");
        setReactVal(findMui(["плегии, парезы", "плегии"]), "Нет");
        setReactVal(findMui(["язык"]), data.tongue || "Влажный , не обложен");
        setReactVal(findMui(["слизистые"]), data.mucous || "Чистые");

        // Кнопки-переключатели
        if (data.gender) selectMui(data.gender);
        selectMui(data.position || "АКТИВНОЕ");
        selectMui(data.behavior || "СПОКОЙНОЕ");
        selectMui(data.pupils || "МИОЗ");
        selectMui(data.speech || "СОХРАНЕНА");
        selectMui(data.face || "СИММЕТРИЧНОЕ");
        selectMui(data.gait || "СВОБОДНАЯ");
        selectMui(data.nystagmus || "НЕТ");
        selectMui(data.liver || "НЕ УВЕЛИЧЕНА");
        selectMui(data.urination || "НЕ НАРУШЕНО");

        // Исход
        selectMui(data.attack || "КУПИРОВАН");
        selectMui(data.therapyEffect || "УЛУЧШЕНИЕ");
        selectMui(data.callResult || "ОСТАВЛЕН НА МЕСТЕ");

        // Да / Нет
        selectMuiYN(["аллергия"], data.allergy || "НЕТ");
        selectMuiYN(["реакция на свет"], data.lightReaction || "ДА");
        selectMuiYN(["высыпания"], "НЕТ");
        selectMuiYN(["перистальтика"], data.peristalsis || "ДА");
        selectMuiYN(["симптомы раздражения брюшины"], "НЕТ");
        selectMuiYN(["асцит"], "НЕТ");
        selectMuiYN(["периферические отеки", "отеки"], "НЕТ");
        selectMuiYN(["запах алкоголя"], "НЕТ");
        selectMuiYN(["запах ацетона"], "НЕТ");

        if (fillBtn) fillBtn.innerText = "⚡";
    } catch (e) {
        console.error("Autofill error:", e);
        const fillBtn = document.getElementById('btn-fill-db');
        if (fillBtn) fillBtn.innerText = "⚡";
    }
};
