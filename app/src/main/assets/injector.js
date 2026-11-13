(function() {
    // Внутреннее постоянное хранилище карты во время работы
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
            if (input) return input;
        }
        return null;
    }

    function isInsideCard() {
        const text = document.body ? document.body.innerText : '';
        const hasCardTabs = text.includes('О ВЫЗОВЕ') && text.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ');
        const hasCardDetails = text.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ') || text.includes('МЕДИЦИНСКАЯ ПОМОЩЬ');
        return hasCardTabs || hasCardDetails;
    }

    // Сканирует текущий открытый экран и объединяет с ранее собранным
    function scanAndMergeVisibleFields() {
        if (!isInsideCard()) return;

        const snap = window.currentCardSnapshot;

        // Вкладка "О вызове" и "Общая информация"
        const fio = findInputByLabel("ФИО")?.value || findInputByLabel("ФИО пациента")?.value;
        if (fio && fio.trim().length >= 3) {
            // Если ввели нового пациента — очищаем старый снимок
            if (snap.fio && snap.fio.toLowerCase() !== fio.trim().toLowerCase()) {
                window.currentCardSnapshot = {};
            }
            window.currentCardSnapshot.fio = fio.trim();
        }

        const bDate = findInputByLabel("Дата рождения")?.value;
        if (bDate) snap.birthDate = bDate;

        const addr = findInputByLabel("Адрес вызова")?.value;
        if (addr) snap.address = addr;

        const reason = findInputByLabel("Повод к вызову")?.value;
        if (reason) snap.callReason = reason;

        // Вкладка "Уточненные сведения"
        const complaints = findInputByLabel("Жалобы")?.value;
        if (complaints) snap.complaints = complaints;

        const anamnesis = findInputByLabel("Анамнез заболевания")?.value;
        if (anamnesis) snap.anamnesis = anamnesis;

        const past = findInputByLabel("Перенесенные заболевания (со слов пациента)")?.value;
        if (past) snap.pastDiseases = past;

        // Вкладка "Объективные данные"
        const bp = findInputByLabel("АД (мм.рт.ст.)")?.value;
        if (bp) snap.bp = bp;

        const bpAdapt = findInputByLabel("Адаптированное АД (мм.рт.ст.)")?.value;
        if (bpAdapt) snap.bpAdapted = bpAdapt;

        const pulse = findInputByLabel("Пульс, ударов в минуту")?.value;
        if (pulse) snap.pulse = pulse;

        const sat = findInputByLabel("Сатурация 1 (%)")?.value;
        if (sat) snap.saturation = sat;

        const gluc = findInputByLabel("Гликемия 1 (ммоль/л)")?.value;
        if (gluc) snap.glucose = gluc;

        const diag = findInputByLabel("Предварительный диагноз")?.value;
        if (diag) snap.diagnosis = diag;

        const mkb = findInputByLabel("Код по МКБ")?.value;
        if (mkb) snap.mkb = mkb;
    }

    // Автосохранение при вводе
    let autoSaveTimer = null;
    function triggerAutoSave() {
        if (!isInsideCard()) return;
        scanAndMergeVisibleFields();
        clearTimeout(autoSaveTimer);
        autoSaveTimer = setTimeout(() => {
            const snap = window.currentCardSnapshot;
            if (snap.fio && snap.fio.length >= 5 && (snap.anamnesis || snap.complaints || snap.bpAdapted)) {
                if (window.AndroidBridge && window.AndroidBridge.autoSaveCardData) {
                    window.AndroidBridge.autoSaveCardData(JSON.stringify(snap));
                }
            }
        }, 1500);
    }

    document.addEventListener('input', triggerAutoSave, true);
    document.addEventListener('change', triggerAutoSave, true);

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

        document.getElementById('btn-save-db').onclick = function() {
            scanAndMergeVisibleFields();
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
                scanAndMergeVisibleFields(); // Фоновый сбор данных при смене вкладок
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

// Автозаполнение всех доступных полей
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
    } catch (e) {
        console.error("Autofill error:", e);
    }
};
