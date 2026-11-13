(function() {
    if (!window.currentCardSnapshot) {
        window.currentCardSnapshot = {};
    }

    // Поиск поля по метке
    function findInputByLabel(text) {
        const labels = Array.from(document.querySelectorAll('label, div, span, p'));
        const target = labels.find(el => el.textContent.trim().toLowerCase() === text.toLowerCase());
        if (!target) return null;

        let container = target.closest('div');
        if (container) {
            let input = container.querySelector('input, textarea, select');
            if (input) return input;
        }
        return null;
    }

    // Получение значения выпадающего списка или текста
    function getDropdownValue(labelText) {
        const labels = Array.from(document.querySelectorAll('label, div, span, p'));
        const target = labels.find(el => el.textContent.trim().toLowerCase() === labelText.toLowerCase());
        if (!target) return "";

        let container = target.closest('div');
        if (container) {
            let input = container.querySelector('input');
            if (input && input.value) return input.value;
            // Ищем текст внутри контейнера
            let textEl = container.innerText.split('\n').filter(t => t.trim() !== labelText && t.trim() !== "Не выбрано" && t.trim().length > 0);
            if (textEl.length > 0) return textEl[0].trim();
        }
        return "";
    }

    // Поиск активной кнопки в группе (например [АКТИВНОЕ] [ПАССИВНОЕ] или [КУПИРОВАН] [НЕ КУПИРОВАН])
    function getActiveButtonValue(groupLabel) {
        const labels = Array.from(document.querySelectorAll('label, div, span, p'));
        const target = labels.find(el => el.textContent.trim().toLowerCase() === groupLabel.toLowerCase());
        if (!target) return "";

        let parent = target.parentElement;
        if (parent) {
            let buttons = Array.from(parent.querySelectorAll('button, div[role="button"], span'));
            for (let btn of buttons) {
                let style = window.getComputedStyle(btn);
                // Активная кнопка имеет бирюзовый цвет фона (#00796B / rgb(0, 121, 107))
                if (style.backgroundColor.includes("0, 121") || style.backgroundColor.includes("0, 105") || btn.className.includes("active")) {
                    return btn.innerText.trim();
                }
            }
        }
        return "";
    }

    function isInsideCard() {
        const text = document.body ? document.body.innerText : '';
        const hasCardTabs = text.includes('О ВЫЗОВЕ') && text.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ');
        const hasCardDetails = text.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ') || text.includes('МЕДИЦИНСКАЯ ПОМОЩЬ');
        return hasCardTabs || hasCardDetails;
    }

    // Сбор всех данных с текущего экрана
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
        const reason = findInputByLabel("Повод к вызову")?.value || getDropdownValue("Повод к вызову");
        if (reason) snap.callReason = reason;

        // Вкладка "Уточненные сведения"
        const complaints = findInputByLabel("Жалобы")?.value;
        if (complaints) snap.complaints = complaints;
        const anamnesis = findInputByLabel("Анамнез заболевания")?.value;
        if (anamnesis) snap.anamnesis = anamnesis;
        const past = findInputByLabel("Перенесенные заболевания (со слов пациента)")?.value;
        if (past) snap.pastDiseases = past;

        // Вкладка "Объективные данные"
        const genState = getDropdownValue("Общее состояние");
        if (genState) snap.generalState = genState;
        const consciousness = getDropdownValue("Сознание");
        if (consciousness) snap.consciousness = consciousness;
        const position = getActiveButtonValue("Положение");
        if (position) snap.position = position;

        const bp = findInputByLabel("АД (мм.рт.ст.)")?.value;
        if (bp) snap.bp = bp;
        const bpAdapt = findInputByLabel("Адаптированное АД (мм.рт.ст.)")?.value;
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

        const heart = getDropdownValue("Тоны сердца");
        if (heart) snap.heartSounds = heart;
        const lungs = getDropdownValue("Дыхание");
        if (lungs) snap.lungs = lungs;
        const abdomen = getDropdownValue("Живот");
        if (abdomen) snap.abdomen = abdomen;
        const localStatus = findInputByLabel("Локальный статус")?.value;
        if (localStatus) snap.localStatus = localStatus;

        const diag = findInputByLabel("Предварительный диагноз")?.value;
        if (diag) snap.diagnosis = diag;
        const mkb = findInputByLabel("Код по МКБ")?.value || getDropdownValue("Код по МКБ");
        if (mkb) snap.mkb = mkb;

        // Вкладка "Медицинская помощь"
        const medHelp = getDropdownValue("Медицинская помощь, оказанная на месте и при транспортировке");
        if (medHelp) snap.medHelp = medHelp;

        // Лекарственные средства
        const drugsContainer = Array.from(document.querySelectorAll('div, table')).find(el => el.innerText && el.innerText.includes("Лекарственные средства"));
        if (drugsContainer) {
            let lines = drugsContainer.innerText.split('\n')
                .map(l => l.trim())
                .filter(l => l.length > 2 && !l.includes("Лекарственные средства") && !l.includes("ДОБАВИТЬ"));
            if (lines.length > 0) snap.medications = lines.slice(0, 8).join("; ");
        }

        const attack = getActiveButtonValue("Приступ");
        if (attack) snap.attack = attack;
        const effect = getActiveButtonValue("Результат оказания");
        if (effect) snap.therapyEffect = effect;
        const callResult = getActiveButtonValue("Результат выезда");
        if (callResult) snap.callResult = callResult;
    }

    // Молниеносный экспресс-обход всех вкладок перед сохранением
    async function scanAllTabsCompletely() {
        const tabNames = ['О ВЫЗОВЕ', 'УТОЧНЕННЫЕ СВЕДЕНИЯ', 'ОБЪЕКТИВНЫЕ ДАННЫЕ', 'МЕДИЦИНСКАЯ ПОМОЩЬ'];
        const allElements = Array.from(document.querySelectorAll('button, div, span, a'));
        
        // Находим кнопки переключения вкладок
        let tabs = {};
        for (let name of tabNames) {
            let el = allElements.find(e => e.innerText && e.innerText.trim() === name && e.children.length === 0);
            if (el) {
                tabs[name] = el.closest('button, div[role="tab"]') || el;
            }
        }

        // Запоминаем текущую активную вкладку
        let activeTab = null;
        for (let name of tabNames) {
            if (tabs[name]) {
                let style = window.getComputedStyle(tabs[name]);
                if (style.backgroundColor.includes("0, 121") || style.backgroundColor.includes("0, 105") || tabs[name].className.includes("active")) {
                    activeTab = tabs[name];
                }
            }
        }

        // Быстро перещелкиваем вкладки для сбора скрытого DOM
        for (let name of tabNames) {
            if (tabs[name]) {
                tabs[name].click();
                await new Promise(res => setTimeout(res, 60));
                scanAndMergeVisibleFields();
            }
        }

        // Возвращаем пользователя на исходную вкладку
        if (activeTab) {
            activeTab.click();
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
            // Запускаем экспресс-обход всех вкладок
            await scanAllTabsCompletely();
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

// Автозаполнение полей
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
