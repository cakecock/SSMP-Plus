(function() {
    if (!window.currentCardSnapshot) {
        window.currentCardSnapshot = {};
    }

    function findInputByLabel(text) {
        const labels = Array.from(document.querySelectorAll('label, div, span, p'));
        const target = labels.find(el => el.textContent.trim().toLowerCase() === text.toLowerCase());
        if (!target) return null;

        let container = target.closest('div');
        if (container) {
            let input = container.querySelector('input, textarea, select');
            if (input && input.value && !input.value.includes('[object')) return input;
        }
        return null;
    }

    // Извлечение выпадающих списков и мультиселектов (Тоны, Дыхание, Живот)
    function getDropdownOrChipsValue(labelText) {
        const labels = Array.from(document.querySelectorAll('label, div, span, p'));
        const target = labels.find(el => el.children.length === 0 && el.textContent.trim().toLowerCase() === labelText.toLowerCase())
                      || labels.find(el => el.textContent.trim().toLowerCase() === labelText.toLowerCase());
        if (!target) return "";

        let container = target.closest('div');
        if (!container) return "";

        // 1. Ищем выбранные чипсы/плашки внутри мультиселекта
        let chips = Array.from(container.querySelectorAll('.q-chip, .v-chip, [class*="chip"], [class*="badge"], [class*="item"]'));
        if (chips.length > 0) {
            let chipTexts = chips
                .map(c => c.innerText.trim())
                .filter(t => t.length > 0 && !t.includes('[object') && t !== '✕' && t !== '×' && t !== 'close');
            if (chipTexts.length > 0) {
                return chipTexts.join(', ');
            }
        }

        // 2. Ищем видимый текст, исключая мусор [object Object]
        let lines = container.innerText.split('\n')
            .map(l => l.trim())
            .filter(l => l.length > 0 && 
                         l.toLowerCase() !== labelText.toLowerCase() && 
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

    function getDualInputValue(labelText) {
        const labels = Array.from(document.querySelectorAll('label, div, span, p'));
        const target = labels.find(el => el.textContent.trim().toLowerCase() === labelText.toLowerCase());
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

    function getActiveButtonValue(groupLabel) {
        const labels = Array.from(document.querySelectorAll('label, div, span, p'));
        const target = labels.find(el => el.textContent.trim().toLowerCase() === groupLabel.toLowerCase());
        if (!target) return "";

        let parent = target.parentElement;
        if (parent) {
            let buttons = Array.from(parent.querySelectorAll('button, div[role="button"], span'));
            for (let btn of buttons) {
                let style = window.getComputedStyle(btn);
                if (style.backgroundColor.includes("0, 121") || style.backgroundColor.includes("0, 105") || btn.className.includes("active")) {
                    return btn.innerText.trim();
                }
            }
        }
        return "";
    }

    // Точный сбор таблицы лекарственных препаратов (без меню сайта)
    function extractMedicationsList() {
        const all = Array.from(document.querySelectorAll('*'));
        const header = all.find(el => {
            const t = el.textContent.trim();
            return (t === "Лекарственные средства" || t === "Лекарственные средства:") && el.children.length === 0;
        });

        if (!header) return "";

        let section = header.parentElement;
        for (let i = 0; i < 4 && section && section !== document.body; i++) {
            const table = section.querySelector('table, tbody, .q-table, [class*="table"], [class*="list"]');
            if (table) {
                const rows = Array.from(table.querySelectorAll('tr, .q-tr, [class*="row"]'));
                const items = [];
                for (let row of rows) {
                    const rowText = row.innerText.trim();
                    if (rowText && !rowText.includes("Наименование") && !rowText.includes("Количество") && !rowText.includes("ДОБАВИТЬ") && !rowText.includes("О ВЫЗОВЕ")) {
                        const cleaned = rowText.split('\n').map(s => s.trim()).filter(Boolean).join(' ');
                        if (cleaned.length > 2) items.push(cleaned);
                    }
                }
                if (items.length > 0) return items.join("; ");
            }

            if (section.nextElementSibling) {
                const siblingText = section.nextElementSibling.innerText.trim();
                if (siblingText && !siblingText.includes("О ВЫЗОВЕ") && !siblingText.includes("ЗАКЛЮЧЕНИЕ")) {
                    const lines = siblingText.split('\n')
                        .map(s => s.trim())
                        .filter(s => s.length > 2 && !s.includes("ДОБАВИТЬ") && !s.includes("УДАЛИТЬ"));
                    if (lines.length > 0) return lines.join("; ");
                }
            }
            section = section.parentElement;
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
        const fio = findInputByLabel("ФИО")?.value || findInputByLabel("ФИО пациента")?.value;
        if (fio && fio.trim().length >= 3) {
            if (snap.fio && snap.fio.toLowerCase() !== fio.trim().toLowerCase()) {
                window.currentCardSnapshot = {};
            }
            window.currentCardSnapshot.fio = fio.trim();
        }

        const bDate = findInputByLabel("Дата рождения")?.value;
        if (bDate) snap.birthDate = bDate;
        const addr = findInputByLabel("Адрес вызова")?.value;
        if (addr) snap.address = addr;
        const reason = findInputByLabel("Повод к вызову")?.value || getDropdownOrChipsValue("Повод к вызову");
        if (reason) snap.callReason = reason;

        // Вкладка "Уточненные сведения"
        const complaints = findInputByLabel("Жалобы")?.value;
        if (complaints) snap.complaints = complaints;
        const anamnesis = findInputByLabel("Анамнез заболевания")?.value;
        if (anamnesis) snap.anamnesis = anamnesis;
        const past = findInputByLabel("Перенесенные заболевания (со слов пациента)")?.value;
        if (past) snap.pastDiseases = past;

        // Вкладка "Объективные данные"
        const genState = getDropdownOrChipsValue("Общее состояние");
        if (genState) snap.generalState = genState;
        const consciousness = getDropdownOrChipsValue("Сознание");
        if (consciousness) snap.consciousness = consciousness;
        const position = getActiveButtonValue("Положение");
        if (position) snap.position = position;

        // Шкала комы Глазго
        const gTest = findInputByLabel("Шкала комы Глазго (тест)")?.value;
        if (gTest) snap.glasgowTest = gTest;
        const gScore = findInputByLabel("Шкала комы Глазго (баллы)")?.value;
        if (gScore) snap.glasgowScore = gScore;

        const bp = getDualInputValue("АД (мм.рт.ст.)") || findInputByLabel("АД (мм.рт.ст.)")?.value;
        if (bp) snap.bp = bp;
        const bpAdapt = getDualInputValue("Адаптированное АД (мм.рт.ст.)") || findInputByLabel("Адаптированное АД (мм.рт.ст.)")?.value;
        if (bpAdapt) snap.bpAdapted = bpAdapt;
        const pulse = findInputByLabel("Пульс, ударов в минуту")?.value;
        if (pulse) snap.pulse = pulse;
        const rr = findInputByLabel("Частота дыхания (в минуту)")?.value;
        if (rr) snap.rr = rr;
        const temp = findInputByLabel("Температура тела (°C)")?.value;
        if (temp) snap.temp = temp;
        const sat = findInputByLabel("Сатурация 1 (%)")?.value;
        if (sat) snap.saturation = sat;
        const gluc = findInputByLabel("Гликемия 1 (ммоль/л)")?.value;
        if (gluc) snap.glucose = gluc;

        // Органы и системы (мультиселекты без [object Object])
        const heart = getDropdownOrChipsValue("Тоны сердца");
        if (heart) snap.heartSounds = heart;
        const lungs = getDropdownOrChipsValue("Дыхание");
        if (lungs) snap.lungs = lungs;
        const percussion = getDropdownOrChipsValue("Перкуссия легких");
        if (percussion) snap.percussion = percussion;
        const abdomen = getDropdownOrChipsValue("Живот");
        if (abdomen) snap.abdomen = abdomen;
        const localStatus = findInputByLabel("Локальный статус")?.value;
        if (localStatus) snap.localStatus = localStatus;

        const diag = findInputByLabel("Предварительный диагноз")?.value;
        if (diag) snap.diagnosis = diag;
        const mkb = findInputByLabel("Код по МКБ")?.value || getDropdownOrChipsValue("Код по МКБ");
        if (mkb) snap.mkb = mkb;

        // Вкладка "Медицинская помощь"
        const medHelp = getDropdownOrChipsValue("Медицинская помощь, оказанная на месте и при транспортировке");
        if (medHelp) snap.medHelp = medHelp;

        const meds = extractMedicationsList();
        if (meds) snap.medications = meds;

        const attack = getActiveButtonValue("Приступ");
        if (attack) snap.attack = attack;
        const effect = getActiveButtonValue("Результат оказания");
        if (effect) snap.therapyEffect = effect;
        const callResult = getActiveButtonValue("Результат выезда");
        if (callResult) snap.callResult = callResult;
    }

    // Надежный обход всех вкладок (150 мс для уверенной загрузки компонентов)
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
                await new Promise(res => setTimeout(res, 150));
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
            const fio = findInputByLabel("ФИО")?.value || findInputByLabel("ФИО пациента")?.value || "";
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
        function setVal(labelText, value) {
            if (!value) return;
            const labels = Array.from(document.querySelectorAll('label, div, span, p'));
            const target = labels.find(el => el.textContent.trim().toLowerCase() === labelText.toLowerCase());
            if (target) {
                let container = target.closest('div');
                let input = container?.querySelector('input, textarea');
                if (input) {
                    input.value = value;
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                }
            }
        }

        setVal("Анамнез заболевания", data.anamnesis);
        setVal("Жалобы", data.complaints);
        setVal("Адаптированное АД (мм.рт.ст.)", data.bpAdapted);
        setVal("Перенесенные заболевания (со слов пациента)", data.pastDiseases);
        setVal("Предварительный диагноз", data.diagnosis);
        setVal("Код по МКБ", data.mkb);
        setVal("Локальный статус", data.localStatus);
    } catch (e) {
        console.error("Autofill error:", e);
    }
};
