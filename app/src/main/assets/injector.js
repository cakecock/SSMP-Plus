(function() {
    // Вспомогательная функция поиска input / textarea по тексту заголовка рядом
    function findInputByLabel(text) {
        const labels = Array.from(document.querySelectorAll('label, div, span, p'));
        const target = labels.find(el => el.textContent.trim().toLowerCase() === text.toLowerCase());
        if (!target) return null;

        // Ищем input/textarea внутри контейнера или следующий за ним
        let container = target.closest('div');
        if (container) {
            let input = container.querySelector('input, textarea, select');
            if (input) return input;
        }
        return null;
    }

    // Добавление плавающей панели кнопок
    if (!document.getElementById('b103-toolbar')) {
        const bar = document.createElement('div');
        bar.id = 'b103-toolbar';
        bar.style.position = 'fixed';
        bar.style.bottom = '90px';
        bar.style.right = '16px';
        bar.style.zIndex = '99999';
        bar.style.display = 'flex';
        bar.style.flexDirection = 'column';
        bar.style.gap = '8px';

        bar.innerHTML = `
            <button id="btn-save-db" style="background:#00796B;color:white;border:none;padding:10px 14px;border-radius:24px;box-shadow:0 3px 6px rgba(0,0,0,0.3);font-size:12px;font-weight:bold;">
                💾 Запомнить карту
            </button>
            <button id="btn-fill-db" style="background:#0288D1;color:white;border:none;padding:10px 14px;border-radius:24px;box-shadow:0 3px 6px rgba(0,0,0,0.3);font-size:12px;font-weight:bold;">
                ⚡ Автозаполнение
            </button>
        `;
        document.body.appendChild(bar);

        document.getElementById('btn-save-db').onclick = function() {
            // Собираем ключевые данные
            const fio = findInputByLabel("ФИО")?.value || "";
            const birthDate = findInputByLabel("Дата рождения")?.value || "";
            const anamnesis = findInputByLabel("Анамнез заболевания")?.value || "";
            const complaints = findInputByLabel("Жалобы")?.value || "";
            const bpAdapted = findInputByLabel("Адаптированное АД (мм.рт.ст.)")?.value || "";

            const payload = {
                fio: fio,
                birthDate: birthDate,
                anamnesis: anamnesis,
                complaints: complaints,
                bpAdapted: bpAdapted
            };

            // Передаем в Android
            if (window.AndroidBridge) {
                AndroidBridge.saveCardData(JSON.stringify(payload));
            }
        };

        document.getElementById('btn-fill-db').onclick = function() {
            const fio = findInputByLabel("ФИО")?.value || "";
            if (window.AndroidBridge && fio) {
                AndroidBridge.requestAutofill(fio);
            } else {
                alert("Сначала введите ФИО пациента");
            }
        };
    }
})();

// Функция, вызываемая из Kotlin для вставки данных
window.applyAutofill = function(jsonStr) {
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
};
