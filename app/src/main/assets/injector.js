(function() {
    if (!window.currentCardSnapshot) {
        window.currentCardSnapshot = {};
    }

    // Проверка активности бирюзовой кнопки
    function isButtonActive(btn) {
        if (!btn) return false;
        const s = window.getComputedStyle(btn);
        const bg = s.backgroundColor;
        const color = s.color;
        const isTeal = (bg.includes("0, 121") || bg.includes("0, 105") || bg.includes("0, 137") || bg.includes("0, 77") || bg.includes("0, 150")) && !bg.includes("0, 0, 0, 0");
        const isWhiteOnColor = color.includes("255, 255, 255") && !bg.includes("rgba(0, 0, 0, 0)") && !bg.includes("255, 255, 255");
        return isTeal || isWhiteOnColor || btn.className.includes("active") || btn.getAttribute("aria-pressed") === "true";
    }

    // Поиск активной кнопки по списку её уникальных вариантов
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

    // Поиск переключателей ДА / НЕТ строго рядом с нужным полем
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

    // Жесткий сбор АД: только 2-3-значные цифры (никаких дат, адресов и фамилий!)
    function extractBPStrict(isAdapted) {
        const labels = Array.from(document.querySelectorAll('label, div, span, p'));
        const target = labels.find(el => {
            const t = el.textContent.trim().toLowerCase();
            if (isAdapted) {
                return t.includes("адаптированное ад");
            } else {
                return (t.includes("ад (мм.рт.ст.)") || t === "ад") && !t.includes("адаптированное") && !t.includes("после");
            }
        });
        if (!target) return "";
        let container = target.closest('.row, [class*="row"], .q-field') || target.parentElement?.parentElement || target.parentElement;
        if (container) {
            const inputs = Array.from(container.querySelectorAll('input')).filter(i => {
                const v = i.value.trim();
                return /^\d{2,3}$/.test(v); // Только числа от 40 до 280
            });
            if (inputs.length >= 2) return `${inputs[0].value}/${inputs[1].value}`;
            if (inputs.length === 1) return inputs[0].value;
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

    // Точный сбор карточек медикаментов (название + дозировка + путь)
    function extractMedicationsCards() {
        const all = Array.from(document.querySelectorAll('*'));
        const qtyElements = all.filter(el => el.children.length === 0 && el.textContent.trim().startsWith("Количество:"));
        if (qtyElements.length === 0) return "";

        const items = [];
        for (let qEl of qtyElements) {
            let card = qEl.closest('.q-card, [class*="card"], div');
            if (card) {
                const textLines = card.innerText.split('\n').map(s => s.trim()).filter(Boolean);
                const drugName = textLines[0] || "";
                const qtyLine = textLines.find(l => l.startsWith("Количество:")) || "";
                const routeLine = textLines.find(l => l.startsWith("Путь введения:")) || "";
                const route = routeLine.replace("Путь введения:", "").trim();
                const routeShort = (route && !route.includes("не определен")) ? ` (${route})` : "";

                if (drugName && qtyLine && !drugName.startsWith("Количество") && !drugName.includes("ДОБАВИТЬ")) {
                    const qty = qtyLine.replace("Количество:", "").trim();
                    items.push(`${drugName} — ${qty}${routeShort}`);
                }
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

        // Вкладка "О вызове"
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

        // Вкладка "Уточненные сведения"
        const complaints = findInputFuzzy(["жалобы"]);
        if (complaints) snap.complaints = complaints;
        const anamnesis = findInputFuzzy(["анамнез заболевания", "анамнез"]);
        if (anamnesis) snap.anamnesis = anamnesis;
        const past = findInputFuzzy(["перенесенные заболевания"]);
        if (past) snap.pastDiseases = past;

        const allergy = getYesNoToggle(["аллергия"]);
        if (allergy) snap.allergy = allergy;

        // Вкладка "Объективные данные"
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

        // Строгое АД
        const bp = extractBPStrict(false);
        if (bp) snap.bp = bp;
        const bpAdapt = extractBPStrict(true);
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

        // Органы
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

        // Неврология и осмотр
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

        // ЭКГ
        const ecgBefore = findInputFuzzy(["экг до оказания", "экг до"]);
        if (ecgBefore) snap.ecgBefore = ecgBefore;
        const ecgAfter = findInputFuzzy(["экг после оказания", "экг после"]);
        if (ecgAfter) snap.ecgAfter = ecgAfter;

        const diag = findInputFuzzy(["предварительный диагноз", "диагноз"]);
        if (diag) snap.diagnosis = diag;
        const mkb = findInputFuzzy(["код по мкб"]) || getDropdownOrChipsValue(["код по мкб"]);
        if (mkb) snap.mkb = mkb;

        // Вкладка "Медицинская помощь"
        const meds = extractMedicationsCards();
        if (meds) snap.medications = meds;

        const attack = findActiveOption(["КУПИРОВАН", "НЕ КУПИРОВАН"]);
        if (attack) snap.attack = attack;
        const effect = findActiveOption(["УЛУЧШЕНИЕ", "БЕЗ ЭФФЕКТА", "УХУДШЕНИЕ"]);
        if (effect) snap.therapyEffect = effect;
        const callResult = findActiveOption(["ОСТАВЛЕН НА МЕСТЕ", "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ", "ПЕРЕДАН БРИГАДЕ СМП"]);
        if (callResult) snap.callResult = callResult;
    }

    // Надежный обход всех вкладок (клик по плашкам в самом верху экрана)
    async function scanAllTabsCompletely() {
        const tabKeywords = ['О ВЫЗОВЕ', 'УТОЧНЕННЫЕ', 'ОБЪЕКТИВНЫЕ', 'МЕДИЦИНСКАЯ'];
        const all = Array.from(document.querySelectorAll('button, div[role="tab"], [class*="tab"], a, div'));

        function getTab(kw) {
            return all.find(el => {
                const rect = el.getBoundingClientRect();
                const t = el.innerText ? el.innerText.toUpperCase() : '';
                return rect.top >= 0 && rect.top < 140 && t.includes(kw);
            });
        }

        let originalTab = getTab('ОБЪЕКТИВНЫЕ') || getTab('О ВЫЗОВЕ');

        for (let kw of tabKeywords) {
            let tab = getTab(kw);
            if (tab) {
                tab.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                tab.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                tab.click();
                await new Promise(res => setTimeout(res, 200)); // 200мс для гарантированного рендера React
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
        bar.style.gap = '8px';

        const btnStyle = `
            border:none;
            padding:11px 16px;
            border-radius:24px;
            box-shadow:0 5px 15px rgba(0,0,0,0.25);
            font-size:12px;
            font-weight:700;
            cursor:pointer;
            font-family:system-ui, -apple-system, Roboto, sans-serif;
            text-align:right;
        `;

        bar.innerHTML = `
            <button id="btn-best-db" style="display:none;background:linear-gradient(135deg, #00897B, #F57F17);color:white;${btnStyle}">
                ⭐ Образцы карт
            </button>
            <button id="btn-search-db" style="display:none;background:linear-gradient(135deg, #00695C, #004D40);color:white;${btnStyle}">
                📂 Общий архив
            </button>
            <button id="btn-save-db" style="display:none;background:linear-gradient(135deg, #00796B, #004D40);color:white;${btnStyle}">
                💾 Запомнить карту
            </button>
            <button id="btn-fill-db" style="display:none;background:linear-gradient(135deg, #0288D1, #01579B);color:white;${btnStyle}">
                ⚡ Автозаполнение
            </button>
        `;
        document.body.appendChild(bar);

        document.getElementById('btn-best-db').onclick = function() {
            if (window.AndroidBridge) AndroidBridge.openBestCardsDialog();
        };

        document.getElementById('btn-search-db').onclick = function() {
            if (window.AndroidBridge) AndroidBridge.openSearchDialog();
        };

        document.getElementById('btn-save-db').onclick = async function() {
            const btn = document.getElementById('btn-save-db');
            btn.innerText = "⏳ Сбор данных...";
            await scanAllTabsCompletely();
            btn.innerText = "💾 Запомнить карту";

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

    function updateVisibility() {
        const inCard = isInsideCard();
        const btnBest = document.getElementById('btn-best-db');
        const btnSearch = document.getElementById('btn-search-db');
        const btnSave = document.getElementById('btn-save-db');
        const btnFill = document.getElementById('btn-fill-db');

        if (btnBest && btnSearch && btnSave && btnFill) {
            if (inCard) {
                btnBest.style.display = 'none';
                btnSearch.style.display = 'none';
                btnSave.style.display = 'block';
                btnFill.style.display = 'block';
                scanAndMergeVisibleFields();
            } else {
                btnBest.style.display = 'block';
                btnSearch.style.display = 'block';
                btnSave.style.display = 'none';
                btnFill.style.display = 'none';
            }
        }
    }

    if (!window.b103VisibilityTimer) {
        window.b103VisibilityTimer = setInterval(updateVisibility, 500);
    }
})();

window.applyAutofill = function(jsonStr) {
    try {
        const data = JSON.parse(jsonStr);
        function setValFuzzy(keywords, value) {
            if (!value) return;
            const all = Array.from(document.querySelectorAll('label, div, span, p'));
            for (let kw of keywords) {
                const target = all.find(el => el.textContent.trim().toLowerCase().includes(kw));
                if (target) {
                    let container = target.closest('div');
                    let input = container?.querySelector('input, textarea');
                    if (input) {
                        input.value = value;
                        input.dispatchEvent(new Event('input', { bubbles: true }));
                        input.dispatchEvent(new Event('change', { bubbles: true }));
                        break;
                    }
                }
            }
        }

        setValFuzzy(["анамнез заболевания", "анамнез"], data.anamnesis);
        setValFuzzy(["жалобы"], data.complaints);
        setValFuzzy(["адаптированное ад"], data.bpAdapted);
        setValFuzzy(["перенесенные заболевания"], data.pastDiseases);
        setValFuzzy(["предварительный диагноз", "диагноз"], data.diagnosis);
        setValFuzzy(["код по мкб"], data.mkb);
        setValFuzzy(["локальный статус"], data.localStatus);
    } catch (e) {
        console.error("Autofill error:", e);
    }
};