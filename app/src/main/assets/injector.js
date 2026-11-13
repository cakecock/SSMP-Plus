(function() {
    if (!window.currentCardSnapshot) {
        window.currentCardSnapshot = {};
    }

    // Нечеткий поиск поля по ключевым словам (спасет от опечаток и пробелов)
    function findInputFuzzy(keywords) {
        const all = Array.from(document.querySelectorAll('label, div, span, p'));
        for (let kw of keywords) {
            const target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
            if (target) {
                let container = target.closest('div');
                if (container) {
                    let input = container.querySelector('input, textarea');
                    if (input && input.value && !input.value.includes('[object')) {
                        return input.value.trim();
                    }
                    let next = target.nextElementSibling || container.nextElementSibling;
                    if (next) {
                        let nextInp = next.querySelector('input, textarea') || (next.tagName === 'INPUT' ? next : null);
                        if (nextInp && nextInp.value && !nextInp.value.includes('[object')) {
                            return nextInp.value.trim();
                        }
                    }
                }
            }
        }
        return "";
    }

    // Извлечение выпадающих списков и мультиселектов
    function getDropdownOrChipsValue(keywords) {
        const all = Array.from(document.querySelectorAll('label, div, span, p'));
        let target = null;
        for (let kw of keywords) {
            target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
            if (target) break;
        }
        if (!target) return "";

        let container = target.closest('div');
        if (!container) return "";

        let chips = Array.from(container.querySelectorAll('.q-chip, .v-chip, [class*="chip"], [class*="badge"], [class*="item"]'));
        if (chips.length > 0) {
            let chipTexts = chips
                .map(c => c.innerText.trim())
                .filter(t => t.length > 0 && !t.includes('[object') && t !== '✕' && t !== '×' && t !== 'close');
            if (chipTexts.length > 0) {
                return chipTexts.join(', ');
            }
        }

        let lines = container.innerText.split('\n')
            .map(l => l.trim())
            .filter(l => l.length > 0 && 
                         !keywords.some(k => l.toLowerCase().includes(k)) && 
                         l !== "Не выбрано" && 
                         !l.includes("[object") &&
                         !l.includes("▼") &&
                         !l.includes("keyboard_arrow") &&
                         !l.includes("arrow_drop_down"));

        if (lines.length > 0) {
            return lines.join(', ');
        }
        return "";
    }

    function getDualInputValue(keywords) {
        const all = Array.from(document.querySelectorAll('label, div, span, p'));
        let target = null;
        for (let kw of keywords) {
            target = all.find(el => el.textContent.trim().toLowerCase().includes(kw));
            if (target) break;
        }
        if (!target) return "";

        let container = target.closest('div');
        if (container) {
            let inputs = Array.from(container.querySelectorAll('input')).filter(i => i.type !== 'hidden');
            if (inputs.length >= 2 && (inputs[0].value || inputs[1].value)) {
                return `${inputs[0].value || '_'}/${inputs[1].value || '_'}`;
            } else if (inputs.length === 1 && inputs[0].value) {
                return inputs[0].value;
            }
        }
        return "";
    }

    // Умный поиск активных бирюзовых кнопок переключателей
    function getActiveButtonValue(keywords) {
        const all = Array.from(document.querySelectorAll('label, div, span, p'));
        let target = null;
        for (let kw of keywords) {
            target = all.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase().includes(kw));
            if (target) break;
        }
        if (!target) return "";

        // Ищем строку с кнопками рядом с заголовком
        let row = target.closest('.row, [class*="row"], .q-field') || target.parentElement?.parentElement || target.parentElement;
        if (row) {
            let buttons = Array.from(row.querySelectorAll('button, div[role="button"], [class*="btn"]'));
            for (let btn of buttons) {
                let style = window.getComputedStyle(btn);
                let bg = style.backgroundColor;
                let color = style.color;
                // Активная кнопка: бирюзовый фон (#00796B / #00897B) или белый текст на заливке
                if (bg.includes("0, 121") || bg.includes("0, 105") || bg.includes("0, 137") || btn.className.includes("active") || (color.includes("255, 255, 255") && !bg.includes("0, 0, 0, 0"))) {
                    return btn.innerText.trim();
                }
            }
        }
        return "";
    }

    // Точный сбор медикаментов по единицам измерения
    function extractMedicationsList() {
        let rows = Array.from(document.querySelectorAll('tr, .q-table tbody tr, [class*="row"], [class*="item"]'));
        let medItems = [];
        for (let r of rows) {
            let text = r.innerText.trim();
            if (text && !text.includes("Наименование") && !text.includes("ДОБАВИТЬ") && !text.includes("О ВЫЗОВЕ") && !text.includes("Состояние пациента")) {
                let cleaned = text.split('\n').map(s => s.trim()).filter(Boolean).join(' ');
                // Проверяем наличие дозировок и единиц (шт, мл, амп, пар, мг, г, таб)
                if (cleaned.match(/\d+\s*(шт|мл|пар|амп|таб|г|мг|доз|фл)/i)) {
                    medItems.push(cleaned);
                }
            }
        }
        if (medItems.length > 0) {
            return Array.from(new Set(medItems)).slice(0, 15).join("; ");
        }
        return "";
    }

    function isInsideCard() {
        const text = document.body ? document.body.innerText : '';
        const hasCardTabs = text.includes('О ВЫЗОВЕ') && text.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ');
        const hasCardDetails = text.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ') || text.includes('МЕДИЦИНСКАЯ ПОМОЩЬ');
        return hasCardTabs || hasCardDetails;
    }

    function scanAndMergeVisibleFields() {
        if (!isInsideCard()) return;
        const snap = window.currentCardSnapshot;

        // Вкладка "О вызове"
        const fio = findInputFuzzy(["фио пациента", "фио"]);
        if (fio && fio.length >= 3) {
            if (snap.fio && snap.fio.toLowerCase() !== fio.toLowerCase()) {
                window.currentCardSnapshot = {};
            }
            window.currentCardSnapshot.fio = fio;
        }

        const bDate = findInputFuzzy(["дата рождения"]);
        if (bDate) snap.birthDate = bDate;
        const addr = findInputFuzzy(["адрес вызова", "адрес"]);
        if (addr) snap.address = addr;
        const reason = findInputFuzzy(["повод к вызову"]) || getDropdownOrChipsValue(["повод к вызову"]);
        if (reason) snap.callReason = reason;

        const gender = getActiveButtonValue(["пол"]);
        if (gender) snap.gender = gender;

        // Вкладка "Уточненные сведения"
        const complaints = findInputFuzzy(["жалобы"]);
        if (complaints) snap.complaints = complaints;
        const anamnesis = findInputFuzzy(["анамнез заболевания", "анамнез"]);
        if (anamnesis) snap.anamnesis = anamnesis;
        const past = findInputFuzzy(["перенесенные заболевания"]);
        if (past) snap.pastDiseases = past;

        const allergy = getActiveButtonValue(["аллергия"]);
        if (allergy) snap.allergy = allergy;

        // Вкладка "Объективные данные"
        const genState = getDropdownOrChipsValue(["общее состояние"]);
        if (genState) snap.generalState = genState;
        const consciousness = getDropdownOrChipsValue(["сознание"]);
        if (consciousness) snap.consciousness = consciousness;
        const position = getActiveButtonValue(["положение"]);
        if (position) snap.position = position;

        const gScore = findInputFuzzy(["шкала комы глазго (баллы)", "глазго (баллы)"]);
        if (gScore) snap.glasgowScore = gScore;
        const gTest = findInputFuzzy(["шкала комы глазго (тест)", "глазго (тест)"]);
        if (gTest) snap.glasgowTest = gTest;

        const bp = getDualInputValue(["ад (мм.рт.ст.)"]) || findInputFuzzy(["ад (мм.рт.ст.)"]);
        if (bp) snap.bp = bp;
        const bpAdapt = getDualInputValue(["адаптированное ад"]) || findInputFuzzy(["адаптированное ад"]);
        if (bpAdapt) snap.bpAdapted = bpAdapt;
        const pulse = findInputFuzzy(["пульс, ударов в минуту", "пульс"]);
        if (pulse) snap.pulse = pulse;
        const rr = findInputFuzzy(["частота дыхания (в минуту)", "частота дыхания", "чдд"]);
        if (rr) snap.rr = rr;
        const temp = findInputFuzzy(["температура тела", "температура"]);
        if (temp) snap.temp = temp;

        // Сатурация и Сахар (Fuzzy)
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

        const diag = findInputFuzzy(["предварительный диагноз", "диагноз"]);
        if (diag) snap.diagnosis = diag;
        const mkb = findInputFuzzy(["код по мкб"]) || getDropdownOrChipsValue(["код по мкб"]);
        if (mkb) snap.mkb = mkb;

        // Вкладка "Медицинская помощь"
        const medHelp = getDropdownOrChipsValue(["медицинская помощь, оказанная", "медицинская помощь"]);
        if (medHelp) snap.medHelp = medHelp;

        const meds = extractMedicationsList();
        if (meds) snap.medications = meds;

        const attack = getActiveButtonValue(["приступ"]);
        if (attack) snap.attack = attack;
        const effect = getActiveButtonValue(["результат оказания"]);
        if (effect) snap.therapyEffect = effect;
        const callResult = getActiveButtonValue(["результат выезда"]);
        if (callResult) snap.callResult = callResult;
    }

    async function scanAllTabsCompletely() {
        const tabNames = ['О ВЫЗОВЕ', 'УТОЧНЕННЫЕ СВЕДЕНИЯ', 'ОБЪЕКТИВНЫЕ ДАННЫЕ', 'МЕДИЦИНСКАЯ ПОМОЩЬ'];
        const allElements = Array.from(document.querySelectorAll('button, div[role="tab"], div, a, span'));

        function findTabBtn(name) {
            return allElements.find(el => el.innerText && el.innerText.trim() === name && el.children.length === 0);
        }

        let activeTab = null;
        for (let name of tabNames) {
            let el = findTabBtn(name);
            let target = el ? (el.closest('button, div[role="tab"]') || el) : null;
            if (target) {
                let style = window.getComputedStyle(target);
                if (target.classList.contains('active') || style.backgroundColor.includes("0, 121") || style.backgroundColor.includes("0, 105")) {
                    activeTab = target;
                }
            }
        }

        for (let name of tabNames) {
            let el = findTabBtn(name);
            let target = el ? (el.closest('button, div[role="tab"]') || el) : null;
            if (target) {
                target.click();
                await new Promise(res => setTimeout(res, 180)); // 180мс для железного монтирования всех подтаблиц
                scanAndMergeVisibleFields();
            }
        }

        if (activeTab) {
            activeTab.click();
            await new Promise(res => setTimeout(res, 80));
        }
    }

    // Панель кнопок
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

        bar.innerHTML = `
            <button id="btn-search-db" style="display:none;background:#00695C;color:white;border:none;padding:10px 14px;border-radius:24px;box-shadow:0 3px 6px rgba(0,0,0,0.3);font-size:12px;font-weight:bold;cursor:pointer;">
                🔍 Поиск в архиве
            </button>
            <button id="btn-save-db" style="display:none;background:#00796B;color:white;border:none;padding:10px 14px;border-radius:24px;box-shadow:0 3px 6px rgba(0,0,0,0.3);font-size:12px;font-weight:bold;cursor:pointer;">
                💾 Запомнить карту
            </button>
            <button id="btn-fill-db" style="display:none;background:#0288D1;color:white;border:none;padding:10px 14px;border-radius:24px;box-shadow:0 3px 6px rgba(0,0,0,0.3);font-size:12px;font-weight:bold;cursor:pointer;">
                ⚡ Автозаполнение
            </button>
        `;
        document.body.appendChild(bar);

        document.getElementById('btn-search-db').onclick = function() {
            if (window.AndroidBridge) {
                AndroidBridge.openSearchDialog();
            }
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
        const btnSearch = document.getElementById('btn-search-db');
        const btnSave = document.getElementById('btn-save-db');
        const btnFill = document.getElementById('btn-fill-db');

        if (btnSearch && btnSave && btnFill) {
            if (inCard) {
                btnSearch.style.display = 'none';
                btnSave.style.display = 'block';
                btnFill.style.display = 'block';
                scanAndMergeVisibleFields();
            } else {
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

// Автозаполнение
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
