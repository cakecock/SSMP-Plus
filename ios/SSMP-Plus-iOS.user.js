// ==UserScript==
// @name         SSMP Plus (iOS / Safari Edition)
// @namespace    https://soft.103brest.by/ssmp/
// @version      3.0.0
// @description  Мобильный ассистент СМП (г. Брест): автозаполнение без дергания экрана, оффлайн-архив, шаблоны, ЭКГ-конструктор, СЛР-таймер, O2 в МПа, шкалы и протоколы.
// @author       SSMP Plus & Nova
// @match        https://soft.103brest.by/*
// @run-at       document-idle
// @grant        none
// ==/UserScript==

(function() {
    'use strict';
    if (window.b103IosInitialized) return;
    window.b103IosInitialized = true;

    const STORAGE_KEY = 'ssmp_plus_ios_cards_v3';
    window.lastLoadedApiCard = window.lastLoadedApiCard || null;
    window.activeAutofillData = window.activeAutofillData || null;
    window.b103ScreenCache = window.b103ScreenCache || {};
    window.b103IsApplying = false;

    // Глобальная проверка границы вкладки "ЗАКЛЮЧЕНИЕ" (для защиты от случайной перезаписи)
    function isInConclusion(el) {
        if (!el) return false;
        const anchor = Array.from(document.querySelectorAll('p, span, div, label')).find(e => {
            if (!e.innerText || e.children.length > 2) return false;
            const t = e.innerText.trim();
            return t === 'Результат оказания мед. помощи' || t === 'Результат выезда' || t === 'Дата и время начала транспортировки';
        });
        if (!anchor) return false;
        return Boolean(anchor.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING);
    }
    window.b103IsInConclusion = isInConclusion;

    // ==========================================
    // 1. БАЗА ДАННЫХ LOCALSTORAGE (СОВМЕСТИМАЯ С ANDROID JSON)
    // ==========================================
    function loadDb() {
        try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]'); } catch (e) { return []; }
    }
    function saveDb(arr) {
        try { localStorage.setItem(STORAGE_KEY, JSON.stringify(arr)); } catch (e) { alert('Ошибка сохранения в память браузера'); }
    }

    function showToast(msg) {
        let t = document.getElementById('b103-ios-toast');
        if (!t) {
            t = document.createElement('div');
            t.id = 'b103-ios-toast';
            t.style.cssText = 'position:fixed;top:24px;left:50%;transform:translateX(-50%);background:rgba(38,50,56,0.95);color:#fff;padding:12px 22px;border-radius:24px;font-size:14px;font-weight:bold;z-index:999999;box-shadow:0 4px 14px rgba(0,0,0,0.3);transition:opacity 0.25s;pointer-events:none;text-align:center;max-width:88vw;';
            document.body.appendChild(t);
        }
        t.innerText = msg;
        t.style.opacity = '1';
        clearTimeout(window.b103ToastTimer);
        window.b103ToastTimer = setTimeout(() => { t.style.opacity = '0'; }, 2400);
    }

    function copyText(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(() => showToast('📋 Скопировано: ' + text.substring(0, 35))).catch(() => {});
        } else {
            const ta = document.createElement('textarea');
            ta.value = text; document.body.appendChild(ta); ta.select();
            try { document.execCommand('copy'); showToast('📋 Скопировано!'); } catch (e) {}
            ta.remove();
        }
    }

    // ==========================================
    // 2. СЕТЕВОЙ ПЕРЕХВАТЧИК API И ПАРСЕР СЕРВЕРНОЙ КАРТЫ
    // ==========================================
    const origFetch = window.fetch;
    window.fetch = async function(...args) {
        try {
            const opts = args[1] || {};
            if (opts.body && typeof opts.body === 'string' && opts.body.includes('"patient"')) {
                const s = JSON.parse(opts.body);
                if (s && s.patient) window.lastLoadedApiCard = Object.assign(window.lastLoadedApiCard || {}, s);
            }
        } catch (e) {}
        const res = await origFetch.apply(this, args);
        try {
            const url = typeof args[0] === 'string' ? args[0] : (args[0] ? args[0].url : '');
            if (url && url.includes('/api/card/') && !url.includes('/short')) {
                res.clone().json().then(d => {
                    if (d && d.patient) {
                        if (window.lastLoadedApiCard && window.lastLoadedApiCard.id !== d.id) {
                            window.activeAutofillData = null; window.b103ScreenCache = {};
                        }
                        window.lastLoadedApiCard = d;
                    }
                }).catch(() => {});
            }
        } catch (e) {}
        return res;
    };

    const origOpen = XMLHttpRequest.prototype.open;
    const origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u) { this._url = u; return origOpen.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function(body) {
        try {
            if (body && typeof body === 'string' && body.includes('"patient"')) {
                const s = JSON.parse(body);
                if (s && s.patient) window.lastLoadedApiCard = Object.assign(window.lastLoadedApiCard || {}, s);
            }
        } catch (e) {}
        this.addEventListener('load', function() {
            try {
                if (this._url && this._url.includes('/api/card/') && !this._url.includes('/short')) {
                    const d = JSON.parse(this.responseText);
                    if (d && d.patient) {
                        if (window.lastLoadedApiCard && window.lastLoadedApiCard.id !== d.id) {
                            window.activeAutofillData = null; window.b103ScreenCache = {};
                        }
                        window.lastLoadedApiCard = d;
                    }
                }
            } catch (e) {}
        });
        return origSend.apply(this, arguments);
    };

    function cleanBp(raw) {
        if (!raw) return "";
        if (typeof raw === 'object') {
            const s = raw.sys || raw.systolic || '';
            const d = raw.dis || raw.dia || raw.diastolic || '';
            return (s || d) ? `${s}/${d}` : "";
        }
        const str = String(raw).trim();
        if (str.startsWith("{")) {
            try { const o = JSON.parse(str); return `${o.sys||''}/${o.dis||o.dia||''}`; } catch (e) { return ""; }
        }
        return str;
    }

    function parseServerCardToInternal(raw) {
        if (!raw) return null;
        const p = raw.patient || {};
        const fio = (p.fullName || raw.initialFullName || "").trim();
        if (!fio) return null;

        const regDateOnly = (raw.dateOfRegistration || "").split("T")[0];
        const rawBDate = p.birthDate || "";
        const ageUnit = (raw.ageType && raw.ageType.type) ? raw.ageType.type : "";
        let bDate = "";
        if (rawBDate.includes("-")) {
            const bDateOnly = rawBDate.split("T")[0];
            if (bDateOnly !== regDateOnly || ageUnit === "Дней" || ageUnit === "Часов") {
                const sp = bDateOnly.split("-");
                if (sp.length === 3) bDate = `${sp[2]}.${sp[1]}.${sp[0]} / 00:00`;
            }
        }
        const jArr = (k) => Array.isArray(raw[k]) ? raw[k].map(x => (x && (x.type || x.name || x.title)) || '').filter(Boolean).join(", ") : "";
        let meds = raw._domMedications || "";
        if (!meds && Array.isArray(raw.medications)) {
            meds = raw.medications.map(m => {
                const nm = m?.medication?.name || m?.name || "";
                const cnt = m?.number || m?.count || "";
                const un = m?.medication?.type?.name || m?.medication?.unitsType?.name || "";
                const rt = m?.routeDict?.display || "";
                if (!nm) return "";
                return `${nm}${cnt ? ` — ${cnt} ${un}`.trimEnd() : ""}${(rt && !rt.includes("не определен")) ? ` [${rt}]` : ""}`;
            }).filter(Boolean).join(";\n• ");
        }

        const bp = cleanBp(raw.arterialPressure);
        const bpAd = cleanBp(raw.arterialPressureAdaptive);
        const icd = raw.icd;
        const liverVal = (typeof raw.liver === 'boolean') ? (raw.liver ? "УВЕЛИЧЕНА" : "НЕ УВЕЛИЧЕНА") : (raw.liver?.type?.toUpperCase() || "НЕ УВЕЛИЧЕНА");

        const card = {
            fio, birthDate: bDate, age: raw.age ? String(raw.age) : "", ageUnit,
            address: p.placeOfRegistration || raw.address || "",
            apo: p.territorialMedicalFacility?.type || "",
            callReason: raw.reasonToCall?.type || "",
            gender: p.gender === true ? "МУЖЧИНА" : "ЖЕНЩИНА",
            complaints: raw.complaints || "", anamnesis: raw.anamnesis || "", pastDiseases: raw.pastIllnesses || "",
            allergy: raw.allergy ? "ДА" : "НЕТ",
            generalState: raw.generalState?.type || "", consciousness: raw.consciousness?.type || "",
            position: raw.position?.type?.toUpperCase() || "", positionDescription: raw.positionDescription || "",
            behavior: raw.behavior?.type?.toUpperCase() || "", glasgowScore: raw.comaScale ? String(raw.comaScale) : "15",
            bp, bpAdapted: bpAd,
            pulse: raw.pulse ? String(raw.pulse) : "", rr: raw.breathCount ? String(raw.breathCount) : "",
            temp: raw.skinTemp ? String(raw.skinTemp) : "", saturation: raw.saturation1 ? String(raw.saturation1) : "",
            glucose: raw.glycemia1 ? String(raw.glycemia1) : "",
            heartSounds: jArr("heartSounds"), lungs: jArr("breaths"), percussion: jArr("percussionOfLungs"),
            abdomen: jArr("stomachs"), skin: jArr("skins"), urination: jArr("urinations").toUpperCase(),
            stool: raw.defecatio?.type?.toUpperCase() || "",
            edema: raw.peripheralEdema ? "ДА" : "НЕТ", edemaDescription: raw.peripheralEdemaDescription || "",
            rash: raw.skinRashes ? "ДА" : "НЕТ", ascites: raw.ascites ? "ДА" : "НЕТ",
            peritoneal: raw.stomachSymptoms ? "ДА" : "НЕТ",
            medHelp: jArr("medicalAssistanceProvided"), medications: meds,
            pupils: raw.pupils?.type?.toUpperCase() || "", lightReaction: raw.pupilsLight !== false ? "ДА" : "НЕТ",
            speech: raw.speech?.type?.toUpperCase() || "", gait: raw.gait?.type?.toUpperCase() || "",
            face: raw.face?.type?.toUpperCase() || "", nystagmus: raw.nystagmus?.type?.toUpperCase() || "",
            muscleTone: raw.muscleTone || "Нормальный", pathologicalReflexes: raw.pathologicalReflexes || "Отрицательные",
            meningealSymptoms: raw.meningealSymptoms || "Отрицательные", plegia: raw.plegia || "Нет",
            tongue: raw.tongue || "Чистые", mucous: raw.mucosa || "Бледно-розовые",
            liver: liverVal, peristalsis: raw.peristalsis !== false ? "ДА" : "НЕТ",
            localStatus: raw.localStatus || "",
            ecgBefore: raw.electrocardiogramBefore || "", ecgAfter: raw.electrocardiogramAfter || "",
            diagnosis: raw.diagnosis || "",
            mkb: icd ? `[${icd.code}] ${icd.name || icd.type || ""}`.trim() : "",
            attack: typeof raw.postAttack === 'boolean' ? (raw.postAttack ? "КУПИРОВАН" : "НЕ КУПИРОВАН") : "",
            therapyEffect: raw.result?.type?.toUpperCase() || "",
            callResult: raw.resultDeparture?.type?.toUpperCase() || ""
        };
        let score = 0;
        if (card.anamnesis.length > 10) score += 35;
        if (card.complaints) score += 25;
        if (card.diagnosis) score += 25;
        if (meds || bp) score += 15;
        return { fio: fio.toLowerCase(), birth_date: bDate, timestamp: Date.now(), score, json_data: JSON.stringify(card), is_builtin: 0 };
    }

    function saveCardToLocalArchive() {
        scrapeCache();
        if (!window.lastLoadedApiCard) { showToast("Откройте карту для сохранения"); return; }
        const merged = Object.assign({}, window.lastLoadedApiCard, window.b103ScreenCache);
        const entry = parseServerCardToInternal(merged);
        if (!entry) { showToast("Не удалось определить ФИО пациента"); return; }

        const db = loadDb();
        const sixHoursAgo = Date.now() - 6 * 3600 * 1000;
        // Строгое совпадение по полному ФИО (защита от однофамильцев)
        const idx = db.findIndex(x => x.is_builtin === 0 && x.fio === entry.fio && x.timestamp > sixHoursAgo);
        if (idx >= 0) db[idx] = entry; else db.unshift(entry);
        saveDb(db);
        showToast(`💾 Карта ${entry.fio.toUpperCase()} сохранена!`);
    }

    // ==========================================
    // 3. ВСТРОЕННЫЕ КЛИНИЧЕСКИЕ ШАБЛОНЫ СМП (63 ШАБЛОНА)
    // ==========================================
    function tpl(id, title, mkb, diagnosis, complaints, anamnesis, opts = {}) {
        const d = Object.assign({
            diagnosis, mkb, complaints, anamnesis,
            pastDiseases: "ИБС, АГ", allergy: "НЕТ", generalState: "Удовлетворительное", consciousness: "Ясное",
            position: "АКТИВНОЕ", positionDescription: "", behavior: "СПОКОЙНОЕ", glasgowScore: "15",
            bp: "130/80", bpAdapted: "120/80", pulse: "78", pulseExists: "ДА", rr: "16", temp: "36.6",
            saturation: "98", glucose: "5.4", heartSounds: "Ритмичные, Ясные", lungs: "Свободное, Везикулярное",
            percussion: "Ясный легочный звук", abdomen: "Участвует в акте дыхания, Мягкий, Безболезненный",
            skin: "Теплая, Сухая, Нормальная", tongue: "Чистые", mucous: "Бледно-розовые",
            pupils: "", lightReaction: "ДА", speech: "СОХРАНЕНА", face: "СИММЕТРИЧНОЕ", gait: "СВОБОДНАЯ",
            nystagmus: "НЕТ", muscleTone: "Нормальный", pathologicalReflexes: "Отрицательные",
            meningealSymptoms: "Отрицательные", plegia: "Нет", rash: "НЕТ", ascites: "НЕТ",
            edema: "НЕТ", edemaDescription: "", alcohol: "НЕТ", acetone: "НЕТ", peritoneal: "НЕТ",
            liver: "НЕ УВЕЛИЧЕНА", peristalsis: "ДА", urination: "НЕ НАРУШЕНО", stool: "НЕ НАРУШЕН",
            localStatus: "", ecgBefore: "Ритм синусовый, правильный, ЧСС 78 в мин. Острой очаговой патологии нет.",
            ecgAfter: "Без динамики.", recommendedTherapy: ""
        }, opts);
        return { id, title, mkb, diagnosis, jsonData: JSON.stringify(d), isCustom: false };
    }

    const BUILTIN_TEMPLATES = [
        tpl(1, "🚨 СЛР (Клиническая смерть, безуспешная)", "[I46.9] Остановка сердца неуточненная", "Внезапная сердечная смерть. Клиническая смерть. Безуспешная СЛР. Биологическая смерть.", "Жалоб нет ввиду клинической смерти.", "В присутствии бригады потерял сознание, прекратились дыхание и пульс на сонных артериях. Проводилась расширенная СЛР (НМС, ИВЛ, ЭКГ, в/в доступ). Через 30 минут спонтанное кровообращение не восстановлено, констатирована биологическая смерть.", { generalState: "Клиническая смерть", consciousness: "Отсутствует", position: "ПАССИВНОЕ", glasgowScore: "3", bp: "0/0", pulse: "0", pulseExists: "НЕТ", rr: "0", temp: "0", saturation: "0", glucose: "0", heartSounds: "Отсутствуют", lungs: "Отсутствует", skin: "Бледная, Цианоз, Холодная", pupils: "МИДРИАЗ", lightReaction: "НЕТ", speech: "ОТСУТСТВУЕТ", gait: "НЕ ОЦЕНИВАЛАСЬ", muscleTone: "Атония", peristalsis: "НЕТ", stool: "ОТСУТСТВУЕТ", ecgBefore: "Асистолия.", ecgAfter: "Изолиния." }),
        tpl(2, "⚡ СЛР (Успешная реанимация)", "[I46.0] Остановка сердца с успешным восстановлением сердечной деятельности", "ИБС: Остановка кровообращения по механизму ФЖ. Клиническая смерть. Успешная СЛР.", "Не предъявляет ввиду угнетения сознания.", "Внезапная потеря сознания с остановкой дыхания и кровообращения. Начата СЛР, на ЭКГ ФЖ, выполнена дефибрилляция 200 Дж, восстановлено спонтанное кровообращение.", { generalState: "Крайне тяжелое", consciousness: "Кома", position: "ПАССИВНОЕ", glasgowScore: "6", bp: "90/60", pulse: "98", rr: "14", temp: "36.2", saturation: "94", glucose: "7.8", heartSounds: "Глухие, Ритмичные", lungs: "Ослабленное, Везикулярное", skin: "Бледная, Влажная", pupils: "МИДРИАЗ", lightReaction: "НЕТ", speech: "ОТСУТСТВУЕТ", gait: "НЕ ОЦЕНИВАЛАСЬ", ecgBefore: "Крупноволновая ФЖ.", ecgAfter: "Ритм синусовый, ЧСС 98 в мин." }),
        tpl(3, "🕊️ Констатация биологической смерти (до СМП)", "[R96.0] Мгновенная смерть", "Биологическая смерть до прибытия бригады СМП.", "Жалоб нет ввиду биологической смерти.", "Обнаружен родственниками без признаков жизни около 1,5 часов назад. Реанимационные мероприятия не проводились ввиду достоверных признаков биологической смерти.", { generalState: "Биологическая смерть", consciousness: "Отсутствует", position: "ПАССИВНОЕ", glasgowScore: "3", bp: "0/0", pulse: "0", pulseExists: "НЕТ", rr: "0", temp: "0", saturation: "0", glucose: "0", heartSounds: "Отсутствуют", lungs: "Отсутствует", skin: "Бледная, Холодная, Гипостатические пятна", pupils: "МИДРИАЗ", lightReaction: "НЕТ", speech: "ОТСУТСТВУЕТ", gait: "НЕ ОЦЕНИВАЛАСЬ", muscleTone: "Трупное окоченение", peristalsis: "НЕТ", stool: "ОТСУТСТВУЕТ", localStatus: "Зрачки широкие, с-м Белоглазова положительный, трупные пятна, трупное окоченение. Признаков насильственной смерти нет.", ecgBefore: "Изолиния.", ecgAfter: "Изолиния." }),
        tpl(4, "Гипертонический криз (неосложненный)", "[I10] Эссенциальная (первичная) гипертензия", "Артериальная гипертензия 2 ст., риск 3. Неосложненный гипертонический криз.", "На головную боль в затылочной области, головокружение, мелькание мушек перед глазами.", "Ухудшение около 2 часов назад после стресса, подъем АД дома до 180/100 мм рт. ст.", { generalState: "Средней степени тяжести", bp: "180/100", bpAdapted: "130/80", pulse: "84", heartSounds: "Глухие, Ритмичные", ecgBefore: "Ритм синусовый, ЧСС 84 в мин. ЭОС отклонена влево, признаки ГЛЖ." }),
        tpl(5, "Гипертонический криз (осложненный, церебральный)", "[I10] Эссенциальная (первичная) гипертензия", "Артериальная гипертензия 3 ст., риск 4. Осложненный гипертонический криз (острая гипертоническая энцефалопатия).", "На интенсивную распирающую головную боль, головокружение, тошноту, рвоту.", "Ухудшение 3 часа назад на фоне подъема АД до 210/115 мм рт. ст.", { generalState: "Тяжелое", position: "ВЫНУЖДЕННОЕ", positionDescription: "Лежа с приподнятым изголовьем", behavior: "ВОЗБУЖДЕННОЕ", bp: "210/115", pulse: "92", heartSounds: "Глухие, Ритмичные" }),
        tpl(6, "Пароксизм фибрилляции предсердий (ФП)", "[I48] Фибрилляция и трепетание предсердий", "ИБС: АТС кардиосклероз. Персистирующая форма ФП, тахисистолический вариант, пароксизм.", "На приступ учащенного неритмичного сердцебиения, перебои в работе сердца, слабость.", "Ухудшение сегодня утром, внезапно ощутил срыв ритма.", { generalState: "Средней степени тяжести", bp: "130/80", pulse: "128", heartSounds: "Аритмичные, Ясные", ecgBefore: "Фибрилляция предсердий с ЧЖС 120-135 в мин.", ecgAfter: "Ритм синусовый, ЧСС 76 в мин." }),
        tpl(7, "Постоянная форма ФП (декомпенсация ХСН)", "[I48] Фибрилляция и трепетание предсердий", "ИБС: кардиосклероз. Постоянная форма ФП, тахисистолия. ХСН ФК III.", "На одышку при минимальной нагрузке, сердцебиение, отечность голеней.", "Ухудшение за несколько дней на фоне пропуска плановой терапии.", { generalState: "Средней степени тяжести", bp: "135/85", pulse: "116", rr: "20", saturation: "95", heartSounds: "Аритмичные, Глухие", edema: "ДА", edemaDescription: "Отечность голеней", ecgBefore: "ФП с ЧЖС 110-120 в мин." }),
        tpl(8, "Пароксизмальная наджелудочковая тахикардия (СВТ)", "[I47.1] Наджелудочковая тахикардия", "ИБС: кардиосклероз. Пароксизмальная АВ-узловая наджелудочковая тахикардия.", "На внезапный приступ частого ритмичного сердцебиения, слабость.", "Приступ начался внезапно 40 мин назад. Вагусные пробы без эффекта.", { generalState: "Средней степени тяжести", bp: "115/75", pulse: "175", rr: "18", ecgBefore: "Узкокомплексная тахикардия с ЧСС 175 в мин.", ecgAfter: "Ритм синусовый, ЧСС 78 в мин." }),
        tpl(10, "ОКС без подъема ST (Нестабильная стенокардия)", "[I20.0] Нестабильная стенокардия", "ИБС: ОКС без подъема сегмента ST. Нестабильная стенокардия.", "На давящие боли за грудиной с иррадиацией в левое плечо, нехватку воздуха.", "Боли в покое около 1 часа, нитроглицерин с кратковременным эффектом.", { generalState: "Средней степени тяжести", bp: "140/90", pulse: "88", rr: "18", saturation: "96", heartSounds: "Глухие, Ритмичные", ecgBefore: "Ритм синусовый, ЧСС 88. Депрессия ST до 1.5 мм в V4-V6." }),
        tpl(12, "ОКС с подъемом ST (ОИМ передней стенки)", "[I21.0] Острый трансмуральный инфаркт передней стенки миокарда", "ИБС: ОКС с подъемом ST. Острый крупноочаговый инфаркт миокарда передней стенки ЛЖ.", "На интенсивные жгучие боли за грудиной с иррадиацией в левую руку, холодный пот, страх смерти.", "Заболел остро 40 мин назад. Нитраты без эффекта.", { generalState: "Тяжелое", position: "ВЫНУЖДЕННОЕ", positionDescription: "Полусидя", behavior: "ВОЗБУЖДЕННОЕ", bp: "110/70", pulse: "96", rr: "20", saturation: "94", heartSounds: "Глухие, Ритмичные", skin: "Холодная, Влажная, Бледная", ecgBefore: "ЧСС 96. Элевация ST 3-4 мм в V1-V4, реципрокная депрессия в III, aVF." }),
        tpl(15, "Отек легких / ОЛЖН", "[I50.1] Левожелудочковая недостаточность", "ИБС: ПИКС. Острая левожелудочковая недостаточность, альвеолярный отек легких.", "На выраженное удушье, клокочущее дыхание, кашель с розоватой пенистой мокротой, ортопноэ.", "Ухудшение за последний час на фоне подъема АД до 200/110 мм рт. ст.", { generalState: "Тяжелое", position: "ВЫНУЖДЕННОЕ", positionDescription: "Сидя, ортопноэ", behavior: "ВОЗБУЖДЕННОЕ", bp: "190/110", pulse: "112", rr: "28", saturation: "86", heartSounds: "Глухие, Ритмичные", lungs: "Затрудненное, Хрипы влажные, Хрипы справа, Хрипы слева", skin: "Холодная, Влажная, Цианоз", edema: "ДА", edemaDescription: "Отечность голеней" }),
        tpl(16, "ТЭЛА", "[I26] Легочная эмболия", "Тромбоэмболия ветвей легочной артерии (ТЭЛА). ОДН 2 ст.", "На резкую одышку, нехватку воздуха, сдавление грудной клетки, отечность голеней.", "Одышка наросла остро. В анамнезе варикозная болезнь / тромбофлебит.", { generalState: "Средней степени тяжести", position: "ВЫНУЖДЕННОЕ", positionDescription: "Сидя легче дышать", bp: "125/80", pulse: "92", rr: "20", saturation: "90", heartSounds: "Глухие, Ритмичные", lungs: "Затрудненное, Ослабленное", percussion: "Притупление звука справа", edema: "ДА", edemaDescription: "Отечность голеней", ecgBefore: "Ритм синусовый, ЧСС 92. Синдром SI-QIII-TIII." }),
        tpl(17, "ОНМК / Ишемический инсульт", "[I63.9] Инфаркт мозга неуточненный", "ОНМК по ишемическому типу в бассейне левой СМА. Правосторонний гемипарез, моторная афазия.", "На слабость в правых конечностях, нарушение речи, перекос лица.", "Заболел остро 1,5 часа назад: нарушилась речь, ослабли правая рука и нога.", { generalState: "Тяжелое", consciousness: "Приглушенное", position: "ПАССИВНОЕ", glasgowScore: "13", bp: "170/100", pulse: "82", speech: "НАРУШЕНА", face: "АСИММЕТРИЧНОЕ", gait: "НАРУШЕНА", muscleTone: "Снижен справа", pathologicalReflexes: "Симптом Бабинского справа", plegia: "Правосторонний гемипарез", localStatus: "Сглажена правая носогубная складка, девиация языка вправо, правосторонний гемипарез 2 балла." }),
        tpl(18, "ОНМК / Геморрагический инсульт", "[I61.9] Внутримозговое кровоизлияние неуточненное", "ОНМК по геморрагическому типу. Левосторонняя гемиплегия. Кома 1 ст.", "Контакт невозможен ввиду комы.", "На фоне высокого АД внезапно возникла резкая головная боль, рвота, утратил сознание.", { generalState: "Тяжелое", consciousness: "Кома", position: "ПАССИВНОЕ", glasgowScore: "7", bp: "220/120", pulse: "58", rr: "22", saturation: "91", pupils: "АНИЗОКАРИЯ", lightReaction: "НЕТ", speech: "ОТСУТСТВУЕТ", face: "АСИММЕТРИЧНОЕ", gait: "НЕ ОЦЕНИВАЛАСЬ", meningealSymptoms: "Ригидность затылочных мышц", plegia: "Левосторонняя гемиплегия" }),
        tpl(21, "Эпилепсия (после судорожного припадка)", "[G40.9] Эпилепсия неуточненная", "Эпилепсия. Состояние после генерализованного тонико-клонического припадка.", "На общую слабость, головную боль, сонливость.", "15 минут назад развился приступ судорог до 2 минут с прикусом языка.", { generalState: "Средней степени тяжести", bp: "130/80", pulse: "86", tongue: "Следы прикуса" }),
        tpl(25, "Дорсалгия / Люмбоишиалгия", "[M54.5] Боль внизу спины", "Остеохондроз ПОП. Острая люмбоишиалгия с выраженным болевым синдромом.", "На острую стреляющую боль в пояснице с иррадиацией в ногу.", "Боль возникла после подъема тяжести.", { position: "ВЫНУЖДЕННОЕ", positionDescription: "Щадящее из-за боли в спине", localStatus: "Болезненность паравертебральных точек L4-S1, дефанс мышц спины. С-м Ласега + 45°." }),
        tpl(27, "Приступ бронхиальной астмы", "[J45.9] Астма неуточненная", "Бронхиальная астма, приступный период. ДН 1-2 ст.", "На экспираторное удушье, свистящее дыхание, сухой кашель.", "Страдает БА. Ухудшение 2 часа назад, ингалятор без эффекта.", { generalState: "Средней степени тяжести", position: "ВЫНУЖДЕННОЕ", positionDescription: "Сидя с упором на руки", bp: "130/80", pulse: "98", rr: "24", saturation: "93", lungs: "Затрудненное, Хрипы сухие", percussion: "Коробочный звук справа, Коробочный звук слева" }),
        tpl(30, "Внебольничная пневмония", "[J18.9] Пневмония неуточненная", "Внебольничная правосторонняя нижнедолевая пневмония, средней тяжести. ДН 1 ст.", "На температуру 38.8°C, озноб, кашель с мокротой, боль в груди справа при вдохе, одышку.", "Болеет 4-й день, жаропонижающие с кратковременным эффектом.", { generalState: "Средней степени тяжести", bp: "120/75", pulse: "96", rr: "22", temp: "38.6", saturation: "94", lungs: "Ослабленное, Хрипы влажные, Хрипы справа", percussion: "Притупление звука справа" }),
        tpl(31, "ОРВИ / Гипертермический синдром", "[J06.9] Острая инфекция верхних дыхательных путей неуточненная", "ОРИ, ринофарингит. Гипертермический синдром.", "На температуру 39.0°C, озноб, ломоту в теле, боль в горле, насморк.", "Болеет 2-е сутки.", { generalState: "Средней степени тяжести", bp: "120/80", pulse: "92", rr: "18", temp: "38.9", skin: "Теплая, Сухая, Гиперемия", mucous: "Гиперемированы" }),
        tpl(32, "Ложный круп (стенозирующий ларинготрахеит)", "[J05.0] Острый обструктивный ларингит [круп]", "ОРИ. Острый стенозирующий ларинготрахеит, стеноз гортани 1-2 ст.", "На грубый лающий кашель, осиплость голоса, шумный вдох при беспокойстве.", "Ребенок проснулся ночью от лающего кашля и затрудненного вдоха.", { generalState: "Средней степени тяжести", bp: "100/60", pulse: "116", rr: "28", temp: "37.8", saturation: "96", lungs: "Жесткое", localStatus: "Голос осиплый, кашель лающий, инспираторный стридор при волнении." }),
        tpl(36, "Острый аппендицит", "[K35.8] Острый аппендицит другой и неуточненный", "Острый аппендицит.", "На постоянные боли в правой подвздошной области, тошноту, сухость во рту.", "Заболел 8 ч назад в эпигастрии, затем боли сместились в правую подвздошную область (с-м Кохера).", { generalState: "Средней степени тяжести", bp: "120/80", pulse: "88", temp: "37.5", abdomen: "Напряженный, Болезненный в области", tongue: "Сухой, обложен", peritoneal: "ДА", localStatus: "Болезненность и напряжение в правой подвздошной области. Симптомы Ровзинга, Ситковского, Щеткина-Блюмберга положительные." }),
        tpl(37, "Острый панкреатит", "[K85] Острый панкреатит", "Острый панкреатит.", "На опоясывающие боли в верхних отделах живота, многократную рвоту без облегчения.", "Ухудшение после погрешности в диете.", { generalState: "Средней степени тяжести", position: "ВЫНУЖДЕННОЕ", positionDescription: "На боку с поджатыми ногами", bp: "130/80", pulse: "92", temp: "37.1", abdomen: "Напряженный, Болезненный в области", localStatus: "Болезненность в эпигастрии и точке Мейо-Робсона." }),
        tpl(38, "Острый холецистит / Печеночная колика", "[K80.0] Камни желчного пузыря с острым холециститом", "ЖКБ: Острый калькулезный холецистит. Печеночная колика.", "На острые боли в правом подреберье с иррадиацией в правую лопатку, тошноту, горечь во рту.", "После жирной пищи 4 ч назад, спазмолитики без эффекта.", { generalState: "Средней степени тяжести", bp: "135/85", pulse: "86", temp: "37.3", abdomen: "Участвует в акте дыхания, Мягкий, Болезненный в области", localStatus: "Положительные симптомы Ортнера, Мерфи, Кера." }),
        tpl(42, "Желудочно-кишечное кровотечение (ЖКК)", "[K92.2] Желудочно-кишечное кровотечение неуточненное", "Состоявшееся желудочно-кишечное кровотечение. Постгеморрагическая анемия.", "На рвоту «кофейной гущей», черный дегтеобразный стул (мелена), резкую слабость, головокружение.", "Со вчерашнего вечера черный стул, утром рвота темным содержимым.", { generalState: "Тяжелое", position: "ПАССИВНОЕ", bp: "95/60", pulse: "108", rr: "20", temp: "36.4", saturation: "96", skin: "Холодная, Влажная, Бледная", localStatus: "Следы мелены." }),
        tpl(43, "Почечная колика (МКБ)", "[N20.0] Камни почки", "Мочекаменная болезнь. Правосторонняя почечная колика.", "На резкие схваткообразные боли в правой поясничной области с иррадиацией в пах, рези при мочеиспускании.", "Боли начались остро 2 часа назад.", { generalState: "Средней степени тяжести", position: "ВЫНУЖДЕННОЕ", positionDescription: "Мечется от боли", behavior: "ВОЗБУЖДЕННОЕ", bp: "140/90", pulse: "88", urination: "УЧАЩЕНО", localStatus: "Симптом поколачивания резко положительный справа." }),
        tpl(48, "Гипогликемическое состояние (купированное)", "[E16.2] Гипогликемия неуточненная", "Сахарный диабет 1 типа. Гипогликемическое состояние, купированное на этапе СМП.", "На умеренную слабость после введения глюкозы. До помощи — потливость, тремор, угнетение сознания.", "После инъекции инсулина пропустил прием пищи.", { generalState: "Средней степени тяжести", bp: "120/80", pulse: "84", glucose: "2.4" }),
        tpl(50, "Отек Квинке / Острая крапивница", "[T78.3] Ангионевротический отек", "Острая аллергическая реакция по типу ангионевротического отека Квинке и крапивницы.", "На отек губ и век, зудящие волдырные высыпания по телу.", "Через 30 минут после приема пищи/препарата появилась сыпь и отек лица.", { generalState: "Средней степени тяжести", bp: "120/75", pulse: "84", rash: "ДА", localStatus: "Плотный отек губ и век, уртикарная сыпь на коже туловища. Дыхание свободное, голос звучный." }),
        tpl(51, "Анафилактический шок", "[T78.2] Анафилактический шок неуточненный", "Анафилактический шок, гемодинамический вариант.", "На резкую слабость, потемнение в глазах, нехватку воздуха, чувство жара и зуд.", "Развился остро через 10 мин после инъекции препарата / укуса насекомого.", { generalState: "Тяжелое", consciousness: "Приглушенное", position: "ПАССИВНОЕ", behavior: "ВОЗБУЖДЕННОЕ", bp: "70/40", pulse: "118", rr: "24", saturation: "91", skin: "Холодная, Влажная, Бледная" }),
        tpl(52, "Острое алкогольное отравление", "[T51.0] Токсическое действие этанола", "Острое бытовое отравление этанолом тяжелой степени.", "Сбор жалоб затруднен ввиду угнетения сознания.", "Обнаружен после употребления алкоголя, резкий запах алкоголя изо рта.", { generalState: "Тяжелое", consciousness: "Сопор", position: "ПАССИВНОЕ", glasgowScore: "9", bp: "105/65", pulse: "92", rr: "14", temp: "36.0", saturation: "94", glucose: "4.5", speech: "НАРУШЕНА", gait: "НАРУШЕНА", skin: "Гиперемия, Влажная", alcohol: "ДА" }),
        tpl(53, "Отравление опиоидами", "[T40.2] Отравление другими опиоидами", "Острое отравление веществом опиоидного ряда. Кома 1-2 ст. ОДН.", "Контакт невозможен.", "Обнаружен без сознания с редким дыханием и точечными зрачками.", { generalState: "Тяжелое", consciousness: "Кома", position: "ПАССИВНОЕ", glasgowScore: "6", bp: "90/50", pulse: "56", rr: "6", saturation: "82", pupils: "МИОЗ", lightReaction: "НЕТ", speech: "ОТСУТСТВУЕТ", gait: "НЕ ОЦЕНИВАЛАСЬ", muscleTone: "Снижен", skin: "Бледная, Цианоз" }),
        tpl(56, "ЗЧМТ / Сотрясение головного мозга", "[S06.0] Сотрясение головного мозга", "ЗЧМТ: Сотрясение головного мозга. Ушиб мягких тканей головы.", "На головную боль, головокружение, тошноту, боль в месте ушиба.", "Упал на улице, ударился затылком, кратковременно терял сознание, была однократная рвота.", { generalState: "Средней степени тяжести", bp: "125/80", pulse: "76", nystagmus: "ГОРИЗОНТАЛЬНЫЙ", localStatus: "В затылочной области припухлость 3х3 см, болезненная. Мелкоразмашистый горизонтальный нистагм." }),
        tpl(58, "Перелом шейки бедра", "[S72.0] Перелом шейки бедра", "Закрытый перелом шейки левой бедренной кости со смещением.", "На боль в левом тазобедренном суставе, невозможность опоры на ногу.", "Упала дома на левый бок с высоты собственного роста.", { generalState: "Средней степени тяжести", position: "ВЫНУЖДЕННОЕ", positionDescription: "Лежа на спине, щадит левую ногу", bp: "140/85", pulse: "84", gait: "НАРУШЕНА", localStatus: "Левая нижняя конечность ротирована кнаружи, укорочена на 2 см, положительный с-м «прилипшей пятки». Пульсация на стопе сохранена." })
    ];

    // ==========================================
    // 4. DOM-УТИЛИТЫ И ТОЧНЫЙ АВТОПИЛОТ (БЕЗ ПРЫЖКОВ И БЕЗ ЗАКЛЮЧЕНИЯ)
    // ==========================================
    function getRealInputs(container) {
        return Array.from(container.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])'))
            .filter(el => el.offsetParent !== null && el.getAttribute('aria-hidden') !== 'true' && el.tabIndex !== -1 && !isInConclusion(el));
    }

    function findInputByKw(keywords, exact = false) {
        const all = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
        for (let kw of keywords) {
            const kwL = kw.toLowerCase();
            const target = all.find(el => {
                if (!el.innerText || el.offsetParent === null || el.children.length > 3 || isInConclusion(el)) return false;
                const t = el.innerText.trim().toLowerCase();
                return (exact ? t === kwL : (t === kwL || t.includes(kwL))) && t.length < kwL.length + 35;
            });
            if (target) {
                const fc = target.closest('.MuiFormControl-root');
                if (fc && !isInConclusion(fc)) {
                    const inps = getRealInputs(fc);
                    if (inps.length > 0) return inps[0];
                }
                let p = target.parentElement;
                for (let i = 0; i < 3 && p && p !== document.body && !isInConclusion(p); i++) {
                    const inps = getRealInputs(p);
                    if (inps.length === 1) return inps[0];
                    p = p.parentElement;
                }
            }
        }
        return null;
    }

    function scrapeCache() {
        const c = window.b103ScreenCache;
        const g = (k) => findInputByKw([k])?.value?.trim();
        if (g("экг до оказания")) c.electrocardiogramBefore = g("экг до оказания");
        if (g("экг после оказания")) c.electrocardiogramAfter = g("экг после оказания");
        if (g("предварительный диагноз")) c.diagnosis = g("предварительный диагноз");
        if (g("жалобы")) c.complaints = g("жалобы");
        if (g("анамнез заболевания")) c.anamnesis = g("анамнез заболевания");
        if (g("локальный статус")) c.localStatus = g("локальный статус");
    }

    // Точечная вставка с гарантированной защитой вкладки Заключение
    window.insertIntoCardField = function(fieldKeyword, textToInsert, appendMode) {
        try {
            const kwL = String(fieldKeyword).toLowerCase();
            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
            for (let l of labels) {
                if (!l.innerText || l.offsetParent === null || l.children.length > 3 || isInConclusion(l)) continue;
                const t = l.innerText.trim().toLowerCase();
                if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 35) {
                    const fc = l.closest('.MuiFormControl-root') || l.parentElement?.parentElement;
                    if (!fc || isInConclusion(fc)) continue;
                    const inps = getRealInputs(fc);
                    if (inps.length > 0) {
                        const inp = inps[0];
                        const cur = (inp.value || '').trim();
                        const finalVal = (appendMode && cur) ? (cur + "\n" + textToInsert) : textToInsert;
                        const proto = inp.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                        const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                        if (setter) setter.call(inp, finalVal); else inp.value = finalVal;
                        if (inp._valueTracker) inp._valueTracker.setValue('');
                        inp.dispatchEvent(new Event('input', { bubbles: true }));
                        inp.dispatchEvent(new Event('change', { bubbles: true }));
                        return true;
                    }
                }
            }
        } catch (e) {}
        return false;
    };

    window.applyAutofill = async function(jsonStr) {
        if (window.b103IsApplying) return;

        const savedScrollWinY = window.scrollY || document.documentElement.scrollTop || 0;
        const savedScrollContainers = Array.from(document.querySelectorAll('main, [class*="content"], [class*="scroll"], div'))
            .filter(el => el.scrollTop > 0)
            .map(el => ({ el, top: el.scrollTop }));

        const activeTabBefore = Array.from(document.querySelectorAll('[role="tab"], button'))
            .find(b => b.getBoundingClientRect().top < 140 && (b.getAttribute('aria-selected') === 'true' || b.className.includes('Mui-selected')));

        if (activeTabBefore && (activeTabBefore.innerText || '').toUpperCase().includes('ЗАКЛЮЧЕНИЕ')) return;

        function restoreScreenPosition() {
            window.scrollTo(0, savedScrollWinY);
            document.documentElement.scrollTop = savedScrollWinY;
            for (let item of savedScrollContainers) item.el.scrollTop = item.top;
        }

        window.b103IsApplying = true;
        const fillBtn = document.getElementById('btn-fill-db');
        if (fillBtn) fillBtn.innerText = "⏳";

        let hideStyle = document.getElementById('b103-hide-menus');
        if (!hideStyle) {
            hideStyle = document.createElement('style');
            hideStyle.id = 'b103-hide-menus';
            hideStyle.textContent = '.MuiPopover-root, .MuiModal-root, .MuiAutocomplete-popper { opacity: 0 !important; pointer-events: auto !important; transition: none !important; }';
            document.head.appendChild(hideStyle);
        }

        function getActivePopovers() {
            return Array.from(document.querySelectorAll('.MuiPopover-root, .MuiModal-root, .MuiAutocomplete-popper'))
                .filter(el => el.offsetParent !== null && el.getAttribute('aria-hidden') !== 'true');
        }

        async function closeAnyOpenMenu() {
            for (let attempt = 0; attempt < 5; attempt++) {
                const popovers = getActivePopovers();
                const expandedBtns = Array.from(document.querySelectorAll('[aria-expanded="true"]'));
                if (popovers.length === 0 && expandedBtns.length === 0) break;

                const escInit = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
                if (document.activeElement) {
                    document.activeElement.dispatchEvent(new KeyboardEvent('keydown', escInit));
                    document.activeElement.dispatchEvent(new KeyboardEvent('keyup', escInit));
                }
                for (let pop of popovers) {
                    const list = pop.querySelector('[role="listbox"], [role="menu"], ul, .MuiMenu-list') || pop;
                    list.dispatchEvent(new KeyboardEvent('keydown', escInit));
                    const backdrop = pop.querySelector('.MuiBackdrop-root') || (pop.firstElementChild?.classList.contains('MuiBackdrop-root') ? pop.firstElementChild : null);
                    if (backdrop) {
                        backdrop.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                        backdrop.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window, button: 0 }));
                        backdrop.click();
                    }
                }
                if (document.activeElement && document.activeElement !== document.body && document.activeElement.blur) document.activeElement.blur();
                document.body.click();
                restoreScreenPosition();
                await new Promise(r => setTimeout(r, 55));
            }
        }

        try {
            const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;

            function setVal(inp, val) {
                if (!inp || val === undefined || val === null || inp.disabled || isInConclusion(inp)) return;
                const str = String(val).trim();
                if (!str || inp.value.trim() === str) return;
                try {
                    const proto = inp.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                    if (setter) setter.call(inp, str); else inp.value = str;
                    if (inp._valueTracker) inp._valueTracker.setValue('');
                    inp.dispatchEvent(new Event('input', { bubbles: true }));
                    inp.dispatchEvent(new Event('change', { bubbles: true }));
                } catch (e) { inp.value = str; }
            }

            function findInputs(keywords, exact = false) {
                const res = [];
                const searchingFio = keywords.some(k => k.toLowerCase() === 'фио');
                const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
                for (let kw of keywords) {
                    const kwL = kw.toLowerCase();
                    for (let l of labels) {
                        if (!l.innerText || l.offsetParent === null || l.children.length > 3 || isInConclusion(l)) continue;
                        const t = l.innerText.trim().toLowerCase();
                        if ((exact ? t === kwL : (t === kwL || t.includes(kwL))) && t.length < kwL.length + 35) {
                            let foundInp = null;
                            const fc = l.closest('.MuiFormControl-root');
                            if (fc && !isInConclusion(fc)) {
                                const inps = getRealInputs(fc);
                                if (inps.length === 1) foundInp = inps[0];
                            }
                            if (!foundInp) {
                                let p = l.parentElement;
                                for (let i = 0; i < 3 && p && p !== document.body && !isInConclusion(p); i++) {
                                    const inps = getRealInputs(p);
                                    if (inps.length === 1) { foundInp = inps[0]; break; }
                                    else if (inps.length > 1) break;
                                    p = p.parentElement;
                                }
                            }
                            if (foundInp && !res.includes(foundInp)) {
                                const holderText = ((foundInp.closest('.MuiFormControl-root') || foundInp.parentElement)?.innerText || '').trim().toLowerCase();
                                if (!searchingFio && (holderText === 'фио' || holderText.startsWith('фио\n') || holderText.includes('фио пациента'))) continue;
                                if (searchingFio) {
                                    const rowTxt = (foundInp.closest('.MuiGrid-container') || foundInp.parentElement?.parentElement?.parentElement)?.innerText || '';
                                    if (rowTxt.includes('Должность') || rowTxt.includes('передачи мед.работнику')) continue;
                                }
                                res.push(foundInp);
                            }
                        }
                    }
                }
                return res;
            }

            const setAll = (kws, val, ex = false) => { if (val) findInputs(kws, ex).forEach(i => setVal(i, val)); };

            async function selectDropdown(keywords, valStr, forceMulti = false) {
                if (!valStr) return;
                const vals = String(valStr).split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
                if (vals.length === 0) return;

                const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
                const ctrls = [];
                for (let kw of keywords) {
                    const kwL = kw.toLowerCase();
                    for (let l of labels) {
                        if (!l.innerText || l.offsetParent === null || l.children.length > 3 || isInConclusion(l)) continue;
                        const t = l.innerText.trim().toLowerCase();
                        if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 35) {
                            let p = l.closest('.MuiFormControl-root, .MuiGrid-item') || l.parentElement;
                            for (let i = 0; i < 3 && p && p !== document.body && !isInConclusion(p); i++) {
                                if (p.querySelector('.MuiSelect-root, [role="button"]')) {
                                    if (!ctrls.includes(p)) ctrls.push(p);
                                    break;
                                }
                                p = p.parentElement;
                            }
                        }
                    }
                }

                for (let c of ctrls) {
                    const btn = c.querySelector('.MuiSelect-root, [role="button"]');
                    if (!btn || btn.offsetParent === null || btn.classList.contains('Mui-disabled') || isInConclusion(btn)) continue;
                    const isMulti = forceMulti || btn.classList.contains('MuiSelect-multiple') || vals.length > 1;
                    const curText = (btn.innerText || '').trim().toLowerCase();
                    if (!isMulti && curText === vals[0]) continue;
                    if (isMulti && vals.every(v => curText.includes(v))) continue;

                    await closeAnyOpenMenu();
                    btn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                    btn.click();
                    restoreScreenPosition();
                    await new Promise(r => setTimeout(r, 105));

                    const activePops = getActivePopovers();
                    const popover = activePops.length > 0 ? activePops[activePops.length - 1] : document;
                    const opts = Array.from(popover.querySelectorAll('li[role="option"], .MuiMenuItem-root'));
                    if (opts.length > 0) {
                        const hasCheckboxes = opts.some(o => !!o.querySelector('input[type="checkbox"], .MuiCheckbox-root'));
                        if (!(isMulti || hasCheckboxes)) {
                            const match = opts.find(o => {
                                const ot = (o.innerText || '').trim().toLowerCase();
                                return ot !== "не выбрано" && (ot === vals[0] || ot.includes(vals[0]) || vals[0].includes(ot));
                            });
                            if (match) { match.click(); restoreScreenPosition(); await new Promise(r => setTimeout(r, 85)); }
                            if (getActivePopovers().length > 0) await closeAnyOpenMenu();
                        } else {
                            const wanted = new Set();
                            for (let v of vals) {
                                const m = opts.find(o => {
                                    const ot = (o.innerText || '').trim().toLowerCase();
                                    return ot !== "не выбрано" && (ot === v || ot.includes(v));
                                });
                                if (m) wanted.add(m);
                            }
                            for (let o of opts) {
                                const shouldBe = wanted.has(o);
                                const isSel = o.classList.contains('Mui-selected') || o.getAttribute('aria-selected') === 'true' || !!o.querySelector('input[type="checkbox"]:checked');
                                if (shouldBe !== isSel) { o.click(); restoreScreenPosition(); await new Promise(r => setTimeout(r, 35)); }
                            }
                            await closeAnyOpenMenu();
                        }
                    } else {
                        await closeAnyOpenMenu();
                    }
                }
            }

            async function fillMkbAutocomplete(mkbStr) {
                if (!mkbStr) return;
                const codeMatch = String(mkbStr).trim().match(/\[?([A-ZА-Я]\d{2}(?:\.\d+)?)\]?/i);
                const searchCode = codeMatch ? codeMatch[1].toUpperCase() : String(mkbStr).trim();
                const mkbInputs = findInputs(["код по мкб"]);
                if (mkbInputs.length === 0) return;
                const inp = mkbInputs[0];
                if (inp.value && inp.value.toUpperCase().includes(searchCode)) return;
                await closeAnyOpenMenu();
                const autoRoot = inp.closest('.MuiAutocomplete-root') || inp.parentElement;
                const popupBtn = autoRoot ? autoRoot.querySelector('.MuiAutocomplete-popupIndicator, button') : null;
                try { inp.focus({ preventScroll: true }); } catch (e) { inp.focus(); }
                setVal(inp, searchCode);
                if (popupBtn) popupBtn.click();
                restoreScreenPosition();
                await new Promise(r => setTimeout(r, 210));
                const popper = document.querySelector('.MuiAutocomplete-popper, .MuiPopover-root');
                if (popper) {
                    const opts = Array.from(popper.querySelectorAll('li[role="option"], .MuiAutocomplete-option, .MuiMenuItem-root'));
                    const targetOpt = opts.find(o => (o.innerText || '').toUpperCase().includes(searchCode)) || opts[0];
                    if (targetOpt) { targetOpt.click(); restoreScreenPosition(); await new Promise(r => setTimeout(r, 75)); }
                }
                if (inp.blur) inp.blur();
                await closeAnyOpenMenu();
            }

            function clickButtonInRow(rowKeywords, buttonChoices) {
                if (!buttonChoices) return;
                const choices = (Array.isArray(buttonChoices) ? buttonChoices : [buttonChoices]).map(c => String(c).trim().toUpperCase()).filter(Boolean);
                const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span, div'));
                for (let kw of rowKeywords) {
                    const kwL = kw.toLowerCase();
                    const targets = labels.filter(el => el.innerText && el.offsetParent !== null && el.children.length <= 3 && !isInConclusion(el) && el.innerText.trim().toLowerCase().includes(kwL) && el.innerText.trim().length < kwL.length + 35);
                    for (let target of targets) {
                        let p = target.closest('.MuiFormControl-root, .MuiGrid-item') || target.parentElement;
                        for (let i = 0; i < 4 && p && p !== document.body && !isInConclusion(p); i++) {
                            const btns = Array.from(p.querySelectorAll('button, div[role="button"]')).filter(b => b.offsetParent !== null && !b.disabled && !isInConclusion(b));
                            const desired = btns.find(b => {
                                const bt = (b.innerText || '').trim().toUpperCase();
                                return choices.some(ch => bt === ch || (ch.includes("СИММЕТР") && ch.startsWith("А") && bt.startsWith("А") && bt.includes("СИМ")));
                            });
                            if (desired) {
                                const s = window.getComputedStyle(desired);
                                const isAlreadyOn = (s.color.includes('255, 255, 255') && !s.backgroundColor.includes('rgba(0, 0, 0, 0)')) || desired.className.includes('MuiButton-contained');
                                if (!isAlreadyOn) desired.click();
                                break;
                            }
                            p = p.parentElement;
                        }
                    }
                }
            }

            function fillBP(isAd, raw) {
                const str = cleanBp(raw); if (!str.includes('/')) return;
                const p = str.split('/');
                Array.from(document.querySelectorAll('*')).filter(e => e.offsetParent !== null && e.children.length === 0 && e.textContent.trim() === '/' && !isInConclusion(e)).forEach(sl => {
                    let pr = sl.parentElement;
                    for (let i = 0; i < 3 && pr && pr !== document.body; i++) {
                        const inps = getRealInputs(pr).filter(el => !isInConclusion(el));
                        if (inps.length === 2) {
                            const tx = (pr.innerText || pr.parentElement?.innerText || '').toLowerCase();
                            if (isAd ? tx.includes("адаптированное") : (tx.includes("ад") && !tx.includes("адаптированное"))) {
                                setVal(inps[0], p[0].trim()); setVal(inps[1], p[1].trim()); break;
                            }
                        }
                        pr = pr.parentElement;
                    }
                });
            }

            if (data.fio) setAll(["фио"], data.fio, true);
            if (data.gender) {
                const gUp = String(data.gender).trim().toUpperCase();
                const gTarget = (gUp.startsWith("М") || gUp === "TRUE") ? ["МУЖЧИНА", "МУЖСКОЙ"] : ["ЖЕНЩИНА", "ЖЕНСКИЙ"];
                clickButtonInRow(["пол"], gTarget);
            }
            if (data.birthDate) {
                const bInps = findInputs(["дата рождения"]);
                if (bInps.length > 0) setVal(bInps[0], data.birthDate);
            }
            if (data.age) setAll(["возраст"], data.age, true);
            await selectDropdown(["возраст (ед.)"], data.ageUnit || "Лет", false);
            if (data.address) setAll(["место жительства (пребывания)"], data.address);
            if (data.apo) await selectDropdown(["территориальная апо", "апо по месту жительства"], data.apo, false);

            setAll(["жалобы"], data.complaints);
            setAll(["анамнез заболевания"], data.anamnesis);
            setAll(["перенесенные заболевания"], data.pastDiseases);
            setAll(["предварительный диагноз"], data.diagnosis);
            setAll(["локальный статус"], data.localStatus);
            if (data.mkb) await fillMkbAutocomplete(data.mkb);

            if (data.bpAdapted) fillBP(true, data.bpAdapted);
            if (data.bp) fillBP(false, data.bp);
            if (data.pulse) setAll(["комментарий (пульс", "пульс, ударов в минуту"], data.pulse);
            if (data.rr) setAll(["частота дыхания (в минуту)"], data.rr);
            if (data.temp) setAll(["температура тела (°c)"], String(data.temp).replace(',', '.'));
            if (data.saturation) setAll(["сатурация 1 (%)"], data.saturation);
            if (data.glucose) setAll(["гликемия 1 (ммоль/л)"], String(data.glucose).replace(',', '.'));

            setAll(["экг до оказания"], data.ecgBefore);
            setAll(["экг после оказания"], data.ecgAfter);
            setAll(["шкала комы глазго (баллы)"], data.glasgowScore || "15");
            setAll(["тонус мышц"], data.muscleTone || "Нормальный");
            setAll(["патологические рефлексы"], data.pathologicalReflexes || "Отрицательные");
            setAll(["менингиальные знаки"], data.meningealSymptoms || "Отрицательные");
            setAll(["плегии, парезы"], data.plegia || "Нет");
            setAll(["язык"], data.tongue || "Чистые");
            setAll(["слизистые"], data.mucous || "Бледно-розовые");

            clickButtonInRow(["положение"], data.position || "АКТИВНОЕ");
            if (data.positionDescription) setAll(["комментарий (вынужденное)"], data.positionDescription);
            clickButtonInRow(["поведение"], data.behavior || "СПОКОЙНОЕ");
            if (data.pupils) clickButtonInRow(["зрачки"], [data.pupils, data.pupils.replace("АНИЗОКОРИЯ", "АНИЗОКАРИЯ")]);
            clickButtonInRow(["речь"], data.speech || "СОХРАНЕНА");
            clickButtonInRow(["лицо"], data.face || "СИММЕТРИЧНОЕ");
            clickButtonInRow(["походка"], data.gait || "СВОБОДНАЯ");
            clickButtonInRow(["нистагм"], data.nystagmus || "НЕТ");
            clickButtonInRow(["печень"], data.liver || "НЕ УВЕЛИЧЕНА");
            clickButtonInRow(["мочеиспускание"], data.urination || "НЕ НАРУШЕНО");
            clickButtonInRow(["стул"], data.stool || "НЕ НАРУШЕН");

            clickButtonInRow(["аллергия"], data.allergy || "НЕТ");
            clickButtonInRow(["реакция на свет"], data.lightReaction || "ДА");
            clickButtonInRow(["высыпания"], data.rash || "НЕТ");
            clickButtonInRow(["пульс, ударов в минуту"], data.pulseExists || (data.pulse === "0" ? "НЕТ" : "ДА"));
            clickButtonInRow(["асцит"], data.ascites || "НЕТ");
            clickButtonInRow(["периферические отеки"], data.edema || "НЕТ");
            if (data.edemaDescription) setAll(["комментарий (периферические отеки)"], data.edemaDescription);
            clickButtonInRow(["запах алкоголя"], data.alcohol || "НЕТ");
            clickButtonInRow(["запах ацетона"], data.acetone || "НЕТ");
            clickButtonInRow(["симптомы раздражения брюшины"], data.peritoneal || "НЕТ");
            clickButtonInRow(["перистальтика"], data.peristalsis || "ДА");
            clickButtonInRow(["получено устное согласие на простое медицинское вмешательство", "получено устное согласие"], "ДА");

            await selectDropdown(["общее состояние"], data.generalState || "Удовлетворительное", false);
            await selectDropdown(["сознание"], data.consciousness || "Ясное", false);
            if (data.skin) await selectDropdown(["кожа"], data.skin, true);
            if (data.heartSounds) await selectDropdown(["тоны сердца"], data.heartSounds, true);
            if (data.lungs) await selectDropdown(["дыхание"], data.lungs, true);
            if (data.percussion) await selectDropdown(["перкуссия легких"], data.percussion, true);
            if (data.abdomen) await selectDropdown(["живот"], data.abdomen, true);

            await closeAnyOpenMenu();
            restoreScreenPosition();
            showToast("⚡ Карта заполнена!");
        } catch (e) { console.error(e); } finally {
            await new Promise(r => setTimeout(r, 70));
            await closeAnyOpenMenu();
            if (activeTabBefore) {
                const curActive = Array.from(document.querySelectorAll('[role="tab"], button'))
                    .find(b => b.getBoundingClientRect().top < 140 && (b.getAttribute('aria-selected') === 'true' || b.className.includes('Mui-selected')));
                if (curActive !== activeTabBefore) activeTabBefore.click();
            }
            restoreScreenPosition();
            setTimeout(restoreScreenPosition, 50);
            document.getElementById('b103-hide-menus')?.remove();
            if (fillBtn) fillBtn.innerText = "⚡";
            setTimeout(() => { window.b103IsApplying = false; }, 200);
        }
    };

    // ==========================================
    // 5. МОДАЛЬНЫЕ ОКНА И ИНТЕРФЕЙС ДЛЯ iOS SAFARI
    // ==========================================
    function openIosSheet(title, colorHex, renderContentFn) {
        document.getElementById('b103-ios-sheet-backdrop')?.remove();
        const backdrop = document.createElement('div');
        backdrop.id = 'b103-ios-sheet-backdrop';
        backdrop.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.52);z-index:100000;display:flex;flex-direction:column;justify-content:flex-end;font-family:-apple-system,BlinkMacSystemFont,Roboto,sans-serif;';

        const sheet = document.createElement('div');
        sheet.style.cssText = 'background:#fff;border-radius:22px 22px 0 0;max-height:88vh;display:flex;flex-direction:column;padding:14px 18px 24px 18px;box-shadow:0 -4px 20px rgba(0,0,0,0.25);overflow:hidden;';

        sheet.innerHTML = `
            <div style="width:44px;height:5px;background:#B0BEC5;border-radius:3px;margin:0 auto 10px auto;"></div>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;">
                <div style="font-size:18px;font-weight:700;color:${colorHex};">${title}</div>
                <button id="b103-sheet-close" style="border:none;background:#ECEFF1;color:#37474F;width:32px;height:32px;border-radius:50%;font-size:16px;font-weight:bold;cursor:pointer;">✕</button>
            </div>
            <div id="b103-sheet-body" style="flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;"></div>
        `;
        backdrop.appendChild(sheet);
        document.body.appendChild(backdrop);

        backdrop.addEventListener('click', (e) => { if (e.target === backdrop) backdrop.remove(); });
        sheet.querySelector('#b103-sheet-close').onclick = () => backdrop.remove();
        renderContentFn(sheet.querySelector('#b103-sheet-body'), () => backdrop.remove());
    }

    // ОКОШКО АВТОЗАПОЛНЕНИЯ И ПОИСКА ШАБЛОНОВ/АРХИВА
    function openAutofillSheet() {
        openIosSheet('⚡ Автозаполнение и Помощник', '#0288D1', (body, close) => {
            body.innerHTML = `
                <button id="ios-open-helper" style="width:100%;padding:13px;border:none;border-radius:12px;background:#00897B;color:#fff;font-weight:700;font-size:13.5px;margin-bottom:10px;cursor:pointer;">🧰 ОТКРЫТЬ ПОМОЩНИК СМП / ЭКГ / СЛР / ДОЗЫ</button>
                <div style="display:flex;gap:8px;margin-bottom:10px;">
                    <button id="tab-tpl" style="flex:1;padding:11px;border:none;border-radius:11px;background:#00796B;color:#fff;font-weight:700;font-size:13px;">⭐ Образцы (${BUILTIN_TEMPLATES.length})</button>
                    <button id="tab-arch" style="flex:1;padding:11px;border:none;border-radius:11px;background:#E0F2F1;color:#004D40;font-weight:600;font-size:13px;">📂 Мой архив</button>
                </div>
                <input id="ios-search-inp" type="text" placeholder="Поиск по диагнозу или фамилии..." style="width:100%;box-sizing:border-box;padding:12px 14px;border-radius:12px;border:1.5px solid #90CAF9;background:#F0F8FF;color:#111;font-size:14px;margin-bottom:10px;outline:none;">
                <div id="ios-list-box" style="display:flex;flex-direction:column;gap:8px;"></div>
            `;
            let mode = 'tpl';
            const listBox = body.querySelector('#ios-list-box');
            const inp = body.querySelector('#ios-search-inp');
            const tTpl = body.querySelector('#tab-tpl');
            const tArch = body.querySelector('#tab-arch');

            body.querySelector('#ios-open-helper').onclick = () => { close(); openHelperMenuSheet(); };

            function render() {
                const q = inp.value.trim().toLowerCase();
                listBox.innerHTML = '';
                if (mode === 'tpl') {
                    const custom = loadDb().filter(x => x.is_builtin === 2).map(x => {
                        let d = {}; try { d = JSON.parse(x.json_data); } catch (e) {}
                        return { title: x.fio, mkb: d.mkb || d.diagnosis || '', jsonData: x.json_data, isCustom: true };
                    });
                    const all = [...custom, ...BUILTIN_TEMPLATES].filter(x => !q || x.title.toLowerCase().includes(q) || x.mkb.toLowerCase().includes(q));
                    all.forEach(item => {
                        const row = document.createElement('div');
                        row.style.cssText = 'padding:13px 15px;border-radius:13px;background:#FFFDE7;border:1.5px solid #FFE082;color:#263238;font-size:13.5px;cursor:pointer;';
                        row.innerHTML = `<b>${item.isCustom ? '📌' : '⭐'} ${item.title}</b><br><span style="color:#546E7A;font-size:12.5px;">📋 ${item.mkb}</span>`;
                        row.onclick = () => { close(); window.activeAutofillData = JSON.parse(item.jsonData); window.applyAutofill(window.activeAutofillData); };
                        listBox.appendChild(row);
                    });
                } else {
                    const arch = loadDb().filter(x => x.is_builtin === 0 && (!q || x.fio.includes(q) || x.json_data.toLowerCase().includes(q)));
                    if (arch.length === 0) {
                        listBox.innerHTML = '<div style="padding:20px;text-align:center;color:#607D8B;">Архив пуст. Нажмите 💾 внутри карты для сохранения.</div>';
                        return;
                    }
                    arch.forEach(item => {
                        let d = {}; try { d = JSON.parse(item.json_data); } catch (e) {}
                        const row = document.createElement('div');
                        row.style.cssText = 'padding:13px 15px;border-radius:13px;background:#F0F8FF;border:1.5px solid #BBDEFB;color:#263238;font-size:13.5px;cursor:pointer;';
                        row.innerHTML = `<b>👤 ${item.fio.toUpperCase()}</b> <span style="font-size:12px;color:#0277BD;">(${new Date(item.timestamp).toLocaleDateString()})</span><br><span style="color:#37474F;font-size:12.5px;">📋 ${d.diagnosis || d.callReason || 'Без диагноза'}</span>`;
                        row.onclick = () => { close(); window.activeAutofillData = d; window.applyAutofill(d); };
                        listBox.appendChild(row);
                    });
                }
            }

            tTpl.onclick = () => { mode = 'tpl'; tTpl.style.background = '#00796B'; tTpl.style.color = '#fff'; tArch.style.background = '#E0F2F1'; tArch.style.color = '#004D40'; render(); };
            tArch.onclick = () => { mode = 'arch'; tArch.style.background = '#00796B'; tArch.style.color = '#fff'; tTpl.style.background = '#E0F2F1'; tTpl.style.color = '#004D40'; render(); };
            inp.oninput = render;
            render();
        });
    }

    // ПОДМЕНЮ ПОМОЩНИКА СМП НА iOS
    function openHelperMenuSheet() {
        openIosSheet('🧰 Помощник СМП (iOS)', '#0277BD', (body) => {
            const tools = [
                { t: '📈 КОНСТРУКТОР ЭКГ (В КАРТУ)', c: '#00897B', fn: openEcgBuilderIos },
                { t: '⏱️ ТАЙМЕР И ПРОТОКОЛ СЛР', c: '#D32F2F', fn: openCprTimerIos },
                { t: '📝 ФРАЗЫ: ОТКАЗЫ И ПРИМЕЧАНИЯ', c: '#5D4037', fn: openQuickPhrasesIos },
                { t: '👶 ПЕДИАТРИЯ: НОРМЫ, ДОЗЫ, ИВЛ (№118)', c: '#0277BD', fn: openPediatricsIos },
                { t: '📋 КАЛЬКУЛЯТОРЫ (% МГ, ДОПАМИН, O2 МПа)', c: '#EF6C00', fn: openCalculatorsIos },
                { t: '🤰 ШКАЛА АПГАР И АКУШЕРСТВО', c: '#D81B60', fn: openApgarIos },
                { t: '📜 ТЛТ, ЧКВ И ПРИКАЗЫ МЗ РБ', c: '#37474F', fn: openProtocolsIos }
            ];
            tools.forEach(item => {
                const b = document.createElement('button');
                b.style.cssText = `width:100%;padding:15px;border:none;border-radius:12px;background:${item.c};color:#fff;font-weight:700;font-size:14px;margin-bottom:10px;cursor:pointer;`;
                b.innerText = item.t;
                b.onclick = () => item.fn();
                body.appendChild(b);
            });
        });
    }

    function openEcgBuilderIos() {
        openIosSheet('📈 Конструктор ЭКГ', '#00897B', (body, close) => {
            body.innerHTML = `
                <textarea id="ios-ecg-out" rows="3" style="width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:2px solid #26A69A;background:#E0F2F1;color:#004D40;font-weight:700;font-size:14px;margin-bottom:10px;">Ритм синусовый, правильный. ЧСС 78 в мин. ЭОС нормальная. Острой очаговой патологии нет.</textarea>
                <div style="display:flex;gap:8px;margin-bottom:12px;">
                    <button id="ecg-ins-1" style="flex:1;padding:12px;border:none;border-radius:10px;background:#00796B;color:#fff;font-weight:700;font-size:12.5px;">⚡ В ЭКГ ДО ПОМОЩИ</button>
                    <button id="ecg-ins-2" style="flex:1;padding:12px;border:none;border-radius:10px;background:#0277BD;color:#fff;font-weight:700;font-size:12.5px;">⚡ В ЭКГ ПОСЛЕ</button>
                </div>
                <div id="ecg-chips" style="display:flex;flex-wrap:wrap;gap:6px;"></div>
            `;
            const out = body.querySelector('#ios-ecg-out');
            const chips = [
                "Ритм синусовый, правильный.", "Синусовая тахикардия.", "Синусовая брадикардия.",
                "Фибрилляция предсердий, тахисистолия.", "ЭОС отклонена влево.", "Признаки ГЛЖ.",
                "Полная БПНПГ.", "Полная БЛНПГ.", "АВ-блокада I ст.", "Одиночная ЖЭС.",
                "Депрессия ST до 1.5 мм в V4-V6.", "Элевация ST 3 мм в V1-V4.", "Элевация ST в II, III, aVF."
            ];
            const box = body.querySelector('#ecg-chips');
            chips.forEach(txt => {
                const c = document.createElement('span');
                c.style.cssText = 'padding:8px 12px;border-radius:14px;background:#E0F2F1;color:#004D40;font-size:12.5px;font-weight:600;cursor:pointer;';
                c.innerText = '+ ' + txt;
                c.onclick = () => { out.value = (out.value.replace('Острой очаговой патологии нет.', '').trim() + ' ' + txt).trim(); };
                box.appendChild(c);
            });
            body.querySelector('#ecg-ins-1').onclick = () => { window.insertIntoCardField('экг до оказания', out.value, false); copyText(out.value); close(); };
            body.querySelector('#ecg-ins-2').onclick = () => { window.insertIntoCardField('экг после оказания', out.value, false); copyText(out.value); close(); };
        });
    }

    // Состояние СЛР на iOS (не сбрасывается при закрытии шторки)
    window.b103CprState = window.b103CprState || { isRunning: false, startEpoch: 0, accumulatedSec: 0, adr: 0, shock: 0, log: [] };

    function openCprTimerIos() {
        openIosSheet('⏱️ Таймер и логгер СЛР (iOS)', '#D32F2F', (body, close) => {
            const st = window.b103CprState;
            body.innerHTML = `
                <div id="cpr-banner" style="padding:16px;border-radius:16px;background:#C62828;color:#fff;font-weight:700;font-size:18px;text-align:center;margin-bottom:12px;"></div>
                <div style="display:flex;gap:8px;margin-bottom:10px;">
                    <button id="cpr-btn-start" style="flex:1.5;padding:14px;border:none;border-radius:12px;background:#2E7D32;color:#fff;font-weight:700;font-size:14px;"></button>
                    <button id="cpr-btn-rosc" style="flex:1;padding:14px;border:none;border-radius:12px;background:#0277BD;color:#fff;font-weight:700;font-size:13px;">❤️ ВСК/ФИНАЛ</button>
                    <button id="cpr-btn-reset" style="padding:14px 18px;border:none;border-radius:12px;background:#ECEFF1;color:#37474F;font-weight:700;font-size:16px;">🔄</button>
                </div>
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px;">
                    <button id="cpr-add-adr" style="padding:11px;border:1px solid #FFCDD2;border-radius:10px;background:#FFEBEE;color:#B71C1C;font-weight:700;font-size:12.5px;">💉 +Адреналин 1 мг</button>
                    <button id="cpr-add-shock" style="padding:11px;border:1px solid #FFCDD2;border-radius:10px;background:#FFEBEE;color:#B71C1C;font-weight:700;font-size:12.5px;">⚡ +Разряд 200 Дж</button>
                </div>
                <div id="cpr-log-box" style="padding:12px;background:#F5F7F8;border-radius:12px;font-size:13px;line-height:1.45;color:#263238;height:120px;overflow-y:auto;white-space:pre-line;margin-bottom:10px;"></div>
                <button id="cpr-btn-insert" style="width:100%;padding:14px;border:none;border-radius:12px;background:#00695C;color:#fff;font-weight:700;font-size:13.5px;cursor:pointer;">⚡ ВСТАВИТЬ ПРОТОКОЛ СЛР В КАРТУ</button>
            `;
            const banner = body.querySelector('#cpr-banner');
            const btnStart = body.querySelector('#cpr-btn-start');
            const btnRosc = body.querySelector('#cpr-btn-rosc');
            const btnReset = body.querySelector('#cpr-btn-reset');
            const logBox = body.querySelector('#cpr-log-box');

            function getSec() {
                return st.isRunning ? (st.accumulatedSec + Math.floor((Date.now() - st.startEpoch)/1000)) : st.accumulatedSec;
            }

            function update() {
                const sTotal = getSec();
                const m = Math.floor(sTotal / 60);
                const s = sTotal % 60;
                const isRhythm = (sTotal % 120 === 0 && sTotal > 0);
                const isAdr = (sTotal % 180 === 0 && sTotal > 0);

                let hint = "НМС 100–120/мин, 30:2";
                if (isRhythm && isAdr) hint = "🔔 2 МИН: Контроль ритма! + 💉 Адреналин 1 мг!";
                else if (isRhythm) hint = "🔔 2 МИНУТЫ! Оцените ритм на ЭКГ!";
                else if (isAdr) hint = "💉 Прошло 3 мин! Время ввести Адреналин 1 мг!";

                banner.style.background = ((isRhythm || isAdr) && st.isRunning) ? '#E65100' : (st.isRunning ? '#C62828' : '#78909C');
                banner.innerHTML = `⏱️ СЛР: ${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')} (Адр: ${st.adr} | Разряд: ${st.shock})<br><span style="font-size:13px;font-weight:600;">${hint}</span>`;
                btnStart.innerText = st.isRunning ? "⏸️ ПАУЗА" : (sTotal === 0 ? "▶️ СТАРТ СЛР" : "▶️ ПРОДОЛЖИТЬ");
                btnStart.style.background = st.isRunning ? "#F57C00" : "#2E7D32";
                logBox.innerText = st.log.length ? st.log.join('\n') : "Журнал реанимации пуст...";
            }

            function addLog(txt) {
                const sTotal = getSec();
                st.log.push(`[${new Date().toLocaleTimeString()} | ${Math.floor(sTotal/60)}:${String(sTotal%60).padStart(2,'0')}] ${txt}`);
                update();
            }

            btnStart.onclick = () => {
                if (!st.isRunning) {
                    st.isRunning = true;
                    st.startEpoch = Date.now();
                    if (getSec() === 0 && !st.log.length) addLog("Начата расширенная СЛР (НМС, ИВЛ, ЭКГ, в/в доступ)");
                } else {
                    st.accumulatedSec += Math.floor((Date.now() - st.startEpoch)/1000);
                    st.isRunning = false;
                }
                update();
            };

            btnRosc.onclick = () => {
                if (st.isRunning) {
                    st.accumulatedSec += Math.floor((Date.now() - st.startEpoch)/1000);
                    st.isRunning = false;
                }
                addLog(`Завершение СЛР. Итого: Адреналин — ${st.adr} мг, Разрядов — ${st.shock}.`);
                update();
            };

            btnReset.onclick = () => {
                if (confirm("Сбросить протокол СЛР для нового вызова?")) {
                    st.isRunning = false; st.startEpoch = 0; st.accumulatedSec = 0; st.adr = 0; st.shock = 0; st.log = [];
                    update();
                }
            };

            body.querySelector('#cpr-add-adr').onclick = () => { st.adr++; addLog(`Введен Адреналин 1 мг в/в (#${st.adr})`); };
            body.querySelector('#cpr-add-shock').onclick = () => { st.shock++; addLog(`Дефибрилляция 200 Дж (#${st.shock})`); };
            body.querySelector('#cpr-btn-insert').onclick = () => {
                const summary = "Хронология СЛР:\n" + st.log.join('; ');
                window.insertIntoCardField('анамнез заболевания', summary, true);
                copyText(summary);
                close();
            };

            const timerInt = setInterval(update, 1000);
            update();
            body.closest('#b103-ios-sheet-backdrop').addEventListener('DOMNodeRemoved', () => clearInterval(timerInt));
        });
    }

    function openQuickPhrasesIos() {
        openIosSheet('📝 Быстрые фразы и Отказы', '#5D4037', (body, close) => {
            const list = [
                { t: '📝 Согласие + Спецсигналы', k: 'примечания', v: 'Дано устное согласие на медицинское вмешательство. Транспортировка на носилках со спецсигналами. Мониторинг АД, ЧСС, SpO2 в пути.' },
                { t: '✋ Юридический отказ от госпитализации', k: 'примечания', v: 'От предложенной госпитализации в стационар пациент в ясном сознании категорически отказался. В доступной форме разъяснены возможные последствия отказа, в т.ч. риск внезапного ухудшения и летального исхода. Даны рекомендации.' },
                { t: '🪑 Отказ от носилок', k: 'примечания', v: 'От предложенной транспортировки на носилках пациент категорически отказался, до автомобиля СМП прошел в сопровождении бригады.' },
                { t: '🩹 Локальный статус: Неврология норма', k: 'локальный статус', v: 'В сознании, контактен, ориентирован верно. Зрачки D=S, фотореакция живая. Нистагма нет. Лицо симметричное, язык по средней линии. Менингеальных знаков и парезов нет.' }
            ];
            list.forEach(item => {
                const d = document.createElement('div');
                d.style.cssText = 'padding:14px;border-radius:12px;background:#EFEBE9;border:1px solid #D7CCC8;margin-bottom:8px;cursor:pointer;font-size:13.5px;color:#263238;';
                d.innerHTML = `<b>${item.t}</b><br><span style="font-size:12.5px;color:#4E342E;">${item.v}</span>`;
                d.onclick = () => { window.insertIntoCardField(item.k, item.v, true); copyText(item.v); close(); };
                body.appendChild(d);
            });
        });
    }

    function openPediatricsIos() {
        openIosSheet('👶 Педиатрия: Дозы и ИВЛ (Пост. №118)', '#0277BD', (body) => {
            body.innerHTML = `
                <div style="font-size:13px;font-weight:700;color:#37474F;margin-bottom:4px;">⚖️ Вес ребенка (кг):</div>
                <input id="ios-ped-w" type="number" value="10" style="width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:1.5px solid #29B6F6;background:#E1F5FE;color:#111;font-size:15px;font-weight:700;margin-bottom:12px;">
                <div id="ios-ped-out" style="font-size:13.8px;line-height:1.5;color:#263238;white-space:pre-line;background:#F5F7F8;padding:14px;border-radius:12px;"></div>
            `;
            const wInp = body.querySelector('#ios-ped-w');
            const out = body.querySelector('#ios-ped-out');
            function calc() {
                const w = parseFloat(wInp.value) || 10;
                const f = (n) => Number(n.toFixed(2));
                out.innerText =
                    `🫁 ИВЛ И ДЕФИБРИЛЛЯЦИЯ НА ${w} КГ:\n` +
                    `• Дыхательный объем ИВЛ (6–8 мл/кг): ${Math.round(w*6)} – ${Math.round(w*8)} мл\n` +
                    `• Разряд дефибрилляции: 1-й = ${Math.round(w*2)} Дж (2 Дж/кг), далее ${Math.round(w*4)} Дж\n\n` +
                    `💉 ЭКСТРЕННЫЕ ДОЗЫ НА ${w} КГ (Пост. №118):\n` +
                    `• Адреналин 0,18% (СЛР / Анафилаксия): ${f(Math.min(0.3, w*0.01))} мл (макс. 0.3 мл детям!)\n` +
                    `• Преднизолон (30 мг/мл): ${f(Math.min(90, w*2))} мг = ${f(Math.min(3, w*2/30))} мл\n` +
                    `• Дексаметазон (4 мг/мл): ${f(Math.min(16, w*0.4))} мг = ${f(Math.min(4, w*0.1))} мл\n` +
                    `• Диазепам 0,5% (судороги): ${f(Math.min(2, w*0.05))} мл\n` +
                    `• Анальгин 50%: ${f(Math.min(2, w*0.02))} мл | Димедрол 1%: ${f(Math.min(1, w*0.02))} мл\n` +
                    `• Болюс NaCl 0,9% при шоке: ${Math.round(w*20)} мл (20 мл/кг)\n` +
                    `• Парацетамол: ${Math.round(Math.min(500, w*15))} мг | Ибупрофен: ${Math.round(Math.min(400, w*10))} мг`;
            }
            wInp.oninput = calc; calc();
        });
    }

    function openCalculatorsIos() {
        openIosSheet('📋 Расчеты: Растворы %, Допамин, O2 (МПа)', '#EF6C00', (body) => {
            body.innerHTML = `
                <div style="font-weight:700;color:#E65100;margin-bottom:6px;">🧮 1. Вещество в мг из % раствора (1% = 10 мг/мл)</div>
                <div style="display:flex;gap:8px;margin-bottom:8px;">
                    <input id="c-pct" type="number" placeholder="Раствор % (напр. 25)" style="flex:1;padding:10px;border-radius:10px;border:1px solid #FFB74D;background:#FFF3E0;color:#111;">
                    <input id="c-ml" type="number" placeholder="Объем мл (напр. 10)" style="flex:1;padding:10px;border-radius:10px;border:1px solid #FFB74D;background:#FFF3E0;color:#111;">
                </div>
                <div id="c-pct-out" style="padding:10px;background:#FFF8E1;border-radius:10px;font-size:13px;margin-bottom:14px;color:#263238;">Введите % и объем в мл...</div>

                <div style="font-weight:700;color:#C62828;margin-bottom:6px;">💧 2. Калькулятор Допамина (200 мг = 5 мл 4%)</div>
                <div style="display:flex;gap:8px;margin-bottom:8px;">
                    <input id="c-dw" type="number" value="70" placeholder="Вес кг" style="flex:1;padding:10px;border-radius:10px;border:1px solid #EF9A9A;background:#FFEBEE;color:#111;">
                    <input id="c-dd" type="number" value="5" placeholder="мкг/кг/мин" style="flex:1;padding:10px;border-radius:10px;border:1px solid #EF9A9A;background:#FFEBEE;color:#111;">
                </div>
                <div id="c-dop-out" style="padding:10px;background:#FFF8F8;border-radius:10px;font-size:13px;margin-bottom:14px;color:#263238;white-space:pre-line;"></div>

                <div style="font-weight:700;color:#0277BD;margin-bottom:6px;">🫁 3. Запас кислорода в баллоне (в МПа: 1л, 2л, 10л)</div>
                <div style="display:flex;gap:8px;margin-bottom:8px;">
                    <input id="c-vol" type="number" value="2" placeholder="Баллон (1, 2, 10 л)" style="flex:1;padding:10px;border-radius:10px;border:1px solid #4FC3F7;background:#E1F5FE;color:#111;">
                    <input id="c-mpa" type="number" value="15" placeholder="Давление МПа" style="flex:1;padding:10px;border-radius:10px;border:1px solid #4FC3F7;background:#E1F5FE;color:#111;">
                    <input id="c-fl" type="number" value="6" placeholder="Поток л/мин" style="flex:1;padding:10px;border-radius:10px;border:1px solid #4FC3F7;background:#E1F5FE;color:#111;">
                </div>
                <div id="c-o2-out" style="padding:10px;background:#E1F5FE;border-radius:10px;font-size:13px;color:#263238;white-space:pre-line;"></div>
            `;
            const calcAll = () => {
                const p = parseFloat(body.querySelector('#c-pct').value);
                const m = parseFloat(body.querySelector('#c-ml').value) || 1;
                if (!isNaN(p)) body.querySelector('#c-pct-out').innerText = `💉 В 1 мл = ${p*10} мг | В ${m} мл = ${p*10*m} мг вещества`;

                const w = parseFloat(body.querySelector('#c-dw').value) || 70;
                const d = parseFloat(body.querySelector('#c-dd').value) || 5;
                const mcg = w * d;
                body.querySelector('#c-dop-out').innerText = `Скорость: ${Math.round(mcg)} мкг/мин\n• На 200 мл NaCl: ${Math.round(mcg/50)} кап/мин | На 400 мл: ${Math.round(mcg/25)} кап/мин\n• Дозатор (200 мг в 20 мл): ${((mcg*60)/10000).toFixed(2)} мл/ч`;

                const vol = parseFloat(body.querySelector('#c-vol').value) || 2;
                const mpa = parseFloat(body.querySelector('#c-mpa').value) || 15;
                const fl = parseFloat(body.querySelector('#c-fl').value) || 6;
                const totalL = mpa * 10 * vol;
                const mins = fl > 0 ? Math.round(totalL / fl) : 0;
                body.querySelector('#c-o2-out').innerText = `🛢️ Баллон ${vol} л при ${mpa} МПа (${mpa*10} атм) = ${Math.round(totalL)} л O2\n⏱️ При потоке ${fl} л/мин хватит на: ${mins} мин (${Math.floor(mins/60)} ч ${mins%60} мин)`;
            };
            body.querySelectorAll('input').forEach(i => i.oninput = calcAll);
            calcAll();
        });
    }

    function openApgarIos() {
        openIosSheet('🤰 Шкала Апгар и Акушерство', '#D81B60', (body) => {
            body.innerHTML = `<div style="font-size:13.8px;line-height:1.55;color:#263238;white-space:pre-line;">
                <b>👶 ШКАЛА АПГАР (0 / 1 / 2 балла):</b>
                • Окраска кожи: Бледная (0) | Акроцианоз (1) | Розовая вся (2)
                • ЧСС: Нет (0) | < 100/мин (1) | >= 100/мин (2)
                • Рефлексы: Нет (0) | Гримаса (1) | Крик/кашель (2)
                • Мышечный тонус: Атония (0) | Снижен (1) | Активные движения (2)
                • Дыхание: Нет (0) | Нерегулярное (1) | Громкий крик (2)
                <i>8–10 б — норма; 6–7 б — легкая асфиксия; 4–5 б — средняя; 0–3 б — тяжелая.</i>

                <b>🤲 ПОСОБИЕ ПО ЦОВЬЯНОВУ I (чисто ягодичное):</b>
                Удерживать ножки плода вытянутыми вдоль туловища (большие пальцы прижимают ножки к животу, 4 пальца — на крестце), чтобы ножки прижимали ручки к груди и сохраняли сгибание головки.

                <b>🦶 ПОСОБИЕ ПО ЦОВЬЯНОВУ II (ножное предлежание):</b>
                Стерильной салфеткой ладонью оказывать противодействие рождающимся стопам во время потуг, пока плод не «присядет на корточки» и не произойдет полное раскрытие шейки матки.
            </div>`;
        });
    }

    function openProtocolsIos() {
        openIosSheet('📜 ТЛТ, ЧКВ и Приказы МЗ РБ', '#37474F', (body) => {
            body.innerHTML = `<div style="font-size:13.5px;line-height:1.5;color:#263238;white-space:pre-line;">
                <b>💉 ТЛТ ПРИ ОКС (Показания — все ДА):</b>
                1. Ишемическая боль > 30 мин, не купирующаяся нитратами.
                2. Элевация ST >= 1 мм в 2 смежных отведениях от конечностей или >= 2 мм в грудных (или новая БЛНПГ).
                3. Время <= 12 часов от начала болей.

                <b>🧠 ТЛТ ПРИ ОНМК (в стационаре):</b>
                • Возраст >= 18 лет, ишемический инсульт, терапевтическое окно <= 4,5 часов, АД < 185/110 мм рт. ст., гликемия 2.8–22.5 ммоль/л.
                • Коррекция АД на СМП при инсульте: снижать плавно на 10–15% только при АД >= 220/120 (или > 185/110 в окне ТЛТ)!

                <b>⚡ АНАФИЛАКСИЯ (Пост. № 50 / № 99):</b>
                1. Эпинефрин 0,18% в/м в переднебоковую поверхность бедра: взрослым 0,3–0,5 мл, детям 0,01 мл/кг (макс. 0.3 мл, повтор через 5–10 мин).
                2. Инфузия NaCl 0,9% 500–1000 мл (детям 20 мл/кг), O2.
                3. Преднизолон 90–120 мг в/в (детям 2–5 мг/кг).
            </div>`;
        });
    }

    // ГЛАВНОЕ МЕНЮ НА iOS (ИМПОРТ / ЭКСПОРТ / АРХИВ)
    function openMainMenuIos() {
        openIosSheet('Меню SSMP Plus (iOS)', '#008577', (body, close) => {
            body.innerHTML = `
                <button id="m-samples" style="width:100%;padding:15px;border:none;border-radius:12px;background:#008577;color:#fff;font-weight:700;font-size:14px;margin-bottom:10px;">⭐ ОБРАЗЦЫ И АРХИВ ВЫЗОВОВ</button>
                <button id="m-helper" style="width:100%;padding:15px;border:none;border-radius:12px;background:#0277BD;color:#fff;font-weight:700;font-size:14px;margin-bottom:10px;">🧰 ПОМОЩНИК СМП (ЭКГ, ДОЗЫ, ШКАЛЫ)</button>
                <div style="display:flex;gap:10px;">
                    <button id="m-imp" style="flex:1;padding:14px;border:none;border-radius:12px;background:#00695C;color:#fff;font-weight:700;font-size:13.5px;">📥 ИМПОРТ JSON</button>
                    <button id="m-exp" style="flex:1;padding:14px;border:none;border-radius:12px;background:#00695C;color:#fff;font-weight:700;font-size:13.5px;">📤 ЭКСПОРТ JSON</button>
                </div>
                <input id="ios-file-inp" type="file" accept=".json,application/json" style="display:none;">
            `;
            body.querySelector('#m-samples').onclick = () => { close(); openAutofillSheet(); };
            body.querySelector('#m-helper').onclick = () => { close(); openHelperMenuSheet(); };
            body.querySelector('#m-exp').onclick = () => {
                const data = JSON.stringify(loadDb());
                const blob = new Blob([data], { type: 'application/json' });
                const a = document.createElement('a');
                a.href = URL.createObjectURL(blob);
                a.download = `ssmp_archive_${new Date().toISOString().slice(0,10)}.json`;
                a.click();
                showToast('📤 Архив экспортирован!');
            };
            const fInp = body.querySelector('#ios-file-inp');
            body.querySelector('#m-imp').onclick = () => fInp.click();
            fInp.onchange = (e) => {
                const file = e.target.files[0]; if (!file) return;
                const reader = new FileReader();
                reader.onload = () => {
                    try {
                        const arr = JSON.parse(reader.result);
                        if (Array.isArray(arr)) {
                            const cur = loadDb();
                            saveDb([...arr, ...cur]);
                            showToast(`📥 Импортировано записей: ${arr.length}`);
                        }
                    } catch (err) { alert('Ошибка чтения JSON файла'); }
                };
                reader.readAsText(file);
            };
        });
    }

    // ==========================================
    // 6. ПЛАВАЮЩАЯ ПАНЕЛЬ КНОПОК (☰, 💾, ⚡)
    // ==========================================
    function isInsideCard() {
        const t = document.body ? document.body.innerText : '';
        return (t.includes('О ВЫЗОВЕ') && t.includes('ОБЪЕКТИВНЫЕ ДАННЫЕ')) || t.includes('УТОЧНЕННЫЕ СВЕДЕНИЯ');
    }

    function ensureToolbar() {
        if (!document.body || document.getElementById('b103-toolbar')) return;
        const bar = document.createElement('div');
        bar.id = 'b103-toolbar';
        bar.style.cssText = 'position:fixed;bottom:88px;right:16px;z-index:99999;display:flex;flex-direction:column;align-items:flex-end;gap:12px;';
        const rs = 'width:52px;height:52px;border-radius:50%;border:none;box-shadow:0 4px 14px rgba(0,0,0,0.32);font-size:22px;display:none;align-items:center;justify-content:center;cursor:pointer;user-select:none;-webkit-tap-highlight-color:transparent;';
        bar.innerHTML = `
            <button id="btn-main-menu" style="background:#008577;color:white;${rs}">☰</button>
            <button id="btn-save-db" style="background:#00796B;color:white;${rs}">💾</button>
            <button id="btn-fill-db" style="background:#0288D1;color:white;${rs}">⚡</button>
        `;
        document.body.appendChild(bar);

        document.getElementById('btn-main-menu').onclick = openMainMenuIos;
        document.getElementById('btn-save-db').onclick = saveCardToLocalArchive;
        document.getElementById('btn-fill-db').onclick = openAutofillSheet;
    }

    function updateVisibility() {
        ensureToolbar();
        const inCard = isInsideCard();
        if (inCard) scrapeCache();
        const m = document.getElementById('btn-main-menu');
        const s = document.getElementById('btn-save-db');
        const f = document.getElementById('btn-fill-db');
        if (m && s && f) {
            m.style.display = inCard ? 'none' : 'flex';
            s.style.display = inCard ? 'flex' : 'none';
            f.style.display = inCard ? 'flex' : 'none';
        }
    }

    ensureToolbar();
    updateVisibility();
    setInterval(updateVisibility, 350);
})();
