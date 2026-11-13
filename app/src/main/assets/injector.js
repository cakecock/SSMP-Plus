(function() {
    if (!window.currentCardSnapshot) {
        window.currentCardSnapshot = {};
    }

    function isButtonActive(btn) {
        if (!btn) return false;
        const s = window.getComputedStyle(btn);
        const bg = s.backgroundColor;
        const color = s.color;
        const isTeal = (bg.includes("0, 121") || bg.includes("0, 105") || bg.includes("0, 137") || bg.includes("0, 77") || bg.includes("0, 150")) && !bg.includes("0, 0, 0, 0");
        const isWhiteOnColor = color.includes("255, 255, 255") && !bg.includes("rgba(0, 0, 0, 0)") && !bg.includes("255, 255, 255");
        return isTeal || isWhiteOnColor || btn.className.includes("active") || btn.getAttribute("aria-pressed") === "true";
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
        let row = target.closest('.row, [class*="row"], .q-field') || target.parentElement?.parentElement || target.parentElement;
        if (row) {
            const btns = Array.from(row.querySelectorAll('button, div[role="button"], [class*="btn"]'));
            for (let b of btns) {
                const t = b.innerText.trim().toUpperCase();
                if ((t === "ДА" || t === "НЕТ") && isButtonActive(b)) return t;
            }
        }
        return "";
    }

    function extractBPBySlash(isAdapted) {
        const all = Array.from(document.querySelectorAll('*'));
        const slashes = all.filter(el => el.children.length === 0 && el.textContent.trim() === '/');
        
        for (let slash of slashes) {
            let container = slash.closest('.row, [class*="row"], .q-field') || slash.parentElement?.parentElement || slash.parentElement;
            if (container) {
                const text = container.innerText.toLowerCase();
                const isMatch = isAdapted ? text.includes("адаптированное") : (text.includes("ад") && !text.includes("адаптированное") && !text.includes("после"));
                if (isMatch) {
                    const inputs = Array.from(container.querySelectorAll('input')).filter(i => /^\d{1,3}$/.test(i.value.trim()));
                    if (inputs.length >= 2) return `${inputs[0].value}/${inputs[1].value}`;
                }
            }
        }
        return "";
    }

    function findInputFuzzy(keywords) {
        const all = Array.from(document.querySelectorAll('label, div, span, p'));
        for (let kw of keywords) {
            const target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
            if (target) {
                let container = target.closest('.row, [class*="row"], .q-field, div');
                if (container) {
                    let input = container.querySelector('input, textarea');
                    if (input && input.value && !input.value.includes('[object')) return input.value.trim();
                    let next = target.nextElementSibling || container.nextElementSibling;
                    if (next) {
                        let nextInp = next.querySelector('input, textarea') || (next.tagName === 'INPUT' ? next : null);
                        if (nextInp && nextInp.value && !nextInp.value.includes('[object')) return nextInp.value.trim();
                    }
                }
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
        let container = target.closest('.row, [class*="row"], .q-field, div');
        if (!container) return "";

        let chips = Array.from(container.querySelectorAll('.q-chip, .v-chip, [class*="chip"], [class*="badge"], [class*="item"]'));
        if (chips.length > 0) {
            let chipTexts = chips.map(c => c.innerText.trim()).filter(t => t.length > 0 && !t.includes('[object') && t !== '✕' && t !== '×' && t !== 'close');
            if (chipTexts.length > 0) return chipTexts.join(', ');
        }

        let lines = container.innerText.split('\n')
            .map(l => l.trim())
            .filter(l => l.length > 0 && !keywords.some(k => l.toLowerCase().includes(k)) && l !== "Не выбрано" && !l.includes("[object") && !l.includes("▼") && !l.includes("keyboard_arrow") && !l.includes("arrow_drop_down"));
        if (lines.length > 0) return lines.join(', ');
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

        const fio = findInputFuzzy(["фио пациента", "фио"]);
        if (fio && fio.length >= 3) {
            if (snap.fio && snap.fio.toLowerCase() !== fio.toLowerCase()) window.currentCardSnapshot = {};
            window.currentCardSnapshot.fio = fio;
        }

        const bDate = findInputFuzzy(["дата рождения"]);
        if (bDate) snap.birthDate = bDate;
        const addr = findInputFuzzy(["место жительства (пребывания)", "адрес вызова", "адрес"]);
        if (addr) snap.address = addr;
        const reason = findInputFuzzy(["повод к вызову"]) || getDropdownOrChipsValue(["повод к вызову"]);
        if (reason) snap.callReason = reason;

        const gender = findActiveOption(["МУЖЧИНА", "ЖЕНЩИНА"]);
        if (gender) snap.gender = gender;

        const apo = getDropdownOrChipsValue(["территориальная апо"]);
        if (apo) snap.apo = apo;

        const complaints = findInputFuzzy(["жалобы"]);
        if (complaints) snap.complaints = complaints;
        const anamnesis = findInputFuzzy(["анамнез заболевания", "анамнез"]);
        if (anamnesis) snap.anamnesis = anamnesis;
        const past = findInputFuzzy(["перенесенные заболевания"]);
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

        const gScore = findInputFuzzy(["шкала комы глазго (баллы)", "глазго (баллы)"]);
        if (gScore) snap.glasgowScore = gScore;

        const bp = extractBPBySlash(false);
        if (bp) snap.bp = bp;
        const bpAdapt = extractBPBySlash(true);
        if (bpAdapt) snap.bpAdapted = bpAdapt;

        const pulse = findInputFuzzy(["комментарий (пульс", "пульс, ударов в минуту", "пульс"]);
        if (pulse) snap.pulse = pulse;
        const rr = findInputFuzzy(["частота дыхания (в минуту)", "частота дыхания", "чдд"]);
        if (rr) snap.rr = rr;
        const temp = findInputFuzzy(["температура тела", "температура"]);
        if (temp) snap.temp = temp;

        const sat = findInputFuzzy(["сатурация 1", "сатурация", "spo2"]);
        if (sat) snap.saturation = sat;
        const gluc = findInputFuzzy(["гликемия 1", "гликемия", "сахар"]);
        if (gluc) snap.glucose = gluc;

        const heart = getDropdownOrChipsValue(["тоны сердца"]);
        if (heart) snap.heartSounds = heart;
        const lungs = getDropdownOrChipsValue(["дыхание"]);
        if (lungs) snap.lungs = lungs;
        const percussion = getDropdownOrChipsValue(["перкуссия легких", "перкуссия"]);
        if (percussion) snap.percussion = percussion;
        const abdomen = getDropdownOrChipsValue(["живот"]);
        if (abdomen) snap.abdomen = abdomen;
        const localStatus = findInputFuzzy(["локальный статус"]);
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
        const tongue = findInputFuzzy(["язык"]);
        if (tongue) snap.tongue = tongue;
        const mucous = findInputFuzzy(["слизистые"]);
        if (mucous) snap.mucous = mucous;

        const peristalsis = getYesNoToggle(["перистальтика"]);
        if (peristalsis) snap.peristalsis = peristalsis;
        const liver = findActiveOption(["УВЕЛИЧЕНА", "НЕ УВЕЛИЧЕНА"]);
        if (liver) snap.liver = liver;
        const urination = findActiveOption(["НЕ НАРУШЕНО", "УЧАЩЕНО", "БОЛЕЗНЕННОЕ", "ЗАДЕРЖКА МОЧЕИСПУСКАНИЯ"]);
        if (urination) snap.urination = urination;

        const ecgBefore = findInputFuzzy(["экг до оказания", "экг до"]);
        if (ecgBefore) snap.ecgBefore = ecgBefore;
        const ecgAfter = findInputFuzzy(["экг после оказания", "экг после"]);
        if (ecgAfter) snap.ecgAfter = ecgAfter;

        const diag = findInputFuzzy(["предварительный диагноз", "диагноз"]);
        if (diag) snap.diagnosis = diag;
        const mkb = findInputFuzzy(["код по мкб"]) || getDropdownOrChipsValue(["код по мкб"]);
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

    // Вспомогательная функция клика по верхней вкладке
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
            <button id="btn-best-db" title="Образцы карт" style="background:linear-gradient(135deg, #00897B, #F57F17);color:white;${roundStyle}">
                ⭐
            </button>
            <button id="btn-mkb-db" title="Справочник МКБ-10" style="background:linear-gradient(135deg, #00796B, #0288D1);color:white;${roundStyle}">
                📖
            </button>
            <button id="btn-search-db" title="Общий архив" style="background:linear-gradient(135deg, #00695C, #004D40);color:white;${roundStyle}">
                📂
            </button>
            <button id="btn-save-db" title="Запомнить карту" style="background:linear-gradient(135deg, #00796B, #004D40);color:white;${roundStyle}">
                💾
            </button>
            <button id="btn-fill-db" title="Автозаполнение" style="background:linear-gradient(135deg, #0288D1, #01579B);color:white;${roundStyle}">
                ⚡
            </button>
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
                alert("Укажите ФИО пациента");
            }
        };

        document.getElementById('btn-fill-db').onclick = function() {
            const fio = findInputFuzzy(["фио пациента", "фио"]);
            if (window.AndroidBridge && fio) {
                AndroidBridge.requestAutofill(fio);
            } else {
                alert("Сначала укажите ФИО пациента");
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

        if (inCard) {
            scanAndMergeVisibleFields();
        }
    }

    if (window.b103Observer) window.b103Observer.disconnect();
    window.b103Observer = new MutationObserver(() => updateVisibility());
    window.b103Observer.observe(document.body, { childList: true, subtree: true });

    setInterval(updateVisibility, 300);
})();

// ==========================================
// ИНТЕЛЛЕКТУАЛЬНОЕ АВТОЗАПОЛНЕНИЕ (АВТОПИЛОТ)
// ==========================================
window.applyAutofill = async function(jsonStr) {
    try {
        const data = JSON.parse(jsonStr);
        const fillBtn = document.getElementById('btn-fill-db');
        if (fillBtn) fillBtn.innerText = "⏳";

        // 1. Нативный установщик значений для виртуального DOM React
        function setReactValue(input, value) {
            if (!input || value === undefined || value === null || value === "") return;
            try {
                const proto = input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) {
                    setter.call(input, value);
                } else {
                    input.value = value;
                }
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
            } catch (e) {
                input.value = value;
            }
        }

        function findInputForFill(keywords) {
            const all = Array.from(document.querySelectorAll('label, div, span, p'));
            for (let kw of keywords) {
                const target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
                if (target) {
                    let container = target.closest('.row, [class*="row"], .q-field, div');
                    let inp = container?.querySelector('input, textarea');
                    if (inp) return inp;
                    let next = target.nextElementSibling || container?.nextElementSibling;
                    let nextInp = next?.querySelector('input, textarea') || (next?.tagName === 'INPUT' ? next : null);
                    if (nextInp) return nextInp;
                }
            }
            return null;
        }

        // 2. Авто-клик по кнопкам-опциям (Пол, Положение, Зрачки, Исход)
        function clickOption(targetText) {
            if (!targetText) return;
            const buttons = Array.from(document.querySelectorAll('button, div[role="button"], [class*="btn"], span, a'));
            const btn = buttons.find(b => b.children.length === 0 && b.innerText.trim().toUpperCase() === targetText.toUpperCase());
            if (btn) {
                const clickable = btn.closest('button, div[role="button"], [class*="btn"]') || btn;
                const style = window.getComputedStyle(clickable);
                const isAlreadyActive = style.backgroundColor.includes("0, 121") || style.backgroundColor.includes("0, 105") || clickable.className.includes("active");
                if (!isAlreadyActive) {
                    clickable.click();
                }
            }
        }

        // 3. Авто-клик переключателей ДА / НЕТ
        function clickYesNo(labelKeywords, choice) {
            if (!choice) return;
            const all = Array.from(document.querySelectorAll('label, div, span, p'));
            let target = null;
            for (let kw of labelKeywords) {
                target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
                if (target) break;
            }
            if (!target) return;
            let row = target.closest('.row, [class*="row"], .q-field') || target.parentElement?.parentElement || target.parentElement;
            if (row) {
                const btns = Array.from(row.querySelectorAll('button, div[role="button"], [class*="btn"]'));
                for (let b of btns) {
                    if (b.innerText.trim().toUpperCase() === choice.toUpperCase()) {
                        const style = window.getComputedStyle(b);
                        const isAlreadyActive = style.backgroundColor.includes("0, 121") || style.backgroundColor.includes("0, 105") || b.className.includes("active");
                        if (!isAlreadyActive) b.click();
                    }
                }
            }
        }

        // 4. Заполнение двойного АД вокруг косой черты "/"
        function fillDualBP(isAdapted, bpStr) {
            if (!bpStr || !bpStr.includes('/')) return;
            const parts = bpStr.split('/');
            const all = Array.from(document.querySelectorAll('*'));
            const slashes = all.filter(el => el.children.length === 0 && el.textContent.trim() === '/');
            for (let slash of slashes) {
                let container = slash.closest('.row, [class*="row"], .q-field') || slash.parentElement?.parentElement || slash.parentElement;
                if (container) {
                    const text = container.innerText.toLowerCase();
                    const isMatch = isAdapted ? text.includes("адаптированное") : (text.includes("ад") && !text.includes("адаптированное") && !text.includes("после"));
                    if (isMatch) {
                        const inputs = Array.from(container.querySelectorAll('input')).filter(i => !i.type.includes('hidden'));
                        if (inputs.length >= 2) {
                            setReactValue(inputs[0], parts[0].trim());
                            setReactValue(inputs[1], parts[1].trim());
                            return;
                        }
                    }
                }
            }
        }

        // Заполнение полей открытого в данный момент экрана
        function fillCurrentScreen() {
            // Текстовые поля и анамнез
            setReactValue(findInputForFill(["жалобы"]), data.complaints);
            setReactValue(findInputForFill(["анамнез заболевания", "анамнез"]), data.anamnesis);
            setReactValue(findInputForFill(["перенесенные заболевания"]), data.pastDiseases);
            setReactValue(findInputForFill(["предварительный диагноз", "диагноз"]), data.diagnosis);
            setReactValue(findInputForFill(["код по мкб"]), data.mkb);
            setReactValue(findInputForFill(["локальный статус"]), data.localStatus);

            // АД
            if (data.bpAdapted) fillDualBP(true, data.bpAdapted);
            if (data.bp) fillDualBP(false, data.bp);

            // ЭКГ
            setReactValue(findInputForFill(["экг до оказания", "экг до"]), data.ecgBefore);
            setReactValue(findInputForFill(["экг после оказания", "экг после"]), data.ecgAfter);

            // Стандартный неврологический статус по умолчанию
            setReactValue(findInputForFill(["шкала комы глазго (баллы)", "глазго (баллы)"]), data.glasgowScore || "15");
            setReactValue(findInputForFill(["тонус мышц"]), "Норма");
            setReactValue(findInputForFill(["патологические рефлексы"]), "Нет");
            setReactValue(findInputForFill(["менингиальные знаки"]), "Нет");
            setReactValue(findInputForFill(["плегии, парезы", "плегии"]), "Нет");
            setReactValue(findInputForFill(["язык"]), data.tongue || "Влажный, не обложен");
            setReactValue(findInputForFill(["слизистые"]), data.mucous || "Чистые");

            // Нажатие кнопок-переключателей
            if (data.gender) clickOption(data.gender);
            clickOption(data.position || "АКТИВНОЕ");
            clickOption(data.behavior || "СПОКОЙНОЕ");
            clickOption(data.pupils || "МИОЗ");
            clickOption(data.speech || "СОХРАНЕНА");
            clickOption(data.face || "СИММЕТРИЧНОЕ");
            clickOption(data.gait || "СВОБОДНАЯ");
            clickOption(data.nystagmus || "НЕТ");
            clickOption(data.liver || "НЕ УВЕЛИЧЕНА");
            clickOption(data.urination || "НЕ НАРУШЕНО");

            // Исход и помощь
            clickOption(data.attack || "КУПИРОВАН");
            clickOption(data.therapyEffect || "УЛУЧШЕНИЕ");
            clickOption(data.callResult || "ОСТАВЛЕН НА МЕСТЕ");

            // Да / Нет переключатели
            clickYesNo(["аллергия"], data.allergy || "НЕТ");
            clickYesNo(["реакция на свет"], data.lightReaction || "ДА");
            clickYesNo(["высыпания"], "НЕТ");
            clickYesNo(["перистальтика"], data.peristalsis || "ДА");
            clickYesNo(["симптомы раздражения брюшины"], "НЕТ");
            clickYesNo(["асцит"], "НЕТ");
            clickYesNo(["периферические отеки", "отеки"], "НЕТ");
            clickYesNo(["запах алкоголя"], "НЕТ");
            clickYesNo(["запах ацетона"], "НЕТ");
        }

        // ЭКСПРЕСС-ПРОХОД ПО ВСЕМ ВКЛАДКАМ (заполняет ВСЮ карту целиком!)
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

        for (let kw of tabKeywords) {
            let tab = getTab(kw);
            if (tab) {
                tab.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                tab.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                tab.click();
                await new Promise(r => setTimeout(r, 200));
                fillCurrentScreen();
            }
        }

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