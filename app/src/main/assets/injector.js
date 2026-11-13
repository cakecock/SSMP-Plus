(function() {
    // Вспомогательная функция поиска input / textarea по тексту заголовка рядом
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

    // Проверка: находится ли пользователь внутри карты вызова
    function isInsideCard() {
        const text = document.body ? document.body.innerText : '';
        // Вкладки, которые есть ТОЛЬКО внутри карты вызова:
        const hasCardTabs = text.includes('О ВЫЗОВЕ') && text.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ');
        const hasCardDetails = text.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ') || text.includes('МЕДИЦИНСКАЯ ПОМОЩЬ');
        return hasCardTabs || hasCardDetails;
    }

    // Создаем панель кнопок (если еще не создана)
    let bar = document.getElementById('b103-toolbar');
    if (!bar) {
        bar = document.createElement('div');
        bar.id = 'b103-toolbar';
        bar.style.position = 'fixed';
        // Располагаем чуть выше стандартной круглой кнопки меню
        bar.style.bottom = '85px';
        bar.style.right = '16px';
        bar.style.zIndex = '99999';
        bar.style.display = 'none'; // По умолчанию скрыта
        bar.style.flexDirection = 'column';
        bar.style.gap = '8px';

        bar.innerHTML = `
            <button id="btn-save-db" style="background:#00796B;color:white;border:none;padding:10px 14px;border-radius:24px;box-shadow:0 3px 6px rgba(0,0,0,0.3);font-size:12px;font-weight:bold;cursor:pointer;">
                💾 Запомнить карту
            </button>
            <button id="btn-fill-db" style="background:#0288D1;color:white;border:none;padding:10px 14px;border-radius:24px;box-shadow:0 3px 6px rgba(0,0,0,0.3);font-size:12px;font-weight:bold;cursor:pointer;">
                ⚡ Автозаполнение
            </button>
        `;
        document.body.appendChild(bar);

        document.getElementById('btn-save-db').onclick = function() {
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

            if (window.AndroidBridge) {
                AndroidBridge.saveCardData(JSON.stringify(payload));
            }
        };

        document.getElementById('btn-fill-db').onclick = function() {
            const fio = findInputByLabel("ФИО")?.value || "";
            if (window.AndroidBridge && fio) {
                AndroidBridge.requestAutofill(fio);
            } else {
                alert("Сначала укажите ФИО пациента");
            }
        };
    }

    // Регулярная проверка видимости: показываем панель ТОЛЬКО внутри карты
    function updateVisibility() {
        if (bar) {
            bar.style.display = isInsideCard() ? 'flex' : 'none';
        }
    }

    // Запускаем проверку каждые 400мс (работает плавно и без нагрузки)
    if (!window.b103VisibilityTimer) {
        window.b103VisibilityTimer = setInterval(updateVisibility, 400);
    }
})();

// Применение данных автозаполнения
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
    } catch (e) {
        console.error("Autofill error:", e);
    }
};
