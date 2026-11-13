(function() {
    if (!window.currentCardSnapshot) {
        window.currentCardSnapshot = {};
    }

    // Железобетонная проверка активности кнопки (белый текст на бирюзовом фоне)
    function isButtonActive(el) {
        if (!el) return false;
        const btn = el.closest('button, div[role="button"], [class*="btn"]') || el;
        const s = window.getComputedStyle(btn);
        
        // 1. Проверка системных классов активности
        if (btn.classList.contains('active') || 
            btn.classList.contains('q-btn--active') || 
            btn.classList.contains('v-btn--active') ||
            btn.getAttribute('aria-pressed') === 'true' ||
            btn.getAttribute('aria-selected') === 'true') {
            return true;
        }

        // 2. Визуальная сигнатура: белый текст на цветном фоне
        const color = s.color;
        const bg = s.backgroundColor;
        const isWhiteText = color.includes('255, 255, 255') || color.includes('250, 250, 250');
        const isColoredBg = !bg.includes('rgba(0, 0, 0, 0)') && !bg.includes('255, 255, 255') && !bg.includes('transparent');
        if (isWhiteText && isColoredBg) return true;

        // 3. Бирюзовые оттенки сайта 103
        if (bg.includes('0, 121') || bg.includes('0, 105') || bg.includes('0, 137') || bg.includes('0, 77') || bg.includes('38, 166')) {
            return true;
        }

        return false;
    }

    function findActiveOption(options) {
        const buttons = Array.from(document.querySelectorAll('button, div[role="button"], [class*="btn"], span, a'));
        for (let opt of options) {
            const btn = buttons.find(b => b.children.length === 0 && b.innerText.trim().toUpperCase() === opt.toUpperCase());
            if (btn) {
                const clickable = btn.closest('button, div[role="button"], [class*="btn"]') || btn;
                if (isButtonActive(clickable)) return opt;
            }
        }
        return "";
    }

    function getYesNoToggle(labelKeywords) {
        const all = Array.from(document.querySelectorAll('label, div, span, p'));
        let target = null;
        for (let kw of labelKeywords) {
            target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
            if (target) break;
        }
        if (!target) return "";
        let parent = target.parentElement;
        for (let i = 0; i < 4 && parent && parent !== document.body; i++) {
            const btns = Array.from(parent.querySelectorAll('button, div[role="button"], [class*="btn"]'));
            for (let b of btns) {
                const t = b.innerText.trim().toUpperCase();
                if ((t === "ДА" || t === "НЕТ") && isButtonActive(b)) return t;
            }
            parent = parent.parentElement;
        }
        return "";
    }

    function findInputNearLabel(keywords) {
        const all = Array.from(document.querySelectorAll('label, div, span, p'));
        for (let kw of keywords) {
            const target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
            if (target) {
                let parent = target.parentElement;
                for (let i = 0; i < 5 && parent && parent !== document.body; i++) {
                    const inp = parent.querySelector('input:not([type="hidden"]), textarea');
                    if (inp && !inp.value.includes('[object')) return inp;
                    parent = parent.parentElement;
                }
            }
        }
        return null;
    }

    function extractBPBySlash(isAdapted) {
        const all = Array.from(document.querySelectorAll('*'));
        const slashes = all.filter(el => el.children.length === 0 && el.textContent.trim() === '/');
        
        for (let slash of slashes) {
            let parent = slash.parentElement;
            for (let i = 0; i < 4 && parent && parent !== document.body; i++) {
                const text = parent.innerText.toLowerCase();
                const isMatch = isAdapted ? text.includes("адаптированное") : (text.includes("ад") && !text.includes("адаптированное") && !text.includes("после"));
                if (isMatch) {
                    const inputs = Array.from(parent.querySelectorAll('input:not([type="hidden"])')).filter(i => /^\d{1,3}$/.test(i.value.trim()));
                    if (inputs.length >= 2) return `${inputs[0].value}/${inputs[1].value}`;
                }
                parent = parent.parentElement;
            }
        }
        return "";
    }

    function getDropdownOrChipsValue(keywords) {
        const all = Array.from(document.querySelectorAll('label, div, span, p'));
        let target = null;
        for (let kw of keywords) {
            target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
            if (target) break;
        }
        if (!target) return "";
        let parent = target.parentElement;
        for (let i = 0; i < 4 && parent && parent !== document.body; i++) {
            let chips = Array.from(parent.querySelectorAll('.q-chip, .v-chip, [class*="chip"], [class*="badge"], [class*="item"]'));
            if (chips.length > 0) {
                let chipTexts = chips.map(c => c.innerText.trim()).filter(t => t.length > 0 && !t.includes('[object') && t !== '✕' && t !== '×' && t !== 'close');
                if (chipTexts.length > 0) return chipTexts.join(', ');
            }
            let lines = parent.innerText.split('\n')
                .map(l => l.trim())
                .filter(l => l.length > 0 && !keywords.some(k => l.toLowerCase().includes(k)) && l !== "Не выбрано" && !l.includes("[object") && !l.includes("▼") && !l.includes("keyboard_arrow") && !l.includes("arrow_drop_down"));
            if (lines.length > 0) return lines.join(', ');
            parent = parent.parentElement;
        }
        return "";
    }

    function extractMedicationsCards() {
        const all = Array.from(document.querySelectorAll('*'));
        const qtyNodes = all.filter(el => el.children.length === 0 && el.textContent.includes("Количество:"));
        const items = [];

        for (let qNode of qtyNodes) {
            let card = qNode.parentElement;
            while (card && card !== document.body) {
                const text = card.innerText || '';
                const lines = text.split('\n').map(s => s.trim()).filter(Boolean);
                const qIndex = lines.findIndex(l => l.includes("Количество:"));
                if (qIndex > 0) {
                    const drugName = lines[0];
                    const qtyFull = lines[qIndex];
                    const routeLine = lines.find(l => l.includes("Путь введения:"));
                    let cleanQty = qtyFull.replace(/Количество:\s*/i, '').trim();
                    let cleanRoute = '';
                    if (routeLine && !routeLine.includes("не определен")) {
                        cleanRoute = ` [${routeLine.replace(/Путь введения:\s*/i, '').trim()}]`;
                    }
                    if (drugName && !drugName.includes("Лекарственные") && !drugName.includes("ДОБАВИТЬ")) {
                        items.push(`${drugName} — ${cleanQty}${cleanRoute}`);
                    }
                    break;
                }
                card = card.parentElement;
            }
        }
        return Array.from(new Set(items)).join(";\n• ");
    }

    function isInsideCard() {
        const text = document.body ? document.body.innerText : '';
        return (text.includes('О ВЫЗОВЕ') && text.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ')) || text.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ');
    }

    function scanAndMergeVisibleFields() {
        if (!isInsideCard()) return;
        const snap = window.currentCardSnapshot;

        const fioInp = findInputNearLabel(["фио пациента", "фио"]);
        const fio = fioInp ? fioInp.value.trim() : "";
        if (fio && fio.length >= 3) {
            if (snap.fio && snap.fio.toLowerCase() !== fio.toLowerCase()) window.currentCardSnapshot = {};
            window.currentCardSnapshot.fio = fio;
        }

        const bDate = findInputNearLabel(["дата рождения"])?.value.trim();
        if (bDate) snap.birthDate = bDate;
        const addr = findInputNearLabel(["место жительства (пребывания)", "адрес вызова", "адрес"])?.value.trim();
        if (addr) snap.address = addr;
        const reason = findInputNearLabel(["повод к вызову"])?.value.trim() || getDropdownOrChipsValue(["повод к вызову"]);
        if (reason) snap.callReason = reason;

        const gender = findActiveOption(["МУЖЧИНА", "ЖЕНЩИНА"]);
        if (gender) snap.gender = gender;

        const apo = getDropdownOrChipsValue(["территориальная апо"]);
        if (apo) snap.apo = apo;

        const complaints = findInputNearLabel(["жалобы"])?.value.trim();
        if (complaints) snap.complaints = complaints;
        const anamnesis = findInputNearLabel(["анамнез заболевания", "анамнез"])?.value.trim();
        if (anamnesis) snap.anamnesis = anamnesis;
        const past = findInputNearLabel(["перенесенные заболевания"])?.value.trim();
        if (past) snap.pastDiseases = past;

        const allergy = getYesNoToggle(["аллергия"]);
        if (allergy) snap.allergy = allergy;

        const genState = getDropdownOrChipsValue(["общее состояние"]);
        if (genState) snap.generalState = genState;
        const consciousness = getDropdownOrChipsValue(["сознание"]);
        if (consciousness) snap.consciousness = consciousness;
        const position = findActiveOption(["АКТИВНОЕ", "ПАССИВНОЕ", "ВЫНУЖДЕННОЕ"]);
        if (position) snap.position = position;
        const behavior = findActiveOption(["СПОКОЙНОЕ", "ВОЗБУЖДЕННОЕ", "АГРЕССИВНОЕ"]);
        if (behavior) snap.behavior = behavior;

        const gScore = findInputNearLabel(["шкала комы глазго (баллы)", "глазго (баллы)"])?.value.trim();
        if (gScore) snap.glasgowScore = gScore;

        const bp = extractBPBySlash(false);
        if (bp) snap.bp = bp;
        const bpAdapt = extractBPBySlash(true);
        if (bpAdapt) snap.bpAdapted = bpAdapt;

        const pulse = findInputNearLabel(["комментарий (пульс", "пульс, ударов в минуту", "пульс"])?.value.trim();
        if (pulse) snap.pulse = pulse;
        const rr = findInputNearLabel(["частота дыхания (в минуту)", "частота дыхания", "чдд"])?.value.trim();
        if (rr) snap.rr = rr;
        const temp = findInputNearLabel(["температура тела", "температура"])?.value.trim();
        if (temp) snap.temp = temp;

        const sat = findInputNearLabel(["сатурация 1", "сатурация", "spo2"])?.value.trim();
        if (sat) snap.saturation = sat;
        const gluc = findInputNearLabel(["гликемия 1", "гликемия", "сахар"])?.value.trim();
        if (gluc) snap.glucose = gluc;

        const heart = getDropdownOrChipsValue(["тоны сердца"]);
        if (heart) snap.heartSounds = heart;
        const lungs = getDropdownOrChipsValue(["дыхание"]);
        if (lungs) snap.lungs = lungs;
        const percussion = getDropdownOrChipsValue(["перкуссия легких", "перкуссия"]);
        if (percussion) snap.percussion = percussion;
        const abdomen = getDropdownOrChipsValue(["живот"]);
        if (abdomen) snap.abdomen = abdomen;
        const localStatus = findInputNearLabel(["локальный статус"])?.value.trim();
        if (localStatus) snap.localStatus = localStatus;

        const pupils = findActiveOption(["МИОЗ", "МИДРИАЗ", "АНИЗОКАРИЯ"]);
        if (pupils) snap.pupils = pupils;
        const lightReaction = getYesNoToggle(["реакция на свет"]);
        if (lightReaction) snap.lightReaction = lightReaction;
        const speech = findActiveOption(["СОХРАНЕНА", "ОТСУТСТВУЕТ", "НАРУШЕНА"]);
        if (speech) snap.speech = speech;
        const gait = findActiveOption(["СВОБОДНАЯ", "НАРУШЕНА", "НЕ ОЦЕНИВАЛАСЬ"]);
        if (gait) snap.gait = gait;
        const face = findActiveOption(["СИММЕТРИЧНОЕ", "АСИММЕТРИЧНОЕ"]);
        if (face) snap.face = face;
        const nystagmus = findActiveOption(["НЕТ", "ГОРИЗОНТАЛЬНЫЙ", "ВЕРТИКАЛЬНЫЙ"]);
        if (nystagmus) snap.nystagmus = nystagmus;

        const skin = getDropdownOrChipsValue(["кожа"]);
        if (skin) snap.skin = skin;
        const tongue = findInputNearLabel(["язык"])?.value.trim();
        if (tongue) snap.tongue = tongue;
        const mucous = findInputNearLabel(["слизистые"])?.value.trim();
        if (mucous) snap.mucous = mucous;

        const peristalsis = getYesNoToggle(["перистальтика"]);
        if (peristalsis) snap.peristalsis = peristalsis;
        const liver = findActiveOption(["УВЕЛИЧЕНА", "НЕ УВЕЛИЧЕНА"]);
        if (liver) snap.liver = liver;
        const urination = findActiveOption(["НЕ НАРУШЕНО", "УЧАЩЕНО", "БОЛЕЗНЕННОЕ", "ЗАДЕРЖКА МОЧЕИСПУСКАНИЯ"]);
        if (urination) snap.urination = urination;

        const ecgBefore = findInputNearLabel(["экг до оказания", "экг до"])?.value.trim();
        if (ecgBefore) snap.ecgBefore = ecgBefore;
        const ecgAfter = findInputNearLabel(["экг после оказания", "экг после"])?.value.trim();
        if (ecgAfter) snap.ecgAfter = ecgAfter;

        const diag = findInputNearLabel(["предварительный диагноз", "диагноз"])?.value.trim();
        if (diag) snap.diagnosis = diag;
        const mkb = findInputNearLabel(["код по мкб"])?.value.trim() || getDropdownOrChipsValue(["код по мкб"]);
        if (mkb) snap.mkb = mkb;

        const meds = extractMedicationsCards();
        if (meds) snap.medications = meds;

        const attack = findActiveOption(["КУПИРОВАН", "НЕ КУПИРОВАН"]);
        if (attack) snap.attack = attack;
        const effect = findActiveOption(["УЛУЧШЕНИЕ", "БЕЗ ЭФФЕКТА", "УХУДШЕНИЕ"]);
        if (effect) snap.therapyEffect = effect;
        const callResult = findActiveOption(["ОСТАВЛЕН НА МЕСТЕ", "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ", "ПЕРЕДАН БРИГАДЕ СМП"]);
        if (callResult) snap.callResult = callResult;
    }

    function getTabElement(kw) {
        const all = Array.from(document.querySelectorAll('button, div[role="tab"], [class*="tab"], a, div'));
        return all.find(el => {
            const rect = el.getBoundingClientRect();
            const t = el.innerText ? el.innerText.toUpperCase() : '';
            return rect.top >= 0 && rect.top < 140 && t.includes(kw);
        });
    }

    async function scanAllTabsCompletely() {
        const tabKeywords = ['О ВЫЗОВЕ', 'УТОЧНЕННЫЕ', 'ОБЪЕКТИВНЫЕ', 'МЕДИЦИНСКАЯ'];
        let originalTab = getTabElement('ОБЪЕКТИВНЫЕ') || getTabElement('О ВЫЗОВЕ');

        for (let kw of tabKeywords) {
            let tab = getTabElement(kw);
            if (tab) {
                tab.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                tab.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                tab.click();
                await new Promise(res => setTimeout(res, 200));
                scanAndMergeVisibleFields();
            }
        }

        if (originalTab) {
            originalTab.click();
            await new Promise(res => setTimeout(res, 80));
        }
    }

    async function resolvePatientFio() {
        if (window.currentCardSnapshot && window.currentCardSnapshot.fio && window.currentCardSnapshot.fio.length >= 3) {
            return window.currentCardSnapshot.fio;
        }

        let inp = findInputNearLabel(["фио пациента", "фио"]);
        if (inp && inp.value.trim().length >= 3) {
            return inp.value.trim();
        }

        let tab1 = getTabElement('О ВЫЗОВЕ') || getTabElement('УТОЧНЕННЫЕ');
        let currTab = getTabElement('ОБЪЕКТИВНЫЕ') || getTabElement('МЕДИЦИНСКАЯ');

        if (tab1) {
            tab1.click();
            await new Promise(r => setTimeout(r, 160));
            inp = findInputNearLabel(["фио пациента", "фио"]);
            if (currTab) {
                currTab.click();
                await new Promise(r => setTimeout(r, 80));
            }
            if (inp && inp.value.trim().length >= 3) {
                window.currentCardSnapshot.fio = inp.value.trim();
                return inp.value.trim();
            }
        }
        return "";
    }

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

        document.getElementById('btn-best-db').onclick = function() {
            if (window.AndroidBridge) AndroidBridge.openBestCardsDialog();
        };

        document.getElementById('btn-mkb-db').onclick = function() {
            if (window.AndroidBridge) AndroidBridge.openMkbDialog();
        };

        document.getElementById('btn-search-db').onclick = function() {
            if (window.AndroidBridge) AndroidBridge.openSearchDialog();
        };

        document.getElementById('btn-save-db').onclick = async function() {
            const btn = document.getElementById('btn-save-db');
            btn.innerText = "⏳";
            await scanAllTabsCompletely();
            btn.innerText = "💾";

            const snap = window.currentCardSnapshot;
            if (window.AndroidBridge && snap.fio) {
                AndroidBridge.saveCardData(JSON.stringify(snap));
            } else {
                AndroidBridge.notifyUser("Укажите фамилию пациента для сохранения");
            }
        };

        document.getElementById('btn-fill-db').onclick = async function() {
            const btn = document.getElementById('btn-fill-db');
            btn.innerText = "⏳";
            const fio = await resolvePatientFio();
            btn.innerText = "⚡";

            if (window.AndroidBridge) {
                AndroidBridge.requestAutofill(fio || "");
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

        if (inCard) scanAndMergeVisibleFields();
    }

    if (window.b103Observer) window.b103Observer.disconnect();
    window.b103Observer = new MutationObserver(() => updateVisibility());
    window.b103Observer.observe(document.body, { childList: true, subtree: true });

    setInterval(updateVisibility, 300);
})();

// ==========================================
// ИНТЕЛЛЕКТУАЛЬНЫЙ АВТОПИЛОТ (БЕЗ МИГАНИЯ И СБРОСОВ)
// ==========================================
window.applyAutofill = async function(jsonStr) {
    try {
        const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
        const fillBtn = document.getElementById('btn-fill-db');
        if (fillBtn) fillBtn.innerText = "⏳";

        // Принудительная перезапись поля с предварительной очисткой старого мусора
        function setInputValue(input, value) {
            if (!input || value === undefined || value === null) return;
            const strVal = String(value).trim();
            if (strVal === "") return;
            
            // Если значение уже в точности такое же — не дергаем DOM
            if (input.value.trim() === strVal) return;

            try {
                input.focus();
                
                // 1. Очищаем старое неверное значение
                input.value = '';
                input.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));

                // 2. Вставляем новое через нативный прототип
                const proto = input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) {
                    setter.call(input, strVal);
                } else {
                    input.value = strVal;
                }
                
                input.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                input.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                input.blur();
            } catch (e) {
                input.value = strVal;
            }
        }

        function findInputForFill(keywords) {
            const all = Array.from(document.querySelectorAll('label, div, span, p'));
            for (let kw of keywords) {
                const target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
                if (target) {
                    let parent = target.parentElement;
                    for (let i = 0; i < 5 && parent && parent !== document.body; i++) {
                        const inp = parent.querySelector('input:not([type="hidden"]), textarea');
                        if (inp) return inp;
                        parent = parent.parentElement;
                    }
                }
            }
            return null;
        }

        // БЕЗОПАСНЫЙ КЛИК ПО КНОПКЕ: если кнопка УЖЕ активна — мы её НЕ трогаем!
        function selectOption(targetText) {
            if (!targetText) return;
            const buttons = Array.from(document.querySelectorAll('button, div[role="button"], [class*="btn"], span, a'));
            const targetBtn = buttons.find(b => b.children.length === 0 && b.innerText.trim().toUpperCase() === targetText.toUpperCase());
            if (targetBtn) {
                const clickable = targetBtn.closest('button, div[role="button"], [class*="btn"]') || targetBtn;
                
                // Проверяем: если уже активна — кликать ЗАПРЕЩЕНО (чтобы не отжать обратно!)
                const style = window.getComputedStyle(clickable);
                const color = style.color;
                const bg = style.backgroundColor;
                const isWhiteText = color.includes('255, 255, 255');
                const isColored = !bg.includes('rgba(0, 0, 0, 0)') && !bg.includes('255, 255, 255') && !bg.includes('transparent');
                const isAlreadyOn = (isWhiteText && isColored) || clickable.className.includes('active');

                if (!isAlreadyOn) {
                    clickable.click();
                }
            }
        }

        // БЕЗОПАСНЫЙ КЛИК ДА / НЕТ
        function selectYesNo(labelKeywords, targetChoice) {
            if (!targetChoice) return;
            const all = Array.from(document.querySelectorAll('label, div, span, p'));
            let target = null;
            for (let kw of labelKeywords) {
                target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
                if (target) break;
            }
            if (!target) return;
            
            let parent = target.parentElement;
            for (let i = 0; i < 4 && parent && parent !== document.body; i++) {
                const btns = Array.from(parent.querySelectorAll('button, div[role="button"], [class*="btn"]'));
                const desiredBtn = btns.find(b => b.innerText.trim().toUpperCase() === targetChoice.toUpperCase());
                if (desiredBtn) {
                    const style = window.getComputedStyle(desiredBtn);
                    const isWhite = style.color.includes('255, 255, 255');
                    const isColored = !style.backgroundColor.includes('rgba(0, 0, 0, 0)') && !style.backgroundColor.includes('255, 255, 255');
                    const isAlreadyOn = (isWhite && isColored) || desiredBtn.className.includes('active');
                    
                    if (!isAlreadyOn) {
                        desiredBtn.click();
                    }
                    return;
                }
                parent = parent.parentElement;
            }
        }

        function fillDualBP(isAdapted, bpStr) {
            if (!bpStr || !bpStr.includes('/')) return;
            const parts = bpStr.split('/');
            const all = Array.from(document.querySelectorAll('*'));
            const slashes = all.filter(el => el.children.length === 0 && el.textContent.trim() === '/');
            for (let slash of slashes) {
                let parent = slash.parentElement;
                for (let i = 0; i < 4 && parent && parent !== document.body; i++) {
                    const text = parent.innerText.toLowerCase();
                    const isMatch = isAdapted ? text.includes("адаптированное") : (text.includes("ад") && !text.includes("адаптированное") && !text.includes("после"));
                    if (isMatch) {
                        const inputs = Array.from(parent.querySelectorAll('input:not([type="hidden"])')).filter(i => /^\d{1,3}$/.test(i.value.trim()) || i.value.trim() === '');
                        if (inputs.length >= 2) {
                            setInputValue(inputs[0], parts[0].trim());
                            setInputValue(inputs[1], parts[1].trim());
                            return;
                        }
                    }
                    parent = parent.parentElement;
                }
            }
        }

        function fillCurrentScreen() {
            setInputValue(findInputForFill(["жалобы"]), data.complaints);
            setInputValue(findInputForFill(["анамнез заболевания", "анамнез"]), data.anamnesis);
            setInputValue(findInputForFill(["перенесенные заболевания"]), data.pastDiseases);
            setInputValue(findInputForFill(["предварительный диагноз", "диагноз"]), data.diagnosis);
            setInputValue(findInputForFill(["код по мкб"]), data.mkb);
            setInputValue(findInputForFill(["локальный статус"]), data.localStatus);

            if (data.bpAdapted) fillDualBP(true, data.bpAdapted);

            setInputValue(findInputForFill(["экг до оказания", "экг до"]), data.ecgBefore);
            setInputValue(findInputForFill(["экг после оказания", "экг после"]), data.ecgAfter);

            setInputValue(findInputForFill(["шкала комы глазго (баллы)", "глазго (баллы)"]), data.glasgowScore || "15");
            setInputValue(findInputForFill(["тонус мышц"]), "Норма");
            setInputValue(findInputForFill(["патологические рефлексы"]), "Нет");
            setInputValue(findInputForFill(["менингиальные знаки"]), "Нет");
            setInputValue(findInputForFill(["плегии, парезы", "плегии"]), "Нет");
            setInputValue(findInputForFill(["язык"]), data.tongue || "Влажный , не обложен");
            setInputValue(findInputForFill(["слизистые"]), data.mucous || "Чистые");

            if (data.gender) selectOption(data.gender);
            selectOption(data.position || "АКТИВНОЕ");
            selectOption(data.behavior || "СПОКОЙНОЕ");
            selectOption(data.pupils || "МИОЗ");
            selectOption(data.speech || "СОХРАНЕНА");
            selectOption(data.face || "СИММЕТРИЧНОЕ");
            selectOption(data.gait || "СВОБОДНАЯ");
            selectOption(data.nystagmus || "НЕТ");
            selectOption(data.liver || "НЕ УВЕЛИЧЕНА");
            selectOption(data.urination || "НЕ НАРУШЕНО");

            selectOption(data.attack || "КУПИРОВАН");
            selectOption(data.therapyEffect || "УЛУЧШЕНИЕ");
            selectOption(data.callResult || "ОСТАВЛЕН НА МЕСТЕ");

            selectYesNo(["аллергия"], data.allergy || "НЕТ");
            selectYesNo(["реакция на свет"], data.lightReaction || "ДА");
            selectYesNo(["высыпания"], "НЕТ");
            selectYesNo(["перистальтика"], data.peristalsis || "ДА");
            selectYesNo(["симптомы раздражения брюшины"], "НЕТ");
            selectYesNo(["асцит"], "НЕТ");
            selectYesNo(["периферические отеки", "отеки"], "НЕТ");
            selectYesNo(["запах алкоголя"], "НЕТ");
            selectYesNo(["запах ацетона"], "НЕТ");
        }

        // 1. СНАЧАЛА ЗАПОЛНЯЕМ ЭКРАН, НА КОТОРОМ ТЫ СЕЙЧАС (МГНОВЕННО)
        fillCurrentScreen();

        // 2. ЗАТЕМ ТИХО ПРОХОДИМСЯ ТОЛЬКО ПО ДРУГИМ ВКЛАДКАМ (БЕЗ ДВОЙНЫХ ПЕРЕКЛЮЧЕНИЙ)
        const tabKeywords = ['О ВЫЗОВЕ', 'УТОЧНЕННЫЕ', 'ОБЪЕКТИВНЫЕ', 'МЕДИЦИНСКАЯ'];
        const all = Array.from(document.querySelectorAll('button, div[role="tab"], [class*="tab"], a, div'));

        function getTab(kw) {
            return all.find(el => {
                const rect = el.getBoundingClientRect();
                const t = el.innerText ? el.innerText.toUpperCase() : '';
                return rect.top >= 0 && rect.top < 140 && t.includes(kw);
            });
        }

        let originalTab = getTab('ОБЪЕКТИВНЫЕ') || getTab('УТОЧНЕННЫЕ') || getTab('О ВЫЗОВЕ');

        // Проходимся по вкладкам аккуратно
        for (let kw of tabKeywords) {
            let tab = getTab(kw);
            if (tab && tab !== originalTab) {
                tab.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                tab.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                tab.click();
                await new Promise(r => setTimeout(r, 180));
                fillCurrentScreen();
            }
        }

        // Возвращаемся на исходную вкладку
        if (originalTab) {
            originalTab.click();
            await new Promise(r => setTimeout(r, 80));
        }

        if (fillBtn) fillBtn.innerText = "⚡";
    } catch (e) {
        console.error("Autofill error:", e);
        const fillBtn = document.getElementById('btn-fill-db');
        if (fillBtn) fillBtn.innerText = "⚡";
    }
};