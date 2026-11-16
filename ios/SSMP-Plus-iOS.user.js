// ==UserScript==
// @name         SSMP Plus (iOS / Safari Edition)
// @namespace    https://soft.103brest.by/ssmp/
// @version      3.5.0
// @description  Полный мобильный ассистент СМП (г. Брест): точный автопилот, 63 шаблона, личные шаблоны, история пациентов, МКБ-10, 11 модулей Помощника СМП (ЭКГ, СЛР-таймер, Пост. №118, 10 шкал, 71 яд/антидот, 20 лекарств, приказы МЗ РБ).
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
    const MKB_IDB_NAME = 'ssmp_plus_mkb_db';
    const MKB_STORE = 'mkb';
    window.lastLoadedApiCard = window.lastLoadedApiCard || null;
    window.activeAutofillData = window.activeAutofillData || null;
    window.b103ScreenCache = window.b103ScreenCache || {};
    window.b103IsApplying = false;
    window.b103ActiveSheetClose = null;
    window.b103MkbList = window.b103MkbList || [];

    // ==========================================
    // 0. БАЗОВЫЕ УТИЛИТЫ И ГРАНИЦА «ЗАКЛЮЧЕНИЕ»
    // ==========================================
    function escapeHtml(str) {
        if (str === undefined || str === null) return '';
        return String(str)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
    }

    let cachedAnchor = null, anchorTime = 0;
    function getConclusionAnchor() {
        const now = (window.performance && performance.now) ? performance.now() : Date.now();
        if (cachedAnchor && (now - anchorTime < 2500) && cachedAnchor.isConnected) return cachedAnchor;
        const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, h6, .MuiTypography-root'));
        cachedAnchor = labels.find(e => {
            if (!e.textContent || e.children.length > 2) return false;
            const t = e.textContent.trim();
            return t === 'Результат оказания мед. помощи' || t === 'Результат выезда';
        }) || null;
        anchorTime = now;
        return cachedAnchor;
    }
    function isInConclusion(el) {
        if (!el) return false;
        const anchor = getConclusionAnchor();
        return anchor ? Boolean(anchor.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING) : false;
    }
    window.b103IsInConclusion = isInConclusion;

    function showToast(msg) {
        let t = document.getElementById('b103-ios-toast');
        if (!t) {
            t = document.createElement('div');
            t.id = 'b103-ios-toast';
            t.style.cssText = 'position:fixed;top:24px;left:50%;transform:translateX(-50%);background:rgba(38,50,56,0.95);color:#fff;padding:12px 22px;border-radius:24px;font-size:14px;font-weight:bold;z-index:9999999;box-shadow:0 4px 14px rgba(0,0,0,0.3);transition:opacity 0.25s;pointer-events:none;text-align:center;max-width:88vw;';
            document.body.appendChild(t);
        }
        t.innerText = msg;
        t.style.opacity = '1';
        clearTimeout(window.b103ToastTimer);
        window.b103ToastTimer = setTimeout(() => { t.style.opacity = '0'; }, 2400);
    }

    function copyText(text) {
        const preview = text.length > 45 ? text.slice(0, 45).replace(/\n/g, ' ') + '...' : text.replace(/\n/g, ' ');
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(() => showToast('📋 Скопировано: ' + preview)).catch(() => fallbackCopy(text, preview));
        } else fallbackCopy(text, preview);
    }
    function fallbackCopy(text, preview) {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        ta.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0;';
        document.body.appendChild(ta);
        ta.select();
        try { ta.setSelectionRange(0, ta.value.length); } catch (e) {}
        try { document.execCommand('copy'); showToast('📋 Скопировано: ' + preview); } catch (e) {}
        ta.remove();
    }

    // ==========================================
    // 1. БАЗА ДАННЫХ LOCALSTORAGE (АРХИВ И ШАБЛОНЫ)
    // ==========================================
    function loadDb() {
        try {
            let raw = localStorage.getItem(STORAGE_KEY) || localStorage.getItem('ssmp_plus_ios_cards_v2') || localStorage.getItem('ssmp_plus_cards');
            if (!raw) return [];
            const parsed = JSON.parse(raw);
            return Array.isArray(parsed) ? parsed : [];
        } catch (e) { return []; }
    }
    function saveDb(arr) {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(Array.isArray(arr) ? arr : []));
        } catch (e) { alert('Ошибка сохранения в память браузера'); }
    }
    let idSeq = 0;
    function genId() { return Date.now() * 1000 + (idSeq++ % 1000); }

    function getBestCardForPatient(fio) {
        const cleanFio = String(fio || '').trim().toLowerCase();
        if (cleanFio.length < 3) return null;
        const list = loadDb()
            .filter(x => x && x.is_builtin === 0 && (x.fio || '').trim().toLowerCase() === cleanFio)
            .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0))
            .slice(0, 5);
        if (!list.length) return null;
        const best = list.reduce((a, b) => ((b.score || 0) > (a.score || 0) ? b : a), list[0]);
        return (best.score || 0) >= 60 ? best.json_data : list[0].json_data;
    }

    function searchPatientSummaries(query) {
        const q = String(query || '').trim().toLowerCase();
        const groups = new Map();
        for (const item of loadDb()) {
            if (!item || item.is_builtin !== 0) continue;
            const f = (item.fio || '').trim().toLowerCase();
            if (!f) continue;
            if (!groups.has(f)) groups.set(f, []);
            groups.get(f).push(item);
        }
        const out = [];
        for (const [fio, items] of groups.entries()) {
            items.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
            const latest = items[0];
            const matches = !q || fio.includes(q) || items.some(x => (x.json_data || '').toLowerCase().includes(q));
            if (!matches) continue;
            let j = {}; try { j = JSON.parse(latest.json_data || '{}'); } catch (e) {}
            out.push({
                fio, birthDate: latest.birth_date || '', callCount: items.length,
                lastTimestamp: latest.timestamp || 0,
                lastReason: j.diagnosis || j.callReason || 'Без диагноза'
            });
        }
        return out.sort((a, b) => b.lastTimestamp - a.lastTimestamp).slice(0, 40);
    }

    function getPatientHistory(fio) {
        const clean = String(fio || '').trim().toLowerCase();
        return loadDb()
            .filter(x => x && x.is_builtin === 0 && (x.fio || '').trim().toLowerCase() === clean)
            .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0))
            .slice(0, 15);
    }

    function saveCustomTemplate(title, rawJsonData) {
        const cleanTitle = String(title || '').trim();
        if (!cleanTitle) return;
        let cleaned = rawJsonData;
        try {
            const o = typeof rawJsonData === 'string' ? JSON.parse(rawJsonData) : Object.assign({}, rawJsonData);
            ['fio', 'birthDate', 'age', 'ageUnit', 'address', 'apo'].forEach(k => delete o[k]);
            cleaned = JSON.stringify(o);
        } catch (e) {}
        const db = loadDb();
        db.unshift({ id: genId(), fio: cleanTitle, birth_date: '', timestamp: Date.now(), score: 100, json_data: cleaned, is_builtin: 2 });
        saveDb(db);
    }

    function deleteRecordFromDb(itemRef) {
        const db = loadDb();
        const idx = db.findIndex(x => x && ((itemRef.id && x.id === itemRef.id) || (x.fio === itemRef.fio && x.timestamp === itemRef.timestamp && x.is_builtin === itemRef.is_builtin)));
        if (idx >= 0) { db.splice(idx, 1); saveDb(db); return true; }
        return false;
    }

    // ==========================================
    // 2. ПЕРЕХВАТ API, ПАРСЕР СЕРВЕРА И FORMATTER
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

    function cleanBpString(raw) {
        if (!raw) return '';
        if (typeof raw === 'object') {
            const s = String(raw.sys || raw.systolic || '').replace(/null/g, '').trim();
            const d = String(raw.dis || raw.dia || raw.diastolic || '').replace(/null/g, '').trim();
            return (s || d) ? `${s}/${d}` : '';
        }
        const str = String(raw).replace(/null/g, '').trim();
        if (!str) return '';
        if (str.startsWith('{')) {
            try {
                const o = JSON.parse(str);
                const s = String(o.sys || o.systolic || '').replace(/null/g, '').trim();
                const d = String(o.dis || o.dia || o.diastolic || '').replace(/null/g, '').trim();
                return (s || d) ? `${s}/${d}` : '';
            } catch (e) { return ''; }
        }
        return (str.includes('ул.') || /\d{2}\.\d{2}\.\d{4}/.test(str)) ? '' : str;
    }

    function cleanMedicationsString(raw) {
        let s = String(raw || '').replace(/null/g, '').trim();
        if (!s) return '';
        if (s.includes('{')) {
            s = s.replace(/\{[^{}]*\}/g, m => {
                const mt = m.match(/"(?:type|name|title)"\s*:\s*"([^"]+)"/);
                return mt ? mt[1] : '';
            });
        }
        return s.replace(/\bid\s*[:=]?\s*\d+\s*[,;]?/gi, '').trim();
    }

    function extractName(obj, keys) {
        if (!obj || typeof obj !== 'object') return '';
        for (const k of keys) {
            const v = obj[k];
            if (v && typeof v === 'object') {
                const t = String(v.type || v.name || v.title || '').replace(/null/g, '').trim();
                if (t && !t.startsWith('{')) return t;
                const deep = v.name || v.type || v.medicine;
                if (deep && typeof deep === 'object') {
                    const dt = String(deep.type || deep.name || '').replace(/null/g, '').trim();
                    if (dt) return dt;
                }
            } else if (v !== undefined && v !== null) {
                const s = String(v).replace(/null/g, '').trim();
                if (s && !s.startsWith('{') && !/^\d+$/.test(s)) return s;
            }
        }
        for (const [k, sub] of Object.entries(obj)) {
            const kl = k.toLowerCase();
            if (kl.includes('unit') || kl.includes('way') || kl.includes('route') || kl.includes('id') || ['count', 'amount', 'quantity', 'number'].includes(kl)) continue;
            if (sub && typeof sub === 'object') {
                const st = String(sub.type || sub.name || sub.title || '').replace(/null/g, '').trim();
                if (st && !st.startsWith('{')) return st;
            }
        }
        return '';
    }

    function cleanVal(v) {
        if (v === undefined || v === null) return '';
        if (typeof v === 'string') {
            const s = v.trim();
            return (s === 'null' || s.startsWith('[object')) ? '' : s;
        }
        if (typeof v === 'number' || typeof v === 'boolean') return String(v);
        if (Array.isArray(v)) return v.map(cleanVal).filter(Boolean).join(', ');
        if (typeof v === 'object') return cleanVal(v.type || v.name || v.display || v.title || '');
        return '';
    }

    function parseServerCardToInternal(raw) {
        if (!raw) return null;
        const p = raw.patient || {};
        const fio = cleanVal(p.fullName || raw.initialFullName);
        if (!fio) return null;

        const regDateOnly = cleanVal(raw.dateOfRegistration).split('T')[0];
        const rawBDate = cleanVal(p.birthDate);
        const ageUnit = cleanVal(raw.ageType?.type || raw.ageType);
        let bDate = '';
        if (rawBDate.includes('-')) {
            const bDateOnly = rawBDate.split('T')[0];
            if (bDateOnly !== regDateOnly || ageUnit === 'Дней' || ageUnit === 'Часов') {
                const sp = bDateOnly.split('-');
                if (sp.length === 3) bDate = `${sp[2]}.${sp[1]}.${sp[0]} / 00:00`;
            }
        }
        const jArr = (k) => Array.isArray(raw[k]) ? raw[k].map(x => cleanVal(x)).filter(Boolean).join(', ') : '';

        let meds = cleanVal(raw._domMedications);
        if (!meds && Array.isArray(raw.medications)) {
            meds = raw.medications.map(m => {
                if (!m || typeof m !== 'object') return '';
                const name = cleanVal(m.medication?.name || m.medication || m.name);
                if (!name) return '';
                const cnt = cleanVal(m.number || m.count || m.amount || m.quantity || m.dose);
                const unit = cleanVal(m.medication?.type?.name || m.medication?.unitsType?.name || m.unit?.name || m.unit);
                const way = cleanVal(m.routeDict?.display || m.wayOfAdministration?.type || m.way || m.route);
                const qtyPart = [cnt, unit].filter(Boolean).join(' ');
                return `${name}${qtyPart ? ` — ${qtyPart}` : ''}${(way && !way.includes('не определен')) ? ` [${way}]` : ''}`;
            }).filter(Boolean).join(';\n• ');
        }

        const bp = cleanBpString(raw.arterialPressure);
        const bpAdapted = cleanBpString(raw.arterialPressureAdaptive);
        const icd = raw.icd;

        const liverVal = (raw.liver && typeof raw.liver === 'object')
            ? (cleanVal(raw.liver.type).toUpperCase() || 'НЕ УВЕЛИЧЕНА')
            : (typeof raw.liver === 'boolean' ? (raw.liver ? 'УВЕЛИЧЕНА' : 'НЕ УВЕЛИЧЕНА') : 'НЕ УВЕЛИЧЕНА');

        let attackVal = '';
        if (raw.postAttack && typeof raw.postAttack === 'object') {
            attackVal = cleanVal(raw.postAttack.type).toUpperCase();
        } else if (raw.postAttack !== undefined && raw.postAttack !== null) {
            attackVal = typeof raw.postAttack === 'boolean' ? (raw.postAttack ? 'КУПИРОВАН' : 'НЕ КУПИРОВАН') : cleanVal(raw.postAttack).toUpperCase();
        }

        const card = {
            fio, birthDate: bDate, age: cleanVal(raw.age), ageUnit,
            citizenship: cleanVal(raw.citizenship || p.countryOfRegistration),
            settlement: cleanVal(raw.settlement || p.settlement),
            street: cleanVal(raw.street || p.street),
            building: cleanVal(raw.building || p.building),
            flat: cleanVal(raw.flat || p.flat),
            clarificationAddress: cleanVal(raw.clarificationAddress || p.clarificationAddress),
            address: cleanVal(raw.address || p.placeOfRegistration),
            apo: cleanVal(raw.apo || p.territorialMedicalFacility),
            callReason: cleanVal(raw.reasonToCall),
            gender: p.gender === true ? 'МУЖЧИНА' : (p.gender === false ? 'ЖЕНЩИНА' : ''),
            complaints: cleanVal(raw.complaints), anamnesis: cleanVal(raw.anamnesis), pastDiseases: cleanVal(raw.pastIllnesses),
            allergy: raw.allergy ? 'ДА' : 'НЕТ',
            generalState: cleanVal(raw.generalState), consciousness: cleanVal(raw.consciousness),
            position: cleanVal(raw.position).toUpperCase(), positionDescription: cleanVal(raw.positionDescription),
            behavior: cleanVal(raw.behavior).toUpperCase(), glasgowScore: cleanVal(raw.comaScale) || '15',
            bp, bpAdapted, pulse: cleanVal(raw.pulse), rr: cleanVal(raw.breathCount),
            temp: cleanVal(raw.skinTemp), saturation: cleanVal(raw.saturation1), glucose: cleanVal(raw.glycemia1),
            heartSounds: jArr('heartSounds'), lungs: jArr('breaths'), percussion: jArr('percussionOfLungs'),
            abdomen: jArr('stomachs'), skin: jArr('skins'), urination: jArr('urinations').toUpperCase(),
            stool: cleanVal(raw.defecatio).toUpperCase(),
            edema: raw.peripheralEdema ? 'ДА' : 'НЕТ', edemaDescription: cleanVal(raw.peripheralEdemaDescription),
            rash: raw.skinRashes ? 'ДА' : 'НЕТ', ascites: raw.ascites ? 'ДА' : 'НЕТ', peritoneal: raw.stomachSymptoms ? 'ДА' : 'НЕТ',
            medHelp: jArr('medicalAssistanceProvided'), medications: meds,
            pupils: cleanVal(raw.pupils).toUpperCase(), lightReaction: raw.pupilsLight !== false ? 'ДА' : 'НЕТ',
            speech: cleanVal(raw.speech).toUpperCase(), gait: cleanVal(raw.gait).toUpperCase(),
            face: cleanVal(raw.face).toUpperCase(), nystagmus: cleanVal(raw.nystagmus).toUpperCase(),
            muscleTone: cleanVal(raw.muscleTone) || 'Нормальный',
            pathologicalReflexes: cleanVal(raw.pathologicalReflexes) || 'Отрицательные',
            meningealSymptoms: cleanVal(raw.meningealSymptoms) || 'Отрицательные',
            plegia: cleanVal(raw.plegia) || 'Нет', tongue: cleanVal(raw.tongue) || 'Чистые',
            mucous: cleanVal(raw.mucosa) || 'Бледно-розовые', liver: liverVal,
            peristalsis: raw.peristalsis !== false ? 'ДА' : 'НЕТ', localStatus: cleanVal(raw.localStatus),
            ecgBefore: cleanVal(raw.electrocardiogramBefore), ecgAfter: cleanVal(raw.electrocardiogramAfter),
            diagnosis: cleanVal(raw.diagnosis), mkb: icd ? `[${icd.code || ''}] ${icd.type || icd.name || ''}`.trim() : '',
            attack: attackVal, therapyEffect: cleanVal(raw.result).toUpperCase(),
            callResult: cleanVal(raw.resultDeparture).toUpperCase()
        };
        let score = 0;
        if (card.anamnesis.length > 10) score += 35;
        if (card.complaints) score += 25;
        if (card.diagnosis) score += 25;
        if (meds || bp) score += 15;
        return { id: genId(), fio: fio.toLowerCase(), birth_date: bDate, timestamp: Date.now(), score, json_data: JSON.stringify(card), is_builtin: 0 };
    }
    
function formatCardText(jsonStr) {
        try {
            const j = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            const lines = [];
            const add = (label, key, suf = '') => { const v = cleanVal(j[key]); if (v) lines.push(`${label}: ${v}${suf}`); };
            add('🎂 Родился', 'birthDate');
            add('⏳ Возраст', 'age', j.ageUnit ? ` ${cleanVal(j.ageUnit)}` : '');
            add('⚧ Пол', 'gender');
            add('🌍 Гражданство', 'citizenship');
            add('📍 Адрес', 'address');
            add('🚨 Повод', 'callReason');
            add('🏥 АПО', 'apo');

            lines.push(`\n📋 ДИАГНОЗ:\n• ${cleanVal(j.diagnosis)}`);
            add('• Код МКБ', 'mkb');
            if (j.recommendedTherapy) lines.push(`\n💊 ПРОТОКОЛЬНАЯ ТЕРАПИЯ (подсказка):\n${cleanVal(j.recommendedTherapy)}`);
            if (j.ecgBefore || j.ecgAfter) {
                lines.push('\n📈 ЭКГ:');
                add('• До помощи', 'ecgBefore');
                add('• После помощи', 'ecgAfter');
            }
            lines.push('\n🩺 ОБЪЕКТИВНО:');
            add('• Состояние', 'generalState');
            add('• Сознание', 'consciousness');
            add('• Положение', 'position', j.positionDescription ? ` (${cleanVal(j.positionDescription)})` : '');
            add('• Поведение', 'behavior');
            add('• Глазго', 'glasgowScore', ' б');
            const bpAd = cleanBpString(j.bpAdapted); if (bpAd) lines.push(`• Привычное АД: ${bpAd}`);
            const bp = cleanBpString(j.bp); if (bp) lines.push(`• АД на вызове: ${bp}`);
            add('• Пульс', 'pulse', ' уд/мин');
            add('• ЧДД', 'rr', ' в мин');
            add('• SpO2', 'saturation', '%');
            add('• Сахар', 'glucose', ' ммоль/л');
            add('• Температура', 'temp', '°C');
            add('• Тоны сердца', 'heartSounds');
            add('• Дыхание', 'lungs');
            add('• Перкуссия', 'percussion');
            add('• Живот', 'abdomen');
            add('• Асцит', 'ascites');
            add('• Кожа', 'skin');
            add('• Высыпания', 'rash');
            add('• Отеки', 'edema', j.edemaDescription ? ` (${cleanVal(j.edemaDescription)})` : '');
            add('• Зрачки', 'pupils');
            add('• Реакция на свет', 'lightReaction');
            add('• Речь', 'speech');
            add('• Лицо', 'face');
            add('• Походка', 'gait');
            add('• Нистагм', 'nystagmus');
            add('• Тонус мышц', 'muscleTone');
            add('• Пат. рефлексы', 'pathologicalReflexes');
            add('• Менингеальные', 'meningealSymptoms');
            add('• Плегии/парезы', 'plegia');
            add('• Язык', 'tongue');
            add('• Слизистые', 'mucous');
            add('• Перистальтика', 'peristalsis');
            add('• Печень', 'liver');
            add('• Брюшина', 'peritoneal');
            add('• Мочеиспускание', 'urination');
            add('• Стул', 'stool');
            add('• Запах алкоголя', 'alcohol');
            add('• Запах ацетона', 'acetone');
            add('• Локально', 'localStatus');

            if (j.complaints) lines.push(`\n🗣️ Жалобы:\n${cleanVal(j.complaints)}`);
            if (j.anamnesis) lines.push(`\n📖 Анамнез:\n${cleanVal(j.anamnesis)}`);
            if (j.pastDiseases) lines.push(`\n🩹 Перенесенные: ${cleanVal(j.pastDiseases)}`);
            add('⚠️ Аллергия', 'allergy');

            const meds = cleanMedicationsString(j.medications);
            if (meds || j.attack || j.therapyEffect || j.callResult) {
                lines.push('\n💉 ОКАЗАННАЯ ПОМОЩЬ:');
                add('• Помощь', 'medHelp');
                if (meds) lines.push(`• Препараты:\n• ${meds}`);
                add('• Приступ', 'attack');
                add('• Эффект', 'therapyEffect');
                add('• Итог', 'callResult');
            }
            return lines.join('\n');
        } catch (e) { return 'Ошибка чтения данных'; }
    }

    window.setAndApplyAutofill = function(jsonStr) {
        try {
            window.activeAutofillData = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            window.applyAutofill(window.activeAutofillData);
        } catch (e) {}
    };
    
    function saveCardToLocalArchive() {
        scrapeCache();
        if (!window.lastLoadedApiCard) { showToast('Откройте карту для сохранения'); return; }
        const merged = Object.assign({}, window.lastLoadedApiCard, window.b103ScreenCache);
        const entry = parseServerCardToInternal(merged);
        if (!entry) { showToast('Не удалось определить ФИО пациента'); return; }
        const db = loadDb();
        const sixHoursAgo = Date.now() - 6 * 3600 * 1000;
        const idx = db.findIndex(x => x && x.is_builtin === 0 && (x.fio || '').toLowerCase() === entry.fio && (x.timestamp || 0) > sixHoursAgo);
        if (idx >= 0) db[idx] = entry; else db.unshift(entry);
        saveDb(db);
        showToast(`💾 Карта ${entry.fio.toUpperCase()} сохранена!`);
    }

    // ==========================================
    // 3. ВСЕ 63 ВСТРОЕННЫХ ШАБЛОНА СМП
    // ==========================================
    function tpl(id, title, mkb, diagnosis, complaints, anamnesis, opts = {}) {
        const gs = opts.generalState || 'Удовлетворительное';
        const d = Object.assign({
            diagnosis, mkb, complaints, anamnesis,
            pastDiseases: 'ИБС, АГ', allergy: 'НЕТ', generalState: gs, consciousness: 'Ясное',
            position: 'АКТИВНОЕ', behavior: 'СПОКОЙНОЕ', glasgowScore: '15',
            bp: '130/80', bpAdapted: '120/80', pulse: '78', pulseExists: 'ДА', rr: '16', temp: '36.6',
            saturation: '98', glucose: '5.4', heartSounds: 'Ритмичные, Ясные', lungs: 'Свободное, Везикулярное',
            percussion: 'Ясный легочный звук', abdomen: 'Участвует в акте дыхания, Мягкий, Безболезненный',
            skin: 'Теплая, Сухая, Нормальная', tongue: 'Чистые', mucous: 'Бледно-розовые',
            pupils: '', lightReaction: 'ДА', speech: 'СОХРАНЕНА', face: 'СИММЕТРИЧНОЕ', gait: 'СВОБОДНАЯ',
            nystagmus: 'НЕТ', muscleTone: 'Нормальный', pathologicalReflexes: 'Отрицательные',
            meningealSymptoms: 'Отрицательные', plegia: 'Нет', rash: 'НЕТ', ascites: 'НЕТ',
            edema: 'НЕТ', alcohol: 'НЕТ', acetone: 'НЕТ', peritoneal: 'НЕТ',
            liver: 'НЕ УВЕЛИЧЕНА', peristalsis: gs.includes('смерть') ? 'НЕТ' : 'ДА',
            urination: 'НЕ НАРУШЕНО', stool: 'НЕ НАРУШЕН', localStatus: '',
            ecgBefore: 'Ритм синусовый, правильный, ЧСС 78 в мин. Острой очаговой патологии нет.',
            ecgAfter: 'Без динамики.', attack: 'КУПИРОВАН', therapyEffect: 'УЛУЧШЕНИЕ',
            callResult: 'ОСТАВЛЕН НА МЕСТЕ', recommendedTherapy: ''
        }, opts);
        return { id, title, mkb, diagnosis, jsonData: JSON.stringify(d), isCustom: false };
    }

    const BUILTIN_TEMPLATES = [
        tpl(1, '🚨 СЛР (Клиническая смерть, безуспешная)', '[I46.9] Остановка сердца неуточненная', 'Внезапная сердечная смерть. Клиническая смерть. Безуспешная СЛР. Биологическая смерть.', 'Жалоб нет ввиду развития клинической смерти.', 'В присутствии бригады (или со слов родственников) внезапно потерял сознание, прекратились дыхание и пульс на сонных артериях. Немедленно начата расширенная СЛР (НМС 100-120/мин, ИВЛ, мониторинг ЭКГ, в/в доступ). Через 30 минут СЛР спонтанное кровообращение не восстановлено, констатирована биологическая смерть.', { pastDiseases: 'ИБС, ПИКС, АГ', generalState: 'Клиническая смерть', consciousness: 'Отсутствует', position: 'ПАССИВНОЕ', glasgowScore: '3', bp: '0/0', bpAdapted: '130/80', pulse: '0', pulseExists: 'НЕТ', rr: '0', temp: '0', saturation: '0', glucose: '0', heartSounds: 'Отсутствуют', lungs: 'Отсутствует', skin: 'Бледная, Цианоз, Холодная', pupils: 'МИДРИАЗ', lightReaction: 'НЕТ', speech: 'ОТСУТСТВУЕТ', gait: 'НЕ ОЦЕНИВАЛАСЬ', muscleTone: 'Атония', stool: 'ОТСУТСТВУЕТ', localStatus: 'Сознание отсутствует (ШКГ 3 б), пульсации на магистральных артериях нет, спонтанного дыхания нет, зрачки широкие (D=S), фотореакция отсутствует. После 30 мин СЛР — стойкая асистолия.', ecgBefore: 'Асистолия (или ФЖ, перешедшая в асистолию).', ecgAfter: 'Изолиния.', attack: 'НЕ КУПИРОВАН', therapyEffect: 'БЕЗ ЭФФЕКТА', recommendedTherapy: 'Адреналин 1 мг в/в каждые 3-5 мин; при ФЖ/ЖТ без пульса — разряд 200 Дж + Амиодарон 300 мг в/в.' }),
        tpl(2, '⚡ СЛР (Успешная реанимация)', '[I46.0] Остановка сердца с успешным восстановлением сердечной деятельности', 'ИБС: Остановка кровообращения по механизму ФЖ. Клиническая смерть. Успешная СЛР.', 'Не предъявляет ввиду угнетения сознания.', 'Внезапная потеря сознания с остановкой дыхания и кровообращения. Немедленно начата СЛР: НМС, ИВЛ, на ЭКГ зафиксирована крупноволновая ФЖ, выполнена дефибрилляция. На 8-й минуте восстановлено спонтанное кровообращение.', { generalState: 'Крайне тяжелое', consciousness: 'Кома', position: 'ПАССИВНОЕ', glasgowScore: '6', bp: '90/60', bpAdapted: '130/80', pulse: '98', rr: '14', temp: '36.2', saturation: '94', glucose: '7.8', heartSounds: 'Глухие, Ритмичные', lungs: 'Ослабленное, Везикулярное', skin: 'Бледная, Влажная', pupils: 'МИДРИАЗ', lightReaction: 'НЕТ', speech: 'ОТСУТСТВУЕТ', gait: 'НЕ ОЦЕНИВАЛАСЬ', muscleTone: 'Снижен', ecgBefore: 'Крупноволновая фибрилляция желудочков.', ecgAfter: 'Ритм синусовый, ЧСС 98 в мин.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'Дефибрилляция 200 Дж, Адреналин 1 мг в/в, Амиодарон 300 мг в/в, О2, инфузия NaCl 0.9%.' }),
        tpl(3, '🕊️ Констатация биологической смерти (до СМП)', '[R96.0] Мгновенная смерть', 'Биологическая смерть до прибытия бригады СМП.', 'Жалоб нет ввиду биологической смерти.', 'Обнаружен родственниками без признаков жизни около 1,5 часов назад. Реанимация не проводилась ввиду достоверных признаков биологической смерти.', { pastDiseases: 'ИБС, АГ, ХСН', generalState: 'Биологическая смерть', consciousness: 'Отсутствует', position: 'ПАССИВНОЕ', glasgowScore: '3', bp: '0/0', bpAdapted: '130/80', pulse: '0', pulseExists: 'НЕТ', rr: '0', temp: '0', saturation: '0', glucose: '0', heartSounds: 'Отсутствуют', lungs: 'Отсутствует', skin: 'Бледная, Холодная, Гипостатические пятна', pupils: 'МИДРИАЗ', lightReaction: 'НЕТ', speech: 'ОТСУТСТВУЕТ', gait: 'НЕ ОЦЕНИВАЛАСЬ', muscleTone: 'Трупное окоченение', stool: 'ОТСУТСТВУЕТ', localStatus: 'Кожные покровы бледные, холодные. Зрачки широкие, фотореакция отсутствует, с-м Белоглазова положительный. Трупные пятна по заднебоковой поверхности, трупное окоченение. Признаков насильственной смерти нет.', ecgBefore: 'Изолиния.', ecgAfter: 'Изолиния.', attack: 'НЕ КУПИРОВАН', therapyEffect: 'БЕЗ ЭФФЕКТА' }),
        tpl(4, 'Гипертонический криз (неосложненный)', '[I10] Эссенциальная (первичная) гипертензия', 'Артериальная гипертензия 2 ст., риск 3. Неосложненный гипертонический криз.', 'На головную боль в затылочной области, головокружение, мелькание мушек перед глазами, слабость.', 'Ухудшение около 2 часов назад после стресса, АД дома 180/100 мм рт. ст. Каптоприл 25 мг без достаточного эффекта.', { pastDiseases: 'АГ, ИБС', generalState: 'Средней степени тяжести', bp: '180/100', bpAdapted: '130/80', pulse: '84', heartSounds: 'Глухие, Ритмичные', ecgBefore: 'Ритм синусовый, ЧСС 84 в мин. ЭОС отклонена влево, признаки ГЛЖ.', ecgAfter: 'Ритм синусовый, ЧСС 76 в мин.', recommendedTherapy: 'Каптоприл 25-50 мг (или Моксонидин 0.2-0.4 мг), Магния сульфат 25% 5-10 мл в/в медленно.' }),
        tpl(5, 'Гипертонический криз (осложненный, церебральный)', '[I10] Эссенциальная (первичная) гипертензия', 'Артериальная гипертензия 3 ст., риск 4. Осложненный гипертонический криз (острая гипертоническая энцефалопатия).', 'На интенсивную распирающую головную боль, головокружение, тошноту, двукратную рвоту, нечеткость зрения.', 'Ухудшение 3 часа назад на фоне подъема АД до 210/115 мм рт. ст.', { pastDiseases: 'АГ 3 ст., ДЭП', generalState: 'Тяжелое', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Лежа с приподнятым головным концом', behavior: 'ВОЗБУЖДЕННОЕ', bp: '210/115', bpAdapted: '130/80', pulse: '92', heartSounds: 'Глухие, Ритмичные', ecgBefore: 'Ритм синусовый, ЧСС 92. Перегрузка ЛЖ.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'Эбрантил 12.5-25 мг в/в (или Эналаприлат 1.25 мг в/в), Магния сульфат 25% 10 мл в/в, Фуросемид 20-40 мг в/в.' }),
        tpl(6, 'Пароксизм фибрилляции предсердий (ФП)', '[I48] Фибрилляция и трепетание предсердий', 'ИБС: АТС кардиосклероз. АГ 2 ст., риск 4. Персистирующая форма ФП, тахисистолический вариант, пароксизм. Н1.', 'На приступ учащенного неритмичного сердцебиения, перебои в работе сердца, слабость.', 'Ухудшение сегодня утром, внезапно ощутил срыв ритма. Бисопролол без эффекта.', { pastDiseases: 'ИБС, АГ, пароксизмальная ФП', generalState: 'Средней степени тяжести', bp: '130/80', pulse: '128', heartSounds: 'Аритмичные, Ясные', ecgBefore: 'Фибрилляция предсердий с ЧЖС 120-135 в мин.', ecgAfter: 'Ритм синусовый, ЧСС 76 в мин.', recommendedTherapy: 'Пароксизм < 48ч: Амиодарон 5% 300 мг в/в на 5% глюкозе (или Новокаинамид 10% 10 мл + Мезатон).' }),
        tpl(7, 'Постоянная форма ФП (декомпенсация)', '[I48] Фибрилляция и трепетание предсердий', 'ИБС: кардиосклероз. Постоянная форма ФП, тахисистолия. АГ 2 ст., риск 4. ХСН ФК III.', 'На одышку при нагрузке, учащенное сердцебиение, отечность голеней.', 'Ухудшение за несколько дней на фоне пропуска плановой терапии.', { pastDiseases: 'ИБС, постоянная ФП, ХСН', generalState: 'Средней степени тяжести', bp: '135/85', bpAdapted: '125/80', pulse: '116', rr: '20', saturation: '95', heartSounds: 'Аритмичные, Глухие', edema: 'ДА', edemaDescription: 'Отечность голеней', ecgBefore: 'ФП с ЧЖС 110-120 в мин.', ecgAfter: 'ФП с ЧЖС 84-90 в мин.', recommendedTherapy: 'Метопролол 2.5-5 мг в/в (или Дигоксин 0.025% 1-2 мл) + Панангин + Фуросемид 40 мг в/в.' }),
        tpl(8, 'Пароксизмальная наджелудочковая тахикардия (СВТ)', '[I47.1] Наджелудочковая тахикардия', 'ИБС: кардиосклероз. Пароксизмальная АВ-узловая наджелудочковая тахикардия.', 'На внезапный приступ частого ритмичного сердцебиения, пульсацию в шее, тревогу.', 'Приступ начался внезапно 40 мин назад. Вагусные пробы без эффекта.', { pastDiseases: 'СВТ', generalState: 'Средней степени тяжести', bp: '115/75', pulse: '175', rr: '18', ecgBefore: 'Узкокомплексная тахикардия ЧСС 175 в мин.', ecgAfter: 'Ритм синусовый, ЧСС 78 в мин.', recommendedTherapy: 'Вагусные приемы ➔ АТФ 1% 1-2 мл быстро болюсом в/в (или Верапамил 0.25% 2-4 мл в/в медленно).' }),
        tpl(9, 'Экстрасистолия', '[I49.3] Преждевременная деполяризация желудочков', 'ИБС: кардиосклероз. Желудочковая экстрасистолия. АГ 2 ст., риск 3.', 'На перебои и «замирания» в работе сердца, тревожность.', 'Перебои появились сегодня после эмоционального стресса.', { bp: '135/80', pulse: '76', heartSounds: 'Аритмичные, Глухие', ecgBefore: 'Ритм синусовый, ЧСС 76, одиночные ЖЭС.', ecgAfter: 'Ритм синусовый, ЧСС 72.' }),
        tpl(10, 'ОКС без подъема ST (Нестабильная стенокардия)', '[I20.0] Нестабильная стенокардия', 'ИБС: ОКС без подъема сегмента ST. Нестабильная стенокардия. АГ 2 ст., риск 4.', 'На давящие боли за грудиной с иррадиацией в левую лопатку и плечо, нехватку воздуха.', 'Боли в покое около 1 часа, нитроглицерин с кратковременным неполным эффектом.', { generalState: 'Средней степени тяжести', bp: '140/90', bpAdapted: '130/80', pulse: '88', rr: '18', saturation: '96', glucose: '6.2', heartSounds: 'Глухие, Ритмичные', ecgBefore: 'Ритм синусовый, ЧСС 88. Депрессия ST до 1,5 мм в V4-V6.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'АСК 250-300 мг + Клопидогрел 300 мг / Тикагрелор 180 мг + Гепарин 4000 ЕД в/в + Нитроглицерин + Морфин при боли.' }),
        tpl(11, 'Стабильная стенокардия напряжения', '[I20.8] Другие формы стенокардии', 'ИБС: Стабильная стенокардия напряжения ФК II. АГ 2 ст., риск 4.', 'На момент осмотра болей нет. До СМП — сжимающая боль за грудиной при ходьбе.', 'Около 30 мин назад после нагрузки возникла боль длительностью 5 мин, полностью купировалась нитроглицерином.', { bp: '135/80', bpAdapted: '130/80', pulse: '74', heartSounds: 'Глухие, Ритмичные', ecgBefore: 'Ритм синусовый, ЧСС 74. Без отрицательной динамики.' }),
        tpl(12, 'ОКС с подъемом ST (ОИМ передней стенки)', '[I21.0] Острый трансмуральный инфаркт передней стенки миокарда', 'ИБС: ОКС с подъемом ST. Острый крупноочаговый инфаркт миокарда передней стенки ЛЖ.', 'На интенсивные жгучие боли за грудиной с иррадиацией в левую руку и челюсть, холодный пот, страх смерти.', 'Заболел остро 40 мин назад. Нитраты без эффекта.', { generalState: 'Тяжелое', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Полусидя из-за боли и одышки', behavior: 'ВОЗБУЖДЕННОЕ', bp: '110/70', bpAdapted: '130/80', pulse: '96', rr: '20', saturation: '94', glucose: '7.1', heartSounds: 'Глухие, Ритмичные', skin: 'Холодная, Влажная, Бледная', ecgBefore: 'Ритм синусовый, ЧСС 96. Элевация ST 3-4 мм в V1-V4, реципрокная депрессия в III, aVF.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'Морфин 1% дробно в/в + Нитроглицерин (САД > 100) + АСК 250 мг + Тикагрелор 180 мг + Гепарин 4000 ЕД в/в. Экстренная доставка на ЧКВ.' }),
        tpl(13, 'ОКС с подъемом ST (ОИМ нижней стенки)', '[I21.1] Острый трансмуральный инфаркт нижней стенки миокарда', 'ИБС: ОКС с подъемом ST. Острый крупноочаговый инфаркт нижней стенки ЛЖ.', 'На интенсивные давящие боли за грудиной и в эпигастрии, тошноту, слабость, холодный пот.', 'Заболел остро около 1 часа назад в покое.', { generalState: 'Тяжелое', position: 'ПАССИВНОЕ', bp: '100/65', bpAdapted: '130/80', pulse: '62', rr: '19', saturation: '95', glucose: '6.8', heartSounds: 'Глухие, Ритмичные', skin: 'Холодная, Влажная, Бледная', ecgBefore: 'ЧСС 62. Элевация ST на 3 мм в II, III, aVF.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(14, 'Кардиогенный шок', '[R57.0] Кардиогенный шок', 'ИБС: Острый ИМ. Осложнение: Истинный кардиогенный шок.', 'На боли за грудиной, резкую слабость, заторможенность.', 'Боли около 2 часов, последние 30 мин — резкое падение АД.', { pastDiseases: 'ИБС, ПИКС', generalState: 'Крайне тяжелое', consciousness: 'Приглушенное', position: 'ПАССИВНОЕ', bp: '70/40', bpAdapted: '130/80', pulse: '114', rr: '26', saturation: '88', glucose: '8.2', heartSounds: 'Глухие, Ритмичные', skin: 'Холодная, Влажная, Цианоз', urination: 'ЗАДЕРЖКА МОЧЕИСПУСКАНИЯ', attack: 'НЕ КУПИРОВАН', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'Допамин / Норадреналин в/в титрованно до САД >= 90, О2, обезболивание, АСК + Гепарин.' }),
        tpl(15, 'Отек легких / ОЛЖН', '[I50.1] Левожелудочковая недостаточность', 'ИБС: ПИКС. АГ 3 ст., риск 4. Острая левожелудочковая недостаточность, альвеолярный отек легких.', 'На выраженное удушье, клокочущее дыхание, кашель с пенистой мокротой, положение ортопноэ.', 'Ухудшение за последний час на фоне подъема АД до 200/110 мм рт. ст.', { pastDiseases: 'ИБС, ПИКС, АГ, ХСН', generalState: 'Тяжелое', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Сидя, ортопноэ', behavior: 'ВОЗБУЖДЕННОЕ', bp: '190/110', bpAdapted: '130/80', pulse: '112', rr: '28', saturation: '86', glucose: '6.8', heartSounds: 'Глухие, Ритмичные', lungs: 'Затрудненное, Хрипы влажные, Хрипы справа, Хрипы слева', skin: 'Холодная, Влажная, Цианоз', edema: 'ДА', edemaDescription: 'Отечность голеней', ecgBefore: 'Синусовая тахикардия ЧСС 112, перегрузка ЛЖ.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'Положение сидя + О2 через спирт + Морфин 1% 0.5-1 мл в/в + Нитроглицерин титрованно + Фуросемид 40-80 мг в/в.' }),
        tpl(16, 'ТЭЛА', '[I26] Легочная эмболия', 'Тромбоэмболия ветвей легочной артерии (ТЭЛА). ОДН 2 ст.', 'На внезапную резкую одышку в покое, нехватку воздуха, сдавление грудной клетки, отечность нижних конечностей.', 'Одышка возникла внезапно. В анамнезе варикозная болезнь / тромбофлебит. Оказана помощь: ЭКГ, ПВК, Кислород, Гепарин 5000 ЕД.', { pastDiseases: 'Варикозная болезнь', generalState: 'Средней степени тяжести', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Сидя легче дышать', bp: '125/80', pulse: '92', rr: '20', saturation: '90', heartSounds: 'Глухие, Ритмичные', lungs: 'Затрудненное, Ослабленное', percussion: 'Притупление звука справа', edema: 'ДА', edemaDescription: 'Отечность голеней', ecgBefore: 'Ритм синусовый. ЧСС 92 уд. Синдром SI-QIII-TIII.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'О2 + Гепарин 5000-10000 ЕД в/в болюсом + обезболивание + инфузия/вазопрессоры при гипотонии.' }),
        tpl(17, 'ОНМК / Ишемический инсульт', '[I63.9] Инфаркт мозга неуточненный', 'ОНМК по ишемическому типу в бассейне левой СМА. Правосторонний гемипарез, моторная афазия.', 'На слабость в правых конечностях, нарушение речи, перекос лица.', 'Заболел остро 1,5 часа назад: нарушилась речь, ослабли правая рука и нога.', { pastDiseases: 'АГ, ЦВБ', generalState: 'Тяжелое', consciousness: 'Приглушенное', position: 'ПАССИВНОЕ', glasgowScore: '13', bp: '170/100', bpAdapted: '130/80', pulse: '82', rr: '18', saturation: '96', glucose: '6.0', heartSounds: 'Глухие, Ритмичные', speech: 'НАРУШЕНА', face: 'АСИММЕТРИЧНОЕ', gait: 'НАРУШЕНА', muscleTone: 'Снижен справа', pathologicalReflexes: 'Симптом Бабинского справа', plegia: 'Правосторонний гемипарез', localStatus: 'Сглажена правая носогубная складка, девиация языка вправо, правосторонний гемипарез 2 балла.', attack: 'НЕ КУПИРОВАН', therapyEffect: 'БЕЗ ЭФФЕКТА', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(18, 'ОНМК / Геморрагический инсульт', '[I61.9] Внутримозговое кровоизлияние неуточненное', 'ОНМК по геморрагическому типу. Левосторонняя гемиплегия. Кома 1 ст.', 'Контакт невозможен ввиду комы.', 'На фоне высокого АД внезапно пожаловался на резкую головную боль, была рвота, затем быстро утратил сознание.', { pastDiseases: 'АГ 3 ст., ЦВБ', generalState: 'Тяжелое', consciousness: 'Кома', position: 'ПАССИВНОЕ', glasgowScore: '7', bp: '220/120', bpAdapted: '140/90', pulse: '58', rr: '22', saturation: '91', glucose: '7.4', heartSounds: 'Глухие, Ритмичные', pupils: 'АНИЗОКАРИЯ', lightReaction: 'НЕТ', speech: 'ОТСУТСТВУЕТ', face: 'АСИММЕТРИЧНОЕ', gait: 'НЕ ОЦЕНИВАЛАСЬ', muscleTone: 'Снижен слева', pathologicalReflexes: 'Положительные слева', meningealSymptoms: 'Ригидность затылочных мышц', plegia: 'Левосторонняя гемиплегия', localStatus: 'Кома 1 ст. (ШКГ 7 б). Анизокория D>S, с-м «паруса» слева, левосторонняя гемиплегия, ригидность затылка +2 см.', attack: 'НЕ КУПИРОВАН', therapyEffect: 'БЕЗ ЭФФЕКТА', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(19, 'Транзиторная ишемическая атака (ТИА)', '[G45.9] Преходящая транзиторная церебральная ишемическая атака неуточненная', 'ЦВБ: Транзиторная ишемическая атака в ВББ. АГ 2 ст., риск 4.', 'На системное головокружение, шаткость, преходящее онемение лица.', 'Час назад возникло головокружение и нечеткость речи, регрессировавшие к осмотру.', { pastDiseases: 'АГ, ДЭП', generalState: 'Средней степени тяжести', bp: '160/90', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(20, 'Обморок / Синкопальное состояние', '[R55] Обморок [синкопе] и коллапс', 'Постсинкопальное состояние. Ортостатическая гипотензия.', 'На общую слабость, легкое головокружение.', 'В душном помещении кратковременно (до 1 мин) потерял сознание без судорог, пришел в себя сам, головой не ударялся.', { pastDiseases: 'ВСД', bp: '110/70', pulse: '72' }),
        tpl(21, 'Эпилепсия (после судорожного припадка)', '[G40.9] Эпилепсия неуточненная', 'Эпилепсия. Состояние после генерализованного тонико-клонического припадка.', 'На общую слабость, головную боль, сонливость.', '15 минут назад развился приступ судорог до 2 минут с прикусом языка. Страдает эпилепсией.', { pastDiseases: 'Эпилепсия', generalState: 'Средней степени тяжести', pulse: '86', tongue: 'Следы прикуса' }),
        tpl(22, 'Эпилептический статус', '[G41.0] Большой судорожный эпилептический статус', 'Эпилепсия. Тонико-клонический эпилептический статус.', 'Сбор жалоб невозможен.', 'Серия из 3 припадков за 30 минут, между ними в сознание не приходил.', { pastDiseases: 'Эпилепсия', generalState: 'Тяжелое', consciousness: 'Кома', position: 'ПАССИВНОЕ', glasgowScore: '6', bp: '150/90', pulse: '118', rr: '24', saturation: '90', glucose: '6.1', speech: 'ОТСУТСТВУЕТ', gait: 'НЕ ОЦЕНИВАЛАСЬ', muscleTone: 'Повышен', tongue: 'Следы прикуса', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'О2 + Диазепам 0.5% 2 мл (10 мг) в/в медленно (повтор до 20 мг) + Вальпроат / Магния сульфат 25% 10 мл в/в.' }),
        tpl(23, 'ДЭП (головокружение, атаксия)', '[I67.2] Церебральный атеросклероз', 'ЦВБ: ДЭП 2 ст. сложного генеза, декомпенсация. Вестибуло-атактический синдром.', 'На головокружение, шум в голове, шаткость при ходьбе, слабость.', 'Ухудшение 2 суток на фоне колебаний АД. Очаговой симптоматики нет.', { pastDiseases: 'ЦВБ, ДЭП, АГ', bp: '150/85', bpAdapted: '130/80', pulse: '76', heartSounds: 'Глухие, Ритмичные', gait: 'НАРУШЕНА', localStatus: 'В позе Ромберга пошатывание. ПНП с легкой интенцией. Парезов нет.' }),
        tpl(24, 'Мигрень', '[G43.9] Мигрень неуточненная', 'Мигрень без ауры, приступный период.', 'На пульсирующую одностороннюю головную боль в лобно-височной области, фотофобию, тошноту.', 'Приступ длится 4 часа, анальгетики дома не помогли.', { pastDiseases: 'Мигрень', generalState: 'Средней степени тяжести', bp: '120/75', bpAdapted: '115/75' }),
        tpl(25, 'Дорсалгия / Люмбоишиалгия', '[M54.5] Боль внизу спины', 'Остеохондроз ПОП. Острая люмбоишиалгия с выраженным болевым синдромом.', 'На острую стреляющую боль в пояснице с иррадиацией в левую ногу, скованность.', 'Боль возникла после подъема тяжести. Таблетки без эффекта.', { pastDiseases: 'Остеохондроз', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Щадящее из-за боли в спине', localStatus: 'Болезненность паравертебральных точек L4-S1, дефанс мышц спины. С-м Ласега + слева 45°.' }),
        tpl(26, 'Межреберная невралгия (торакалгия)', '[G58.0] Межреберная невропатия', 'Остеохондроз ГОП. Вертеброгенная торакалгия (межреберная невралгия).', 'На колющую боль в левой половине грудной клетки, усиливающуюся при вдохе и поворотах.', 'Боль после неловкого движения и переохлаждения. На ЭКГ без острой патологии.', { pastDiseases: 'Остеохондроз', bp: '125/80', pulse: '74', localStatus: 'Болезненность при пальпации по ходу V-VI межреберья слева.' }),
        tpl(27, 'Приступ бронхиальной астмы', '[J45.9] Астма неуточненная', 'Бронхиальная астма, средней тяжести, приступный период. ДН 1-2 ст.', 'На экспираторное удушье, свистящее дыхание, сухой кашель.', 'Страдает БА. Ухудшение 2 часа назад, сальбутамол без достаточного эффекта.', { pastDiseases: 'Бронхиальная астма', generalState: 'Средней степени тяжести', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Сидя с упором на руки', pulse: '98', rr: '24', saturation: '93', lungs: 'Затрудненное, Хрипы сухие', percussion: 'Коробочный звук справа, Коробочный звук слева', recommendedTherapy: 'Небулайзер: Беродуал 20 кап + Пульмикорт 0.5-1 мг на NaCl; Преднизолон 60-90 мг в/в (или Дексаметазон 8-12 мг).' }),
        tpl(28, 'Астматический статус', '[J46] Астматический статус', 'Бронхиальная астма, тяжелое жизнеугрожающее обострение (астматический статус). ОДН 2-3 ст.', 'На тяжелое удушье, невозможность говорить фразами, слабость.', 'Удушье более 6 часов, ингаляторы без эффекта.', { pastDiseases: 'Бронхиальная астма', generalState: 'Тяжелое', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Сидя, ортопноэ', behavior: 'ВОЗБУЖДЕННОЕ', bp: '140/90', pulse: '122', rr: '30', saturation: '87', lungs: 'Ослабленное, Затрудненное, Хрипы сухие', percussion: 'Коробочный звук справа, Коробочный звук слева', skin: 'Влажная, Цианоз', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(29, 'Обострение ХОБЛ', '[J44.1] Хроническая обструктивная легочная болезнь с обострением', 'ХОБЛ, тяжелое течение, стадия обострения. ХДН 2 ст.', 'На усиление одышки в покое, кашель со слизисто-гнойной мокротой.', 'Ухудшение 3 дня после переохлаждения.', { pastDiseases: 'ХОБЛ', generalState: 'Средней степени тяжести', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Полусидя', bp: '135/85', bpAdapted: '130/80', pulse: '94', rr: '22', temp: '37.2', saturation: '92', lungs: 'Затрудненное, Жесткое, Хрипы сухие', percussion: 'Коробочный звук справа, Коробочный звук слева' }),
        tpl(30, 'Внебольничная пневмония', '[J18.9] Пневмония неуточненная', 'Внебольничная правосторонняя нижнедолевая пневмония, средней тяжести. ДН 1 ст.', 'На температуру 38.8°C, озноб, кашель с мокротой, боль справа при вдохе, одышку.', 'Болеет 4-й день, жаропонижающие без эффекта, наросла одышка.', { pastDiseases: 'ОРВИ', generalState: 'Средней степени тяжести', bp: '120/75', pulse: '96', rr: '22', temp: '38.6', saturation: '94', lungs: 'Ослабленное, Хрипы влажные, Хрипы справа', percussion: 'Притупление звука справа', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(31, 'ОРВИ / Гипертермия', '[J06.9] Острая инфекция верхних дыхательных путей неуточненная', 'ОРИ, ринофарингит. Гипертермический синдром.', 'На температуру 39.0°C, озноб, ломоту в теле, першение в горле, насморк.', 'Болеет 2-е сутки, ибупрофен со слабым кратковременным эффектом.', { pastDiseases: 'ОРИ', generalState: 'Средней степени тяжести', bp: '120/80', pulse: '92', rr: '18', temp: '38.9', skin: 'Теплая, Сухая, Гиперемия', mucous: 'Гиперемированы' }),
        tpl(32, 'Ложный круп (стенозирующий ларинготрахеит)', '[J05.0] Острый обструктивный ларингит [круп]', 'ОРИ. Острый стенозирующий ларинготрахеит, стеноз гортани 1-2 ст.', 'На грубый лающий кашель, осиплость голоса, шумный вдох при беспокойстве.', 'Ребенок болеет 2-й день, ночью проснулся от лающего кашля и затрудненного вдоха.', { pastDiseases: 'По возрасту', generalState: 'Средней степени тяжести', bp: '100/60', bpAdapted: '100/60', pulse: '116', rr: '28', temp: '37.8', saturation: '96', lungs: 'Жесткое', localStatus: 'Голос осиплый, кашель лающий, инспираторный стридор при волнении.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'Ингаляция Пульмикорт 1-2 мг через небулайзер + Дексаметазон 0.3-0.6 мг/кг в/м или в/в + увлажненный O2.' }),
        tpl(33, 'Острый тонзиллит (ангина)', '[J03.9] Острый тонзиллит неуточненный', 'Острый лакунарный тонзиллит. Гипертермический синдром.', 'На резкую боль в горле при глотании, температуру 39.2°C, озноб, слабость.', 'Заболел остро вчера вечером.', { pastDiseases: 'Хр. тонзиллит', generalState: 'Средней степени тяжести', bp: '120/80', pulse: '94', temp: '39.1', skin: 'Теплая, Сухая, Гиперемия', mucous: 'Гиперемированы, миндалины гипертрофированы с гнойным налетом в лакунах', localStatus: 'Подчелюстные лимфоузлы увеличены, болезненны.' }),
        tpl(34, 'Носовое кровотечение (эпистаксис)', '[R04.0] Носовое кровотечение', 'Переднее носовое кровотечение. АГ 2 ст., риск 3.', 'На выделение алой крови из носовых ходов, головокружение.', 'Началось спонтанно 30 мин назад на фоне подъема АД.', { pastDiseases: 'АГ', bp: '165/95', bpAdapted: '130/80', pulse: '82', localStatus: 'Из правого носового хода кровотечение, по задней стенке глотки не стекает. Передняя тампонада, гемостаз.' }),
        tpl(35, 'Острый гастроэнтерит (ОКИ / ПТИ)', '[A09] Диарея и гастроэнтерит предположительно инфекционного происхождения', 'Острый гастроэнтерит (ПТИ), средней степени тяжести.', 'На многократный жидкий стул, тошноту, рвоту, боли вокруг пупка, температуру 37.6°C.', 'Заболел сегодня ночью после употребления недоброкачественной пищи.', { pastDiseases: 'Хр. гастрит', generalState: 'Средней степени тяжести', bp: '110/70', pulse: '90', temp: '37.6', abdomen: 'Участвует в акте дыхания, Мягкий, Болезненный в области', tongue: 'Суховат, обложен', stool: 'УЧАЩЕН', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(36, 'Острый аппендицит', '[K35.8] Острый аппендицит другой и неуточненный', 'Острый аппендицит.', 'На постоянные боли в правой подвздошной области, тошноту, однократную рвоту.', 'Заболел 8 ч назад в эпигастрии, затем боли сместились в правую подвздошную область (с-м Кохера).', { pastDiseases: 'Отрицает', generalState: 'Средней степени тяжести', bp: '120/80', pulse: '88', rr: '17', temp: '37.5', saturation: '99', abdomen: 'Напряженный, Болезненный в области', tongue: 'Сухой, обложен', peritoneal: 'ДА', localStatus: 'Пальпация болезненна и напряжена в правой подвздошной области. Симптомы Ровзинга, Ситковского, Щеткина-Блюмберга +.', attack: 'НЕ КУПИРОВАН', therapyEffect: 'БЕЗ ЭФФЕКТА', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(37, 'Острый панкреатит', '[K85] Острый панкреатит', 'Острый панкреатит.', 'На опоясывающие боли в верхних отделах живота, многократную рвоту без облегчения.', 'Ухудшение через 3 часа после жирной пищи.', { pastDiseases: 'Хр. панкреатит', generalState: 'Средней степени тяжести', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'На боку с поджатыми ногами', pulse: '92', rr: '18', temp: '37.1', abdomen: 'Напряженный, Болезненный в области', tongue: 'Суховат, обложен', localStatus: 'Болезненность в эпигастрии и точке Мейо-Робсона.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(38, 'Острый холецистит / Печеночная колика', '[K80.0] Камни желчного пузыря с острым холециститом', 'ЖКБ: Острый калькулезный холецистит. Печеночная колика.', 'На острые боли в правом подреберье с иррадиацией в правую лопатку, горечь во рту, тошноту.', 'После погрешности в диете 4 ч назад, дротаверин без эффекта.', { pastDiseases: 'ЖКБ', generalState: 'Средней степени тяжести', bp: '135/85', pulse: '86', rr: '17', temp: '37.3', abdomen: 'Участвует в акте дыхания, Мягкий, Болезненный в области', localStatus: 'Положительные симптомы Ортнера, Мерфи, Мюсси.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(39, 'Перфоративная язва желудка / ДПК', '[K25.1] Язва желудка острая с прободением', 'Язвенная болезнь, осложненная перфорацией. Разлитой перитонит.', 'На внезапную нестерпимую «кинжальную» боль в эпигастрии и по всему животу, холодный пот.', '1 час назад внезапно возникла «кинжальная» боль.', { pastDiseases: 'Язвенная болезнь', generalState: 'Тяжелое', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'На боку с приведенными к животу ногами', bp: '100/60', pulse: '104', rr: '22', temp: '37.4', skin: 'Холодная, Влажная, Бледная', abdomen: 'Напряженный, Болезненный в области', tongue: 'Сухой, обложен', peritoneal: 'ДА', localStatus: 'Доскообразный живот, с-м Щеткина-Блюмберга + во всех отделах, исчезновение печеночной тупости.', attack: 'НЕ КУПИРОВАН', therapyEffect: 'БЕЗ ЭФФЕКТА', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(40, 'Острая кишечная непроходимость (ОКН)', '[K56.6] Другая и неуточненная кишечная непроходимость', 'Острая спаечная кишечная непроходимость.', 'На сильные схваткообразные боли, вздутие живота, многократную рвоту, задержку стула и газов.', 'Беспокоит 10 часов, газы не отходят.', { pastDiseases: 'Спаечная болезнь', generalState: 'Тяжелое', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Беспокойное из-за болей', bp: '115/75', pulse: '98', rr: '20', temp: '37.2', abdomen: 'Напряженный, Болезненный в области', tongue: 'Сухой, обложен', stool: 'ОТСУТСТВУЕТ', localStatus: 'Симптомы Валя, Склярова («шум плеска») +.', attack: 'НЕ КУПИРОВАН', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(41, 'Ущемленная паховая грыжа', '[K40.3] Односторонняя или неуточненная паховая грыжа с непроходимостью', 'Ущемленная правосторонняя паховая грыжа.', 'На резкую боль в паху, невправимость грыжевого выпячивания, тошноту.', '2 часа назад после подъема тяжести вышла грыжа и перестала вправляться.', { pastDiseases: 'Паховая грыжа', generalState: 'Средней степени тяжести', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Лежа на спине', pulse: '88', localStatus: 'В правой паховой области плотноэластическое резко болезненное невправимое образование 5х4 см, с-м кашлевого толчка отрицательный.', attack: 'НЕ КУПИРОВАН', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(42, 'Желудочно-кишечное кровотечение (ЖКК)', '[K92.2] Желудочно-кишечное кровотечение неуточненное', 'Состоявшееся желудочно-кишечное кровотечение. Постгеморрагическая анемия.', 'На рвоту «кофейной гущей», черный дегтеобразный стул (мелена), резкую слабость.', 'Со вчерашнего вечера черный стул, утром рвота темным содержимым.', { pastDiseases: 'Язвенная болезнь', generalState: 'Тяжелое', position: 'ПАССИВНОЕ', bp: '95/60', pulse: '108', rr: '20', temp: '36.4', saturation: '96', skin: 'Холодная, Влажная, Бледная', localStatus: 'Следы мелены.', attack: 'НЕ КУПИРОВАН', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(43, 'Почечная колика (МКБ)', '[N20.0] Камни почки', 'Мочекаменная болезнь. Правосторонняя почечная колика.', 'На нестерпимые схваткообразные боли в правой пояснице с иррадиацией в пах, частое болезненное мочеиспускание.', 'Боли остро 2 часа назад. Пациент беспокоен.', { pastDiseases: 'МКБ', generalState: 'Средней степени тяжести', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Мечется от боли', behavior: 'ВОЗБУЖДЕННОЕ', bp: '140/90', pulse: '88', rr: '18', temp: '36.7', urination: 'УЧАЩЕНО', localStatus: 'Симптом поколачивания резко положительный справа.' }),
        tpl(44, 'Острая задержка мочи (ОЗМ)', '[R33] Задержка мочи', 'ДГПЖ. Острая задержка мочи.', 'На невозможность мочеиспускания 10 часов, распирающие боли над лоном.', 'Страдает ДГПЖ. Мочеиспускание отсутствует с утра.', { pastDiseases: 'ДГПЖ', generalState: 'Средней степени тяжести', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Согнувшись из-за болей над лоном', bp: '145/85', pulse: '84', urination: 'ЗАДЕРЖКА МОЧЕИСПУСКАНИЯ', localStatus: 'Над лоном пальпируется увеличенный, напряженный мочевой пузырь.' }),
        tpl(45, 'Острый пиелонефрит', '[N10] Острый тубулоинтерстициальный нефрит', 'Острый правосторонний пиелонефрит.', 'На тупые боли в пояснице справа, температуру 38.9°C с ознобом, слабость.', 'Болеет 2 дня после переохлаждения.', { pastDiseases: 'Хр. пиелонефрит', generalState: 'Средней степени тяжести', bp: '120/75', pulse: '94', temp: '38.8', urination: 'УЧАЩЕНО', localStatus: 'Симптом поколачивания положительный справа.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(46, 'Угроза прерывания беременности', '[O20.0] Угрожающий аборт', 'Беременность 10 недель. Угрожающий самопроизвольный выкидыш.', 'На тянущие боли внизу живота и пояснице, мажущие кровянистые выделения.', 'Боли и выделения появились около 2 часов назад.', { pastDiseases: 'Отрицает', generalState: 'Средней степени тяжести', bp: '110/70', bpAdapted: '110/70', pulse: '82', abdomen: 'Участвует в акте дыхания, Мягкий, Болезненный в области', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(47, 'Срочные роды (I период родов)', '[O80.0] Самопроизвольные роды в затылочном предлежании', 'Беременность 39 недель. Первый период срочных родов.', 'На регулярные схватки каждые 7-8 минут по 30 секунд.', 'Родовая деятельность началась около 2 часов назад. Воды не изливались, шевеление плода ощущает.', { pastDiseases: 'Отрицает', bp: '115/75', bpAdapted: '110/70', pulse: '84', localStatus: 'Матка в тонусе во время схватки. Сердцебиение плода ясное, ритмичное 140 в мин.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(48, 'Гипогликемия (купированная)', '[E16.2] Гипогликемия неуточненная', 'Сахарный диабет 1 типа. Гипогликемическое состояние, купированное.', 'На умеренную слабость после введения глюкозы. До помощи — потливость, тремор, спутанность.', 'После инъекции инсулина пропустил прием пищи, покрылся холодным потом.', { pastDiseases: 'Сахарный диабет', generalState: 'Средней степени тяжести', bp: '120/80', pulse: '84', temp: '36.4', glucose: '2.4' }),
        tpl(49, 'Гипергликемия / Кетоацидоз', '[E10.1] Инсулинзависимый сахарный диабет с кетоацидозом', 'Сахарный диабет 1 типа, декомпенсация. Диабетический кетоацидоз.', 'На жажду, сухость во рту, полиурию, тошноту, рвоту, сонливость.', 'Ухудшение 2 дня на фоне пропуска инсулина.', { pastDiseases: 'Сахарный диабет', generalState: 'Тяжелое', consciousness: 'Приглушенное', bp: '105/65', pulse: '102', rr: '22', glucose: '24.6', skin: 'Сухая, Теплая', tongue: 'Сухой, обложен', acetone: 'ДА', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(50, 'Отек Квинке / Крапивница', '[T78.3] Ангионевротический отек', 'Острая аллергическая реакция (отек Квинке, генерализованная крапивница).', 'На отек губ и век, зудящую сыпь по всему телу.', 'Через 30 минут после еды/препарата появилась сыпь и отек лица. Дыхание свободное.', { pastDiseases: 'Аллергический дерматит', generalState: 'Средней степени тяжести', bp: '120/75', pulse: '84', rash: 'ДА', localStatus: 'Плотный отек губ и параорбитальной клетчатки, уртикарная сыпь. Голос звучный, стридора нет.' }),
        tpl(51, 'Анафилактический шок', '[T78.2] Анафилактический шок неуточненный', 'Анафилактический шок, гемодинамический вариант, средней степени тяжести.', 'На резкую слабость, потемнение в глазах, чувство жара, нехватку воздуха, зуд.', 'Развился остро через 10 мин после приема препарата / укуса насекомого.', { pastDiseases: 'Аллергия в анамнезе', generalState: 'Тяжелое', consciousness: 'Приглушенное', position: 'ПАССИВНОЕ', behavior: 'ВОЗБУЖДЕННОЕ', bp: '70/40', pulse: '118', rr: '24', temp: '36.4', saturation: '91', glucose: '6.0', skin: 'Холодная, Влажная, Бледная', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'Адреналин 0.1% 0.3-0.5 мл в/м в бедро + NaCl 0.9% 500-1000 мл в/в струйно + Преднизолон 90-120 мг в/в + Супрастин/Димедрол, О2.' }),
        tpl(52, 'Острое алкогольное отравление', '[T51.0] Токсическое действие этанола', 'Острое бытовое отравление этанолом тяжелой степени.', 'Сбор жалоб затруднен.', 'Обнаружен окружающими после употребления алкоголя, резкий запах алкоголя.', { pastDiseases: 'Хр. алкоголизм', generalState: 'Тяжелое', consciousness: 'Сопор', position: 'ПАССИВНОЕ', glasgowScore: '9', bp: '105/65', pulse: '92', rr: '14', temp: '36.0', saturation: '94', glucose: '4.5', speech: 'НАРУШЕНА', gait: 'НАРУШЕНА', skin: 'Гиперемия, Влажная', alcohol: 'ДА', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(53, 'Отравление опиоидами', '[T40.2] Отравление другими опиоидами', 'Острое отравление веществом опиоидного ряда. Кома 1-2 ст. ОДН.', 'Контакт невозможен.', 'Обнаружен без сознания. По данным очевидцев, употребляет психоактивные вещества.', { pastDiseases: 'Наркозависимость', generalState: 'Тяжелое', consciousness: 'Кома', position: 'ПАССИВНОЕ', glasgowScore: '6', bp: '90/50', pulse: '56', rr: '6', temp: '36.0', saturation: '82', glucose: '4.8', pupils: 'МИОЗ', lightReaction: 'НЕТ', speech: 'ОТСУТСТВУЕТ', gait: 'НЕ ОЦЕНИВАЛАСЬ', muscleTone: 'Снижен', skin: 'Бледная, Цианоз', localStatus: 'Зрачки точечные, брадипноэ. Следы в/в инъекций.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ', recommendedTherapy: 'ИВЛ / О2 + Налоксон 0.4-0.8 мг в/в (до 2 мг) титрованно.' }),
        tpl(54, 'Отравление угарным газом (CO)', '[T58] Токсическое действие окиси углерода', 'Острое ингаляционное отравление угарным газом.', 'На сильную головную боль, головокружение, шум в ушах, тошноту, рвоту, одышку.', 'Пострадал в задымленном помещении. Эвакуирован на свежий воздух.', { pastDiseases: 'Отрицает', generalState: 'Средней степени тяжести', pulse: '102', rr: '22', saturation: '92', skin: 'Гиперемия', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(55, 'Паническая атака / ВСД', '[F45.3] Соматоформная дисфункция вегетативной нервной системы', 'Соматоформная дисфункция вегетативной нервной системы, криз (паническая атака).', 'На нехватку воздуха, сердцебиение, внутреннюю дрожь, страх.', 'Приступ развился 30 минут назад после эмоционального стресса.', { pastDiseases: 'ВСД', behavior: 'ВОЗБУЖДЕННОЕ', bp: '135/85', bpAdapted: '115/75', pulse: '98', rr: '20', saturation: '99', ecgBefore: 'Синусовая тахикардия ЧСС 98 в мин. Очаговой патологии нет.' }),
        tpl(56, 'ЗЧМТ / Сотрясение головного мозга (СГМ)', '[S06.0] Сотрясение головного мозга', 'ЗЧМТ: Сотрясение головного мозга. Ушиб мягких тканей головы.', 'На головную боль, головокружение, тошноту, боль в месте ушиба.', 'Час назад упал на улице, ударился затылком. Кратковременная потеря сознания, однократная рвота.', { pastDiseases: 'Отрицает', generalState: 'Средней степени тяжести', bp: '125/80', pulse: '76', nystagmus: 'ГОРИЗОНТАЛЬНЫЙ', localStatus: 'В затылочной области припухлость 3х3 см, кожа цела. В позе Ромберга пошатывание, мелкоразмашистый горизонтальный нистагм.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(57, 'Тяжелая ЧМТ / Ушиб головного мозга', '[S06.2] Диффузная травма головного мозга', 'ЗЧМТ тяжелой степени: Ушиб головного мозга со сдавлением. Субдуральная гематома. Кома 1 ст.', 'Контакт невозможен.', 'Падение с высоты (или ДТП) 40 мин назад. С момента травмы без сознания.', { pastDiseases: 'Неизвестен', generalState: 'Тяжелое', consciousness: 'Кома', position: 'ПАССИВНОЕ', glasgowScore: '7', bp: '160/95', pulse: '56', rr: '20', saturation: '92', pupils: 'АНИЗОКАРИЯ', lightReaction: 'НЕТ', speech: 'ОТСУТСТВУЕТ', gait: 'НЕ ОЦЕНИВАЛАСЬ', meningealSymptoms: 'Ригидность затылочных мышц', localStatus: 'В теменно-височной области гематома 6х5 см. Анизокория D>S, брадикардия, ригидность затылочных мышц.', attack: 'НЕ КУПИРОВАН', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(58, 'Перелом шейки бедра', '[S72.0] Перелом шейки бедра', 'Закрытый перелом шейки левой бедренной кости со смещением.', 'На боль в области левого тазобедренного сустава, невозможность опоры и движения.', 'Час назад упала дома с высоты собственного роста на левый бок.', { pastDiseases: 'ИБС, АГ, остеопороз', generalState: 'Средней степени тяжести', position: 'ВЫНУЖДЕННОЕ', positionDescription: 'Лежа на спине, щадит левую ногу', bp: '140/85', bpAdapted: '130/80', pulse: '84', gait: 'НАРУШЕНА', localStatus: 'Левая нога ротирована кнаружи, укорочена на 2 см. Положительный симптом «прилипшей пятки». Пульсация на стопе сохранена.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(59, 'Перелом лучевой кости в типичном месте', '[S52.5] Перелом нижнего конца лучевой кости', 'Закрытый перелом нижней трети левой лучевой кости в типичном месте со смещением.', 'На боль, отек и деформацию левого лучезапястного сустава.', '40 минут назад упал с упором на вытянутую левую руку.', { pastDiseases: 'Отрицает', pulse: '80', localStatus: 'В нижней трети предплечья штыкообразная деформация, отек, резкая болезненность. Кровообращение и чувствительность в пальцах сохранены.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(60, 'Перелом лодыжек / голени', '[S82.8] Переломы других отделов голени', 'Закрытый двухлодыжечный перелом правой голени.', 'На резкую боль в правом голеностопном суставе, отек, невозможность наступить на ногу.', '30 минут назад подвернул ногу на лестнице.', { pastDiseases: 'Отрицает', generalState: 'Средней степени тяжести', pulse: '82', gait: 'НАРУШЕНА', localStatus: 'Голеностопный сустав деформирован, отек и болезненность в области обеих лодыжек. Пульсация на стопе сохранена.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(61, 'Перелом ребер / Ушиб грудной клетки', '[S22.3] Перелом ребра', 'Закрытая травма грудной клетки. Перелом VI-VII ребер слева.', 'На острую боль в левой половине груди, усиливающуюся при вдохе и кашле.', 'Упал 2 часа назад, ударился левым боком о край мебели.', { pastDiseases: 'АГ', generalState: 'Средней степени тяжести', bp: '135/80', pulse: '84', rr: '19', saturation: '97', lungs: 'Ослабленное', localStatus: 'Резкая локальная болезненность и крепитация в проекции VI-VII ребер слева по среднеподмышечной линии. Подкожной эмфиземы нет.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(62, 'Ушибленная / резаная рана мягких тканей', '[S01.0] Открытая рана волосистой части головы', 'Ушибленная рана теменной области головы.', 'На боль и умеренное кровотечение из раны головы.', '30 минут назад ударился головой в быту. Сознание не терял, рвоты не было.', { pastDiseases: 'Отрицает', bp: '125/80', localStatus: 'В теменной области линейная рана 3,0х0,5 см, умеренное капиллярное кровотечение. Асептическая повязка, гемостаз.', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' }),
        tpl(63, 'Термический ожог I-II степени', '[T30.2] Термический ожог второй степени, область тела не уточнена', 'Термический ожог (кипятком) I-II степени правого предплечья и кисти, площадь 4%.', 'На жгучую боль в области ожога правой руки, покраснение и пузыри.', '20 минут назад случайно пролил на руку кипяток.', { pastDiseases: 'Отрицает', generalState: 'Средней степени тяжести', pulse: '88', localStatus: 'Яркая гиперемия, отек, эпидермальные пузыри с серозным содержимым на коже правого предплечья и кисти (около 4% ПТ).', callResult: 'ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ' })
    ];

    // ==========================================
    // 4. DOM-УТИЛИТЫ И ТОЧНЫЙ АВТОПИЛОТ (1-В-1 С injector.js)
    // ==========================================
    function getRealInputs(container) {
        return Array.from(container.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])'))
            .filter(el => !el.classList.contains('MuiSelect-nativeInput') && !el.classList.contains('MuiTextareaAutosize-shadow') && el.getAttribute('aria-hidden') !== 'true' && el.tabIndex !== -1 && !isInConclusion(el));
    }

    function findInputByKw(keywords, exact = false) {
        const all = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
        for (const kw of keywords) {
            const kwL = kw.toLowerCase();
            const target = all.find(el => {
                if (!el.textContent) return false;
                const t = el.textContent.trim().toLowerCase();
                return (exact ? t === kwL : (t === kwL || t.includes(kwL))) && t.length < kwL.length + 35;
            });
            if (target) {
                const fc = target.closest('.MuiFormControl-root');
                if (fc && !isInConclusion(fc)) {
                    const inps = getRealInputs(fc);
                    if (inps.length > 0) return inps[0];
                }
                let p = target.parentElement;
                for (let i = 0; i < 3 && p && p !== document.body; i++) {
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
        const setValIfFound = (kw, fieldName, exact = false) => {
            const v = findInputByKw(Array.isArray(kw) ? kw : [kw], exact)?.value?.trim();
            if (v && !v.startsWith('__')) c[fieldName] = v;
        };
        setValIfFound('гражданство', 'citizenship', true);
        setValIfFound('нас. пункт', 'settlement');
        setValIfFound('улица', 'street');
        setValIfFound('дом', 'building', true);
        setValIfFound('квартира', 'flat', true);
        setValIfFound('уточнение места жительства', 'clarificationAddress');
        setValIfFound('место жительства (пребывания)', 'address');
        setValIfFound('экг до оказания', 'electrocardiogramBefore');
        setValIfFound('экг после оказания', 'electrocardiogramAfter');
        setValIfFound('предварительный диагноз', 'diagnosis');
        setValIfFound('жалобы', 'complaints');
        setValIfFound('анамнез заболевания', 'anamnesis');
        setValIfFound('перенесенные заболевания', 'pastIllnesses');
        setValIfFound('локальный статус', 'localStatus');
        setValIfFound('возраст', 'age', true);
        setValIfFound(['комментарий (пульс', 'пульс, ударов в минуту'], 'pulse');
        setValIfFound('частота дыхания (в минуту)', 'breathCount');
        setValIfFound('температура тела (°c)', 'skinTemp');
        setValIfFound('сатурация 1 (%)', 'saturation1');
        setValIfFound('гликемия 1 (ммоль/л)', 'glycemia1');
        setValIfFound('шкала комы глазго (баллы)', 'comaScale');
        setValIfFound('тонус мышц', 'muscleTone');
        setValIfFound('патологические рефлексы', 'pathologicalReflexes');
        setValIfFound('менингиальные знаки', 'meningealSymptoms');
        setValIfFound('плегии, парезы', 'plegia');
        setValIfFound('язык', 'tongue');
        setValIfFound('слизистые', 'mucosa');
        setValIfFound('комментарий (вынужденное)', 'positionDescription');
        setValIfFound('комментарий (периферические отеки)', 'peripheralEdemaDescription');

        const mkbVal = findInputByKw(['код по мкб'])?.value?.trim();
        if (mkbVal) {
            const m = mkbVal.match(/^\[?([A-ZА-Я]\d{2}(?:\.\d+)?)\]?\s*(.*)$/i);
            if (m) c.icd = { code: m[1].toUpperCase(), name: m[2].trim() };
        }

        const slashNodes = Array.from(document.querySelectorAll('span, p, .MuiTypography-root'))
            .filter(e => e.children.length === 0 && e.textContent?.trim() === '/' && !isInConclusion(e));
        for (const sl of slashNodes) {
            let pr = sl.parentElement;
            for (let i = 0; i < 3 && pr && pr !== document.body; i++) {
                const inps = getRealInputs(pr);
                if (inps.length === 2) {
                    const tx = (pr.innerText || pr.parentElement?.innerText || '').toLowerCase();
                    const s = inps[0].value.trim(), d = inps[1].value.trim();
                    if (s || d) {
                        if (tx.includes('адаптированное')) c.arterialPressureAdaptive = { sys: s, dia: d };
                        else if (tx.includes('ад (мм') || tx.includes('артериальн')) c.arterialPressure = { sys: s, dia: d };
                    }
                    break;
                }
                pr = pr.parentElement;
            }
        }

        const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
        const getSelectText = (kw) => {
            const kwL = kw.toLowerCase();
            const l = labels.find(e => e.textContent && e.offsetParent !== null && !isInConclusion(e) && (e.textContent.trim().toLowerCase() === kwL || e.textContent.trim().toLowerCase().includes(kwL)) && e.textContent.trim().length < kwL.length + 35);
            if (!l) return '';
            let p = l.closest('.MuiFormControl-root') || l.parentElement;
            for (let i = 0; i < 3 && p && p !== document.body; i++) {
                const b = p.querySelector('.MuiSelect-root, [role="button"]');
                if (b) {
                    const t = (b.innerText || '').trim();
                    return (t && t.toLowerCase() !== 'не выбрано') ? t : '';
                }
                p = p.parentElement;
            }
            return '';
        };
        const gs = getSelectText('общее состояние'); if (gs) c.generalState = { type: gs };
        const cs = getSelectText('сознание'); if (cs) c.consciousness = { type: cs };
        const au = getSelectText('возраст (ед.)'); if (au) c.ageType = { type: au };
        const ap = getSelectText('территориальная апо') || getSelectText('апо по месту жительства'); if (ap) c.apo = ap;
        const toArr = (kw, key) => {
            const v = getSelectText(kw);
            if (v) c[key] = v.split(',').map(s => ({ type: s.trim() })).filter(x => x.type);
        };
        toArr('кожа', 'skins');
        toArr('тоны сердца', 'heartSounds');
        toArr('дыхание', 'breaths');
        toArr('перкуссия легких', 'percussionOfLungs');
        toArr('живот', 'stomachs');

        const getActiveBtnText = (kw) => {
            const kwL = kw.toLowerCase();
            const l = labels.find(e => e.textContent && e.offsetParent !== null && !isInConclusion(e) && (e.textContent.trim().toLowerCase() === kwL || e.textContent.trim().toLowerCase().includes(kwL)) && e.textContent.trim().length < kwL.length + 35);
            if (!l) return null;
            let p = l.closest('.MuiFormControl-root') || l.parentElement;
            for (let i = 0; i < 3 && p && p !== document.body; i++) {
                const btns = Array.from(p.querySelectorAll('button')).filter(b => !isInConclusion(b));
                if (btns.length >= 2 && btns.length <= 6) {
                    const act = btns.find(b => b.className.includes('MuiButton-contained'));
                    return act ? (act.innerText || act.textContent || '').trim().toUpperCase() : '';
                }
                p = p.parentElement;
            }
            return null;
        };
        const pup = getActiveBtnText('зрачки'); if (pup !== null) c.pupils = pup ? { type: pup } : null;
        const pos = getActiveBtnText('положение'); if (pos) c.position = { type: pos };
        const beh = getActiveBtnText('поведение'); if (beh) c.behavior = { type: beh };
        const spc = getActiveBtnText('речь'); if (spc) c.speech = { type: spc };
        const fac = getActiveBtnText('лицо'); if (fac) c.face = { type: fac };
        const gat = getActiveBtnText('походка'); if (gat) c.gait = { type: gat };
        const nys = getActiveBtnText('нистагм'); if (nys) c.nystagmus = { type: nys };
        const stu = getActiveBtnText('стул'); if (stu) c.defecatio = { type: stu };
        const uri = getActiveBtnText('мочеиспускание'); if (uri) c.urinations = [{ type: uri }];
        const liv = getActiveBtnText('печень'); if (liv) c.liver = { type: liv };
        const alr = getActiveBtnText('аллергия'); if (alr) c.allergy = (alr === 'ДА');
        const lgt = getActiveBtnText('реакция на свет'); if (lgt) c.pupilsLight = (lgt === 'ДА');
        const rsh = getActiveBtnText('высыпания'); if (rsh) c.skinRashes = (rsh === 'ДА');
        const asc = getActiveBtnText('асцит'); if (asc) c.ascites = (asc === 'ДА');
        const edm = getActiveBtnText('периферические отеки'); if (edm) c.peripheralEdema = (edm === 'ДА');
        const prt = getActiveBtnText('симптомы раздражения брюшины'); if (prt) c.stomachSymptoms = (prt === 'ДА');
        const prs = getActiveBtnText('перистальтика'); if (prs) c.peristalsis = (prs === 'ДА');

        const qtyNodes = Array.from(document.querySelectorAll('span, p, .MuiTypography-root'))
            .filter(e => e.children.length === 0 && e.textContent && e.textContent.includes('Количество:'));
        if (qtyNodes.length > 0) {
            const meds = [];
            for (const q of qtyNodes) {
                let card = q.parentElement;
                while (card && card !== document.body) {
                    const lines = (card.innerText || '').split('\n').map(s => s.trim()).filter(Boolean);
                    const qi = lines.findIndex(l => l.includes('Количество:'));
                    if (qi > 0) {
                        const nm = lines[0], qt = lines[qi].replace(/Количество:\s*/i, '').trim();
                        const rt = (lines.find(l => l.includes('Путь введения:')) || '').replace(/Путь введения:\s*/i, '').trim();
                        const rtStr = (rt && !rt.includes('не определен')) ? ` [${rt}]` : '';
                        if (nm && !nm.includes('Лекарственные') && !nm.includes('ДОБАВИТЬ')) meds.push(`${nm} — ${qt}${rtStr}`);
                        break;
                    }
                    card = card.parentElement;
                }
            }
            if (meds.length > 0) c._domMedications = Array.from(new Set(meds)).join(';\n• ');
        }
    }

    window.insertIntoCardField = function(fieldKeyword, textToInsert, appendMode) {
        try {
            const kwL = String(fieldKeyword).toLowerCase();
            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
            for (const l of labels) {
                if (!l.innerText || l.offsetParent === null || l.children.length > 3 || isInConclusion(l)) continue;
                const t = l.innerText.trim().toLowerCase();
                if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 35) {
                    const fc = l.closest('.MuiFormControl-root') || l.parentElement?.parentElement;
                    if (!fc || isInConclusion(fc)) continue;
                    const inps = getRealInputs(fc);
                    if (inps.length > 0) {
                        const inp = inps[0];
                        const cur = (inp.value || '').trim();
                        const finalVal = (appendMode && cur) ? (cur + '\n' + textToInsert) : textToInsert;
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
    const savedScrollContainers = Array.from(document.querySelectorAll('main, .MuiDialog-paper, [role="tabpanel"], .MuiContainer-root'))
        .filter(el => el.scrollTop > 0)
        .map(el => ({ el, top: el.scrollTop }));

    const activeTabBefore = Array.from(document.querySelectorAll('[role="tab"], button'))
        .find(b => b.getBoundingClientRect().top < 140 && (b.getAttribute('aria-selected') === 'true' || b.className.includes('Mui-selected')));

    if (activeTabBefore && (activeTabBefore.innerText || '').toUpperCase().includes('ЗАКЛЮЧЕНИЕ')) return;

    function restoreScreenPosition() {
        window.scrollTo(0, savedScrollWinY);
        document.documentElement.scrollTop = savedScrollWinY;
        for (const item of savedScrollContainers) item.el.scrollTop = item.top;
    }

    window.b103IsApplying = true;
    const fillBtn = document.getElementById('btn-fill-db');
    if (fillBtn) fillBtn.innerText = '⏳';

    let hideStyle = document.getElementById('b103-hide-menus');
    if (!hideStyle) {
        hideStyle = document.createElement('style');
        hideStyle.id = 'b103-hide-menus';
        hideStyle.textContent = '.MuiPopover-root, .MuiModal-root, .MuiAutocomplete-popper { opacity: 0 !important; pointer-events: auto !important; transition: none !important; }';
        document.head.appendChild(hideStyle);
    }

    function isElemVisible(el) {
        if (!el || !el.isConnected) return false;
        if (el.getAttribute('aria-hidden') === 'true') return false;
        const st = window.getComputedStyle(el);
        if (st.display === 'none' || st.visibility === 'hidden') return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
    }

    function getActivePopovers() {
        return Array.from(document.querySelectorAll('.MuiPopover-root, .MuiModal-root, .MuiAutocomplete-popper')).filter(isElemVisible);
    }

    async function closeAnyOpenMenu() {
        for (let attempt = 0; attempt < 5; attempt++) {
            const popovers = getActivePopovers();
            const expandedBtns = Array.from(document.querySelectorAll('[aria-expanded="true"]'));
            if (!popovers.length && !expandedBtns.length) break;

            const escInit = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
            if (document.activeElement && document.activeElement !== document.body) {
                document.activeElement.dispatchEvent(new KeyboardEvent('keydown', escInit));
                document.activeElement.dispatchEvent(new KeyboardEvent('keyup', escInit));
                if (document.activeElement.blur) document.activeElement.blur();
            }
            for (const pop of popovers) {
                const list = pop.querySelector('[role="listbox"], [role="menu"], ul, .MuiMenu-list') || pop;
                list.dispatchEvent(new KeyboardEvent('keydown', escInit));
                list.dispatchEvent(new KeyboardEvent('keyup', escInit));
                const backdrop = pop.querySelector('.MuiBackdrop-root') || (pop.firstElementChild?.classList.contains('MuiBackdrop-root') ? pop.firstElementChild : null);
                if (backdrop) {
                    backdrop.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                    backdrop.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window, button: 0 }));
                    backdrop.click();
                }
            }
            for (const b of expandedBtns) {
                b.dispatchEvent(new KeyboardEvent('keydown', escInit));
                if (attempt >= 2) {
                    b.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 }));
                    b.click();
                }
            }
            restoreScreenPosition();
            await new Promise(r => setTimeout(r, 45));
        }
    }

    try {
        const data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;

        function setVal(inp, val, allowEmpty = false) {
            if (!inp || val === undefined || val === null || inp.disabled || isInConclusion(inp)) return;
            const str = cleanVal(val);
            const prev = cleanVal(inp.value);
            if (!allowEmpty && !str) return;
            if (prev === str) return;
            try {
                const proto = inp.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(inp, str); else inp.value = str;
                if (inp._valueTracker) inp._valueTracker.setValue(prev + '__force_diff');
                inp.dispatchEvent(new Event('input', { bubbles: true }));
                inp.dispatchEvent(new Event('change', { bubbles: true }));
            } catch (e) { inp.value = str; }
        }

        function getRealInputsAutofill(container) {
            return Array.from(container.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])'))
                .filter(el => !el.classList.contains('MuiSelect-nativeInput') && !el.classList.contains('MuiTextareaAutosize-shadow') && el.getAttribute('aria-hidden') !== 'true' && el.tabIndex !== -1 && !isInConclusion(el));
        }

        function findInputs(keywords, exact = false) {
            const res = [];
            const searchingFio = keywords.some(k => k.toLowerCase() === 'фио');
            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));

            for (const kw of keywords) {
                const kwL = kw.toLowerCase();
                for (const l of labels) {
                    if (!l.textContent || l.children.length > 3 || isInConclusion(l)) continue;

                    const t = l.textContent.trim().toLowerCase();
                    const isMatch = exact ? t === kwL : (t === kwL || t.includes(kwL));
                    if (isMatch && t.length < kwL.length + 35) {
                        let foundInp = null;
                        const fc = l.closest('.MuiFormControl-root');
                        if (fc && !isInConclusion(fc)) {
                            const inps = getRealInputsAutofill(fc);
                            if (inps.length === 1) foundInp = inps[0];
                        }
                        if (!foundInp) {
                            let p = l.parentElement;
                            for (let i = 0; i < 3 && p && p !== document.body && !isInConclusion(p); i++) {
                                const inps = getRealInputsAutofill(p);
                                if (inps.length === 1) { foundInp = inps[0]; break; }
                                else if (inps.length > 1) break;
                                p = p.parentElement;
                            }
                        }
                        if (foundInp && !res.includes(foundInp)) {
                            const holderText = cleanVal((foundInp.closest('.MuiFormControl-root') || foundInp.parentElement)?.innerText).toLowerCase();
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

        const setAll = (kws, val, ex = false, allowEmpty = false) => {
            if (val !== undefined && val !== null && (allowEmpty || cleanVal(val) !== '')) {
                findInputs(kws, ex).forEach(i => setVal(i, val, allowEmpty));
            }
        };

        function formatBirthDateAndCalcAge(rawDateStr) {
            if (!rawDateStr) return { dateStr: '', ageStr: '', ageUnitGuess: '' };
            const clean = String(rawDateStr).trim();
            if (clean.startsWith('__')) return { dateStr: '', ageStr: '', ageUnitGuess: '' };
            let datePart = '', timePart = '00:00';
            if (clean.includes('-')) {
                const p = clean.split('T'), d = p[0].split('-');
                if (d.length === 3) datePart = `${d[2]}.${d[1]}.${d[0]}`;
                if (p[1] && /^\d{2}:\d{2}/.test(p[1].trim())) timePart = p[1].trim().substring(0, 5);
            } else if (clean.includes('.')) {
                const p = clean.split('/'); datePart = p[0].trim();
                if (p[1] && /^\d{2}:\d{2}/.test(p[1].trim())) timePart = p[1].trim().substring(0, 5);
            }
            if (!datePart || datePart.includes('_')) return { dateStr: '', ageStr: '', ageUnitGuess: '' };

            const now = new Date();
            const todayStr = `${String(now.getDate()).padStart(2, '0')}.${String(now.getMonth() + 1).padStart(2, '0')}.${now.getFullYear()}`;
            let calcAge = '', ageUnitGuess = '';
            const dp = datePart.split('.');
            if (dp.length === 3) {
                const bd = parseInt(dp[0], 10), bm = parseInt(dp[1], 10), by = parseInt(dp[2], 10);
                const bObj = new Date(by, bm - 1, bd);
                if (by > 1900 && bObj <= now) {
                    let yrs = now.getFullYear() - by;
                    if ((now.getMonth() + 1) < bm || ((now.getMonth() + 1) === bm && now.getDate() < bd)) yrs--;
                    if (yrs >= 1) {
                        calcAge = String(yrs); ageUnitGuess = 'Лет';
                    } else {
                        let mos = (now.getFullYear() - by) * 12 + ((now.getMonth() + 1) - bm);
                        if (now.getDate() < bd) mos--;
                        if (mos >= 1) {
                            calcAge = String(mos); ageUnitGuess = 'Месяцев';
                        } else {
                            const days = Math.floor((now - bObj) / 86400000);
                            calcAge = String(Math.max(1, days)); ageUnitGuess = 'Дней';
                        }
                    }
                }
            }
            return { dateStr: (datePart !== todayStr) ? `${datePart} / 00:00` : `${datePart} / ${timePart}`, ageStr: calcAge, ageUnitGuess };
        }

        async function selectDropdown(keywords, valStr, forceMulti = false, allowClearMulti = false) {
            const vals = cleanVal(valStr).split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
            if (!vals.length && !(forceMulti && allowClearMulti)) return;

            const matchVal = (ot, v) => {
                if (!ot || ot === 'не выбрано') return false;
                if (ot === v || ot.includes(v) || v.includes(ot)) return true;
                if (v.startsWith('месяц') && ot.startsWith('месяц')) return true;
                if ((v === 'лет' || v.startsWith('год')) && (ot === 'лет' || ot.startsWith('год'))) return true;
                if ((v.startsWith('дн') || v === 'день') && (ot.startsWith('дн') || ot === 'день')) return true;
                if (v.startsWith('час') && ot.startsWith('час')) return true;
                return false;
            };

            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
            const ctrls = [];
            for (const kw of keywords) {
                const kwL = kw.toLowerCase();
                for (const l of labels) {
                    if (!l.textContent || l.children.length > 3 || isInConclusion(l)) continue;
                    const t = l.textContent.trim().toLowerCase();
                    if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 35) {
                        let p = l.closest('.MuiFormControl-root') || l.parentElement;
                        for (let i = 0; i < 3 && p && p !== document.body && !isInConclusion(p); i++) {
                            const selBtns = p.querySelectorAll('.MuiSelect-root, [role="button"]');
                            if (selBtns.length === 1) {
                                if (!ctrls.includes(p)) ctrls.push(p);
                                break;
                            } else if (selBtns.length > 1) break;
                            p = p.parentElement;
                        }
                    }
                }
            }

            for (const c of ctrls) {
                const btn = c.querySelector('.MuiSelect-root, [role="button"]');
                if (!btn || btn.classList.contains('Mui-disabled') || isInConclusion(btn)) continue;

                const isMulti = forceMulti || btn.classList.contains('MuiSelect-multiple') || vals.length > 1;
                const curText = cleanVal(btn.innerText).toLowerCase();
                const curItems = (!curText || curText === 'не выбрано') ? [] : curText.split(',').map(s => s.trim()).filter(Boolean);

                if (!isMulti && vals.length > 0 && matchVal(curText, vals[0])) continue;
                if (isMulti && curItems.length === vals.length && vals.every(v => curItems.some(ci => matchVal(ci, v)))) continue;

                await closeAnyOpenMenu();
                btn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                restoreScreenPosition();
                await new Promise(r => setTimeout(r, 60));
                if (getActivePopovers().length === 0) {
                    btn.click();
                    restoreScreenPosition();
                }

                let activePops = [];
                for (let w = 0; w < 6; w++) {
                    activePops = getActivePopovers();
                    if (activePops.length > 0 && activePops[activePops.length - 1].querySelector('li[role="option"], .MuiMenuItem-root')) break;
                    await new Promise(r => setTimeout(r, 55));
                }

                const popover = activePops.length > 0 ? activePops[activePops.length - 1] : null;
                const opts = popover ? Array.from(popover.querySelectorAll('li[role="option"], .MuiMenuItem-root')) : [];

                if (opts.length > 0) {
                    const hasCheckboxes = opts.some(o => !!o.querySelector('input[type="checkbox"], .MuiCheckbox-root'));
                    const treatAsMulti = isMulti || hasCheckboxes;

                    if (!treatAsMulti) {
                        const match = opts.find(o => matchVal(cleanVal(o.innerText).toLowerCase(), vals[0]));
                        if (match) {
                            match.click();
                            restoreScreenPosition();
                            await new Promise(r => setTimeout(r, 80));
                        }
                        if (getActivePopovers().length > 0) await closeAnyOpenMenu();
                    } else {
                        const wanted = new Set();
                        for (const v of vals) {
                            const m = opts.find(o => matchVal(cleanVal(o.innerText).toLowerCase(), v));
                            if (m) wanted.add(m);
                        }
                        for (const o of opts) {
                            const shouldBe = wanted.has(o);
                            const isSel = o.classList.contains('Mui-selected') || o.getAttribute('aria-selected') === 'true' || !!o.querySelector('input[type="checkbox"]:checked');
                            if (shouldBe !== isSel) {
                                o.click();
                                restoreScreenPosition();
                                await new Promise(r => setTimeout(r, 35));
                            }
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

            const mkbInputs = findInputs(['код по мкб']);
            if (!mkbInputs.length) return;
            const inp = mkbInputs[0];

            if (inp.value && inp.value.toUpperCase().includes(`[${searchCode}]`)) return;

            await closeAnyOpenMenu();
            const autoRoot = inp.closest('.MuiAutocomplete-root') || inp.closest('.MuiFormControl-root') || inp.parentElement;
            const popupBtn = autoRoot ? autoRoot.querySelector('.MuiAutocomplete-popupIndicator') : null;

            try { inp.focus({ preventScroll: true }); } catch (e) { inp.focus(); }
            restoreScreenPosition();
            setVal(inp, searchCode);
            await new Promise(r => setTimeout(r, 120));
            if (!Array.from(document.querySelectorAll('.MuiAutocomplete-popper')).some(isElemVisible) && popupBtn) {
                popupBtn.click();
                restoreScreenPosition();
            }

            let targetOpt = null;
            for (let w = 0; w < 12; w++) {
                const poppers = Array.from(document.querySelectorAll('.MuiAutocomplete-popper')).filter(isElemVisible);
                const popper = poppers[poppers.length - 1];
                if (popper) {
                    const opts = Array.from(popper.querySelectorAll('li[role="option"], .MuiAutocomplete-option, .MuiMenuItem-root'))
                        .filter(o => {
                            const tx = cleanVal(o.innerText).toLowerCase();
                            return tx && !tx.includes('загрузка') && !tx.includes('нет вариантов');
                        });
                    if (opts.length > 0) {
                        targetOpt = opts.find(o => cleanVal(o.innerText).toUpperCase().includes(`[${searchCode}]`))
                            || opts.find(o => cleanVal(o.innerText).toUpperCase().startsWith(searchCode))
                            || opts.find(o => cleanVal(o.innerText).toUpperCase().includes(searchCode))
                            || opts[0];
                        if (targetOpt) break;
                    }
                }
                await new Promise(r => setTimeout(r, 80));
            }

            if (targetOpt) {
                targetOpt.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                targetOpt.click();
                restoreScreenPosition();
                await new Promise(r => setTimeout(r, 80));
            }
            if (inp.blur) inp.blur();
            await closeAnyOpenMenu();
        }

        async function fillSimpleAutocomplete(keywords, targetVal, skipIfFilled = false) {
            const cleanTarget = cleanVal(targetVal);
            if (!cleanTarget) return;
            const inps = findInputs(keywords);
            if (!inps.length) return;
            const inp = inps[0];
            const cur = cleanVal(inp.value);
            const fullTargetL = cleanTarget.toLowerCase();
            const shortSearch = cleanTarget.split('(')[0].trim();
            const shortTargetL = shortSearch.toLowerCase();

            if (skipIfFilled && cur) return;
            if (cur && (cur.toLowerCase() === fullTargetL || cur.toLowerCase().startsWith(shortTargetL))) return;

            await closeAnyOpenMenu();
            const autoRoot = inp.closest('.MuiAutocomplete-root') || inp.closest('.MuiFormControl-root') || inp.parentElement;
            const clearBtn = autoRoot ? autoRoot.querySelector('.MuiAutocomplete-clearIndicator') : null;
            const popupBtn = autoRoot ? autoRoot.querySelector('.MuiAutocomplete-popupIndicator') : null;
            try { inp.focus({ preventScroll: true }); } catch (e) { inp.focus(); }
            if (cur && clearBtn) {
                clearBtn.click();
                await new Promise(r => setTimeout(r, 50));
            }
            restoreScreenPosition();
            setVal(inp, shortSearch, true);
            await new Promise(r => setTimeout(r, 140));
            if (!Array.from(document.querySelectorAll('.MuiAutocomplete-popper')).some(isElemVisible) && popupBtn) {
                popupBtn.click();
                restoreScreenPosition();
            }

            let targetOpt = null;
            for (let w = 0; w < 12; w++) {
                const poppers = Array.from(document.querySelectorAll('.MuiAutocomplete-popper')).filter(isElemVisible);
                const popper = poppers[poppers.length - 1];
                if (popper) {
                    const opts = Array.from(popper.querySelectorAll('li[role="option"], .MuiAutocomplete-option, .MuiMenuItem-root'))
                        .filter(o => {
                            const tx = cleanVal(o.innerText).toLowerCase();
                            return tx && !tx.includes('загрузка') && !tx.includes('нет вариантов');
                        });
                    if (opts.length > 0) {
                        targetOpt = opts.find(o => cleanVal(o.innerText).toLowerCase() === fullTargetL)
                            || opts.find(o => cleanVal(o.innerText).toLowerCase().startsWith(shortTargetL))
                            || opts.find(o => cleanVal(o.innerText).toLowerCase().includes(shortTargetL))
                            || opts[0];
                        if (targetOpt) break;
                    }
                }
                await new Promise(r => setTimeout(r, 80));
            }
            if (targetOpt) {
                targetOpt.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                targetOpt.click();
                restoreScreenPosition();
                await new Promise(r => setTimeout(r, 80));
            }
            if (inp.blur) inp.blur();
            await closeAnyOpenMenu();
        }

        function clickButtonInRow(rowKeywords, buttonChoices) {
            if (!buttonChoices) return;
            const choices = (Array.isArray(buttonChoices) ? buttonChoices : [buttonChoices])
                .map(c => cleanVal(c).toUpperCase()).filter(Boolean);
            if (!choices.length) return;

            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p, span'));
            for (const kw of rowKeywords) {
                const kwL = kw.toLowerCase();
                const targets = labels.filter(el => {
                    if (!el.textContent || el.children.length > 2 || isInConclusion(el)) return false;
                    const t = el.textContent.trim().toLowerCase();
                    return (t === kwL || t.includes(kwL)) && t.length < kwL.length + 35;
                });

                for (const target of targets) {
                    let p = target.closest('.MuiFormControl-root') || target.parentElement;
                    for (let i = 0; i < 4 && p && p !== document.body && !isInConclusion(p); i++) {
                        const btns = Array.from(p.querySelectorAll('button, div[role="button"]')).filter(b => !b.disabled && !isInConclusion(b));
                        const desired = btns.find(b => {
                            const bt = cleanVal(b.innerText || b.textContent).toUpperCase();
                            return choices.some(ch => bt === ch || (ch.includes('СИММЕТР') && ch.startsWith('А') && bt.startsWith('А') && bt.includes('СИМ')));
                        });
                        if (desired) {
                            const s = window.getComputedStyle(desired);
                            const isWhite = s.color.includes('255, 255, 255');
                            const isColored = !s.backgroundColor.includes('rgba(0, 0, 0, 0)') && !s.backgroundColor.includes('255, 255, 255');
                            const isAlreadyOn = (isWhite && isColored) || desired.className.includes('MuiButton-contained');
                            if (!isAlreadyOn) desired.click();
                            break;
                        }
                        p = p.parentElement;
                    }
                }
            }
        }

        function fillBP(isAd, raw, allowEmpty = false) {
            const str = cleanBpString(raw);
            if (!str.includes('/') && !allowEmpty) return;
            const p = str.includes('/') ? str.split('/') : ['', ''];
            const slashNodes = Array.from(document.querySelectorAll('span, p, .MuiTypography-root'))
                .filter(e => e.children.length === 0 && e.textContent?.trim() === '/' && !isInConclusion(e));

            for (const sl of slashNodes) {
                let pr = sl.parentElement;
                for (let i = 0; i < 3 && pr && pr !== document.body; i++) {
                    const inps = getRealInputsAutofill(pr);
                    if (inps.length === 2) {
                        const tx = (pr.innerText || pr.parentElement?.innerText || '').toLowerCase();
                        const isMatch = isAd
                            ? (tx.includes('адаптированное') && (tx.includes('ад') || tx.includes('давлен')))
                            : (!tx.includes('адаптированное') && (tx.includes('ад (мм') || tx.includes('артериальн')));
                        if (isMatch) {
                            setVal(inps[0], p[0].trim(), allowEmpty);
                            setVal(inps[1], p[1].trim(), allowEmpty);
                            return;
                        }
                    }
                    pr = pr.parentElement;
                }
            }
        }

        if (data.fio) setAll(['фио'], data.fio, true);

        if (data.gender) {
            const gUp = cleanVal(data.gender).toUpperCase();
            const gTarget = (gUp.startsWith('М') || gUp === 'TRUE') ? ['МУЖЧИНА', 'МУЖСКОЙ'] : ['ЖЕНЩИНА', 'ЖЕНСКИЙ'];
            clickButtonInRow(['пол'], gTarget);
        }

        const initAgeRaw = cleanVal(window.lastLoadedApiCard?.initialAge);
        const initAgeM = initAgeRaw.match(/^(\d+)\s+([А-Яа-яЁё]+)/);
        let finAge = cleanVal(data.age) || cleanVal(window.lastLoadedApiCard?.age) || (initAgeM ? initAgeM[1] : '');
        let unitGuess = '';

        const bDateInps = findInputs(['дата рождения']);
        if (bDateInps.length > 0) {
            const now = new Date();
            const todayIso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
            const todayDot = `${String(now.getDate()).padStart(2, '0')}.${String(now.getMonth() + 1).padStart(2, '0')}.${now.getFullYear()}`;
            const regDateIso = (window.lastLoadedApiCard?.dateOfRegistration || '').split('T')[0];

            let servB = cleanVal(window.lastLoadedApiCard?.patient?.birthDate);
            if (servB && (servB.startsWith(todayIso) || (regDateIso && servB.startsWith(regDateIso)))) servB = '';
            let archB = cleanVal(data.birthDate);
            if (archB && (archB.includes(todayIso) || archB.includes(todayDot))) archB = '';
            let scrB = cleanVal(bDateInps[0].value);
            let scrDateOnly = scrB.split('/')[0].trim();
            if (scrDateOnly.includes('_') || scrDateOnly === todayDot) scrB = '';

            const rawB = servB || archB || scrB;
            if (rawB) {
                const parsed = formatBirthDateAndCalcAge(rawB);
                if (parsed.dateStr) setVal(bDateInps[0], parsed.dateStr);
                if (!finAge && parsed.ageStr) finAge = parsed.ageStr;
                if (parsed.ageUnitGuess) unitGuess = parsed.ageUnitGuess;
            }
        }

        const unitVal = cleanVal(data.ageUnit) || cleanVal(window.lastLoadedApiCard?.ageType) || (initAgeM ? initAgeM[2] : '') || unitGuess || 'Лет';
        await selectDropdown(['возраст (ед.)'], unitVal, false);
        if (finAge) setAll(['возраст'], finAge, true);

        const getInpVal = (kws, exact = false) => cleanVal(findInputs(kws, exact)[0]?.value);
        const savedSettl = cleanVal(data.settlement) || getInpVal(['нас. пункт']) || cleanVal(window.lastLoadedApiCard?.patient?.settlement) || cleanVal(window.lastLoadedApiCard?.settlement);
        const savedStreet = cleanVal(data.street) || getInpVal(['улица']) || cleanVal(window.lastLoadedApiCard?.patient?.street) || cleanVal(window.lastLoadedApiCard?.street);
        const savedBld = cleanVal(data.building) || getInpVal(['дом'], true) || cleanVal(window.lastLoadedApiCard?.patient?.building) || cleanVal(window.lastLoadedApiCard?.building);
        const savedFlat = cleanVal(data.flat) || getInpVal(['квартира'], true) || cleanVal(window.lastLoadedApiCard?.patient?.flat) || cleanVal(window.lastLoadedApiCard?.flat);
        const savedClar = cleanVal(data.clarificationAddress) || getInpVal(['уточнение места жительства']) || cleanVal(window.lastLoadedApiCard?.patient?.clarificationAddress);
        const savedAddr = cleanVal(data.address) || getInpVal(['место жительства (пребывания)']) || cleanVal(window.lastLoadedApiCard?.patient?.placeOfRegistration) || cleanVal(window.lastLoadedApiCard?.address);

        const ctzVal = cleanVal(data.citizenship) || cleanVal(window.lastLoadedApiCard?.patient?.countryOfRegistration) || '';
        if (ctzVal) await fillSimpleAutocomplete(['гражданство'], ctzVal, !data.citizenship);
        if (savedSettl) await fillSimpleAutocomplete(['нас. пункт'], savedSettl, false);
        if (savedStreet) await fillSimpleAutocomplete(['улица'], savedStreet, false);
        if (savedBld) setAll(['дом'], savedBld, true);
        if (savedFlat) setAll(['квартира'], savedFlat, true);
        if (savedClar) setAll(['уточнение места жительства'], savedClar);
        if (savedAddr) setAll(['место жительства (пребывания)'], savedAddr);

        if (data.apo) await selectDropdown(['территориальная апо', 'апо по месту жительства'], data.apo, false);

        setAll(['жалобы'], data.complaints ?? '', false, true);
        setAll(['анамнез заболевания'], data.anamnesis ?? '', false, true);
        setAll(['перенесенные заболевания'], data.pastDiseases ?? '', false, true);
        setAll(['предварительный диагноз'], data.diagnosis ?? '', false, true);
        setAll(['локальный статус'], data.localStatus ?? '', false, true);
        if (data.mkb) await fillMkbAutocomplete(data.mkb);

        fillBP(true, data.bpAdapted ?? '', true);
        fillBP(false, data.bp ?? '', true);
        setAll(['комментарий (пульс', 'пульс, ударов в минуту'], data.pulse ?? '', false, true);
        setAll(['частота дыхания (в минуту)'], data.rr ?? '', false, true);
        setAll(['температура тела (°c)'], data.temp !== undefined && data.temp !== null && String(data.temp) !== '' ? String(data.temp).replace(',', '.') : '', false, true);
        setAll(['сатурация 1 (%)'], data.saturation ?? '', false, true);
        setAll(['гликемия 1 (ммоль/л)'], data.glucose !== undefined && data.glucose !== null && String(data.glucose) !== '' ? String(data.glucose).replace(',', '.') : '', false, true);

        setAll(['экг до оказания'], data.ecgBefore ?? '', false, true);
        setAll(['экг после оказания'], data.ecgAfter ?? '', false, true);
        setAll(['шкала комы глазго (баллы)'], cleanVal(data.glasgowScore) || '15', false, true);
        setAll(['тонус мышц'], cleanVal(data.muscleTone) || 'Нормальный', false, true);
        setAll(['патологические рефлексы'], cleanVal(data.pathologicalReflexes) || 'Отрицательные', false, true);
        setAll(['менингиальные знаки'], cleanVal(data.meningealSymptoms) || 'Отрицательные', false, true);
        setAll(['плегии, парезы'], cleanVal(data.plegia) || 'Нет', false, true);
        setAll(['язык'], cleanVal(data.tongue) || 'Чистые', false, true);
        setAll(['слизистые'], cleanVal(data.mucous) || 'Бледно-розовые', false, true);

        clickButtonInRow(['положение'], data.position || 'АКТИВНОЕ');
        clickButtonInRow(['поведение'], data.behavior || 'СПОКОЙНОЕ');
        if (data.pupils) {
            clickButtonInRow(['зрачки'], [data.pupils, data.pupils.replace('АНИЗОКОРИЯ', 'АНИЗОКАРИЯ')]);
        } else {
            const pupLbl = Array.from(document.querySelectorAll('label')).find(e => e.textContent && e.textContent.trim().toLowerCase() === 'зрачки' && !isInConclusion(e));
            if (pupLbl && pupLbl.parentElement) {
                Array.from(pupLbl.parentElement.querySelectorAll('button'))
                    .filter(b => b.className.includes('MuiButton-contained') && !b.disabled)
                    .forEach(b => b.click());
            }
        }
        clickButtonInRow(['речь'], data.speech || 'СОХРАНЕНА');
        clickButtonInRow(['лицо'], data.face || 'СИММЕТРИЧНОЕ');
        clickButtonInRow(['походка'], data.gait || 'СВОБОДНАЯ');
        clickButtonInRow(['нистагм'], data.nystagmus || 'НЕТ');
        clickButtonInRow(['печень'], data.liver || 'НЕ УВЕЛИЧЕНА');
        clickButtonInRow(['мочеиспускание'], data.urination || 'НЕ НАРУШЕНО');
        clickButtonInRow(['стул'], data.stool || 'НЕ НАРУШЕН');

        clickButtonInRow(['аллергия'], data.allergy || 'НЕТ');
        clickButtonInRow(['реакция на свет'], data.lightReaction || 'ДА');
        clickButtonInRow(['высыпания'], data.rash || 'НЕТ');
        clickButtonInRow(['пульс, ударов в минуту'], data.pulseExists || (data.pulse === '0' ? 'НЕТ' : 'ДА'));
        clickButtonInRow(['асцит'], data.ascites || 'НЕТ');
        clickButtonInRow(['периферические отеки'], data.edema || 'НЕТ');

        clickButtonInRow(['запах алкоголя'], data.alcohol || 'НЕТ');
        clickButtonInRow(['запах ацетона'], data.acetone || 'НЕТ');
        clickButtonInRow(['симптомы раздражения брюшины'], data.peritoneal || 'НЕТ');
        clickButtonInRow(['перистальтика'], data.peristalsis || 'ДА');
        clickButtonInRow(['получено устное согласие на простое медицинское вмешательство', 'получено устное согласие'], 'ДА');

        await new Promise(r => setTimeout(r, 50));
        setAll(['комментарий (вынужденное)'], data.positionDescription ?? '', false, true);
        setAll(['комментарий (периферические отеки)'], data.edemaDescription ?? '', false, true);

        await selectDropdown(['общее состояние'], data.generalState || 'Удовлетворительное', false);
        await selectDropdown(['сознание'], data.consciousness || 'Ясное', false);

        await selectDropdown(['кожа'], data.skin ?? '', true, true);
        await selectDropdown(['тоны сердца'], data.heartSounds ?? '', true, true);
        await selectDropdown(['дыхание'], data.lungs ?? '', true, true);
        await selectDropdown(['перкуссия легких'], data.percussion ?? '', true, true);
        await selectDropdown(['живот'], data.abdomen ?? '', true, true);

        await closeAnyOpenMenu();
        restoreScreenPosition();
    } catch (e) { console.error(e); } finally {
        await new Promise(r => setTimeout(r, 60));
        await closeAnyOpenMenu();
        if (activeTabBefore) {
            const curActive = Array.from(document.querySelectorAll('[role="tab"], button'))
                .find(b => b.getBoundingClientRect().top < 140 && (b.getAttribute('aria-selected') === 'true' || b.className.includes('Mui-selected')));
            if (curActive !== activeTabBefore) activeTabBefore.click();
        }
        restoreScreenPosition();
        setTimeout(restoreScreenPosition, 40);

        document.getElementById('b103-hide-menus')?.remove();
        if (fillBtn) fillBtn.innerText = '⚡';
        setTimeout(() => { window.b103IsApplying = false; }, 150);
    }
};
    // ==========================================
    // 5. МКБ-10 (IndexedDB + CSV из build.yml + ручной файл)
    // ==========================================
    function idbOpen() {
        return new Promise((resolve, reject) => {
            if (!window.indexedDB) return reject('No IDB');
            const req = indexedDB.open(MKB_IDB_NAME, 1);
            req.onupgradeneeded = () => { if (!req.result.objectStoreNames.contains(MKB_STORE)) req.result.createObjectStore(MKB_STORE); };
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
        });
    }
    async function idbGetMkb() {
        try {
            const db = await idbOpen();
            return await new Promise(res => {
                const tx = db.transaction(MKB_STORE, 'readonly').objectStore(MKB_STORE).get('list');
                tx.onsuccess = () => res(Array.isArray(tx.result) ? tx.result : null);
                tx.onerror = () => res(null);
            });
        } catch (e) { return null; }
    }
    async function idbSaveMkb(list) {
        try {
            const db = await idbOpen();
            await new Promise(res => {
                const tx = db.transaction(MKB_STORE, 'readwrite').objectStore(MKB_STORE).put(list, 'list');
                tx.oncomplete = () => res(true); tx.onerror = () => res(false);
            });
        } catch (e) {}
    }

    function parseCsvMkb(text, delim) {
        const list = [];
        const lines = text.split(/\r?\n/);
        for (let i = 1; i < lines.length; i++) {
            const line = lines[i];
            if (!line) continue;
            const cols = [];
            let cur = '', inQ = false;
            for (let j = 0; j < line.length; j++) {
                const ch = line[j];
                if (ch === '"') { inQ = !inQ; }
                else if (ch === delim && !inQ) { cols.push(cur); cur = ''; }
                else cur += ch;
            }
            cols.push(cur);
            if (cols.length >= 2) {
                let code = cols[0].trim().toUpperCase();
                let name = cols[1].trim();
                if (!(code && /^[A-Z]/.test(code) && /\d/.test(code)) && cols.length >= 3) {
                    code = cols[1].trim().toUpperCase();
                    name = cols[2].trim();
                }
                if (code.length >= 3 && /^[A-Z]/.test(code) && /\d/.test(code) && name.length > 2 && !['CODE', 'NAME', 'VERSION', 'ID'].includes(code)) {
                    list.push({ c: code, n: name, l: code[0] });
                }
            }
        }
        return list;
    }

    let mkbLoadPromise = null;
    async function ensureMkbLoaded(forceRemote = false) {
        if (!forceRemote && window.b103MkbList.length > 1000) return window.b103MkbList;
        if (mkbLoadPromise && !forceRemote) return mkbLoadPromise;
        mkbLoadPromise = (async () => {
            if (!forceRemote) {
                const cached = await idbGetMkb();
                if (cached && cached.length > 1000) { window.b103MkbList = cached; return cached; }
            }
            const sources = [
                ['https://raw.githubusercontent.com/KindYAK/mkb-10-parsed/master/mkb-parsed.csv', ','],
                ['https://raw.githubusercontent.com/ak4nv/mkb10/master/1.2.643.5.1.13.13.11.1005_2.27.csv', ';']
            ];
            for (const [url, delim] of sources) {
                try {
                    const resp = await origFetch(url);
                    if (!resp.ok) continue;
                    const txt = await resp.text();
                    const parsed = parseCsvMkb(txt, delim);
                    if (parsed.length > 5000) {
                        window.b103MkbList = parsed;
                        await idbSaveMkb(parsed);
                        return parsed;
                    }
                } catch (e) {}
            }
            return window.b103MkbList;
        })();
        return mkbLoadPromise;
    }

    // ==========================================
    // 6. МОДАЛЬНЫЕ ШТОРКИ И ДИАЛОГИ (UI 1-В-1)
    // ==========================================
    function openIosSheet(title, colorHex, renderContentFn, onClose) {
        if (typeof window.b103ActiveSheetClose === 'function') {
            try { window.b103ActiveSheetClose(); } catch (e) {}
            window.b103ActiveSheetClose = null;
        }
        document.getElementById('b103-ios-sheet-backdrop')?.remove();

        const backdrop = document.createElement('div');
        backdrop.id = 'b103-ios-sheet-backdrop';
        backdrop.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.52);z-index:100000;display:flex;flex-direction:column;justify-content:flex-end;font-family:-apple-system,BlinkMacSystemFont,Roboto,sans-serif;';
        const sheet = document.createElement('div');
        sheet.style.cssText = 'background:#fff;border-radius:22px 22px 0 0;max-height:88vh;display:flex;flex-direction:column;padding:14px 18px 24px 18px;box-shadow:0 -4px 20px rgba(0,0,0,0.25);overflow:hidden;';
        sheet.innerHTML = `
            <div style="width:44px;height:5px;background:#B0BEC5;border-radius:3px;margin:0 auto 10px auto;"></div>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;">
                <div id="b103-sheet-title" style="font-size:18px;font-weight:700;color:${colorHex};">${escapeHtml(title)}</div>
                <button id="b103-sheet-close" style="border:none;background:#ECEFF1;color:#37474F;width:32px;height:32px;border-radius:50%;font-size:16px;font-weight:bold;cursor:pointer;">✕</button>
            </div>
            <div id="b103-sheet-body" style="flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;"></div>
        `;
        backdrop.appendChild(sheet);
        document.body.appendChild(backdrop);

        function doClose() {
            if (typeof onClose === 'function') { try { onClose(); } catch (e) {} }
            window.b103ActiveSheetClose = null;
            backdrop.remove();
        }
        window.b103ActiveSheetClose = doClose;
        backdrop.addEventListener('click', (e) => { if (e.target === backdrop) doClose(); });
        sheet.querySelector('#b103-sheet-close').onclick = doClose;
        renderContentFn(sheet.querySelector('#b103-sheet-body'), doClose, sheet.querySelector('#b103-sheet-title'));
    }

    function showDetailDialog(title, bodyText, accentHex = '#00695C') {
        document.getElementById('b103-ios-detail-modal')?.remove();
        const ov = document.createElement('div');
        ov.id = 'b103-ios-detail-modal';
        ov.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.58);z-index:100500;display:flex;align-items:center;justify-content:center;padding:16px;font-family:-apple-system,BlinkMacSystemFont,Roboto,sans-serif;';
        ov.innerHTML = `
            <div style="background:#fff;border-radius:18px;width:100%;max-width:540px;max-height:84vh;display:flex;flex-direction:column;padding:18px;box-shadow:0 8px 28px rgba(0,0,0,0.3);">
                <div style="font-size:17px;font-weight:700;color:${accentHex};margin-bottom:12px;">${escapeHtml(title)}</div>
                <div style="flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;font-size:14.2px;line-height:1.5;color:#263238;white-space:pre-line;user-select:text;">${escapeHtml(bodyText)}</div>
                <div style="display:flex;justify-content:flex-end;gap:10px;margin-top:14px;">
                    <button id="det-copy" style="border:none;background:transparent;color:${accentHex};font-weight:700;font-size:13px;padding:10px 14px;cursor:pointer;">📋 КОПИРОВАТЬ</button>
                    <button id="det-close" style="border:none;background:${accentHex};color:#fff;font-weight:700;font-size:13px;padding:10px 18px;border-radius:10px;cursor:pointer;">ЗАКРЫТЬ</button>
                </div>
            </div>
        `;
        document.body.appendChild(ov);
        ov.addEventListener('click', (e) => { if (e.target === ov) ov.remove(); });
        ov.querySelector('#det-close').onclick = () => ov.remove();
        ov.querySelector('#det-copy').onclick = () => copyText(`${title}:\n${bodyText}`);
    }

    // ==========================================
    // 7. ГЛАВНОЕ МЕНЮ, ПОИСК, ШАБЛОНЫ И ИСТОРИЯ ПАЦИЕНТА
    // ==========================================
    function openMainMenuIos() {
        openIosSheet('Меню SSMP Plus (iOS)', '#008577', (body, close) => {
            body.innerHTML = `
                <button id="m-samples" style="width:100%;padding:15px;border:none;border-radius:12px;background:#008577;color:#fff;font-weight:700;font-size:14px;margin-bottom:10px;">⭐ ОБРАЗЦЫ КАРТ (${BUILTIN_TEMPLATES.length}+)</button>
                <button id="m-arch" style="width:100%;padding:15px;border:none;border-radius:12px;background:#008577;color:#fff;font-weight:700;font-size:14px;margin-bottom:10px;">📂 ОБЩИЙ АРХИВ ВЫЗОВОВ</button>
                <button id="m-helper" style="width:100%;padding:15px;border:none;border-radius:12px;background:#0277BD;color:#fff;font-weight:700;font-size:14px;margin-bottom:10px;">🧰 ПОМОЩНИК СМП (11 МОДУЛЕЙ)</button>
                <div style="display:flex;gap:10px;">
                    <button id="m-imp" style="flex:1;padding:14px;border:none;border-radius:12px;background:#00695C;color:#fff;font-weight:700;font-size:13.5px;">📥 ИМПОРТ JSON</button>
                    <button id="m-exp" style="flex:1;padding:14px;border:none;border-radius:12px;background:#00695C;color:#fff;font-weight:700;font-size:13.5px;">📤 ЭКСПОРТ JSON</button>
                </div>
                <input id="ios-file-inp" type="file" accept=".json,application/json" style="display:none;">
            `;
            body.querySelector('#m-samples').onclick = () => { close(); openSearchSheet(true); };
            body.querySelector('#m-arch').onclick = () => { close(); openSearchSheet(false); };
            body.querySelector('#m-helper').onclick = () => { close(); openHelperMenuSheet(); };
            body.querySelector('#m-exp').onclick = () => {
                const exportArr = loadDb().filter(x => x && (x.is_builtin === 0 || x.is_builtin === 2)).map(x => ({
                    fio: x.fio, birth_date: x.birth_date || '', timestamp: x.timestamp, score: x.score || 80, json_data: x.json_data, is_builtin: x.is_builtin
                }));
                const blob = new Blob([JSON.stringify(exportArr)], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url; a.download = `ssmp_archive_${new Date().toISOString().slice(0, 10)}.json`;
                document.body.appendChild(a);
                a.click();
                a.remove();
                setTimeout(() => URL.revokeObjectURL(url), 1500);
                showToast('📤 База экспортирована!');
            };
            const fInp = body.querySelector('#ios-file-inp');
            body.querySelector('#m-imp').onclick = () => fInp.click();
            fInp.onchange = (e) => {
                const file = e.target.files[0]; if (!file) return;
                const reader = new FileReader();
                reader.onload = () => {
                    try {
                        const parsed = JSON.parse(reader.result);
                        if (!Array.isArray(parsed)) throw new Error('Not array');
                        const cur = loadDb();
                        const getSig = (isB, f, ts) => isB === 2 ? `tpl_${String(f || '').trim().toLowerCase()}` : `card_${String(f || '').trim().toLowerCase()}_${ts || 0}`;
                        const existing = new Set(cur.map(c => getSig(c.is_builtin, c.fio, c.timestamp)));
                        let importedCount = 0;
                        parsed.forEach(item => {
                            if (!item || typeof item !== 'object') return;
                            const isB = item.is_builtin === 2 ? 2 : 0;
                            const fRaw = String(item.fio || '').trim();
                            const f = isB === 2 ? fRaw : fRaw.toLowerCase();
                            const jd = String(item.json_data || '').trim();
                            const ts = item.timestamp || Date.now();
                            if (!f || !jd) return;
                            const sig = getSig(isB, f, ts);
                            if (!existing.has(sig)) {
                                existing.add(sig);
                                cur.unshift({ id: genId(), fio: f, birth_date: item.birth_date || '', timestamp: ts, score: typeof item.score === 'number' ? item.score : 80, json_data: jd, is_builtin: isB });
                                importedCount++;
                            }
                        });
                        saveDb(cur);
                        showToast(`📥 Импортировано новых записей: ${importedCount}`);
                    } catch (err) { alert('Ошибка: файл поврежден или имеет неверный формат JSON'); }
                };
                reader.readAsText(file);
            };
        });
    }

    function openSearchSheet(isBest) {
        openIosSheet(isBest ? `⭐ Образцы карт (${BUILTIN_TEMPLATES.length} шаблонов)` : '📂 Общий архив вызовов', isBest ? '#E65100' : '#004D40', (body, close, titleEl) => {
            body.innerHTML = `
                <input id="srch-inp" type="text" placeholder="${isBest ? 'Поиск по диагнозу или СЛР...' : 'Поиск по фамилии...'}" style="width:100%;box-sizing:border-box;padding:12px 14px;border-radius:12px;border:1.5px solid ${isBest ? '#FFE082' : '#B0BEC5'};background:${isBest ? '#FFF8E1' : '#F0F4F4'};color:#111;font-size:14px;margin-bottom:10px;outline:none;">
                <div id="srch-list" style="display:flex;flex-direction:column;gap:8px;"></div>
            `;
            const inp = body.querySelector('#srch-inp'), list = body.querySelector('#srch-list');
            function render() {
                const q = inp.value.trim().toLowerCase();
                list.innerHTML = '';
                if (isBest) {
                    const custom = loadDb().filter(x => x && x.is_builtin === 2 && (!q || (x.fio || '').toLowerCase().includes(q) || (x.json_data || '').toLowerCase().includes(q)))
                        .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0))
                        .map(x => {
                            let j = {}; try { j = JSON.parse(x.json_data || '{}'); } catch (e) {}
                            return { id: x.id, timestamp: x.timestamp, title: x.fio || 'Шаблон', mkb: j.mkb || j.diagnosis || '', jsonData: x.json_data || '{}', isCustom: true };
                        });
                    const built = BUILTIN_TEMPLATES.filter(t => !q || t.title.toLowerCase().includes(q) || t.diagnosis.toLowerCase().includes(q) || t.mkb.toLowerCase().includes(q));
                    const all = [...custom, ...built];
                    if (!q) titleEl.innerText = `⭐ Образцы карт (${all.length} шаблонов)`;
                    all.forEach(item => {
                        const row = document.createElement('div');
                        row.style.cssText = 'padding:13px 15px;border-radius:13px;background:#FFFDE7;border:1.5px solid #FFE082;color:#263238;font-size:13.8px;cursor:pointer;';
                        row.innerHTML = `<b>${item.isCustom ? '📌' : '⭐'} ${escapeHtml(item.title)}</b><br><span style="color:#546E7A;font-size:12.5px;">📋 ${escapeHtml(item.mkb)}</span>`;
                        row.onclick = () => { close(); showTemplatePreview(item); };
                        list.appendChild(row);
                    });
                } else {
                    const items = searchPatientSummaries(q);
                    if (!items.length) { list.innerHTML = '<div style="padding:20px;text-align:center;color:#607D8B;">Архив пуст. Нажмите 💾 внутри карты для сохранения.</div>'; return; }
                    items.forEach(it => {
                        const dt = it.lastTimestamp ? new Date(it.lastTimestamp).toLocaleDateString() : '';
                        const row = document.createElement('div');
                        row.style.cssText = 'padding:13px 15px;border-radius:13px;background:#F7FAFA;border:1.5px solid #E0EAEA;color:#263238;font-size:13.8px;cursor:pointer;';
                        row.innerHTML = `<b>👤 ${escapeHtml(it.fio.toUpperCase())}${it.callCount > 1 ? ` [${it.callCount}]` : ''}</b><br><span style="font-size:12.5px;color:#546E7A;">Сохранено: ${dt}<br>• ${escapeHtml(it.lastReason)}</span>`;
                        row.onclick = () => { close(); showPatientHistory(it.fio); };
                        list.appendChild(row);
                    });
                }
            }
            inp.oninput = render; render();
        });
    }

    function showTemplatePreview(s) {
        openIosSheet(`${s.isCustom ? '📌' : '⭐'} ${s.title}`, '#E65100', (body, close) => {
            body.innerHTML = `
                <div style="display:flex;gap:8px;margin-bottom:12px;">
                    <button id="tp-fill" style="flex:1;padding:12px;border:none;border-radius:12px;background:#0288D1;color:#fff;font-weight:700;font-size:13.5px;cursor:pointer;">⚡ Вставить в карту</button>
                    ${s.isCustom ? '<button id="tp-del" style="padding:12px 16px;border:none;border-radius:12px;background:#FFEBEE;color:#C62828;font-weight:700;font-size:14px;cursor:pointer;">🗑️</button>' : ''}
                </div>
                <div style="font-size:14.2px;line-height:1.5;color:#263238;white-space:pre-line;user-select:text;">${escapeHtml(formatCardText(s.jsonData))}</div>
            `;
            body.querySelector('#tp-fill').onclick = () => {
                close();
                try {
                    const p = JSON.parse(s.jsonData);
                    window.activeAutofillData = p;
                    window.applyAutofill(p);
                } catch (e) { showToast('Ошибка данных шаблона'); }
            };
            const delBtn = body.querySelector('#tp-del');
            if (delBtn) delBtn.onclick = () => {
                if (confirm('Удалить шаблон?')) {
                    if (deleteRecordFromDb({ id: s.id, fio: s.title, timestamp: s.timestamp, is_builtin: 2 })) {
                        showToast('Удалено'); close();
                    }
                }
            };
        });
    }

    function showPatientHistory(fio) {
        const history = getPatientHistory(fio);
        if (!history.length) return;
        let selIdx = 0;

        openIosSheet(fio.toUpperCase(), '#004D40', (body, close) => {
            body.innerHTML = `
                <div style="display:flex;gap:8px;margin-bottom:10px;">
                    <button id="ph-tpl" style="flex:1;padding:11px;border:1.5px solid #FFE082;border-radius:11px;background:#FFF8E1;color:#E65100;font-weight:700;font-size:12.5px;cursor:pointer;">⭐ В шаблон</button>
                    <button id="ph-fill" style="flex:1;padding:11px;border:none;border-radius:11px;background:#0288D1;color:#fff;font-weight:700;font-size:12.5px;cursor:pointer;">⚡ Вставить</button>
                    <button id="ph-del" style="padding:11px 14px;border:none;border-radius:11px;background:#FFEBEE;color:#C62828;font-weight:700;font-size:14px;cursor:pointer;">🗑️</button>
                </div>
                <div id="ph-chips" style="display:flex;gap:8px;overflow-x:auto;padding-bottom:8px;"></div>
                <div id="ph-badges" style="display:flex;gap:8px;overflow-x:auto;padding-bottom:10px;"></div>
                <div id="ph-details" style="font-size:14.2px;line-height:1.5;color:#263238;white-space:pre-line;user-select:text;padding-bottom:14px;"></div>
            `;
            const chips = body.querySelector('#ph-chips'), badges = body.querySelector('#ph-badges'), det = body.querySelector('#ph-details');

            function render() {
                const itm = history[selIdx];
                let j = {}; try { j = JSON.parse(itm.json_data || '{}'); } catch (e) {}

                body.querySelector('#ph-tpl').onclick = () => {
                    const defName = (j.diagnosis || j.callReason || '').slice(0, 45);
                    const title = prompt('Сохранить в свои шаблоны (ФИО и адрес будут удалены):', defName);
                    if (title && title.trim()) {
                        saveCustomTemplate(title.trim(), itm.json_data);
                        showToast(`📌 Шаблон «${title.trim()}» сохранен!`);
                    }
                };
                body.querySelector('#ph-fill').onclick = () => {
                    close();
                    window.activeAutofillData = j;
                    window.applyAutofill(j);
                };
                body.querySelector('#ph-del').onclick = () => {
                    if (confirm('Удалить вызов?')) {
                        if (deleteRecordFromDb(itm)) {
                            history.splice(selIdx, 1);
                            showToast('Удалено');
                            if (!history.length) close(); else { selIdx = 0; render(); }
                        }
                    }
                };

                chips.innerHTML = '';
                history.forEach((c, idx) => {
                    const on = idx === selIdx;
                    const dt = c.timestamp ? new Date(c.timestamp).toLocaleString().slice(0, 16) : '';
                    const b = document.createElement('span');
                    b.style.cssText = `white-space:nowrap;padding:7px 12px;border-radius:14px;font-size:12px;cursor:pointer;font-weight:${on ? '700' : '500'};background:${on ? '#00796B' : '#E0F2F1'};color:${on ? '#fff' : '#004D40'};`;
                    b.innerText = `Вызов #${history.length - idx} (${dt})`;
                    b.onclick = () => { selIdx = idx; render(); };
                    chips.appendChild(b);
                });

                badges.innerHTML = '';
                const addB = (t, bg) => {
                    const s = document.createElement('span');
                    s.style.cssText = `white-space:nowrap;padding:4px 10px;border-radius:12px;font-size:12px;font-weight:700;background:${bg};color:#fff;`;
                    s.innerText = t; badges.appendChild(s);
                };
                const bp = cleanBpString(j.bp) || cleanBpString(j.bpAdapted);
                if (bp) addB(`❤️ ${bp}`, '#E53935');
                if (j.pulse) addB(`💓 ${j.pulse}`, '#D81B60');
                if (j.saturation) addB(`🫁 ${j.saturation}%`, '#00897B');
                if (j.glucose) addB(`🩸 ${j.glucose}`, '#8E24AA');
                if (j.glasgowScore) addB(`🧠 ${j.glasgowScore} б`, '#3949AB');

                det.innerText = formatCardText(itm.json_data);
                body.scrollTop = 0;
            }
            render();
        });
    }

    function openAutofillSheet() {
        const currentFio = (findInputByKw(['фио'], true)?.value || '').trim();
        const exactCardJson = currentFio.length >= 3 ? getBestCardForPatient(currentFio) : null;

        openIosSheet('⚡ Автозаполнение и Помощник', '#0288D1', (body, close) => {
            body.innerHTML = `
                <button id="ios-open-helper" style="width:100%;padding:13px;border:none;border-radius:12px;background:#00897B;color:#fff;font-weight:700;font-size:13.5px;margin-bottom:10px;cursor:pointer;">🧰 ОТКРЫТЬ ПОМОЩНИК СМП / ЭКГ / СЛР / ДОЗЫ</button>
                ${exactCardJson ? `<button id="ios-exact-fill" style="width:100%;padding:13px;border:none;border-radius:12px;background:#2E7D32;color:#fff;font-weight:700;font-size:13.5px;margin-bottom:10px;cursor:pointer;">👤 ЗАПОЛНИТЬ ИЗ АРХИВА: ${escapeHtml(currentFio.toUpperCase())}</button>` : ''}
                <div style="display:flex;gap:8px;margin-bottom:10px;">
                    <button id="tab-tpl" style="flex:1;padding:11px;border:none;border-radius:11px;background:#00796B;color:#fff;font-weight:700;font-size:13px;">⭐ Образцы (${BUILTIN_TEMPLATES.length})</button>
                    <button id="tab-arch" style="flex:1;padding:11px;border:none;border-radius:11px;background:#E0F2F1;color:#004D40;font-weight:600;font-size:13px;">📂 Мой архив</button>
                </div>
                <input id="ios-search-inp" type="text" placeholder="Поиск (СЛР, диагноз, фамилия)..." style="width:100%;box-sizing:border-box;padding:12px 14px;border-radius:12px;border:1.5px solid #90CAF9;background:#F0F8FF;color:#111;font-size:14px;margin-bottom:10px;outline:none;">
                <div id="ios-list-box" style="display:flex;flex-direction:column;gap:8px;"></div>
            `;
            let mode = 'tpl';
            const listBox = body.querySelector('#ios-list-box'), inp = body.querySelector('#ios-search-inp');
            const tTpl = body.querySelector('#tab-tpl'), tArch = body.querySelector('#tab-arch');

            body.querySelector('#ios-open-helper').onclick = () => { close(); openHelperMenuSheet(); };
            if (exactCardJson) {
                body.querySelector('#ios-exact-fill').onclick = () => {
                    close();
                    try {
                        const d = JSON.parse(exactCardJson);
                        window.activeAutofillData = d; window.applyAutofill(d);
                    } catch (e) {}
                };
            }

            function render() {
                const q = inp.value.trim().toLowerCase();
                listBox.innerHTML = '';
                if (mode === 'tpl') {
                    const custom = loadDb().filter(x => x && x.is_builtin === 2).map(x => {
                        let d = {}; try { d = JSON.parse(x.json_data || '{}'); } catch (e) {}
                        return { title: x.fio || 'Шаблон', mkb: d.mkb || d.diagnosis || '', jsonData: x.json_data || '{}', isCustom: true };
                    });
                    const all = [...custom, ...BUILTIN_TEMPLATES].filter(x => !q || x.title.toLowerCase().includes(q) || x.mkb.toLowerCase().includes(q) || (x.diagnosis || '').toLowerCase().includes(q));
                    if (!q) tTpl.innerText = `⭐ Образцы (${all.length})`;
                    all.forEach(item => {
                        const row = document.createElement('div');
                        row.style.cssText = 'padding:13px 15px;border-radius:13px;background:#FFFDE7;border:1.5px solid #FFE082;color:#263238;font-size:13.5px;cursor:pointer;';
                        row.innerHTML = `<b>${item.isCustom ? '📌' : '⭐'} ${escapeHtml(item.title)}</b><br><span style="color:#546E7A;font-size:12.5px;">📋 ${escapeHtml(item.mkb)}</span>`;
                        row.onclick = () => {
                            try { const parsed = JSON.parse(item.jsonData); close(); window.activeAutofillData = parsed; window.applyAutofill(parsed); }
                            catch (e) { alert('Ошибка чтения шаблона'); }
                        };
                        listBox.appendChild(row);
                    });
                } else {
                    const arch = searchPatientSummaries(q);
                    if (!arch.length) { listBox.innerHTML = '<div style="padding:20px;text-align:center;color:#607D8B;">Архив пуст. Нажмите 💾 внутри карты для сохранения.</div>'; return; }
                    arch.forEach(item => {
                        const row = document.createElement('div');
                        row.style.cssText = 'padding:13px 15px;border-radius:13px;background:#F0F8FF;border:1.5px solid #BBDEFB;color:#263238;font-size:13.5px;cursor:pointer;';
                        row.innerHTML = `<b>👤 ${escapeHtml(item.fio.toUpperCase())}</b><br><span style="color:#37474F;font-size:12.5px;">📋 ${escapeHtml(item.lastReason)}</span>`;
                        row.onclick = () => {
                            const best = getBestCardForPatient(item.fio);
                            if (best) {
                                try { const d = JSON.parse(best); close(); window.activeAutofillData = d; window.applyAutofill(d); } catch (e) {}
                            }
                        };
                        listBox.appendChild(row);
                    });
                }
            }
            tTpl.onclick = () => { mode = 'tpl'; tTpl.style.background = '#00796B'; tTpl.style.color = '#fff'; tArch.style.background = '#E0F2F1'; tArch.style.color = '#004D40'; render(); };
            tArch.onclick = () => { mode = 'arch'; tArch.style.background = '#00796B'; tArch.style.color = '#fff'; tTpl.style.background = '#E0F2F1'; tTpl.style.color = '#004D40'; render(); };
            inp.oninput = render; render();
        });
    }

    // ==========================================
    // 8. ПОМОЩНИК СМП (ВСЕ 11 МОДУЛЕЙ 1-В-1)
    // ==========================================
    function openHelperMenuSheet() {
        openIosSheet('Помощник СМП (11 модулей)', '#0277BD', (body) => {
            const tools = [
                { t: '📈 КОНСТРУКТОР ЭКГ (В КАРТУ)', c: '#00897B', fn: openEcgBuilderIos },
                { t: '⏱️ ТАЙМЕР И ПРОТОКОЛ СЛР', c: '#D32F2F', fn: openCprTimerIos },
                { t: '📝 ФРАЗЫ: ОТКАЗЫ И ПРИМЕЧАНИЯ', c: '#5D4037', fn: openQuickPhrasesIos },
                { t: '📖 СПРАВОЧНИК МКБ-10', c: '#008577', fn: openMkbSheetIos },
                { t: '👶 ПЕДИАТРИЯ: НОРМЫ, ДОЗЫ, ИВЛ', c: '#0277BD', fn: openPediatricsIos },
                { t: '🧠 ШКАЛЫ И ИНДЕКСЫ СМП', c: '#5E35B1', fn: openScalesIos },
                { t: '📋 ШПАРГАЛКИ И РАСЧЕТЫ (O2 МПа)', c: '#EF6C00', fn: openCheatSheetsIos },
                { t: '🤰 АКУШЕРСТВО И ШКАЛА АПГАР', c: '#D81B60', fn: openObstetricsIos },
                { t: '🧪 ЯДЫ И АНТИДОТЫ', c: '#C62828', fn: openAntidotesIos },
                { t: '💊 ЛЕКАРСТВА И ОФОРМЛЕНИЕ КАРТ', c: '#00695C', fn: openDrugsAndGuidesIos },
                { t: '📜 ПРИКАЗЫ МЗ РБ, ТЛТ И ЧКВ', c: '#37474F', fn: openOrdersIos }
            ];
            tools.forEach(item => {
                const b = document.createElement('button');
                b.style.cssText = `width:100%;padding:14px;border:none;border-radius:11px;background:${item.c};color:#fff;font-weight:700;font-size:13.5px;margin-bottom:9px;cursor:pointer;`;
                b.innerText = item.t; b.onclick = () => item.fn();
                body.appendChild(b);
            });
        });
    }

    // --- 8.1 КОНСТРУКТОР ЭКГ ---
    function openEcgBuilderIos() {
        openIosSheet('📈 Конструктор описания ЭКГ', '#00897B', (body, close) => {
            let selRhythm = 'Ритм синусовый, правильный.', hrVal = '78', selEos = 'ЭОС нормальная.';
            const selCond = new Set(), selIsch = new Set();

            body.innerHTML = `
                <textarea id="ecg-prev" rows="3" style="width:100%;box-sizing:border-box;padding:12px;border-radius:14px;border:2px solid #26A69A;background:#E0F2F1;color:#004D40;font-weight:700;font-size:14px;margin-bottom:10px;"></textarea>
                <div style="display:flex;gap:8px;margin-bottom:12px;">
                    <button id="ecg-b1" style="flex:1;padding:12px;border:none;border-radius:12px;background:#00796B;color:#fff;font-weight:700;font-size:12.5px;">⚡ В ЭКГ ДО ПОМОЩИ</button>
                    <button id="ecg-b2" style="flex:1;padding:12px;border:none;border-radius:12px;background:#0277BD;color:#fff;font-weight:700;font-size:12.5px;">⚡ В ЭКГ ПОСЛЕ</button>
                </div>
                <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px;">
                    <span style="font-size:13.5px;font-weight:700;color:#263238;">💓 ЧСС / ЧЖС (в мин):</span>
                    <input id="ecg-hr" type="text" value="78" style="flex:1;padding:8px 12px;border-radius:10px;border:1px solid #B0BEC5;background:#F0F4F4;color:#111;font-size:14px;">
                </div>
                <div id="ecg-groups"></div>
            `;
            const prev = body.querySelector('#ecg-prev'), hrInp = body.querySelector('#ecg-hr'), grp = body.querySelector('#ecg-groups');

            function updatePrev() {
                const parts = [selRhythm];
                if (hrVal) parts.push((selRhythm.includes('Фибрилляция') || selRhythm.includes('Трепетание')) ? `ЧЖС ${hrVal} в мин.` : `ЧСС ${hrVal} в мин.`);
                if (selEos) parts.push(selEos);
                if (selCond.size) parts.push(Array.from(selCond).join(' '));
                parts.push(selIsch.size ? Array.from(selIsch).join(' ') : 'Острой очаговой патологии нет.');
                prev.value = parts.join(' ').replace(/\s+/g, ' ').trim();
            }

            function addSingle(title, opts, getC, setC) {
                const h = document.createElement('div');
                h.style.cssText = 'font-size:13px;font-weight:700;color:#00695C;margin:8px 0 6px 0;'; h.innerText = title;
                const row = document.createElement('div');
                row.style.cssText = 'display:flex;gap:8px;overflow-x:auto;padding-bottom:4px;';
                function draw() {
                    row.innerHTML = '';
                    opts.forEach(([lbl, full]) => {
                        const sel = getC() === full;
                        const c = document.createElement('span');
                        c.style.cssText = `white-space:nowrap;padding:8px 12px;border-radius:12px;font-size:12px;cursor:pointer;font-weight:${sel ? '700' : '500'};background:${sel ? '#00897B' : '#E0F2F1'};color:${sel ? '#fff' : '#263238'};`;
                        c.innerText = lbl;
                        c.onclick = () => { setC(full); draw(); updatePrev(); };
                        row.appendChild(c);
                    });
                }
                draw(); grp.appendChild(h); grp.appendChild(row);
            }

            function addMulti(title, opts, set) {
                const h = document.createElement('div');
                h.style.cssText = 'font-size:13px;font-weight:700;color:#00695C;margin:10px 0 6px 0;'; h.innerText = title;
                const row = document.createElement('div');
                row.style.cssText = 'display:flex;gap:8px;overflow-x:auto;padding-bottom:4px;';
                function draw() {
                    row.innerHTML = '';
                    opts.forEach(([lbl, full]) => {
                        const sel = set.has(full);
                        const c = document.createElement('span');
                        c.style.cssText = `white-space:nowrap;padding:8px 12px;border-radius:12px;font-size:12px;cursor:pointer;font-weight:${sel ? '700' : '500'};background:${sel ? '#0277BD' : '#E1F5FE'};color:${sel ? '#fff' : '#263238'};`;
                        c.innerText = (sel ? '✓ ' : '') + lbl;
                        c.onclick = () => { if (sel) set.delete(full); else set.add(full); draw(); updatePrev(); };
                        row.appendChild(c);
                    });
                }
                draw(); grp.appendChild(h); grp.appendChild(row);
            }

            addSingle('1. Водитель ритма:', [
                ['Синусовый прав.', 'Ритм синусовый, правильный.'], ['Синус. тахикардия', 'Синусовая тахикардия.'],
                ['Синус. брадикардия', 'Синусовая брадикардия.'], ['Синус. аритмия', 'Синусовая аритмия.'],
                ['ФП (мерцательная)', 'Фибрилляция предсердий, тахисистолическая форма.'], ['ФП нормосистолия', 'Фибрилляция предсердий, нормосистолическая форма.'],
                ['Трепетание предсердий', 'Трепетание предсердий, правильная форма 2:1.'], ['Пароксизм СВТ', 'Пароксизмальная наджелудочковая тахикардия.'],
                ['Пароксизм ЖТ', 'Пароксизмальная ширококомплексная желудочковая тахикардия.'], ['Ритм ЭКС', 'Ритм искусственного водителя ритма (ЭКС), навязывание эффективное.']
            ], () => selRhythm, v => { selRhythm = v; });

            addSingle('2. Положение ЭОС:', [
                ['Нормальная', 'ЭОС нормальная.'], ['Горизонтальная', 'ЭОС горизонтальная.'],
                ['Отклонена влево', 'ЭОС отклонена влево.'], ['Резко влево', 'ЭОС резко отклонена влево.'],
                ['Вертикальная', 'ЭОС вертикальная.'], ['Отклонена вправо', 'ЭОС отклонена вправо.']
            ], () => selEos, v => { selEos = v; });

            addMulti('3. Гипертрофия, блокады и экстрасистолия (можно несколько):', [
                ['Признаки ГЛЖ', 'Признаки гипертрофии левого желудочка (ГЛЖ).'], ['Перегрузка ЛЖ', 'Признаки систолической перегрузки ЛЖ.'],
                ['Перегрузка ПЖ (SI-QIII)', 'Перегрузка правых отделов сердца (синдром SI-QIII-TIII).'], ['Неполная БПНПГ', 'Неполная блокада правой ножки пучка Гиса.'],
                ['Полная БПНПГ', 'Полная блокада правой ножки пучка Гиса (БПНПГ).'], ['Полная БЛНПГ', 'Полная блокада левой ножки пучка Гиса (БЛНПГ).'],
                ['БПВЛНПГ', 'Блокада передней ветви левой ножки пучка Гиса.'], ['АВ-блокада I ст.', 'АВ-блокада I степени (PQ > 0.20 с).'],
                ['АВ-блокада II ст.', 'АВ-блокада II степени.'], ['АВ-блокада III ст.', 'Полная АВ-блокада III степени.'],
                ['Одиночная ЖЭС', 'Одиночная желудочковая экстрасистолия.'], ['Частая ЖЭС / бигеминия', 'Частая желудочковая экстрасистолия по типу бигеминии.'],
                ['Наджелудочковая ЭС', 'Одиночная наджелудочковая экстрасистолия.'], ['ПИКС (рубцовые)', 'Рубцовые изменения миокарда (зубец Q).']
            ], selCond);

            addMulti('4. Ишемия / ОКС (ST и зубец T):', [
                ['Элевация ST V1-V4 (передний)', 'Элевация сегмента ST до 3–4 мм в V1–V4, реципрокная депрессия ST в II, III, aVF.'],
                ['Элевация ST I, aVL, V5-V6 (боковой)', 'Элевация сегмента ST в I, aVL, V5–V6.'],
                ['Элевация ST II, III, aVF (нижний)', 'Элевация сегмента ST до 3 мм в II, III, aVF, реципрокная депрессия ST в I, aVL, V1–V3.'],
                ['Депрессия ST V4-V6', 'Косонисходящая/горизонтальная депрессия сегмента ST до 1.5–2 мм в V4–V6.'],
                ['Депрессия ST II, III, aVF', 'Депрессия сегмента ST в II, III, aVF.'],
                ['Отрицательный T V1-V4', 'Отрицательные коронарные зубцы T в V1–V4.'],
                ['Отрицательный T V4-V6', 'Отрицательные зубцы T в V4–V6.'],
                ['Диффузные изменения', 'Диффузные изменения миокарда.']
            ], selIsch);

            hrInp.oninput = () => { hrVal = hrInp.value.trim(); updatePrev(); };
            body.querySelector('#ecg-b1').onclick = () => { window.insertIntoCardField('экг до оказания', prev.value.trim(), false); copyText(prev.value.trim()); close(); };
            body.querySelector('#ecg-b2').onclick = () => { window.insertIntoCardField('экг после оказания', prev.value.trim(), false); copyText(prev.value.trim()); close(); };
            updatePrev();
        });
    }

    // --- 8.2 ТАЙМЕР И ЛОГГЕР СЛР ---
    window.b103CprState = window.b103CprState || {
        isRunning: false, startPerf: 0, accumulatedSec: 0, adr: 0, amio: 0, shock: 0, log: [], nextRhythmSec: 120, nextAdrSec: 180
    };

    function openCprTimerIos() {
        let timerInt = null;
        const st = window.b103CprState;
        openIosSheet('⏱️ Реанимационный таймер и логгер СЛР', '#D32F2F', (body, close) => {
            body.innerHTML = `
                <div id="cpr-banner" style="padding:16px;border-radius:16px;background:#C62828;color:#fff;font-weight:700;font-size:17px;text-align:center;margin-bottom:10px;"></div>
                <div style="display:flex;gap:8px;margin-bottom:10px;">
                    <button id="cpr-start" style="flex:1.4;padding:13px;border:none;border-radius:12px;background:#2E7D32;color:#fff;font-weight:700;font-size:13.5px;"></button>
                    <button id="cpr-rosc" style="flex:1;padding:13px;border:none;border-radius:12px;background:#0277BD;color:#fff;font-weight:700;font-size:13px;">❤️ ВСК / ФИНАЛ</button>
                    <button id="cpr-reset" style="padding:13px 16px;border:none;border-radius:12px;background:#ECEFF1;color:#455A64;font-weight:700;font-size:16px;">🔄</button>
                </div>
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px;">
                    <button id="cpr-adr" style="padding:11px;border:1px solid #FFCDD2;border-radius:10px;background:#FFEBEE;color:#B71C1C;font-weight:700;font-size:12px;">💉 +Адреналин 1 мг</button>
                    <button id="cpr-shk" style="padding:11px;border:1px solid #FFCDD2;border-radius:10px;background:#FFEBEE;color:#B71C1C;font-weight:700;font-size:12px;">⚡ +Разряд 200 Дж</button>
                    <button id="cpr-amio" style="padding:11px;border:1px solid #FFCDD2;border-radius:10px;background:#FFEBEE;color:#B71C1C;font-weight:700;font-size:12px;">💉 +Амиодарон 300/150</button>
                    <button id="cpr-int" style="padding:11px;border:1px solid #FFCDD2;border-radius:10px;background:#FFEBEE;color:#B71C1C;font-weight:700;font-size:12px;">🫁 Интубация / Ларинг.</button>
                    <button id="cpr-asy" style="padding:11px;border:1px solid #FFCDD2;border-radius:10px;background:#FFEBEE;color:#B71C1C;font-weight:700;font-size:12px;">📈 Ритм: Асистолия</button>
                    <button id="cpr-vf" style="padding:11px;border:1px solid #FFCDD2;border-radius:10px;background:#FFEBEE;color:#B71C1C;font-weight:700;font-size:12px;">📈 Ритм: ФЖ / ЖТ</button>
                </div>
                <div id="cpr-log" style="padding:12px;background:#FAFAFA;border:1px solid #E0E0E0;border-radius:12px;font-size:13px;line-height:1.45;color:#263238;height:125px;overflow-y:auto;white-space:pre-line;margin-bottom:10px;"></div>
                <button id="cpr-ins" style="width:100%;padding:14px;border:none;border-radius:12px;background:#00695C;color:#fff;font-weight:700;font-size:13px;cursor:pointer;">⚡ ДОБАВИТЬ ПРОТОКОЛ СЛР В АНАМНЕЗ КАРТЫ</button>
            `;
            const banner = body.querySelector('#cpr-banner'), btnStart = body.querySelector('#cpr-start'), logBox = body.querySelector('#cpr-log');
            const nowPerf = () => (window.performance && performance.now) ? performance.now() : Date.now();
            const getSec = () => st.isRunning ? st.accumulatedSec + Math.floor((nowPerf() - st.startPerf) / 1000) : st.accumulatedSec;

            function update() {
                const sTotal = getSec(), m = Math.floor(sTotal / 60), s = sTotal % 60;
                while (sTotal >= st.nextRhythmSec + 12) st.nextRhythmSec += 120;
                while (sTotal >= st.nextAdrSec + 12) st.nextAdrSec += 180;
                const isRhythm = sTotal >= st.nextRhythmSec && sTotal < st.nextRhythmSec + 12;
                const isAdr = sTotal >= st.nextAdrSec && sTotal < st.nextAdrSec + 12;
                let hint = 'НМС 100–120/мин (глубина 5–6 см), соотношение 30:2';
                if (sTotal >= 1800) hint = '⚠️ Прошло 30 минут СЛР! Оценить целесообразность / констатацию';
                else if (isRhythm && isAdr) hint = '🔔 2 МИН: Контроль ритма на ЭКГ! + 💉 Адреналин 1 мг в/в!';
                else if (isRhythm) hint = '🔔 2 МИНУТЫ! Оцените ритм на ЭКГ и пульс!';
                else if (isAdr) hint = '💉 Прошло 3 мин! Время ввести Адреналин 1 мг в/в!';
                else if (!st.isRunning && sTotal === 0) hint = 'Нажмите СТАРТ для начала отсчета реанимации';
                else if (!st.isRunning) hint = 'СЛР приостановлена (нажмите ПРОДОЛЖИТЬ)';

                banner.style.background = ((isRhythm || isAdr) && st.isRunning) ? '#E65100' : (st.isRunning ? '#C62828' : '#78909C');
                banner.innerHTML = `⏱️ СЛР: ${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')} (Адр: ${st.adr} | Разряд: ${st.shock})<br><span style="font-size:12.5px;font-weight:600;">${hint}</span>`;
                btnStart.innerText = st.isRunning ? '⏸️ ПАУЗА' : (sTotal === 0 ? '▶️ СТАРТ СЛР' : '▶️ ПРОДОЛЖИТЬ');
                btnStart.style.background = st.isRunning ? '#F57C00' : '#2E7D32';
                logBox.innerText = st.log.length ? st.log.join('\n') : 'Журнал реанимации пуст. Нажимайте кнопки при введении препаратов и разрядах...';
            }
            function addLog(txt) {
                const sTotal = getSec();
                st.log.push(`[${new Date().toLocaleTimeString()} | ${String(Math.floor(sTotal / 60)).padStart(2, '0')}:${String(sTotal % 60).padStart(2, '0')}] ${txt}`);
                update();
            }

            btnStart.onclick = () => {
                if (!st.isRunning) {
                    st.isRunning = true; st.startPerf = nowPerf();
                    if (getSec() === 0 && !st.log.length) addLog('Начата расширенная СЛР (НМС, ИВЛ, мониторинг ЭКГ, в/в доступ)');
                } else {
                    st.accumulatedSec += Math.floor((nowPerf() - st.startPerf) / 1000);
                    st.isRunning = false;
                }
                update();
            };
            body.querySelector('#cpr-rosc').onclick = () => {
                if (st.isRunning) { st.accumulatedSec += Math.floor((nowPerf() - st.startPerf) / 1000); st.isRunning = false; }
                const t = getSec();
                addLog(`Завершение СЛР (длительность ${Math.floor(t / 60)} мин ${t % 60} сек). Итого: Адреналин — ${st.adr} мг, Разрядов — ${st.shock}, Амиодарон — ${st.amio}.`);
            };
            body.querySelector('#cpr-reset').onclick = () => {
                if (confirm('Сбросить протокол СЛР для нового пациента?')) {
                    Object.assign(st, { isRunning: false, startPerf: 0, accumulatedSec: 0, adr: 0, amio: 0, shock: 0, log: [], nextRhythmSec: 120, nextAdrSec: 180 });
                    update();
                }
            };
            body.querySelector('#cpr-adr').onclick = () => { st.adr++; addLog(`Введен Эпинефрин (Адреналин) 1 мг в/в (#${st.adr})`); };
            body.querySelector('#cpr-shk').onclick = () => { st.shock++; addLog(`Дефибрилляция разрядом 200 Дж (#${st.shock})`); };
            body.querySelector('#cpr-amio').onclick = () => { st.amio++; addLog(`Введен Амиодарон ${st.amio === 1 ? '300 мг' : '150 мг'} в/в`); };
            body.querySelector('#cpr-int').onclick = () => addLog('Обеспечена проходимость ВДП (ЭТТ / Ларингеальная маска), ИВЛ 100% O2');
            body.querySelector('#cpr-asy').onclick = () => addLog('Контроль ритма на ЭКГ: Асистолия / ЭМД');
            body.querySelector('#cpr-vf').onclick = () => addLog('Контроль ритма на ЭКГ: Фибрилляция желудочков (ФЖ)');
            body.querySelector('#cpr-ins').onclick = () => {
                const summary = 'Хронология СЛР:\n' + st.log.join('; ');
                window.insertIntoCardField('анамнез заболевания', summary, true);
                copyText(summary); close();
            };

            timerInt = setInterval(update, 1000);
            update();
        }, () => { if (timerInt) clearInterval(timerInt); });
    }

    // --- 8.3 БЫСТРЫЕ ФРАЗЫ И ОТКАЗЫ (8 ШАБЛОНОВ) ---
    function openQuickPhrasesIos() {
        const phrases = [
            ['📝 Согласие + Транспортировка со спецсигналами', 'примечания', 'Дано устное согласие на медицинское вмешательство. Транспортировка на носилках со спецсигналами. Непрерывный мониторинг АД, ЧСС, SpO2 в пути.'],
            ['🚨 Оповещение стационара (ТРИАЖ — ОКС / ОНМК / ТЭЛА / Шок)', 'примечания', 'Дано устное согласие на медицинское вмешательство. Через диспетчера СМП и систему ТРИАЖ приемное отделение стационара заблаговременно оповещено о доставке тяжелого пациента.'],
            ['✋ Юридически грамотный отказ от госпитализации', 'примечания', 'От предложенной госпитализации в стационар пациент в ясном сознании категорически отказался. В доступной форме разъяснены возможные осложнения и последствия отказа, в том числе риск внезапного ухудшения состояния и неблагоприятного (летального) исхода. Даны подробные рекомендации по приему плановых препаратов и вызову СМП при ухудшении. Оставлен актив в поликлинику.'],
            ['🪑 Отказ пациента от переноски на носилках', 'примечания', 'От предложенной транспортировки на носилках пациент категорически отказался, до автомобиля СМП прошел в сопровождении бригады.'],
            ['🍺 Отказ от мед. вмешательства в алкогольном опьянении', 'примечания', 'От осмотра, проведения ЭКГ, медицинских манипуляций и госпитализации пациент категорически отказался в присутствии родственников/свидетелей. Последствия отказа разъяснены в доступной форме. Угрожающих жизни состояний на момент осмотра не выявлено.'],
            ['🩹 Локальный статус: Чистый неврологический осмотр (норма)', 'локальный статус', 'В сознании, контактен, ориентирован в месте, времени и собственной личности верно. Зрачки D=S, фотореакция живая. Нистагма нет. Лицо симметричное, язык по средней линии. Менингеальных знаков нет. Парезов и параличей нет, чувствительность сохранена. В позе Ромберга устойчив, ПНП выполняет уверенно.'],
            ['🩹 Локальный статус: Ушиб мягких тканей / Гематома', 'локальный статус', 'При осмотре в области травмы определяется умеренный локальный отек и подкожная гематома 3,0х3,0 см, кожные покровы целы, пальпация умеренно болезненна. Крепитации и патологической подвижности нет. Чувствительность, пульсация и активные движения в дистальных отделах сохранены в полном объеме.'],
            ['🩹 Локальный статус: Ушибленная / Резаная рана', 'локальный статус', 'При осмотре определяется линейная рана длиной 3,0 см с ровными краями, дном раны является подкожно-жировая клетчатка. Кровотечение капиллярное, необильное. Наложена асептическая повязка, гемостаз.']
        ];
        openIosSheet('📝 Быстрые фразы: Отказы, Примечания, Статус', '#5D4037', (body, close) => {
            phrases.forEach(([t, k, v]) => {
                const d = document.createElement('div');
                d.style.cssText = 'padding:13px 14px;border-radius:12px;background:#EFEBE9;border:1px solid #D7CCC8;margin-bottom:8px;cursor:pointer;font-size:13.2px;color:#263238;';
                d.innerHTML = `<b>${escapeHtml(t)}</b><br><span style="font-size:12px;color:#5D4037;">➔ Поле: «${escapeHtml(k.toUpperCase())}»</span><br><span style="font-size:12.5px;color:#4E342E;">${escapeHtml(v)}</span>`;
                d.onclick = () => { window.insertIntoCardField(k, v, true); copyText(v); close(); };
                body.appendChild(d);
            });
        });
    }

    // --- 8.4 СПРАВОЧНИК МКБ-10 (19 КАТЕГОРИЙ + ЗАГРУЗКА/КЭШ) ---
    function openMkbSheetIos() {
        const cats = [
            ['🌟 Все', 'ALL'], ['❤️ I Кардио', 'I'], ['🫁 J Дыхание', 'J'], ['🧠 G Невро', 'G'], ['🩹 S Травмы', 'S'],
            ['🧪 T Токсико', 'T'], ['🚨 R Симптомы', 'R'], ['🤢 K ЖКТ', 'K'], ['🩸 E Эндокрин', 'E'], ['🤰 O Акушерство', 'O'],
            ['🏥 Z Прочее', 'Z'], ['🦠 A/B Инфекции', 'AB'], ['🤪 F Психиатрия', 'F'], ['🚽 N Урология', 'N'],
            ['🦴 M Кости', 'M'], ['🎗️ C/D Онко', 'CD'], ['👂 H ЛОР', 'H'], ['👶 P/Q Дети', 'PQ'], ['⚠️ V-Y Причины', 'VY']
        ];
        let selCat = 'ALL', mkbDone = window.b103MkbList.length > 0;
        openIosSheet('📖 Справочник МКБ-10', '#00695C', (body, close, titleEl) => {
            body.innerHTML = `
                <div id="mkb-chips" style="display:flex;gap:8px;overflow-x:auto;padding-bottom:8px;"></div>
                <div style="display:flex;gap:8px;margin-bottom:8px;">
                    <input id="mkb-inp" type="text" placeholder="Поиск (I48, инфаркт, пневмония)..." style="flex:1;padding:11px 13px;border-radius:12px;border:1.5px solid #80CBC4;background:#E0F2F1;color:#111;font-size:14px;outline:none;">
                    <button id="mkb-file-btn" title="Загрузить mkb10.json/csv вручную" style="padding:0 12px;border:none;border-radius:12px;background:#E0F2F1;color:#004D40;font-weight:700;font-size:14px;cursor:pointer;">📥</button>
                    <input id="mkb-file-inp" type="file" accept=".json,.csv" style="display:none;">
                </div>
                <div id="mkb-list" style="display:flex;flex-direction:column;gap:8px;"></div>
            `;
            const chips = body.querySelector('#mkb-chips'), inp = body.querySelector('#mkb-inp'), list = body.querySelector('#mkb-list');
            const fInp = body.querySelector('#mkb-file-inp');

            function renderChips() {
                chips.innerHTML = '';
                cats.forEach(([lbl, key]) => {
                    const sel = key === selCat;
                    const s = document.createElement('span');
                    s.style.cssText = `white-space:nowrap;padding:7px 12px;border-radius:14px;font-size:12px;cursor:pointer;font-weight:${sel ? '700' : '500'};background:${sel ? '#00796B' : '#E0F2F1'};color:${sel ? '#fff' : '#004D40'};`;
                    s.innerText = lbl;
                    s.onclick = () => { selCat = key; renderChips(); filter(); };
                    chips.appendChild(s);
                });
            }

            function filter() {
                const full = window.b103MkbList;
                if (!full.length) {
                    list.innerHTML = mkbDone
                        ? '<div style="padding:18px;text-align:center;color:#C62828;background:#FFF8F8;border:1px solid #FFCDD2;border-radius:12px;">⚠️ Не удалось скачать базу МКБ-10 из сети (офлайн или ограничение CSP сайта).<br><span style="font-size:12px;color:#546E7A;">Нажмите 📥 справа от поиска и выберите файл mkb10.json или mkb-parsed.csv</span></div>'
                        : '<div style="padding:18px;text-align:center;color:#546E7A;background:#F7FAFA;border-radius:12px;">⏳ Загрузка базы МКБ-10 (12 500+ кодов)...<br><span style="font-size:12px;">Если сеть заблокирована, нажмите 📥 и выберите mkb10.json или mkb-parsed.csv</span></div>';
                    return;
                }
                titleEl.innerText = `📖 Справочник МКБ-10 (${full.length} кодов)`;
                const q = inp.value.trim().toLowerCase();
                const res = [];
                for (let i = 0; i < full.length && res.length < 80; i++) {
                    const it = full[i];
                    const mc = selCat === 'ALL' || (selCat === 'AB' && (it.l === 'A' || it.l === 'B')) || (selCat === 'CD' && (it.l === 'C' || it.l === 'D')) || (selCat === 'PQ' && (it.l === 'P' || it.l === 'Q')) || (selCat === 'VY' && ['V', 'W', 'X', 'Y'].includes(it.l)) || it.l === selCat;
                    if (mc && (!q || it.c.toLowerCase().includes(q) || it.n.toLowerCase().includes(q))) res.push(it);
                }
                list.innerHTML = '';
                res.forEach(it => {
                    const d = document.createElement('div');
                    d.style.cssText = 'padding:12px 14px;border-radius:12px;background:#F7FAFA;border:1px solid #E0EAEA;color:#263238;font-size:13.5px;cursor:pointer;';
                    d.innerText = `[${it.c}] ${it.n}`;
                    d.onclick = () => { copyText(`[${it.c}] ${it.n}`); close(); };
                    list.appendChild(d);
                });
            }

            body.querySelector('#mkb-file-btn').onclick = () => fInp.click();
            fInp.onchange = (e) => {
                const file = e.target.files[0]; if (!file) return;
                const r = new FileReader();
                r.onload = async () => {
                    try {
                        let arr = [];
                        const txt = String(r.result || '').trim();
                        if (txt.startsWith('[')) {
                            arr = JSON.parse(txt).filter(x => x && x.c && x.n).map(x => ({ c: String(x.c).trim().toUpperCase(), n: String(x.n).trim(), l: String(x.c).trim().toUpperCase()[0] }));
                        } else {
                            arr = parseCsvMkb(txt, txt.includes(';') ? ';' : ',');
                        }
                        if (arr.length > 100) {
                            window.b103MkbList = arr; await idbSaveMkb(arr); filter();
                            showToast(`✅ Загружено ${arr.length} кодов МКБ-10!`);
                        } else alert('Не удалось распознать коды МКБ-10');
                    } catch (err) { alert('Ошибка чтения файла МКБ-10'); }
                };
                r.readAsText(file);
            };

            inp.oninput = filter; renderChips(); filter();
            ensureMkbLoaded(false).then(() => { mkbDone = true; filter(); });
        });
    }

    // --- 8.5 ПЕДИАТРИЯ (8 ПРЕСЕТОВ + ПОСТ. №118) ---
    function openPediatricsIos() {
        const presets = [
            ['Новор.', 3.5, '120–160', '40–60', '65–85 / 45–55', '3.0–3.5', '3.0', '9–10 см', '№1 (<5 кг)', '0.3–0.5 л'],
            ['6 мес', 7.5, '110–140', '30–40', '80–95 / 50–65', '3.5–4.0', '3.0–3.5', '10.5–11 см', '№1.5 (5–10 кг)', '0.8–1.0 л'],
            ['1 год', 10.0, '105–130', '28–35', '90–105 / 55–70', '4.0–4.5', '3.5–4.0', '12 см', '№2 (10–20 кг)', '1.0–1.5 л'],
            ['3 года', 15.0, '95–120', '24–28', '95–110 / 60–70', '4.5–5.0', '4.0–4.5', '13.5 см', '№2 (10–20 кг)', '3.0–4.0 л'],
            ['5 лет', 19.0, '90–110', '22–25', '98–112 / 60–72', '5.0–5.5', '4.5–5.0', '14.5 см', '№2–2.5', '4.0–5.0 л'],
            ['7 лет', 23.0, '85–105', '20–24', '102–116 / 62–75', '5.5–6.0', '5.0–5.5', '15.5 см', '№2.5 (20–30 кг)', '5.0–6.0 л'],
            ['10 лет', 32.0, '75–95', '18–22', '108–122 / 65–78', '6.0–6.5', '6.0', '17 см', '№3 (30–50 кг)', '7.0–8.0 л'],
            ['14 лет', 50.0, '65–85', '16–20', '110–128 / 70–80', '7.0–7.5', '6.5–7.0', '19–20 см', '№3–4 (50+ кг)', '8.0–10.0 л']
        ];
        let selIdx = 2;
        openIosSheet('👶 Педиатрия: Нормы, Дозы, ИВЛ (Пост. №118)', '#0277BD', (body) => {
            body.innerHTML = `
                <div id="ped-chips" style="display:flex;gap:8px;overflow-x:auto;padding-bottom:10px;"></div>
                <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px;">
                    <span style="font-size:14px;font-weight:700;color:#263238;">⚖️ Вес ребенка (кг):</span>
                    <input id="ped-w" type="number" step="0.5" value="10.0" style="flex:1;padding:10px 12px;border-radius:12px;border:1.5px solid #29B6F6;background:#E1F5FE;color:#111;font-size:15px;font-weight:700;">
                </div>
                <div id="ped-out" style="font-size:13.5px;line-height:1.52;color:#263238;white-space:pre-line;background:#F5F7F8;padding:14px;border-radius:12px;user-select:text;"></div>
            `;
            const chips = body.querySelector('#ped-chips'), wInp = body.querySelector('#ped-w'), out = body.querySelector('#ped-out');
            const fmt = (v) => Number(v.toFixed(2)).toString();

            function drawChips() {
                chips.innerHTML = '';
                presets.forEach(([lbl, defW], idx) => {
                    const sel = idx === selIdx;
                    const s = document.createElement('span');
                    s.style.cssText = `white-space:nowrap;padding:7px 12px;border-radius:14px;font-size:12px;cursor:pointer;font-weight:${sel ? '700' : '500'};background:${sel ? '#0277BD' : '#E1F5FE'};color:${sel ? '#fff' : '#01579B'};`;
                    s.innerText = `${lbl} (${defW} кг)`;
                    s.onclick = () => { selIdx = idx; wInp.value = defW; drawChips(); calc(); };
                    chips.appendChild(s);
                });
            }

            function calc() {
                const w = parseFloat(String(wInp.value).replace(',', '.')) || 0;
                const p = presets[selIdx];
                let txt = `📊 ВОЗРАСТНЫЕ НОРМЫ (${p[0]}):\n• Пульс (ЧСС): ${p[2]} уд/мин | Дыхание (ЧДД): ${p[3]} в мин\n• АД норма: ${p[4]} мм рт.ст.\n\n`;
                if (w > 0) {
                    const cprRatio = selIdx <= 1 ? '5:1 (до 1 года: 5 компрессий : 1 вдох)' : '15:2 (2 спасателя) / 30:2 (1 спасатель)';
                    const depthCm = selIdx <= 1 ? '4 см (<1 года)' : (selIdx <= 6 ? '5 см (1–10 лет)' : '5–6 см (>10 лет)');
                    const berodual = selIdx === 0 ? 'не показан новорожденным без строгих показаний' : (selIdx <= 4 ? '10 капель + 2–3 мл NaCl 0.9% (по инструкции <6 лет)' : '20 капель + 2–3 мл NaCl 0.9% (6–12 лет и старше)');
                    txt += `🫁 РЕСПИРАТОРНАЯ ПОДДЕРЖКА И СЛР НА ${fmt(w)} КГ (Пост. №118):\n` +
                        `• ЭТТ без манжеты: Ø ${p[5]} мм | с манжетой: Ø ${p[6]} мм\n` +
                        `• Глубина ЭТТ от угла рта: ${p[7]} | Ларинг. маска: ${p[8]}\n` +
                        `• ИВЛ дыхательный объем (6–8 мл/кг): ${Math.round(w * 6)} – ${Math.round(w * 8)} мл\n` +
                        `• Дефибрилляция (ФЖ/ЖТ, п. 22.3): 1-й и 2-й разряд ${Math.round(w * 4)} Дж (4 Дж/кг), 3-й — ${Math.round(w * 6)} Дж (6 Дж/кг), далее до ${Math.round(w * 8)} Дж (8 Дж/кг)\n` +
                        `• СЛР (п. 20.3): старт с 5 вдохов, далее ${cprRatio}, глубина ${depthCm} (~1/3 грудной клетки)\n` +
                        `• Объем для промывания желудка: ${p[9]}\n\n` +
                        `💉 ЭКСТРЕННЫЕ ДОЗИРОВКИ НА ${fmt(w)} КГ (Пост. МЗ РБ №118):\n` +
                        `• Адреналин 0,18% (СЛР в/в, в/к): ${fmt(Math.min(1.0, w * 0.01))} мл (в разведении 1 мл + 9 мл NaCl = ${fmt(Math.min(10.0, w * 0.1))} мл)\n` +
                        `• Адреналин 0,18% (Анафилаксия в/м): ${fmt(Math.min(0.3, w * 0.01))} мл в переднебоковую поверхность бедра (макс. 0.3 мл)\n` +
                        `• Амиодарон 5% (СЛР ФЖ/ЖТ в/в): ${fmt(Math.min(6.0, w * 0.1))} мл (${fmt(Math.min(300.0, w * 5.0))} мг; 5 мг/кг, повтор до 15 мг/кг; <3 лет — off-label)\n` +
                        `• Натрия гидрокарбонат 4% (ацидоз/гиперК+ при СЛР): ${fmt(w * 2.0)} мл (1 ммоль/кг = 2 мл/кг, далее 1 мл/кг)\n` +
                        `• Кальция хлорид 10%: ${fmt(Math.min(10.0, w * 0.2))} мл (20 мг/кг = 0.2 мл/кг) или Кальция глюконат 10%: ${fmt(Math.min(20.0, w * 0.6))} – ${fmt(Math.min(20.0, w * 0.8))} мл (0.6–0.8 мл/кг)\n` +
                        `• Атропин 0,1% (брадикардия): ${fmt(Math.min(0.5, Math.max(0.1, w * 0.02)))} мл (0.02 мг/кг, мин. 0.1 мг)\n` +
                        `• Преднизолон (30 мг/мл): ${fmt(Math.min(90.0, w * 2.0))} мг = ${fmt(Math.min(3.0, w * 2.0 / 30.0))} мл (2 мг/кг)\n` +
                        `• Дексаметазон (4 мг/мл): ${fmt(Math.min(16.0, w * 0.4))} мг = ${fmt(Math.min(4.0, w * 0.1))} мл (0.3–0.6 мг/кг при крупе)\n` +
                        `• Диазепам 0,5% (судороги в/в, в/м): ${fmt(Math.min(2.0, w * 0.05))} – ${fmt(Math.min(2.0, w * 0.06))} мл (0.25–0.3 мг/кг)\n` +
                        `• Магния сульфат 25%: ${fmt(Math.min(8.0, w * 0.1))} – ${fmt(Math.min(10.0, w * 0.2))} мл (25–50 мг/кг в/в капельно)\n` +
                        `• Метамизол (Анальгин 50%): ${fmt(Math.min(2.0, w * 0.02))} мл (10 мг/кг = 0.02 мл/кг)\n` +
                        `• Супрастин 2% (20 мг/мл): ${fmt(Math.min(1.0, w * 0.02))} – ${fmt(Math.min(1.0, w * 0.03))} мл (0.4–0.6 мг/кг)\n` +
                        `• Димедрол 1% (10 мг/мл): ${fmt(Math.min(2.0, w * 0.05))} – ${fmt(Math.min(2.0, w * 0.1))} мл (0.5–1 мг/кг, макс. 2 мл)\n` +
                        `• Фуросемид 1% (10 мг/мл): ${fmt(Math.min(4.0, w * 0.1))} мл (1 мг/кг)\n` +
                        `• Декстроза 10% (гипогликемия в/в): ${fmt(w * 2.0)} – ${fmt(w * 4.0)} мл (2–4 мл/кг)\n` +
                        `• NaCl 0,9% (болюс при шоке/эксикозе): ${fmt(w * 20.0)} мл (20 мл/кг)\n` +
                        `• Парацетамол (внутрь/свечи): ${fmt(Math.min(500.0, w * 15.0))} мг (15 мг/кг)\n` +
                        `• Ибупрофен (внутрь): ${fmt(Math.min(400.0, w * 10.0))} мг (10 мг/кг)\n` +
                        `• Беродуал (небулайзер): ${berodual}\n` +
                        `• Будесонид / Пульмикорт (круп): 0.5 – 2.0 мг через небулайзер`;
                }
                out.innerText = txt;
            }
            wInp.oninput = calc; drawChips(); calc();
        });
    }

    // --- 8.6 ШКАЛЫ И ИНДЕКСЫ СМП (7 ВКЛАДОК / 10 ШКАЛ) ---
    function openScalesIos() {
        const tabs = ['👁️ Глазго (Взр/Дет)', '❤️ Шок-индекс и Инфузия', '🫁 Шкала ОДН', '🩸 ТЭЛА (Wells/Женева)', '🤢 Аппендицит (Альварадо)', '🧠 Инсульт (NIHSS)', '🫀 ХСН (ШОКС) / CIWA'];
        let curTab = 0;
        openIosSheet('🧠 Клинические шкалы и индексы СМП', '#5E35B1', (body) => {
            body.innerHTML = `<div id="sc-tabs" style="display:flex;gap:8px;overflow-x:auto;padding-bottom:10px;"></div><div id="sc-body"></div>`;
            const tabsEl = body.querySelector('#sc-tabs'), scBody = body.querySelector('#sc-body');

            function addChecklist(title, items, exclPairs, interpreter) {
                const h = document.createElement('div');
                h.style.cssText = 'font-size:14.5px;font-weight:700;color:#4527A0;margin:6px 0 8px 0;'; h.innerText = title;
                const banner = document.createElement('div');
                banner.style.cssText = 'padding:14px;border-radius:14px;background:#5E35B1;color:#fff;font-weight:700;font-size:13.5px;text-align:center;margin-bottom:10px;cursor:pointer;';
                scBody.appendChild(h); scBody.appendChild(banner);

                const sel = new Array(items.length).fill(false);
                const rows = [];
                const updateScore = () => {
                    let sum = 0;
                    for (let i = 0; i < items.length; i++) if (sel[i]) sum += items[i][1];
                    const sStr = sum % 1 === 0 ? String(sum) : sum.toFixed(1);
                    const interp = interpreter(sum);
                    banner.innerText = `Сумма: ${sStr} б. — ${interp}\n📋 Нажмите, чтобы скопировать`;
                    banner.onclick = () => copyText(`${title}: ${sStr} б. (${interp})`);
                };
                const styleRow = (i) => {
                    const on = sel[i], [lbl, pts] = items[i], pStr = pts % 1 === 0 ? `+${pts}` : `+${pts}`;
                    rows[i].innerText = `${on ? '☑️' : '☐'}  ${lbl} (${pStr} б.)`;
                    rows[i].style.background = on ? '#7E57C2' : '#F3E5F5';
                    rows[i].style.color = on ? '#fff' : '#263238';
                    rows[i].style.fontWeight = on ? '700' : '400';
                };
                items.forEach((_, idx) => {
                    const tv = document.createElement('div');
                    tv.style.cssText = 'padding:12px 14px;border-radius:11px;font-size:13.5px;margin-bottom:7px;cursor:pointer;';
                    tv.onclick = () => {
                        sel[idx] = !sel[idx];
                        if (sel[idx]) {
                            for (const [a, b] of exclPairs) {
                                if (a === idx && sel[b]) { sel[b] = false; styleRow(b); }
                                else if (b === idx && sel[a]) { sel[a] = false; styleRow(a); }
                            }
                        }
                        styleRow(idx); updateScore();
                    };
                    rows.push(tv); styleRow(idx); scBody.appendChild(tv);
                });
                updateScore();
            }

            function renderTab() {
                scBody.innerHTML = '';
                if (curTab === 0) {
                    let isPed = false, e = 4, v = 5, m = 6;
                    scBody.innerHTML = `<div id="g-mode" style="display:flex;gap:8px;margin-bottom:10px;"></div><div id="g-ban" style="padding:14px;border-radius:14px;background:#00796B;color:#fff;font-weight:700;font-size:14px;text-align:center;margin-bottom:10px;cursor:pointer;"></div><div id="g-grps"></div>`;
                    const gMode = scBody.querySelector('#g-mode'), gBan = scBody.querySelector('#g-ban'), gGrps = scBody.querySelector('#g-grps');
                    const updG = () => {
                        const tot = e + v + m;
                        const st = tot === 15 ? 'Ясное сознание' : tot >= 13 ? 'Умеренное оглушение' : tot >= 11 ? 'Глубокое оглушение' : tot >= 9 ? 'Сопор' : tot >= 7 ? 'Кома I ст.' : tot >= 5 ? 'Кома II ст.' : 'Кома III ст.';
                        gBan.innerText = `ШКГ (${isPed ? 'Дети' : 'Взрослые'}): E${e} V${v} M${m} = ${tot} б (${st})\n📋 Нажмите, чтобы скопировать`;
                        gBan.onclick = () => copyText(`ШКГ: E${e} V${v} M${m} = ${tot} б (${st})`);
                    };
                    const buildG = () => {
                        gGrps.innerHTML = '';
                        const grp = (t, opts, getC, setC) => {
                            const hd = document.createElement('div'); hd.style.cssText = 'font-size:13px;font-weight:700;color:#311B92;margin:8px 0 6px 0;'; hd.innerText = t;
                            const row = document.createElement('div'); row.style.cssText = 'display:flex;gap:8px;overflow-x:auto;padding-bottom:4px;';
                            const draw = () => {
                                row.innerHTML = '';
                                opts.forEach(([pts, lbl]) => {
                                    const on = pts === getC();
                                    const s = document.createElement('span');
                                    s.style.cssText = `white-space:nowrap;padding:8px 12px;border-radius:12px;font-size:12px;cursor:pointer;font-weight:${on ? '700' : '400'};background:${on ? '#5E35B1' : '#EDE7F6'};color:${on ? '#fff' : '#263238'};`;
                                    s.innerText = `${pts} — ${lbl}`;
                                    s.onclick = () => { setC(pts); draw(); updG(); };
                                    row.appendChild(s);
                                });
                            };
                            draw(); gGrps.appendChild(hd); gGrps.appendChild(row);
                        };
                        grp('👁️ Открывание глаз (E):', [[4, 'Спонтанное'], [3, 'На звук/речь'], [2, 'На боль'], [1, 'Отсутствует']], () => e, x => e = x);
                        grp(isPed ? '👶 Речь / Гуление у детей (V):' : '🗣️ Речевая реакция (V):',
                            isPed ? [[5, 'Улыбается, гулит'], [4, 'Плачет, успокаивается'], [3, 'Крик на боль, стон'], [2, 'Беспокоен, стонет'], [1, 'Нет реакции']]
                                  : [[5, 'Связная'], [4, 'Спутанная'], [3, 'Отд. слова'], [2, 'Нечленоразд. звуки'], [1, 'Отсутствует']], () => v, x => v = x);
                        grp('✋ Двигательная реакция (M):', [[6, isPed ? 'Спонтанные движения' : 'По команде'], [5, 'Локализует боль'], [4, 'Отдергивает'], [3, 'Сгибание'], [2, 'Разгибание'], [1, 'Отсутствует']], () => m, x => m = x);
                    };
                    const drawModes = () => {
                        gMode.innerHTML = '';
                        [[false, '🧑 Взрослые'], [true, '👶 Дети (до 4 лет)']].forEach(([ped, lbl]) => {
                            const on = isPed === ped;
                            const b = document.createElement('button');
                            b.style.cssText = `flex:1;padding:10px;border:none;border-radius:11px;font-size:13px;cursor:pointer;font-weight:${on ? '700' : '500'};background:${on ? '#5E35B1' : '#EDE7F6'};color:${on ? '#fff' : '#4527A0'};`;
                            b.innerText = lbl; b.onclick = () => { isPed = ped; drawModes(); buildG(); updG(); };
                            gMode.appendChild(b);
                        });
                    };
                    drawModes(); buildG(); updG();
                } else if (curTab === 1) {
                    scBody.innerHTML = `
                        <div style="font-size:14.5px;font-weight:700;color:#C62828;margin-bottom:10px;">❤️ Шок-индекс Альговера (ЧСС / САД), кровопотеря и объем инфузии</div>
                        <div style="display:flex;gap:8px;margin-bottom:10px;">
                            <div style="flex:1;"><div style="font-size:12px;font-weight:700;color:#37474F;margin-bottom:4px;">💓 Пульс (ЧСС):</div><input id="sh-hr" type="number" placeholder="Напр. 110" style="width:100%;box-sizing:border-box;padding:10px;border-radius:10px;border:1.5px solid #EF9A9A;background:#FFEBEE;color:#111;"></div>
                            <div style="flex:1;"><div style="font-size:12px;font-weight:700;color:#37474F;margin-bottom:4px;">🩸 САД (верхнее АД):</div><input id="sh-sbp" type="number" placeholder="Напр. 90" style="width:100%;box-sizing:border-box;padding:10px;border-radius:10px;border:1.5px solid #EF9A9A;background:#FFEBEE;color:#111;"></div>
                        </div>
                        <div id="sh-res" style="padding:14px;border-radius:12px;background:#FFF8E1;border:1px solid #FFE082;font-size:13.8px;line-height:1.5;color:#263238;white-space:pre-line;cursor:pointer;"></div>
                    `;
                    const hrI = scBody.querySelector('#sh-hr'), sbI = scBody.querySelector('#sh-sbp'), res = scBody.querySelector('#sh-res');
                    const calcS = () => {
                        const hr = parseFloat(hrI.value), sbp = parseFloat(sbI.value);
                        if (hr > 0 && sbp > 0) {
                            const idx = hr / sbp;
                            const info = idx < 0.8 ? '• Норма (0.5–0.7)\n• Дефицит ОЦК: нет (<10%)\n• Инфузия: поддерживающая 200–400 мл кристаллоидов'
                                : idx < 1.0 ? '• Шок I ст. (компенсированный)\n• Потеря ОЦК: 15–20% (750–1000 мл)\n• Догоспитальный объем инфузии: 800–1200 мл кристаллоидов'
                                : idx < 1.5 ? '• Шок II ст. (субкомпенсированный)\n• Потеря ОЦК: 25–35% (1200–1800 мл)\n• Догоспитальный объем инфузии: 1500–2000 мл (кристаллоиды + коллоиды 2:1 в 2 вены)'
                                : '• Шок III–IV ст. (декомпенсированный)\n• Потеря ОЦК: >40% (>2000 мл)\n• Инфузия: струйно в 2 катетера 2000–2500 мл (1:1) + Норадреналин / Допамин';
                            res.innerText = `📊 Шок-индекс: ${idx.toFixed(2)}\n${info}\n\n📋 Нажмите, чтобы скопировать`;
                            res.onclick = () => copyText(`Шоковый индекс = ${idx.toFixed(2)}`);
                        } else {
                            res.innerText = 'Введите ЧСС и САД для расчета шокового индекса и объема инфузии.\n\n📌 Ориентировочная кровопотеря при переломах:\n• Голень: 500–800 мл | Бедро: 1000–1800 мл\n• Кости таза: 1500–3000 мл | Плечо/предплечье: 300–600 мл';
                            res.onclick = null;
                        }
                    };
                    hrI.oninput = calcS; sbI.oninput = calcS; calcS();
                } else if (curTab === 2) {
                    scBody.innerHTML = `<div style="padding:16px;border-radius:14px;background:#F3E5F5;font-size:14px;line-height:1.55;color:#263238;white-space:pre-line;">🫁 Классификация степеней ОДН:\n\n🟢 ОДН I ст.: сознание ясное/беспокойство, ЧДД 22–28/мин, SpO2 90–94%, акроцианоз, умеренная тахикардия.\n\n🟡 ОДН II ст.: возбуждение/заторможенность, ЧДД 30–38/мин, участие вспом. мышц, SpO2 75–89%, диффузный цианоз, пот, ЧСС >110–120.\n\n🔴 ОДН III ст.: сопор/кома, судороги, ЧДД >40 или <8/мин, SpO2 <75%, гипотония, аритмия → экстренная интубация и ИВЛ!</div>`;
                } else if (curTab === 3) {
                    addChecklist('🩸 Шкала Уэллса (Wells) — вероятность ТЭЛА:', [
                        ['Клинические признаки ТГВ (отек и боль в голени)', 3], ['ТЭЛА более вероятна, чем альтернативный диагноз', 3],
                        ['Тахикардия (ЧСС > 100 в минуту)', 1.5], ['Иммобилизация (>3 дней) или операция за 4 недели', 1.5],
                        ['ТГВ или ТЭЛА в анамнезе', 1.5], ['Кровохарканье', 1], ['Онкологическое заболевание', 1]
                    ], [], s => s <= 1 ? 'Низкий риск ТЭЛА (<2%)' : s <= 6 ? 'Умеренный риск ТЭЛА (20–30%)' : 'Высокий риск ТЭЛА (>60%)');

                    addChecklist('🇨🇭 Женевская шкала (пересмотренная) — ТЭЛА:', [
                        ['Возраст старше 65 лет', 1], ['ТГВ или ТЭЛА в анамнезе', 3], ['Операция или перелом конечности за 1 месяц', 2],
                        ['Активная онкология', 2], ['Односторонняя боль в нижней конечности', 3], ['Кровохарканье', 2],
                        ['ЧСС 75–94 в минуту', 3], ['ЧСС >= 95 в минуту', 5], ['Боль при пальпации вен и односторонний отек', 4]
                    ], [[6, 7]], s => s <= 3 ? 'Низкая вероятность ТЭЛА (<10%)' : s <= 10 ? 'Средняя вероятность ТЭЛА (30%)' : 'Высокая вероятность ТЭЛА (>65%)');
                } else if (curTab === 4) {
                    addChecklist('🤢 Шкала Альварадо (MANTRELS) — острый аппендицит:', [
                        ['Миграция боли в правую подвздошную область (с-м Кохера)', 1], ['Анорексия', 1], ['Тошнота или рвота', 1],
                        ['Болезненность в правой подвздошной области', 2], ['Положительный с-м Щеткина-Блюмберга', 1],
                        ['Температура тела >= 37.3 °C', 1], ['Лейкоцитоз > 10×10⁹/л', 2], ['Нейтрофильный сдвиг влево', 1]
                    ], [], s => s <= 4 ? 'Аппендицит маловероятен' : s <= 6 ? 'Возможен аппендицит' : 'Высокая вероятность острого аппендицита');
                } else if (curTab === 5) {
                    addChecklist('🧠 Догоспитальная оценка инсульта (LAMS / NIHSS):', [
                        ['Асимметрия лица (парез мимической мускулатуры)', 1], ['Парез руки (медленно опускается)', 1],
                        ['Плегия руки (быстро падает / нет движений, еще +1 б.)', 1], ['Снижение силы сжатия кисти', 1],
                        ['Отсутствие сжатия кисти (еще +1 б.)', 1], ['Нарушение речи (дизартрия / афазия)', 2],
                        ['Парез взора (поворот глаз в сторону очага)', 2], ['Угнетение сознания', 2]
                    ], [], s => s === 0 ? 'Очагового дефицита нет' : s <= 3 ? 'Умеренный дефицит (Инсульт / ТИА)' : 'Тяжелый инсульт (окно ТЛТ 4,5 ч)');
                } else if (curTab === 6) {
                    addChecklist('🫀 Шкала ШОКС (оценка тяжести ХСН):', [
                        ['Одышка в покое (2 б.) или при нагрузке', 2], ['Изменение веса за неделю', 1], ['Перебои в работе сердца', 1],
                        ['Ортопноэ', 2], ['Набухшие шейные вены', 2], ['Влажные хрипы в легких', 2], ['Ритм галопа', 1],
                        ['Увеличение печени', 2], ['Отеки ног / анасарка', 2], ['САД < 100 мм рт. ст.', 2]
                    ], [], s => s === 0 ? 'Признаков ХСН нет' : s <= 3 ? 'I ФК ХСН' : s <= 6 ? 'II ФК ХСН' : s <= 9 ? 'III ФК ХСН' : 'IV ФК ХСН');
                    addChecklist('🍺 Отмена алкоголя (CIWA-Ar):', [
                        ['Тошнота и рвота', 4], ['Выраженный тремор рук', 4], ['Профузная потливость', 4], ['Тревога', 4],
                        ['Психомоторное возбуждение', 5], ['Зрительные/слуховые обманы (предделирий)', 5], ['Дезориентация', 4]
                    ], [], s => s <= 8 ? 'Легкий синдром отмены' : s <= 15 ? 'Умеренный синдром отмены' : 'Тяжелая абстиненция, риск делирия');
                }
            }

            function drawTabs() {
                tabsEl.innerHTML = '';
                tabs.forEach((t, i) => {
                    const sel = i === curTab;
                    const s = document.createElement('span');
                    s.style.cssText = `white-space:nowrap;padding:8px 13px;border-radius:14px;font-size:12px;cursor:pointer;font-weight:${sel ? '700' : '500'};background:${sel ? '#5E35B1' : '#EDE7F6'};color:${sel ? '#fff' : '#4527A0'};`;
                    s.innerText = t;
                    s.onclick = () => { curTab = i; drawTabs(); renderTab(); };
                    tabsEl.appendChild(s);
                });
            }
            drawTabs(); renderTab();
        });
    }

    // --- 8.7 ШПАРГАЛКИ И 3 КАЛЬКУЛЯТОРА (% МГ, ДОПАМИН, O2 МПа) ---
    function openCheatSheetsIos() {
        const sheets = [
            ['📍 Локализация инфаркта миокарда (ИМ)', 'По отведениям ЭКГ + реципрокные изменения',
                '• Передне-перегородочный ИМ: Элевация ST в V1–V2 (V3) | Реципрокно: II, III, aVF\n• Передний распространенный ИМ: Элевация ST в I, aVL, V1–V6 | Реципрокно: II, III, aVF\n• Боковой ИМ: Элевация ST в I, aVL, V5–V6 | Реципрокно: II, III, aVF\n• Нижний (диафрагмальный) ИМ: Элевация ST в II, III, aVF | Реципрокно: I, aVL, V1–V3\n• Задне-базальный ИМ: Прямые V7–V9 (элевация ST >= 0.5 мм) | Реципрокно в V1–V3: высокий R > S, депрессия ST, высокий положительный T\n• ИМ правого желудочка: Элевация ST в II, III, aVF + V3R–V4R >= 1 мм (⚠️ противопоказаны нитраты, морфин и диуретики!)'],
            ['📈 Анализ ЭКГ: зубцы, интервалы и ЭОС', 'Нормы интервалов и положение ЭОС',
                '• При 25 мм/с: 1 мм = 0.04 с, 5 мм = 0.20 с | При 50 мм/с: 1 мм = 0.02 с, 5 мм = 0.10 с.\n• Нормы: P до 0.10 с; PQ 0.12–0.20 с (>0.20 с — АВ-блокада I ст.); QRS 0.06–0.10 с (>=0.12 с — полная блокада ножки пучка Гиса); QTc до 0.44 с.\n• ЭОС: Нормальная — R(II) > R(I) > R(III); Влево — высокий R в I, aVL и глубокий S в III (SIII > RIII); Вправо — глубокий S в I (SI > RI) и высокий R в III.'],
            ['💓 Расчет ЧСС по ЭКГ', 'Формулы для 25 мм/с и 50 мм/с и при ФП',
                '• 25 мм/с: ЧСС = 300 / число больших клеток (по 5 мм) между R–R (1 кл=300, 2=150, 3=100, 4=75, 5=60, 6=50).\n• 50 мм/с: ЧСС = 600 / число больших клеток между R–R (2 кл=300, 4=150, 6=100, 8=75, 10=60, 12=50).\n• При ФП: число комплексов QRS за 3 секунды (15 больших клеток при 25 мм/с или 30 больших клеток при 50 мм/с) умножить на 20.'],
            ['🔥 Площадь и степени ожога', 'Правило девяток Уоллеса, правило ладони и степени',
                '• Правило ладони: ладонь с пальцами = 1% поверхности тела.\n• Правило девяток у взрослых: голова и шея 9%, рука по 9%, грудь+живот 18%, спина+поясница 18%, нога по 18%, промежность 1%.\n• Степени: I ст. — гиперемия и отек; II ст. — пузыри с прозрачным серозным содержимым; IIIА ст. — напряженные/вскрывшиеся пузыри с желеобразным/геморрагическим содержимым; IIIБ ст. — тотальный некроз кожи (струп); IV ст. — обугливание.'],
            ['💧 ОКИ: степени дегидротации (эксикоза)', 'Клиническая оценка обезвоживания',
                '• I ст. (до 5%): жажда умеренная, слизистые влажные, тургор и диурез сохранены.\n• II ст. (6–9%): выраженная жажда, слизистые сухие, глаза/родничок запавшие, кожная складка расправляется за 1–2 с, тахикардия, олигурия.\n• III ст. (>=10%, шок): сопор/кома, кожа холодная мраморная, складка >2 с, гипотония, анурия → болюс NaCl 0.9% 20 мл/кг струйно!'],
            ['🫁 Респираторная поддержка и параметры ИВЛ', 'Показания к интубации и стартовые режимы ИВЛ',
                '• Оксигенотерапия: при SpO2 < 92–94% (при ХОБЛ целевая SpO2 88–92%, поток 1–3 л/мин).\n• Показания к интубации и ИВЛ: апноэ, ЧДД > 38–40 или < 8 в мин, SpO2 < 80% на фоне O2, кома <= 8 баллов по ШКГ.\n• Параметры ИВЛ у взрослых: ДО (Vt) = 6–8 мл/кг (450–600 мл), ЧД = 12–16/мин, МОВ = 6–8 л/мин, PEEP = 5 см вод. ст.']
        ];
        let cylVol = 2.0;
        openIosSheet('📋 Шпаргалки и быстрые расчеты СМП', '#EF6C00', (body) => {
            body.innerHTML = `
                <div style="font-weight:700;color:#E65100;margin-bottom:6px;">🧮 1. Расчет вещества в мг из % раствора</div>
                <div style="display:flex;gap:8px;margin-bottom:8px;">
                    <input id="c-pct" type="number" step="0.1" placeholder="Концентрация %" style="flex:1;padding:10px;border-radius:10px;border:1px solid #FFB74D;background:#FFF3E0;color:#111;">
                    <input id="c-ml" type="number" step="0.1" placeholder="Объем ампулы мл" style="flex:1;padding:10px;border-radius:10px;border:1px solid #FFB74D;background:#FFF3E0;color:#111;">
                </div>
                <div id="c-pct-out" style="padding:10px;background:#FFF8E1;border:1px solid #FFE082;border-radius:10px;font-size:13px;font-weight:700;margin-bottom:14px;color:#263238;white-space:pre-line;"></div>

                <div style="font-weight:700;color:#C62828;margin-bottom:6px;">💧 2. Калькулятор инфузии Допамина (200 мг = 5 мл 4%)</div>
                <div style="display:flex;gap:8px;margin-bottom:8px;">
                    <input id="c-dw" type="number" value="70" placeholder="Вес кг" style="flex:1;padding:10px;border-radius:10px;border:1px solid #EF9A9A;background:#FFEBEE;color:#111;">
                    <input id="c-dd" type="number" value="5" placeholder="мкг/кг/мин" style="flex:1;padding:10px;border-radius:10px;border:1px solid #EF9A9A;background:#FFEBEE;color:#111;">
                </div>
                <div id="c-dop-out" style="padding:10px;background:#FFF8F8;border:1px solid #FFCDD2;border-radius:10px;font-size:13px;margin-bottom:14px;color:#263238;white-space:pre-line;"></div>

                <div style="font-weight:700;color:#0277BD;margin-bottom:6px;">🫁 3. Расчет запаса кислорода в баллоне (в МПа)</div>
                <div id="c-cyls" style="display:flex;gap:8px;margin-bottom:8px;"></div>
                <div style="display:flex;gap:8px;margin-bottom:8px;">
                    <input id="c-mpa" type="number" step="0.5" value="15" placeholder="Давление МПа" style="flex:1;padding:10px;border-radius:10px;border:1px solid #4FC3F7;background:#E1F5FE;color:#111;">
                    <input id="c-fl" type="number" step="0.5" value="6" placeholder="Поток л/мин" style="flex:1;padding:10px;border-radius:10px;border:1px solid #4FC3F7;background:#E1F5FE;color:#111;">
                </div>
                <div id="c-o2-out" style="padding:10px;background:#E1F5FE;border:1px solid #81D4FA;border-radius:10px;font-size:13px;margin-bottom:14px;color:#263238;white-space:pre-line;"></div>

                <div style="font-weight:700;color:#E65100;margin-bottom:8px;">📚 Справочные шпаргалки (нажмите для открытия):</div>
                <div id="c-guides"></div>
            `;
            const fmtT = (mins) => { const m = Math.floor(mins); return m < 60 ? `${m} мин` : `${Math.floor(m / 60)} ч ${m % 60} мин (${m} мин)`; };
            const calcAll = () => {
                const p = parseFloat(body.querySelector('#c-pct').value), m = parseFloat(body.querySelector('#c-ml').value) || 1;
                body.querySelector('#c-pct-out').innerText = !isNaN(p)
                    ? `💉 В 1 мл содержится ${(p * 10).toFixed(1)} мг\n📦 В ${m} мл раствора = ${(p * 10 * m).toFixed(1)} мг вещества`
                    : '💡 Правило: 1% = 10 мг/мл (0.1% = 1 мг/мл, 2.4% = 24 мг/мл, 25% = 250 мг/мл)';

                const w = parseFloat(body.querySelector('#c-dw').value) || 70, d = parseFloat(body.querySelector('#c-dd').value) || 5, mcg = w * d;
                const eff = d <= 3 ? 'Почечная доза (диуретический эффект)' : d <= 10 ? 'Кардиотоническая доза (бета1 — рост выброса и САД)' : 'Вазопрессорная доза (альфа1 — вазоконстрикция и АД)';
                body.querySelector('#c-dop-out').innerText = `⚡ Скорость: ${Math.round(mcg)} мкг/мин (${eff})\n• Капельница (200 мг / 200 мл): ${Math.round(mcg / 50)} кап/мин | (на 400 мл): ${Math.round(mcg / 25)} кап/мин\n• Шприцевой дозатор (200 мг / 20 мл): ${((mcg * 60) / 10000).toFixed(2)} мл/час`;

                const mpa = parseFloat(body.querySelector('#c-mpa').value) || 0, fl = parseFloat(body.querySelector('#c-fl').value) || 6;
                const totL = mpa * 10 * cylVol, useL = Math.max(0, mpa - 1) * 10 * cylVol;
                body.querySelector('#c-o2-out').innerText = `🛢️ Баллон ${cylVol} л при ${mpa} МПа (${Math.round(mpa * 10)} атм):\n• Полный объем O2: ${Math.round(totL)} л (полезный до 1 МПа: ${Math.round(useL)} л)\n• При потоке ${fl} л/мин хватит на: ${fl > 0 ? fmtT(totL / fl) : '—'} (до 1 МПа — ${fl > 0 ? fmtT(useL / fl) : '—'})\n• Шпаргалка: 4 л/мин → ${fmtT(totL / 4)} | 10 л/мин → ${fmtT(totL / 10)} | 15 л/мин → ${fmtT(totL / 15)}`;
            };
            const cylRow = body.querySelector('#c-cyls');
            const drawCyls = () => {
                cylRow.innerHTML = '';
                [[1, '🛢️ Баллон 1 л'], [2, '🛢️ Баллон 2 л'], [10, '🛢️ Баллон 10 л']].forEach(([v, t]) => {
                    const sel = cylVol === v;
                    const b = document.createElement('button');
                    b.style.cssText = `flex:1;padding:10px;border:none;border-radius:10px;font-size:12px;cursor:pointer;font-weight:${sel ? '700' : '500'};background:${sel ? '#0277BD' : '#E1F5FE'};color:${sel ? '#fff' : '#01579B'};`;
                    b.innerText = t; b.onclick = () => { cylVol = v; drawCyls(); calcAll(); };
                    cylRow.appendChild(b);
                });
            };
            const gBox = body.querySelector('#c-guides');
            sheets.forEach(([t, sub, cont]) => {
                const c = document.createElement('div');
                c.style.cssText = 'padding:13px 15px;border-radius:13px;background:#FFF3E0;border:1px solid #FFE0B2;margin-bottom:8px;cursor:pointer;';
                c.innerHTML = `<b style="font-size:14px;color:#263238;">${escapeHtml(t)}</b><br><span style="font-size:12px;color:#607D8B;">${escapeHtml(sub)}</span>`;
                c.onclick = () => showDetailDialog(t, cont, '#EF6C00');
                gBox.appendChild(c);
            });
            body.querySelectorAll('input').forEach(i => i.oninput = calcAll);
            drawCyls(); calcAll();
        });
    }

    // --- 8.8 АКУШЕРСТВО И ИНТЕРАКТИВНАЯ ШКАЛА АПГАР ---
    function openObstetricsIos() {
        const guides = [
            ['🤰 Акушерское пособие в родах (головное предлежание)', 'Последовательность действий при приеме родов',
                '1. Сгибание головки плода: задерживают преждевременное разгибание головки, пока она не подойдет подзатылочной ямкой под лонное сочленение.\n2. Выведение теменных бугров и защита промежности: вне потуги бережно сдвигают ткани вульварного кольца с головки, правой рукой поддерживают промежность.\n3. Разгибание головки: вне схватки головке дают медленно разогнуться — рождаются лоб, личико и подбородок. Проверяют отсутствие обвития пуповины.\n4. Внутренний поворот плечиков и наружный поворот головки (личиком к правому бедру при I позиции или к левому при II позиции).\n5. Освобождение плечевого пояса: головку отводят книзу до рождения переднего плечика, затем приподнимают кверху — рождается заднее плечико.\n6. Первичный туалет новорожденного: обсушивание теплой пеленкой, выкладывание на живот матери, оценка по Апгар на 1-й и 5-й минуте.'],
            ['🔄 Механизм родов при тазовых предлежаниях', 'Биомеханизм и особенности ведения',
                '• 1 момент: Внутренний поворот ягодиц (первой врезывается передняя ягодица).\n• 2 момент: Боковое сгибание поясничной части позвоночника — рождается задняя ягодица и туловище до нижнего угла лопаток.\n• 3 момент: Внутренний поворот плечиков и освобождение плечевого пояса.\n• 4 момент: Внутренний поворот и сгибание последующей головки.\n⚠️ Главное правило на СМП: не тянуть плод за тазовый конец или ножки (иначе запрокинутся ручки и разогнется головка)!'],
            ['🤲 Пособие по Цовьянову I (чисто ягодичное предлежание)', 'Сохранение нормального членорасположения плода',
                '• Цель: удержать ножки плода вытянутыми вдоль туловища, чтобы они прижимали скрещенные ручки и поддерживали сгибание головки.\n• Техника: большие пальцы прижимают вытянутые ножки плода к животу и груди, остальные 4 пальца лежат на крестце плода. По мере рождения туловища руки перехватывают ближе к половой щели, направляя туловище книзу до рождения переднего плечика, затем кверху для рождения заднего плечика и согнутой головки.'],
            ['🦶 Пособие по Цовьянову II (ножные предлежания)', 'Превращение ножного предлежания в смешанное',
                '• Цель: не дать ножкам родиться преждевременно при неполном раскрытии шейки матки, чтобы не ущемилась последующая головка.\n• Техника: вульварное кольцо накрывают стерильной салфеткой и ладонью оказывают противодействие показывающимся стопам плода во время потуг, пока плод не «присядет на корточки» и ягодицы не опустятся на тазовое дно при полном раскрытии.'],
            ['👶 Признаки недоношенного ребенка', 'Критерии и внешние признаки',
                '• Критерии: срок < 37 недель, масса тела < 2500 г, длина < 45 см.\n• Признаки: большая голова (1/3 длины тела), пупочное кольцо смещено к лобку, кожа тонкая ярко-розовая, обильное лануго, мягкие ушные раковины, открыт малый родничок, ногти не доходят до кончиков пальцев, яички не опущены в мошонку / половая щель зияет, мышечная гипотония, быстрое охлаждение.']
        ];
        const apgarScores = [2, 2, 2, 2, 2];
        const criteria = [
            ['A — Окраска кожи:', [[0, 'Бледная / цианоз'], [1, 'Акроцианоз'], [2, 'Розовая вся']]],
            ['P — Пульс (ЧСС):', [[0, 'Отсутствует'], [1, '< 100 в мин'], [2, '>= 100 в мин']]],
            ['G — Рефлексы (гримаса):', [[0, 'Нет реакции'], [1, 'Слабая гримаса'], [2, 'Крик / кашель']]],
            ['A — Мышечный тонус:', [[0, 'Атония (вялый)'], [1, 'Снижен'], [2, 'Активные движения']]],
            ['R — Дыхание и крик:', [[0, 'Отсутствует'], [1, 'Слабый крик'], [2, 'Громкий крик']]]
        ];
        openIosSheet('🤰 Акушерство и Шкала Апгар', '#D81B60', (body) => {
            body.innerHTML = `
                <div style="font-size:14.5px;font-weight:700;color:#AD1457;margin-bottom:8px;">👶 Интерактивный калькулятор Шкалы Апгар (0–10 баллов):</div>
                <div id="apg-ban" style="padding:14px;border-radius:14px;background:#D81B60;color:#fff;font-weight:700;font-size:14px;text-align:center;margin-bottom:10px;cursor:pointer;"></div>
                <div id="apg-rows"></div>
                <div style="font-size:14.5px;font-weight:700;color:#AD1457;margin:12px 0 8px 0;">📖 Акушерские пособия и справочник:</div>
                <div id="apg-guides"></div>
            `;
            const ban = body.querySelector('#apg-ban'), rowsEl = body.querySelector('#apg-rows'), gEl = body.querySelector('#apg-guides');
            const upd = () => {
                const tot = apgarScores.reduce((a, b) => a + b, 0);
                const st = tot >= 8 ? 'Удовлетворительное состояние (норма)' : tot >= 6 ? 'Легкая асфиксия (санация ВДП, согревание, O2)' : tot >= 4 ? 'Средняя асфиксия (ИВЛ мешком Амбу, санация)' : 'Тяжелая асфиксия (немедленная реанимация новорожденного!)';
                ban.innerText = `Оценка по Апгар: ${tot} из 10 б.\n${st}\n📋 Нажмите, чтобы скопировать`;
                ban.onclick = () => copyText(`Оценка по шкале Апгар: ${tot} б. (${st})`);
            };
            criteria.forEach(([title, opts], cIdx) => {
                const h = document.createElement('div'); h.style.cssText = 'font-size:12.5px;font-weight:700;color:#880E4F;margin:6px 0 4px 0;'; h.innerText = title;
                const row = document.createElement('div'); row.style.cssText = 'display:flex;gap:6px;margin-bottom:6px;';
                const draw = () => {
                    row.innerHTML = '';
                    opts.forEach(([pts, lbl]) => {
                        const on = apgarScores[cIdx] === pts;
                        const b = document.createElement('button');
                        b.style.cssText = `flex:1;padding:9px 6px;border:none;border-radius:10px;font-size:11.5px;cursor:pointer;font-weight:${on ? '700' : '400'};background:${on ? '#D81B60' : '#FCE4EC'};color:${on ? '#fff' : '#263238'};`;
                        b.innerText = `${pts} — ${lbl}`;
                        b.onclick = () => { apgarScores[cIdx] = pts; draw(); upd(); };
                        row.appendChild(b);
                    });
                };
                draw(); rowsEl.appendChild(h); rowsEl.appendChild(row);
            });
            guides.forEach(([t, sub, cont]) => {
                const c = document.createElement('div');
                c.style.cssText = 'padding:13px 15px;border-radius:13px;background:#FCE4EC;border:1px solid #F8BBD0;margin-bottom:8px;cursor:pointer;';
                c.innerHTML = `<b style="font-size:14px;color:#263238;">${escapeHtml(t)}</b><br><span style="font-size:12px;color:#607D8B;">${escapeHtml(sub)}</span>`;
                c.onclick = () => showDetailDialog(t, cont, '#D81B60');
                gEl.appendChild(c);
            });
            upd();
        });
    }

    // --- 8.9 ЯДЫ, АНТИДОТЫ И ТОКСИКОЛОГИЯ (71 ПОЗИЦИЯ + КЛИНИКА + ЗАПАХИ) ---
    function openAntidotesIos() {
        const antidotes = [
            ['Опиоиды (морфин, героин, кодеин, промедол, омнопон, опий, фентанил, метадон, бупренорфин)', 'Налоксон 0,4–2 мг в/в, в/м, п/к или интраназально, повтор каждые 2–3 мин до восстановления дыхания; при угнетении дыхания — ИВЛ. Налоксон короткодействующий: при необходимости — продлённая инфузия (≈2/3 начальной дозы в час). При бупренорфине нужны более высокие дозы.'],
            ['Трамадол', 'Налоксон 0,4–2 мг в/в (частично эффективен); при судорогах — Диазепам 0,3 мг/кг в/в; поддержка дыхания и гемодинамики.'],
            ['Бензодиазепины (диазепам, сибазон, седуксен, мидазолам, феназепам, тазепам, хлозепид, элениум, мезапам, радедорм, нитразепам, реладорм)', 'Флумазенил (Анексат) 0,2 мг в/в, повтор до 1–2 мг. ⚠️ Противопоказан при смешанном отравлении ТЦА/карбамазепином, при хроническом приёме бензодиазепинов и эпилепсии (риск судорог). При угнетении дыхания — ИВЛ.'],
            ['Барбитураты (фенобарбитал)', 'Специфического антидота нет. Натрия гидрокарбонат 4% в/в (ощелачивание мочи ускоряет выведение), ИВЛ; при глубокой коме — гемодиализ/гемосорбция. ⚠️ Бемегрид не применять (устарел, провоцирует судороги).'],
            ['Гексенал, тиопентал (тиобарбитураты)', 'Как при барбитуратах: ИВЛ, инфузия, поддержка гемодинамики; при тяжёлом течении — гемодиализ.'],
            ['Клонидин (клофелин)', 'Налоксон 0,4–2 мг в/в (при необходимости повторно), Атропин 0,1% при брадикардии, инфузия NaCl 0,9%, Допамин/Мезатон при гипотонии; при судорогах — Диазепам.'],
            ['Трициклические антидепрессанты (амитриптилин, имипрамин)', 'Натрия гидрокарбонат 4% в/в болюсно 1–2 ммоль/кг (цель — сужение QRS и pH 7,45–7,55), при судорогах — Диазепам, при гипотонии — Норадреналин. ⚠️ Флумазенил противопоказан. При рефрактерности — липидная эмульсия 20% 1,5 мл/кг.'],
            ['Нейролептики (галоперидол, рисперидон, оланзапин, аминазин, прометазин)', 'Специфического антидота нет. При экстрапирамидном синдроме — Тригексифенидил (циклодол)/Бипериден (акинетон), при возбуждении — Диазепам; контроль QT (при удлинении — Магния сульфат).'],
            ['М-холиноблокаторы (атропин, белладонна, красавка, дурман, белена, амизил, циклодол, скополамин)', 'Физостигмина салицилат 1–2 мг в/в медленно (или Галантамин/Аминостигмин). ⚠️ Осторожно при расширении QRS и аритмии; при возбуждении/судорогах — Диазепам.'],
            ['Хлорохин, гидроксихлорохин (делагил, хингамин)', 'Диазепам 2 мг/кг в/в (при судорогах), Эпинефрин, Натрия гидрокарбонат, инфузия; при тяжёлой интоксикации — липидная эмульсия 20%.'],
            ['Хинин', 'Натрия гидрокарбонат, инфузия, Диазепам при судорогах, поддержка гемодинамики.'],
            ['Никотин (табак, инсектициды-неоникотиноиды)', 'Специфического антидота нет. Атропин при брадикардии/холинергическом синдроме, Диазепам при судорогах, инфузия, O2.'],
            ['Парацетамол', 'Ацетилцистеин (АЦЦ, Флуимуцил) внутрь или в/в по схеме (150/50/100 мг/кг), при фульминантной печёночной недостаточности — трансплантация.'],
            ['Аспирин (салицилаты)', 'Натрия гидрокарбонат 4% в/в (ощелачивание мочи + коррекция ацидоза), инфузия глюкозы, при тяжёлом отравлении — гемодиализ. ⚠️ Витамин К не показан.'],
            ['Метамизол (анальгин), НПВС (ибупрофен, диклофенак, нимесулид)', 'Специфического антидота нет: промывание/энтеросорбция, инфузия, Натрия гидрокарбонат; при судорогах — Диазепам.'],
            ['Сульфаниламиды', 'Обильное щелочное питьё / Натрия гидрокарбонат 4% в/в, инфузия; при метгемоглобинемии — Метиленовый синий 1%; при ОПН — гемодиализ.'],
            ['Бета-адреноблокаторы (анаприлин, метопролол, бисопролол, атенолол)', 'Глюкагон 3–10 мг в/в болюсно, далее инфузия 3–5 мг/ч; Атропин 0,5–1 мг; Кальция хлорид/глюконат; Норадреналин/Допамин. При рефрактерности — высокодозная инсулинотерапия (1 МЕ/кг/ч с глюкозой) и липидная эмульсия 20%.'],
            ['Блокаторы кальциевых каналов (верапамил, нифедипин, амлодипин)', 'Кальция хлорид 10% 10–20 мл (или глюконат 10% 20 мл) в/в, Глюкагон, высокодозная инсулинотерапия, Норадреналин; Атропин при брадикардии; при рефрактерности — липидная эмульсия 20%.'],
            ['Сердечные гликозиды (дигоксин, коргликон, дигиталис, строфантин)', 'Дигоксин-специфические Fab-антитела (DigiFab) — при наличии. Иначе поддержка: Калия хлорид/Магния сульфат (⚠️ при гиперкалиемии калий не вводить!), Атропин при брадикардии, Лидокаин/Фенитоин при ЖТ, при гиперкалиемии — Кальция хлорид. Унитиол и Дигибайнд не применять.'],
            ['Адреналин, орципреналин (алупент), эфедрин', 'Фентоламин 5–10 мг в/в, бета-блокаторы (Метопролол), нитраты; при аритмии — Лидокаин.'],
            ['Хлорид калия, гиперкалиемия (в т.ч. ОПН, массивный гемолиз)', 'Кальция глюконат/хлорид 10% 10–20 мл в/в, Глюкоза 40% + Инсулин, Натрия гидрокарбонат 4%, β2-агонист (сальбутамол через небулайзер); при тяжёлой — гемодиализ.'],
            ['Теофиллин, эуфиллин (аминофиллин)', 'Специфического антидота нет: повторная энтеросорбция, при судорогах — Диазепам, при тахиаритмии — короткодействующий бета-блокатор (эсмолол/метопролол), контроль калия; при тяжёлом — гемодиализ/гемоперфузия.'],
            ['Симпатомиметики (кокаин, амфетамины, метамфетамин, кофеин)', 'Диазепам (возбуждение, судороги, гипертензия), нитраты + Фентоламин, физическое охлаждение, инфузия; ⚠️ бета-блокаторы при кокаине — с осторожностью (вазоспазм).'],
            ['Диуретики (фуросемид, торасемид)', 'Специфического антидота нет: восполнение ОЦК и электролитов (K⁺, Mg²⁺), инфузия NaCl 0,9%.'],
            ['Метгемоглобинобразователи (нитраты, нитриты, анилин, нитробензол, нитропентон, нитросорбид, эринит, дапсон, бензокаин, «селитра»)', 'Метиленовый синий 1% 1–2 мг/кг (0,1–0,2 мл/кг) в/в, повтор; Аскорбиновая кислота 5%; O2. ⚠️ При дефиците Г6ФД метиленовый синий противопоказан — аскорбиновая кислота/заменное переливание.'],
            ['Анилин, красители, чернила', 'Метиленовый синий 1% 1–2 мг/кг в/в, Аскорбиновая кислота 5%, O2. ⚠️ Не давать молоко, растительные масла, жиры, спирт (усиливают всасывание).'],
            ['Цианиды (синильная кислота, соли цианидов, продукты горения)', 'Гидроксокобаламин (Cyanokit) 5 г в/в за 15 мин — препарат выбора (при наличии). При отсутствии: Натрия тиосульфат 30% 50 мл в/в + Натрия нитрит 3% 10 мл в/в (или Амилнитрит ингаляционно); 100% O2.'],
            ['Окись углерода (угарный газ, CO)', '100% кислород (10–15 л/мин, ИВЛ), гипербарическая оксигенация; при отравлении продуктами горения — исключить цианиды.'],
            ['Сероводород, сернистый ангидрид (двуокись серы)', '100% O2, Натрия гидрокарбонат, бронхолитики, ГКС; обильное промывание глаз и кожи.'],
            ['Метанол (метиловый спирт, суррогаты)', 'Фомепизол 15 мг/кг в/в нагрузочно, далее 10 мг/кг каждые 12 ч (предпочтительно). При отсутствии — Этанол 30% внутрь или 10% в/в (целевая концентрация этанола 1–1,5 г/л). Фолиновая кислота 50 мг каждые 6 ч, Тиамин 100 мг в/в, Натрия гидрокарбонат 4% в/в; гемодиализ.'],
            ['Этиленгликоль (антифриз, тормозная жидкость)', 'Фомепизол (или Этанол по схеме), Тиамин 100 мг + Пиридоксин 100 мг в/в, Кальция глюконат 10%, Натрия гидрокарбонат 4%; гемодиализ.'],
            ['Этанол (алкоголь, передозировка)', 'Специфического антидота нет: Глюкоза 40% в/в + Тиамин 100 мг в/в (⚠️ не вводить глюкозу без тиамина), инфузия; при тяжёлом отравлении — гемодиализ.'],
            ['Изопропанол', 'Специфического антидота нет: инфузия, коррекция гипогликемии; при глубокой коме — гемодиализ.'],
            ['Хлорорганические растворители (дихлорэтан, тетрахлорметан, четырёххлористый углерод)', 'N-ацетилцистеин (АЦЦ), ранняя энтеросорбция, инфузия, гепатопротекторы. ⚠️ При дихлорэтане адреналин и жиры не вводить (сенсибилизация миокарда).'],
            ['Фосфорорганические соединения (дихлофос, карбофос, хлорофос, метафос, инсектициды)', 'Атропин 0,1% 2–4 мл в/в каждые 5–10 мин до атропинизации (сухость слизистых, ЧСС 90–100) — дозы могут быть очень высокими + оксим: Пралидоксим 30 мг/кг в/в, далее инфузия; Дипироксим/Изонитрозин. При судорогах — Диазепам.'],
            ['Карбаматы (карбарил, пропоксур)', 'Атропин до атропинизации; оксимы малоэффективны; при судорогах — Диазепам.'],
            ['Динитрокрезол (ДНОК, нитрофенолы)', 'Специфического антидота нет: физическое охлаждение, инфузия, 100% O2, симптоматическая терапия. ⚠️ Холинэстеразные реактиваторы (дипироксим/изонитрозин) не показаны — это не ФОС.'],
            ['Крысид (АНТУ, альфа-нафтилтиомочевина)', 'Специфического антидота нет: Натрия гидрокарбонат, N-ацетилцистеин, O2, инфузия; при отёке лёгких — ГКС.'],
            ['Цинка фосфид (цинк фосфористый)', 'Специфического антидота нет: промывание, вазелиновое масло, инфузия, поддержка. ⚠️ Противопоказаны жиры, молоко, яйца (выделение фосфина).'],
            ['Паракват, дикват', 'Специфического антидота нет: ранняя энтеросорбция (Fuller\'s earth/уголь), инфузия, поддержка; ⚠️ кислород применять с осторожностью (усиливает повреждение лёгких).'],
            ['Мышьяк, ртуть, висмут, сурьма, медь (медный купорос)', 'DMPS (Унитиол) 5% 5–10 мл в/м или в/в, DMSA (сукцимер) внутрь, Тетацин-кальций (CaNa₂ЭДТА); при отравлении мышьяком — также BAL (димеркапрол). Натрия тиосульфат.'],
            ['Свинец', 'DMSA (сукцимер) внутрь, Тетацин-кальций (CaNa₂ЭДТА); при тяжёлом — BAL (димеркапрол).'],
            ['Таллий, цезий', 'Берлинская лазурь (Ферроцин) 250 мг/кг внутрь в сутки (или 1–3 г каждые 6 ч) + слабительное; Унитиол; при тяжёлом — гемодиализ/гемоперфузия.'],
            ['Железо (препараты железа, Феррум Лек)', 'Дефероксамин (Десферал) 15 мг/кг/ч в/в (или 1–2 г в/м); инфузия, при тяжёлом — гемодиализ.'],
            ['Мышьяковистый водород (арсин)', 'Специфического антидота нет: массивный внутрисосудистый гемолиз — инфузия, обменное переливание крови, гемодиализ, поддержка.'],
            ['Бледная поганка (аматоксины, фаллоидин)', 'Силибинин (Легалон) в/в, Бензилпенициллин в высоких дозах, N-ацетилцистеин, энтеросорбция (в первые часы), инфузия, ГКС; при ОПН — гемодиализ. Контрикал не применять.'],
            ['Мухомор (мускарин, гиромитрин)', 'Атропин 0,1% 1–2 мл в/в (при бронхорее/брадикардии); при гиромитриновом синдроме — Пиридоксин; при возбуждении — Диазепам; инфузия.'],
            ['Псилоцибин, галлюциногенные грибы', 'Специфического антидота нет: бензодиазепины (Диазепам) при возбуждении, инфузия, безопасная среда.'],
            ['Ракитник (цитизин, анабазин)', 'Специфического антидота нет: противосудорожные (Диазепам), энтеросорбция, O2, инфузия (картина как при никотине).'],
            ['Клещевина (рицин)', 'Специфического антидота нет: ранняя энтеросорбция, инфузия, поддержка.'],
            ['Вех ядовитый (цикутотоксин), болиголов', 'Специфического антидота нет: противосудорожные (Диазепам), ИВЛ, инфузия, поддержка.'],
            ['Кислоты (уксусная эссенция, органические и неорганические кислоты)', '⚠️ Не вызывать рвоту, соду внутрь не давать (CO₂ → перфорация). Обезболивание (Морфин), промывание через зонд холодной водой только в первые минуты, Натрия гидрокарбонат 4% в/в лишь при ацидозе/гемолизе; при эссенции — контроль гемолиза и ОПН.'],
            ['Щёлочи (едкий натр, нашатырь, известь)', '⚠️ Не промывать желудок. Обезболивание, вода/молоко малыми порциями внутрь при сохранённом сознании, инфузия; эндоскопия.'],
            ['Калия перманганат (кристаллы, концентрированный раствор)', 'При кристаллах — ⚠️ не промывать желудок (эндоскопия). Аскорбиновая кислота, Метиленовый синий (при системном действии марганца), обезболивание, инфузия.'],
            ['Фенол (карболовая кислота)', 'Обезболивание, энтеросорбция (уголь), инфузия, ГКС, поддержка печени (Токоферол). ⚠️ Растительные масла, глицерин, спирт внутрь не давать (усиливают всасывание) — промывание полиэтиленгликолем.'],
            ['Формалин (формальдегид)', 'Аммония хлорид/карбонат (1–3% раствор) внутрь, промывание, инфузия, Натрия гидрокарбонат (коррекция ацидоза).'],
            ['Йод, настойка йода', 'Промывание желудка раствором крахмала, Натрия тиосульфат 30% 10–20 мл в/в и 5% внутрь; инфузия.'],
            ['Ляпис (нитрат серебра), соли серебра', 'Промывание желудка 1–2% раствором натрия хлорида (образование нерастворимого AgCl), поддержка.'],
            ['Фториды (фтора соли, криолит)', 'Кальция глюконат/хлорид 10% 10–20 мл в/в + промывание желудка раствором кальция; при гипокальциемии — повтор. ⚠️ Магния сульфат противопоказан.'],
            ['Гидразин (диамид, ракетное топливо)', 'Специфического антидота нет: Пиридоксин (В6) при судорогах, инфузия, гемодиализ, поддержка печени.'],
            ['Бария соли (растворимые)', 'Натрия сульфат или Магния сульфат 20–30 г внутрь (связывание в нерастворимый сульфат бария), Натрия тиосульфат в/в; инфузия.'],
            ['Бром и бромиды', 'Натрия хлорид 0,9% в/в + Фуросемид; при тяжёлом — гемодиализ.'],
            ['Изониазид, противотуберкулёзные (ГИНК, фтивазид)', 'Пиридоксин (В6) 5%: 1 г на каждый 1 г изониазида (или 70 мг/кг, макс. 5 г) в/в; при судорогах — Диазепам; инфузия.'],
            ['Метформин (лактатацидоз)', 'Натрия гидрокарбонат в/в при pH < 7,0, инфузия, гемодиализ; отмена метформина.'],
            ['Сульфонилурея (глибенкламид, глимепирид, гликлазид)', 'Декстроза 40% в/в + продлённая инфузия глюкозы 10%; при рецидивирующей гипогликемии — Октреотид 50–100 мкг п/к каждые 6–8 ч; контроль гликемии.'],
            ['Литий', 'Специфического антидота нет: инфузия NaCl 0,9%; при концентрации > 2,5 ммоль/л или тяжёлых симптомах — гемодиализ.'],
            ['Местные анестетики (лидокаин, бупивакаин, новокаин)', 'Липидная эмульсия 20% 1,5 мл/кг болюсно + инфузия 0,25 мл/кг/мин, ИВЛ, поддержка гемодинамики, при судорогах — Диазепам.'],
            ['Сульфат цинка', 'Натрия гидрокарбонат, Унитиол, Тетацин-кальций; инфузия.'],
            ['Нафтизин, ксилометазолин (имидазолины)', 'Специфического антидота нет: Атропин при брадикардии, Налоксон при угнетении ЦНС (частично), инфузия, согревание; при гипотонии — Допамин.'],
            ['Змеиный яд (укус гадюки)', 'Противозмеиная сыворотка (Антигюрза/моновалентная) при системных проявлениях, Преднизолон 60–90 мг в/в (адъювантно), антигистаминные, иммобилизация, инфузия, обезболивание. ⚠️ Жгут и гепарин не применять.'],
            ['Антибиотики', 'Специфического антидота нет. При аллергии/анафилаксии — Эпинефрин (адреналин) в/м, Преднизолон, антигистаминные.']
        ];
        const syndromesTxt =
            '📌 КЛИНИЧЕСКИЕ СИМПТОМЫ ОТРАВЛЕНИЙ (17.html–18.html):\n\n' +
            '• Атропин (красавка, белена, дурман):\n  Мидриаз (широкие зрачки без фотореакции), сухость и гиперемия кожи и слизистых, тахикардия, гипертермия, возбуждение, галлюцинации, судороги.\n\n' +
            '• ФОС (дихлофос, карбофос, хлорофос):\n  Точечный миоз, бледность, потливость, гиперсаливация, бронхорея, бронхоспазм, боли в животе, рвота, брадикардия, гипотония, фибрилляции мышц, судороги.\n\n' +
            '• Селитра, нитраты, анилин:\n  Разлитой серо-синюшный цианоз, не уменьшающийся от 100% кислорода («шоколадная» кровь), одышка, тахикардия.\n\n' +
            '• Угарный газ (CO):\n  Головная боль («обруч»), головокружение, рвота, ярко-розовая окраска кожи (затем бледность), судороги, кома, отек легких.\n\n' +
            '• Бледная поганка (фаллоидин):\n  Латентный период 6–24 ч! Неукротимая рвота, боли в животе, холероподобный понос, печеночно-почечная недостаточность, коллапс, кома.\n\n' +
            '• Мухомор (мускарин):\n  Латентный период 1–2 ч. Слюнотечение, рвота, понос, миоз, бронхорея, брадикардия, галлюцинации, бред.\n\n' +
            '• Действие ядов на ЖКТ:\n  — Боли в эпигастрии: салицилаты, кислоты/щелочи, железо, ртуть, мышьяк, грибы.\n  — Слюнотечение: ФОС, мускарин, никотин.\n  — Сухость слизистых и парез кишечника: атропин, димедрол, ТЦА, опиаты.';
        const signsTxt =
            '🎨 ЦВЕТ РВОТНЫХ МАСС (19.html):\n• Фиолетовый/розовый — калия перманганат, соли кобальта\n• Голубой/зеленоватый — соли меди (медный купорос)\n• Желтый — пикриновая и азотная кислота\n• Черный / «кофейная гуща» — крепкие кислоты, препараты железа, салицилаты\n\n' +
            '👃 СПЕЦИФИЧЕСКИЕ ЗАПАХИ ИЗО РТА:\n• Горького миндаля — цианиды, синильная кислота, нитробензол\n• Чеснока — ФОС, мышьяк, фосфор, таллий\n• Тухлых яиц — сероводород, меркаптаны, тетурам\n• Ацетона (сладкий фруктовый) — изопропанол, хлороформ, кетоацидоз\n• Дезинфектантов (гуаши) — фенол, креозот\n• Моркови — цикута (вех ядовитый)\n\n' +
            '👅 «МЕТАЛЛИЧЕСКИЙ» ПРИВКУС ВО РТУ:\n• Свинец, медь, мышьяк, соли железа, ртуть, йод.\n\n' +
            '👁️ ЗРАЧКИ И ЗРЕНИЕ:\n• Точечный миоз: опиоиды, ФОС, мухомор, клофелин, барбитураты.\n• Широкий мидриаз: атропин, белена, дурман, димедрол, амитриптилин, метанол (слепота).\n• Предметы в желто-зеленом цвете (ксантопсия): сердечные гликозиды.';

        let tab = 0;
        openIosSheet(`🧪 Токсикология: Яды и Антидоты (${antidotes.length})`, '#C62828', (body) => {
            body.innerHTML = `<div id="tx-tabs" style="display:flex;gap:8px;margin-bottom:10px;"></div><div id="tx-body"></div>`;
            const tRow = body.querySelector('#tx-tabs'), tBody = body.querySelector('#tx-body');
            function drawTabs() {
                tRow.innerHTML = '';
                [`☠️ Антидоты (${antidotes.length})`, '🤢 Клиника', '👃 Запахи и Рвота'].forEach((lbl, i) => {
                    const sel = i === tab;
                    const b = document.createElement('button');
                    b.style.cssText = `flex:1;padding:10px 6px;border:none;border-radius:11px;font-size:12px;cursor:pointer;font-weight:${sel ? '700' : '400'};background:${sel ? '#C62828' : '#FFEBEE'};color:${sel ? '#fff' : '#B71C1C'};`;
                    b.innerText = lbl; b.onclick = () => { tab = i; drawTabs(); render(); };
                    tRow.appendChild(b);
                });
            }
            function render() {
                if (tab === 0) {
                    tBody.innerHTML = `<input id="tx-inp" type="text" placeholder="Поиск из ${antidotes.length} ядов (фос, сибазон, крысид, уксус)..." style="width:100%;box-sizing:border-box;padding:11px 13px;border-radius:12px;border:1.5px solid #EF9A9A;background:#FFEBEE;color:#111;font-size:14px;margin-bottom:10px;outline:none;"><div id="tx-list" style="display:flex;flex-direction:column;gap:8px;"></div>`;
                    const inp = tBody.querySelector('#tx-inp'), list = tBody.querySelector('#tx-list');
                    const upd = () => {
                        const q = inp.value.trim().toLowerCase();
                        list.innerHTML = '';
                        antidotes.filter(([p, a]) => !q || p.toLowerCase().includes(q) || a.toLowerCase().includes(q)).forEach(([p, a]) => {
                            const d = document.createElement('div');
                            d.style.cssText = 'padding:12px 14px;border-radius:13px;background:#FFF8F8;border:1px solid #FFCDD2;color:#263238;font-size:13.3px;line-height:1.45;cursor:pointer;';
                            d.innerHTML = `<b>☠️ ${escapeHtml(p)}</b><br><span style="color:#455A64;">💉 Антидот: ${escapeHtml(a)}</span>`;
                            d.onclick = () => showDetailDialog(`☠️ ${p}`, `💉 СПЕЦИФИЧЕСКИЙ АНТИДОТ И ПОМОЩЬ:\n\n${a}`, '#C62828');
                            list.appendChild(d);
                        });
                    };
                    inp.oninput = upd; upd();
                } else {
                    tBody.innerHTML = `<div style="padding:14px;border-radius:14px;background:#FFF8F8;border:1px solid #FFCDD2;font-size:13.8px;line-height:1.52;color:#263238;white-space:pre-line;user-select:text;">${escapeHtml(tab === 1 ? syndromesTxt : signsTxt)}</div>`;
                }
            }
            drawTabs(); render();
        });
    }
// --- 8.10 ЛЕКАРСТВА СМП (20) И ОФОРМЛЕНИЕ КАРТЫ (8) ---
    function openDrugsAndGuidesIos() {
        const drugs = [
            ['💉 Адреналин (Эпинефрин / Epinephrine) 0,18% — 1 мл', 'Альфа- и бета-адреномиметик',
                '• Показания: СЛР (асистолия, ФЖ/ЖТ без пульса, ЭМД), анафилактический шок, купирование тяжелого бронхоспазма/отека гортани.\n• Дозирование:\n  — СЛР взрослым: по 1 мг (1 мл) в/в каждые 3–5 мин. Детям (Пост. № 118): 0,01 мг/кг (0,1 мл/кг в разведении 1:10).\n  — Анафилактический шок: в/м в переднебоковую поверхность бедра взрослым 0,3–0,5 мл; детям 0,01 мл/кг (макс. 0,3 мл), повтор через 5–15 мин. При рефрактерном шоке — в/в медленно 0,1–0,25 мг в 10–20 мл NaCl 0,9%.\n• Передозировка / Антагонист: Фентоламин 5–10 мг в/в, бета-блокаторы, нитраты.'],
            ['💉 Норадреналин (Норэпинефрин / Norepinephrine) 0,2%', 'Альфа1,2-адреномиметик, вазопрессор',
                '• Показания: острая артериальная гипотензия, кардиогенный, септический, анафилактический шок (после восполнения ОЦК).\n• Дозирование: строго в/в капельно или через дозатор! 2–4 мг (1–2 мл 0,2% р-ра) разводят в 250–500 мл 5% декстрозы или NaCl 0,9%, вводят под контролем АД (0,05–0,5 мкг/кг/мин).\n• Особые указания: при попадании под кожу вызывает некроз (обколоть фентоламином 5–10 мг в 10 мл NaCl).'],
            ['💉 Допамин (Дофамин / Dopamine) 0,5% или 4% — 5 мл', 'Кардиотоническое и вазопрессорное средство',
                '• Показания: кардиогенный, гиповолемический (после инфузии), инфекционно-токсический, анафилактический шок, острая СН.\n• Дозозависимый эффект:\n  — Низкие дозы (1–3 мкг/кг/мин): дофаминовые рецепторы (улучшение почечного кровотока и диуреза);\n  — Средние дозы (4–10 мкг/кг/мин): бета1-рецепторы (положительный инотропный эффект, рост сердечного выброса и САД);\n  — Высокие дозы (>10–20 мкг/кг/мин): альфа1-рецепторы (вазоконстрикция, рост ОПСС и АД).\n• Разведение: 200 мг (1 амп. 4% 5 мл) на 200 мл (1 капля = 50 мкг) или 400 мл NaCl 0,9% / 5% глюкозы (1 капля = 25 мкг).\n• Несовместим со щелочными растворами (натрия гидрокарбонатом)!'],
            ['💉 Мезатон (Фенилэфрин / Phenylephrine) 1% — 1 мл', 'Альфа1-адреномиметик',
                '• Показания: острая артериальная гипотензия, коллапс, медикаментозная гипотония, проведение фармакологических проб/разведение с новокаинамидом.\n• Дозирование: в/в медленно струйно 0,1–0,5 мл 1% р-ра в 10–20 мл NaCl 0,9% или 5% декстрозы; в/в капельно 1 мл в 250–500 мл; в/м или п/к 0,3–1 мл.'],
            ['💉 Кордарон (Амиодарон / Amiodarone) 5% — 3 мл (150 мг)', 'Антиаритмик III класса',
                '• Показания: СЛР при ФЖ/ЖТ без пульса (резистентной к дефибрилляции); купирование пароксизмов ЖТ, СВТ, пароксизмальной и персистирующей ФП (в т.ч. при WPW).\n• Дозирование:\n  — При СЛР (после 3-го разряда): взрослым 300 мг (2 амп. = 6 мл) в 20 мл 5% декстрозы в/в болюсом; при сохранении ФЖ — еще 150 мг (1 амп.). Детям (Пост. № 118): 5 мг/кг (до 15 мг/кг; <3 лет — off-label).\n  — При пароксизме тахиаритмии: 300 мг (5 мг/кг) разводят ТОЛЬКО в 5% растворе декстрозы (глюкозы) и вводят в/в медленно/капельно за 20–60 мин.\n• Противопоказания (кроме СЛР): СССУ, АВ-блокада II–III ст., брадикардия, кардиогенный шок, удлинение QT, аллергия на йод.'],
            ['💉 Новокаинамид (Прокаинамид / Procainamide) 10% — 5 мл', 'Антиаритмик Ia класса',
                '• Показания: купирование пароксизма фибрилляции предсердий (давностью <48 ч), пароксизмальной наджелудочковой и желудочковой тахикардии.\n• Дозирование: 500–1000 мг (5–10 мл 10% р-ра) разводят в 10–20 мл NaCl 0,9% и вводят в/в ОЧЕНЬ МЕДЛЕННО (не быстрее 50 мг/мин = 1 мл за 2 минуты) под постоянным контролем АД и ЭКГ (для профилактики гипотонии при нормальном/сниженном АД в один шприц добавляют 0,1–0,3 мл 1% Мезатона).\n• При расширении QRS более чем на 25–50% или падении АД введение немедленно прекращают!'],
            ['💉 Лидокаин (Lidocaine) 2% или 10%', 'Антиаритмик Ib класса и местный анестетик',
                '• Показания: желудочковая тахикардия (ЖТ), частая ЖЭС, ФЖ (при отсутствии или неэффективности амиодарона), дигиталисная интоксикация.\n• Дозирование:\n  — Взрослым: в/в струйно нагрузочная доза 1–1,5 мг/кг (обычно 80–100 мг = 4–5 мл 2% р-ра в 10 мл NaCl 0,9%) за 2–3 мин, при необходимости повтор 0,5–0,75 мг/кг через 5–10 мин (суммарно не более 300 мг/ч).\n  — Детям при ФЖ/ЖТ (Пост. № 118, п. 22.2): 1 мг/кг в/в болюсно, далее инфузия 20–50 мкг/кг/мин.'],
            ['💉 Беталок (Метопролол) и Анаприлин (Пропранолол)', 'Бета-адреноблокаторы',
                '• Метопролол (селективный бета1-блокатор): таблетки 25–50 мг внутрь при АГ, тахикардии, ИБС; раствор 1 мг/мл (5 мл) — по 2,5–5 мг в/в медленно (1–2 мг/мин) с интервалом 5 мин до 15 мг при СВТ, тахисистолии ФП и ОКС (при САД > 100 мм рт. ст. и ЧСС > 60).\n• Пропранолол (Анаприлин, неселективный): 10–40 мг внутрь/с/л при синусовой тахикардии, ГК с тахикардией, треморе, тиреотоксикозе (противопоказан при БА и ХОБЛ!).'],
            ['💉 Верапамил (Verapamil) 0,25% — 2 мл (5 мг)', 'Блокатор медленных кальциевых каналов',
                '• Показания: купирование пароксизмальной АВ-узловой наджелудочковой тахикардии (СВТ), урежение ЧЖС при трепетании/фибрилляции предсердий (строго БЕЗ синдрома WPW!).\n• Дозирование: 2–4 мл 0,25% р-ра (5–10 мг) в 10 мл NaCl 0,9% в/в медленно за 2–5 мин под контролем ЭКГ и АД.\n• ⚠️ КАТЕГОРИЧЕСКИ ПРОТИВОПОКАЗАН: при синдроме WPW с ФП, при широком комплексе QRS (ЖТ), при АВ-блокаде II–III ст., СССУ, кардиогенном шоке и одновременно с в/в бета-блокаторами!'],
            ['💊 Нитроглицерин (Nitroglycerin) таб. 0,5 мг / спрей 0,4 мг / 0,1%', 'Периферический вазодилататор (нитрат)',
                '• Показания: купирование ангинозного приступа при стенокардии и ОКС, острая левожелудочковая недостаточность (кардиальная астма, отек легких), осложненный ГК.\n• Дозирование: сублингвально 0,4–0,5 мг (повтор через 5 мин до 3 доз под контролем АД); в/в капельно/дозатором 10–200 мкг/мин под контролем САД >= 90–100 мм рт. ст.\n• Противопоказания: САД < 90 мм рт. ст., инфаркт правого желудочка, прием силденафила/тадалафила за последние 24–48 ч, ЧМТ, геморрагический инсульт.'],
            ['💉 Эбрантил (Урапидил / Urapidil) 5 мг/мл — 5 или 10 мл', 'Альфа1-адреноблокатор центрального и периферического действия',
                '• Показания: гипертонический криз (в т.ч. осложненный ОНМК, отеком легких, энцефалопатией), рефрактерная АГ.\n• Дозирование: 10–25 мг (2–5 мл) разводят в 10–20 мл NaCl 0,9% и вводят в/в медленно в положении лежа под контролем АД. При необходимости через 5 мин повтор до 25–50 мг.'],
            ['💊 Каптоприл, Моксонидин, Коринфар (Нифедипин), Клофелин', 'Антигипертензивные препараты',
                '• Каптоприл (иАПФ): 12,5–25–50 мг внутрь/под язык при неосложненном ГК (противопоказан при отеке Квинке в анамнезе и беременности).\n• Моксонидин (агонист имидазолиновых рецепторов): 0,2–0,4 мг внутрь (макс. 0,6 мг/сут).\n• Нифедипин (Коринфар, БМКК): 10–20 мг внутрь (не применять сублингвально при ОКС и тахикардии!).\n• Клонидин (Клофелин) 0,01% — 1 мл: 0,5–1 мл в 10–20 мл NaCl 0,9% в/в медленно за 5–10 мин строго в положении лежа (противопоказан при брадикардии, АВ-блокаде и алкогольном опьянении).'],
            ['💉 Магния сульфат (Magnesium sulfate) 25% — 5 или 10 мл', 'Вазодилататор, противосудорожное, антиаритмик',
                '• Показания: эклампсия и преэклампсия беременных, полиморфная ЖТ типа «пируэт» (torsades de pointes), тяжелый приступ бронхиальной астмы, гипертонический криз, судорожный синдром.\n• Дозирование: 5–10–20 мл 25% р-ра разводят в 10–20 мл (или 100–200 мл) NaCl 0,9% и вводят в/в медленно (при преэклампсии нагрузочная доза 4 г = 16 мл 25% р-ра за 15–20 мин).\n• Антидот при передозировке (угнетении дыхания): Кальция хлорид или Кальция глюконат 10% 10 мл в/в.'],
            ['💉 Атропин (Atropine sulfate) 0,1% — 1 мл (1 мг)', 'М-холиноблокатор',
                '• Показания: гемодинамически значимая синусовая брадикардия и АВ-блокада, отравление ФОС, карбаматами и мухоморами.\n• Дозирование: при брадикардии — 0,5–1 мл (0,5–1 мг) в/в болюсом каждые 3–5 мин (до общей дозы 3 мг; детям 0,02 мг/кг). При отравлении ФОС — по 2–4 мл в/в каждые 5–10 мин до признаков атропинизации.'],
            ['💉 Эуфиллин (Аминофиллин / Aminophylline) 2,4% — 5 или 10 мл', 'Бронхолитическое средство (метилксантин)',
                '• Показания: бронхообструктивный синдром (БА, ХОБЛ) при недостаточной эффективности ингаляционных бронхолитиков и ГКС.\n• Дозирование: взрослым 5–10 мл 2,4% р-ра (120–240 мг) разводят в 10–20 мл NaCl 0,9% и вводят в/в очень медленно (за 5–10 мин). Противопоказан при ОИМ, тахиаритмиях и эпилепсии!'],
            ['💉 Преднизолон (30 мг/мл) и Дексаметазон (4 мг/мл)', 'Системные глюкокортикостероиды (ГКС)',
                '• Показания: анафилактический и др. виды шока, отек Квинке, крапивница, приступ БА и астматический статус, острый стенозирующий ларинготрахеит (круп), отек мозга.\n• Дозирование:\n  — Преднизолон: взрослым 60–150 мг (2–5 ампул) в/в или в/м; при тяжелом шоке до 300 мг. Детям: 2–5 мг/кг.\n  — Дексаметазон: взрослым 4–16 мг (1–4 ампулы) в/в или в/м. Детям при крупе: 0,3–0,6 мг/кг.'],
            ['💉 Супрастин (Хлоропирамин 2%) и Димедрол (Дифенгидрамин 1%)', 'Блокаторы H1-гистаминовых рецепторов',
                '• Показания: крапивница, ангионевротический отек (Квинке), анафилактические реакции (после введения адреналина и стабилизации гемодинамики), премедикация.\n• Дозирование (⚠️ разные концентрации!):\n  — Супрастин 2% (20 мг/мл): взрослым 1–2 мл (20–40 мг) в/м или в/в. Детям: 0,4–0,6 мг/кг = 0,02–0,03 мл/кг (1–12 мес — 0,25 мл; 1–6 лет — 0,5 мл; 7–14 лет — 0,5–1 мл).\n  — Димедрол 1% (10 мг/мл): взрослым 1–5 мл (10–50 мг) в/м или в/в. Детям: 0,5–1 мг/кг = 0,05–0,1 мл/кг, макс. 2 мл (1–12 мес — 0,3–0,5 мл; 1–3 лет — 0,5–1 мл; 4–6 лет — 1–1,5 мл; 7–14 лет — 1,5–2 мл).'],
            ['💉 Окситоцин (Oxytocin) 5 МЕ/мл — 1 мл', 'Утеротонизирующее средство',
                '• Показания: профилактика и лечение гипотонического маточного кровотечения в послеродовом периоде (строго ПОСЛЕ рождения плода и отделения последа!).\n• Дозирование: 5–10 МЕ (1–2 мл) в/м или в/в медленно/капельно в 250–500 мл NaCl 0,9% / 5% декстрозы.'],
            ['💉 Глюкоза (Декстроза 5%, 40%), Кальция глюконат 10%, Витамин B6', 'Метаболические средства и антидоты',
                '• Декстроза 40%: при гипогликемии — 20–60 мл в/в струйно (детям 10–20% р-р 2–4 мл/кг). При алкоголизме перед введением глюкозы ввести Тиамин (В1) 100 мг в/в (профилактика энцефалопатии Вернике).\n• Кальция глюконат 10% (10 мл): при гиперкалиемии, передозировке БМКК (верапамила) и магния сульфата, гипокальциемических судорогах — взрослым 10–20 мл, детям 0,6–0,8 мл/кг в/в медленно (не вводить в/м детям!).\n• Пиридоксин (Витамин B6) 5%: специфический антидот при отравлении изониазидом (тубазидом) и грибами (гиромитрином) — 5–10 мл и более в/в.'],
            ['💧 Раствор Рингера, Реополиглюкин, РеоХЕС (ГЭК), Уголь', 'Инфузионные среды и энтеросорбенты',
                '• Раствор Рингера / NaCl 0,9%: базовые кристаллоиды при шоке, кровопотере, ожогах и дегидратации (500–1500 мл в/в, детям болюс 20 мл/кг).\n• ГЭК 6% (РеоХЕС) / Декстран (Реополиглюкин): коллоидные плазмозаменители при гиповолемическом и геморрагическом шоке (250–500–1000 мл в/в в сочетании с кристаллоидами).\n• Уголь активированный: при острых пероральных отравлениях — 0,5–1 г/кг (20–50 г) в виде водной взвеси внутрь или через зонд до и после промывания желудка.']
        ];
        const guides = [
            ['⚖️ 1. Критерии оценки тяжести состояния пациента', 'Удовлетворительное, средней тяжести, тяжелое, крайне тяжелое (20.html)',
                'Оценка базируется на 4 критериях: 1) Степень декомпенсации функций жизненно важных органов; 2) Необходимость неотложной госпитализации и интенсивной терапии; 3) Ближайший прогноз для жизни; 4) Двигательная активность.\n\n🟢 УДОВЛЕТВОРИТЕЛЬНОЕ СОСТОЯНИЕ:\n• Функции жизненно важных органов компенсированы (сознание ясное, положение активное, витальные показатели стабильные).\n• Нет угрозы для жизни и нет необходимости в экстренной госпитализации (например: неосложненное повышение АД без криза, купированная стенокардия, плановая хроника, дорсалгия).\n\n🟡 СОСТОЯНИЕ СРЕДНЕЙ ТЯЖЕСТИ:\n• Умеренная декомпенсация функций органов, есть необходимость неотложных мероприятий и/или госпитализации, нет прямой угрозы жизни в ближайшие минуты, но есть риск осложнений.\n• Примеры: интенсивный болевой синдром (почечная/печеночная колика, острый аппендицит, переломы), приступ БА или ФП без шока, гипертонический криз, высокая лихорадка с интоксикацией, пневмония с одышкой.\n\n🟠 ТЯЖЕЛОЕ СОСТОЯНИЕ:\n• Выраженная декомпенсация жизненно важных органов, прямая угроза для жизни, требуется лечение в ОРИТ/ПИТ.\n• Примеры: острый инфаркт миокарда (даже при стабильной гемодинамике!), ОНМК (инсульт), отек легких, астматический статус, эпистатус, сопор/кома, разлитой перитонит, массивное ЖКК, шок I–II ст., тяжелые отравления.\n\n🔴 КРАЙНЕ ТЯЖЕЛОЕ СОСТОЯНИЕ:\n• Критическая декомпенсация с угрозой смерти в ближайшие минуты: глубокая кома, шок III–IV ст. (АД едва определяется, нитевидный пульс), агональное дыхание, тотальный альвеолярный отек легких.'],
            ['🗣️ 2. Правила описания разделов «Жалобы» и «Анамнез»', 'Юридически и клинически грамотное оформление (10.html, 11.html)',
                '📌 ЖАЛОБЫ (10.html):\n• Начинают с главных жалоб, послуживших поводом к вызову СМП и подтверждающих основной диагноз.\n• Для болевого синдрома обязательно отразить 9 характеристик: характер (давящая, жгучая, схваткообразная, «кинжальная»), локализация, интенсивность, иррадиация, продолжительность, время появления, провоцирующие факторы, чем купируется.\n\n📌 АНАМНЕЗ ЗАБОЛЕВАНИЯ И ЖИЗНИ (11.html):\n• Отражает хронологию развития симптомов. ⚠️ Нельзя подменять описание симптомов готовым диагнозом (неверно: «со слов родственников — был приступ эпилепсии»; верно: «со слов родственников — 15 мин назад развился приступ генерализованных тонико-клонических судорог длительностью до 2 мин с потерей сознания и прикусом языка»).\n• Обязательно указать: рабочее (адаптированное) и максимальное АД, какие препараты и в какой дозе пациент принял до приезда СМП и с каким эффектом.\n• Указать источник сведений: «со слов пациента», «со слов родственников / очевидцев», «из выписки». Если пациент без сознания и документов — «достоверный анамнез неизвестен».'],
            ['🩺 3. Описание объективного и локального статуса', 'Избежание экспертных ошибок при проверке карт (12.html, 13.html)',
                '📌 ОБЪЕКТИВНЫЙ СТАТУС (12.html):\n• При отсутствии речевого контакта, афазии, сопора или комы НЕЛЬЗЯ писать шаблонные фразы «живот безболезненный» или «пальце-носовую пробу выполняет уверенно»! Правильная формулировка при коме/афазии: «живот мягкий, на пальпацию живота мимикой / двигательной реакцией не реагирует».\n• У тяжелых лежачих пациентов при аускультации легких грамотно писать: «в доступных для аускультации отделах дыхание везикулярное...».\n\n📌 ЛОКАЛЬНЫЙ СТАТУС ПРИ ТРАВМАХ И РАНАХ (13.html):\n• Описывая раны, гематомы, ожоги, указывают их точную анатомическую локализацию, форму, края, дно раны, характер кровотечения и размеры в сантиметрах.\n• ⚠️ Нельзя заменять описание раны диагнозом в локальном статусе (вместо «резаная рана» в локальном статусе пишем: «линейная рана с ровными краями длиной 4,0 см, зияющая, дном является ПЖК, умеренное капиллярное кровотечение»).\n• Обязательно указать состояние дистального кровообращения, пульсации, чувствительности и функции конечности ниже места перелома/раны!\n• У неизвестных в коме — подробно описать одежду, приметы и ценности с передачей под роспись.'],
            ['📋 4. Правила формулировки клинического диагноза СМП', 'Структура диагноза и частые ошибки (14.html)',
                '📌 СТРУКТУРА ДИАГНОЗА:\n1. Основное заболевание (обусловившее вызов СМП и требующее неотложной помощи);\n2. Осложнения основного заболевания (шок, отек легких, ОДН, кома, кровотечение);\n3. Фоновая / конкурирующая патология.\n\n⚠️ ЧАСТЫЕ ОШИБКИ ФОРМУЛИРОВОК (14.html):\n• Сторона поражения пишется прилагательным: правильно «Правосторонняя почечная колика» (а не «почечная колика справа»).\n• При вывихах вывихивается нижележащая кость, а не сустав: правильно «Вывих правого плеча / правой плечевой кости» (а не «вывих плечевого сустава»).\n• При переломах сначала указывается ЧТО сломано, а затем ГДЕ: правильно «Закрытый перелом левой бедренной кости в средней трети».'],
            ['💉 5. Описание неотложной помощи, госпитализации и отказа', 'Юридическая защита фельдшера/врача СМП (15.html, 16.html)',
                '• Порядок записи препаратов (15.html): препараты записываются в хронологическом порядке от главных патогенетических к симптоматическим (например при ОКС сначала нитраты, морфин, АСК, гепарин). Если препарат из протокола не введен — в карте обязательно должно быть обоснование противопоказания (например: «АСК не вводилась ввиду язвенного кровотечения в анамнезе / принята до СМП»).\n• Оформление отказа от госпитализации (16.html): пациенту в доступной форме разъясняются возможные последствия отказа (вплоть до неблагоприятного/летального исхода), берутся подписи пациента и медработника, даются четкие рекомендации и при необходимости передается актив в поликлинику / СМП.\n• Оказание помощи без согласия допускается при угрозе жизни (коме/шоке), опасных инфекциях и тяжелых психических расстройствах с опасностью для себя или окружающих.'],
            ['🔪 6. Симптомы «острого живота» и раздражения брюшины', 'Полный справочник хирургических симптомов (3.html, 4.html, 8.html)',
                '📌 ОСТРЫЙ АППЕНДИЦИТ:\n• С-м Кохера-Волковича — миграция боли из эпигастрия в правую подвздошную область через 1–3 ч;\n• С-м Ровзинга — боль справа при толчкообразном надавливании в левой подвздошной области;\n• С-м Ситковского — усиление боли справа при повороте пациента на левый бок;\n• С-м Бартомье-Михельсона — резкая болезненность при пальпации слепой кишки в положении на левом боку;\n• С-м Образцова — усиление боли при пальпации правой подвздошной области с поднятой прямой правой ногой (ретроцекальный аппендицит);\n• С-м Воскресенского («рубашки») — резкая боль при быстром скольжении рукой сверху вниз к правой подвздошной области;\n• С-м Раздольского — болезненность при перкуссии в правой подвздошной области;\n• С-м Коупа — боль в глубине таза при ротации согнутого правого бедра (тазовый аппендицит);\n• С-м Брендо — усиление боли справа при смещении матки у беременных.\n\n📌 ОСТРЫЙ ХОЛЕЦИСТИТ:\n• С-м Ортнера — боль при поколачивании ребром ладони по правой реберной дуге;\n• С-м Мерфи («прерванного вдоха») — резкая боль на вдохе при погружении пальцев в правое подреберье;\n• С-м Кера — боль на вдохе при пальпации точки желчного пузыря;\n• С-м Мюсси-Георгиевского (френикус-симптом) — болезненность при надавливании между ножками правой кивательной мышцы над ключицей.\n\n📌 ОСТРЫЙ ПАНКРЕАТИТ:\n• С-м Мейо-Робсона — болезненность в левом реберно-позвоночном углу;\n• С-м Керте — поперечная болезненная резистентность в эпигастрии;\n• С-м Воскресенского — исчезновение пульсации брюшной аорты в эпигастрии;\n• С-м Мендельсона — боль при поколачивании по левой реберной дуге.\n\n📌 ПЕРФОРАТИВНАЯ ЯЗВА И ПЕРИТОНИТ:\n• С-м Щеткина-Блюмберга — резкое усиление боли в момент быстрого отнятия руки от брюшной стенки;\n• С-м Спижарного — исчезновение печеночной тупости при перкуссии;\n• Доскообразное напряжение мышц живота (дефанс).\n\n📌 ВНУТРИБРЮШНОЕ КРОВОТЕЧЕНИЕ (внематочная беременность, разрыв селезенки):\n• С-м Куленкампфа — положительный с-м Щеткина-Блюмберга при мягком животе;\n• С-м Розанова («ваньки-встаньки») — при разрыве селезенки пациент лежит на левом боку с поджатыми ногами и при попытке повернуть его сразу возвращается в прежнее положение.'],
            ['🧠 7. Неврологический осмотр: сознание, менингеальные и очаговые знаки', 'Степени угнетения сознания, зрачки, парезы (2.html, 9.html)',
                '📌 УРОВНИ УГНЕТЕНИЯ СОЗНАНИЯ (2.html):\n• Оглушение умеренное (ШКГ 13–14): сонливость, речевой контакт сохранен, ответы односложные, команды выполняет.\n• Оглушение глубокое (ШКГ 11–12): резкая сонливость, дезориентация, выполняет только простейшие команды.\n• Сопор (ШКГ 9–10): речевой контакт невозможен, открывает глаза и координированно отдергивает руку на сильную боль.\n• Кома I ст. (умеренная, ШКГ 7–8): нескоординированные движения на боль, роговичные и фотореакции сохранены.\n• Кома II ст. (выраженная, ШКГ 5–6): реакции на боль почти отсутствуют или децеребрационные, зрачковые и глотательные рефлексы угнетены, нарушения дыхания.\n• Кома III ст. (глубокая/атоническая, ШКГ 3–4): полная арефлексия, атония мышц, мидриаз без фотореакции, критические расстройства дыхания и гемодинамики.\n\n📌 МЕНИНГЕАЛЬНЫЕ СИМПТОМЫ (9.html):\n• Ригидность затылочных мышц — невозможность пассивно привести подбородок к грудине;\n• Симптом Кернига — невозможность разогнуть в коленном суставе ногу, предварительно согнутую под 90° в тазобедренном и коленном суставах;\n• Симптомы Брудзинского: верхний (сгибание ног при пассивном наклоне головы), средний (сгибание ног при надавливании на лобок), нижний (сгибание второй ноги при проверке с-ма Кернига).\n\n📌 ОЦЕНКА МЫШЕЧНОЙ СИЛЫ ПРИ ПАРЕЗАХ (6-балльная шкала):\n• 5 б — нормальная сила; 4 б — движение против легкого сопротивления; 3 б — поднимает конечность против силы тяжести (без сопротивления); 2 б — движения только по плоскости постели; 1 б — шевеление пальцами/сокращение мышц; 0 б — плегия (полное отсутствие движений).'],
            ['🫁 8. Аускультация и перкуссия легких и сердца, элементы сыпи', 'Хрипы, крепитация, шумы, морфология сыпи (5.html, 6.html, 7.html)',
                '📌 ПЕРКУТОРНЫЙ ЗВУК НАД ЛЕГКИМИ (6.html):\n• Ясный легочный — норма;\n• Коробочный — эмфизема, приступ бронхиальной астмы, ХОБЛ;\n• Тимпанический — пневмоторакс, крупная полость;\n• Притупление (тупой) — крупозная пневмония, экссудативный плеврит (гидроторакс), гемоторакс, ателектаз.\n\n📌 ХРИПЫ И ПОБОЧНЫЕ ДЫХАТЕЛЬНЫЕ ШУМЫ:\n• Сухие свистящие (дискантовые) — спазм мелких бронхов (БА, бронхиолит);\n• Сухие жужжащие (басовые) — вязкая мокрота в крупных и средних бронхах (бронхит);\n• Влажные мелкопузырчатые звонкие — пневмония;\n• Влажные мелкопузырчатые незвонкие (застойные) в нижних отделах — левожелудочковая СН, интерстициальный отек легких;\n• Влажные крупнопузырчатые клокочущие — альвеолярный отек легких;\n• Крепитация — выслушивается ТОЛЬКО на высоте вдоха и не исчезает после кашля (разлипание альвеол при пневмонии, инфаркте легкого);\n• Шум трения плевры — слышен на вдохе и выдохе, усиливается при нажатии стетоскопом, не меняется после кашля.\n\n📌 ЭЛЕМЕНТЫ КОЖНОЙ СЫПИ (5.html):\n• Пятно (macula): розеолы/эритема исчезают при надавливании пальцем (стеклом), а геморрагические пятна (петехии, пурпура, экхимозы при менингококцемии!) при надавливании НЕ бледнеют и НЕ исчезают!\n• Волдырь (urtica) — возвышающийся зудящий элемент при крапивнице;\n• Папула (узелок), Везикула (пузырек с серозной жидкостью), Булла (пузырь при ожоге II ст.), Пустула (гнойничок).']
        ];

        let tab = 0;
        openIosSheet('💊 Лекарства СМП и Оформление карты', '#00695C', (body) => {
            body.innerHTML = `
                <div id="dg-tabs" style="display:flex;gap:8px;margin-bottom:10px;"></div>
                <input id="dg-inp" type="text" placeholder="Поиск (кордарон, допамин, аппендицит, кома, диагноз)..." style="width:100%;box-sizing:border-box;padding:11px 13px;border-radius:12px;border:1.5px solid #80CBC4;background:#E0F2F1;color:#111;font-size:14px;margin-bottom:10px;outline:none;">
                <div id="dg-list" style="display:flex;flex-direction:column;gap:8px;"></div>
            `;
            const tRow = body.querySelector('#dg-tabs'), inp = body.querySelector('#dg-inp'), list = body.querySelector('#dg-list');
            function drawTabs() {
                tRow.innerHTML = '';
                [`💊 Препараты СМП (${drugs.length})`, `✍️ Оформление карты (${guides.length})`].forEach((lbl, i) => {
                    const sel = i === tab;
                    const b = document.createElement('button');
                    b.style.cssText = `flex:1;padding:10px 8px;border:none;border-radius:11px;font-size:12.5px;cursor:pointer;font-weight:${sel ? '700' : '400'};background:${sel ? '#00796B' : '#E0F2F1'};color:${sel ? '#fff' : '#004D40'};`;
                    b.innerText = lbl; b.onclick = () => { tab = i; drawTabs(); render(); };
                    tRow.appendChild(b);
                });
            }
            function render() {
                const q = inp.value.trim().toLowerCase();
                const src = tab === 0 ? drugs : guides;
                list.innerHTML = '';
                src.filter(([t, s, c]) => !q || t.toLowerCase().includes(q) || s.toLowerCase().includes(q) || c.toLowerCase().includes(q)).forEach(([t, s, c]) => {
                    const d = document.createElement('div');
                    d.style.cssText = 'padding:13px 14px;border-radius:13px;background:#F2F9F9;border:1px solid #B2DFDB;color:#263238;font-size:13.5px;line-height:1.45;cursor:pointer;';
                    d.innerHTML = `<b>${escapeHtml(t)}</b><br><span style="color:#546E7A;font-size:12.5px;">• ${escapeHtml(s)}</span>`;
                    d.onclick = () => showDetailDialog(t, c, '#00695C');
                    list.appendChild(d);
                });
            }
            inp.oninput = render; drawTabs(); render();
        });
    }
// --- 8.11 ПРИКАЗЫ МЗ РБ, ТЛТ И ЧКВ (10) ---
    function openOrdersIos() {
        const orders = [
            ['💉 ТЛТ при ОКС (тромболизис на этапе СМП)', 'Показания, абсолютные и относительные противопоказания (tlt.html)',
                '✅ ПОКАЗАНИЯ (Тромболизис ТОЛЬКО при «ДА» во всех пунктах):\n1. Типичная боль в грудной клетке ишемического характера (или ее эквиваленты) не менее 30 мин., не купирующаяся повторным приемом нитроглицерина.\n2. Подъем сегмента ST на 1 мм и более по меньшей мере в двух смежных отведениях от конечностей и/или на 2 мм и выше в двух смежных грудных отведениях, впервые возникшая БЛНПГ или идиовентрикулярный ритм.\n3. Возможность начать ТЛТ не позднее 12 часов от начала заболевания (при невозможности выполнить первичное ЧКВ в течение 120 минут).\n\n⛔ АБСОЛЮТНЫЕ ПРОТИВОПОКАЗАНИЯ (Тромболизис ТОЛЬКО при всех «НЕТ»):\n• Геморрагический инсульт или инсульт неизвестной этиологии любой давности в анамнезе.\n• Ишемический инсульт, перенесенный в течение последних 6 месяцев.\n• Внутричерепное кровоизлияние, АВ-мальформация, аневризмы сосудов мозга, опухоли ЦНС.\n• ЧМТ или нейрохирургическое вмешательство на головном/спинном мозге за последние 4 недели.\n• Кровотечение из ЖКТ или мочеполовых путей в настоящее время или за последние 4 недели.\n• Подозрение на расслаивающую аневризму аорты.\n• Злокачественные новообразования с высоким риском кровотечения.\n• Аллергические реакции на планируемый тромболитик в анамнезе.\n\n⚠️ ОТНОСИТЕЛЬНЫЕ ПРОТИВОПОКАЗАНИЯ (оценка риск/польза):\n• ТИА в предшествующие 6 месяцев.\n• Рефрактерная неконтролируемая АГ (> 180/110 мм рт. ст.).\n• Травматические и/или длительные (> 10 минут) реанимационные мероприятия.\n• Обширные операции или тяжелые травмы за последние 4 недели.\n• Пункция крупных неприжимаемых сосудов за последние 7 дней.\n• Геморрагический диатез или прием непрямых антикоагулянтов.\n• Язвенная болезнь желудка/ДПК в фазе обострения, острый панкреатит, тяжелый цирроз печени, ВРВП.\n• Инфекционный эндокардит, острый перикардит, сепсис, активный туберкулез.\n• Беременность и 10 дней после родов.\n• Для стрептокиназы: введение стрептокиназы от 5 дней до 6 мес назад или стрептококковая инфекция за 3 мес.'],
            ['🫀 ЧКВ при ОКС (чрескожное коронарное вмешательство)', 'Показания и противопоказания к экстренной коронарографии (chkv.html)',
                '✅ ПОКАЗАНИЯ К ЭКСТРЕННОМУ ЧКВ:\n• Пациенты с ОКС с подъемом сегмента ST (ОКСпST).\n• ОКС без подъема ST (ОКСбпST) с кардиогенным шоком, жизнеугрожающими ЖТ/ФЖ, острой левожелудочковой недостаточностью (отеком легких).\n• Рецидивирующий ангинозный приступ, зафиксированный медработником в течение суток.\n\n⛔ ПРОТИВОПОКАЗАНИЯ К ЧКВ:\n• Отказ пациента от ангиографии.\n• Аллергия к контрастным веществам.\n• ОНМК до 1 месяца.\n• АГ, рефрактерная к лекарственной терапии.\n• Кома.\n• Туберкулез легких (активная стадия) или острое инфекционное заболевание с лихорадкой.\n• Острое психическое расстройство.\n• Тяжелая соматическая патология в стадии декомпенсации.\n• Хирургические операции, тяжелые травмы в течение 10 дней.'],
            ['🧠 ТЛТ при ОНМК (тромболизис при ишемическом инсульте)', 'Терапевтическое окно 4,5 ч, показания и противопоказания (tlt_onmk.html)',
                '🚨 ТЛТ проводится только в стационаре! Необходимо доставить пациента максимально быстро в «терапевтическом окне»!\n\n✅ ПОКАЗАНИЯ ДЛЯ ТЛТ:\n• Клинический диагноз ишемического инсульта.\n• Возраст 18 – 80 лет (и старше по решению консилиума).\n• Время не более 4,5 часов от начала заболевания до начала тромболизиса.\n• Отсутствие значительного клинического улучшения перед началом ТЛТ.\n\n⛔ ПРОТИВОПОКАЗАНИЯ ДЛЯ ТЛТ:\n• Клинические признаки САК или внутричерепного кровоизлияния.\n• Быстрое улучшение или малый дефицит (NIHSS < 4 баллов).\n• Гликемия < 2,8 или > 22,5 ммоль/л.\n• АД свыше 185/110 мм рт. ст.\n• Подозрение на расслоение аорты, бактериальный эндокардит, перикардит.\n• Кровотечение или острая травма (перелом) на момент осмотра.\n• Судороги в начале инсульта.\n• Время > 4,5 часов от начала симптомов (или если инсульт развился во сне и время неизвестно).\n• Прием оральных антикоагулянтов или гепарина за последние 48 ч с повышением АЧТВ.\n• Беременность и 10 дней после родов.\n• Обширное хирургическое вмешательство, травматичная СЛР (>2 мин) или пункция центральных вен за последние 10 дней.\n• ЖКК или кровотечения из мочеполовой системы за последние 3 недели.\n• ЧМТ или подтвержденное обострение язвы желудка/ДПК за последние 3 месяца.\n• Острый панкреатит, тяжелый цирроз печени, портальная гипертензия, ВРВП.'],
            ['🚑 Постановление МЗ РБ № 179 (2025) / № 2 (2020) — Служба СМП', 'Организация деятельности СМП, приоритеты вызовов, нормативы доезда',
                '📌 ПРИОРИТЕТЫ ВЫЗОВОВ БРИГАД СМП:\n1. ЭКСТРЕННЫЕ ВЫЗОВЫ (передача бригаде <= 5 мин, доезд в городе <= 20 мин, в иных НП <= 35 мин):\n   • Пожар, ЧС, ДТП с пострадавшими;\n   • Потеря пациентом сознания, сыпь с высокой температурой у детей;\n   • Остановка дыхания и кровообращения (СЛР), повешение, утопление;\n   • Острые нарушения дыхания, приступ астмы, инородное тело ВДП;\n   • Боль в грудной клетке (ОКС), аритмия, шок;\n   • ОНМК (внезапное нарушение речи, асимметрия лица, слабость конечностей);\n   • Обильное кровотечение, острая боль в животе;\n   • Отравление химическими веществами или ЛС;\n   • Ожог любой площади и глубины у ребенка до 3 лет, обширные ожоги, глубокие отморожения, электротравма;\n   • Падение с высоты выше собственного роста, судороги;\n   • Роды, угроза прерывания беременности;\n   • Острое психическое расстройство, представляющее угрозу жизни пациента или окружающих.\n\n2. НЕОТЛОЖНЫЕ ВЫЗОВЫ (передача бригаде <= 1 ч, доезд в городе <= 75 мин, в иных НП <= 90 мин):\n   • Повышение АД, головная боль, температура тела;\n   • Боль в спине, суставах, конечностях;\n   • Аллергия без анафилаксии, задержка мочи;\n   • Обезболивание онкопациентов в нерабочее время поликлиники;\n   • Транспортировка пациента по заявке медработника, консультация психиатрической бригады СМП.\n\n🚫 СЛУЖБА СМП НЕ ОСУЩЕСТВЛЯЕТ:\n• Назначение дальнейшего курсового лечения, выписку рецептов, листков нетрудоспособности и справок;\n• Выполнение плановых назначений врачей поликлиник (инъекции, капельницы, перевязки, плановая замена катетеров);\n• Медицинское освидетельствование на состояние опьянения;\n• Транспортировку пациентов без медицинских показаний (в т.ч. домой после выписки).'],
            ['⚡ Постановление МЗ РБ № 50 / № 99 — Анафилаксия', 'Клинический протокол экстренной помощи пациентам с анафилаксией',
                '📌 МЕРОПРИЯТИЯ ПЕРВОГО ПОРЯДКА (НЕМЕДЛЕННО!):\n1. Прекратить поступление аллергена. Оценить ABCDE.\n2. ЭПИНЕФРИН (АДРЕНАЛИН 0,18%) В/М в середину переднелатеральной поверхности бедра:\n   • Взрослые: 0,3 – 0,5 мл (0,3–0,5 мг);\n   • Дети: 0,01 мл/кг (максимально 0,3 мл);\n   • При отсутствии ответа через 5–10 минут — повторное введение эпинефрина в той же дозе!\n   • При наличии венозного доступа и рефрактерном шоке — в/в титрованно в разведении на 20 мл NaCl 0,9%.\n\n📌 МЕРОПРИЯТИЯ ВТОРОГО ПОРЯДКА:\n• При гипотензии и коллапсе — горизонтальное положение с приподнятыми ногами, увлажненный кислород 6–10 л/мин, болюсная инфузия NaCl 0,9% 500–1000 мл (детям 20 мл/кг).\n• При стридоре (отеке ВДП) — положение сидя, ингаляция эпинефрина или будесонида через небулайзер.\n• При бронхоспазме — ингаляция сальбутамола 100–200 мкг (или фенотерол+ипратропий через небулайзер).\n\n📌 МЕРОПРИЯТИЯ ТРЕТЬЕГО ПОРЯДКА:\n• Глюкокортикостероиды в/в: Преднизолон 90–120 мг (детям 2–5 мг/кг) или Дексаметазон 8–16 мг.\n• Антигистаминные (после стабилизации АД!): Хлоропирамин (Супрастин) 20 мг или Дифенгидрамин (Димедрол) 25–50 мг в/м или в/в.\n• Экстренная доставка в ОРИТ ближайшего стационара, минуя приемное отделение (наблюдение не менее 24 ч).'],
            ['❤️ Постановление МЗ РБ № 59 / № 99 и Приказ № 1000 — Кардиология', 'Протоколы: ОКС, гипертонический криз, отек легких, тахиаритмии',
                '1. ОСТРЫЙ КОРОНАРНЫЙ СИНДРОМ (ОКС):\n   • ЭКГ в 12 отведениях в первые 10 минут от контакта;\n   • Оксигенотерапия при SpO2 < 90–94%;\n   • Нитроглицерин 0,4–0,5 мг с/л или спрей (при САД >= 90 мм рт. ст. и отсутствии ИМ правого желудочка);\n   • Купирование боли: Морфин 1% 1 мл (10 мг) развести до 10 мл NaCl 0,9%, вводить дробно по 2–4 мг в/в каждые 5–15 мин до купирования боли (при непереносимости — Фентанил);\n   • Двойная антиагрегантная терапия: Ацетилсалициловая кислота 250–300 мг разжевать + Клопидогрел 300 мг (или 600 мг перед ЧКВ, старше 75 лет при ТЛТ — 75 мг) или Тикагрелор 180 мг;\n   • Антикоагулянт: Гепарин 60–70 МЕ/кг в/в болюсом (макс. 4000–5000 ЕД) или Эноксапарин;\n   • При ОКСпST: первичное ЧКВ (<120 мин) или системная ТЛТ (Тенектеплаза / Альтеплаза / Стрептокиназа).\n\n2. ГИПЕРТОНИЧЕСКИЙ КРИЗ (Приказ № 1000, Пост. № 59):\n   • Неосложненный ГК (снижение АД на 15–25% за 1–2 часа): Каптоприл 25–50 мг внутрь/с/л или Моксонидин 0,2–0,4 мг внутрь; при тахикардии — Метопролол 25–50 мг или Пропранолол 10–20 мг.\n   • Осложненный ГК (снижение АД на 15–25% в течение первых минут–часа в/в титрованно): Урапидил (Эбрантил) 12,5–25 мг в/в медленно, Эналаприлат 1,25 мг в/в, Фуросемид 20–40 мг в/в, Магния сульфат 25% 10 мл в/в медленно, Нитроглицерин в/в (при ОКС и ОЛЖН).'],
            ['🧠 Приказы МЗ РБ № 47 и № 155 — Помощь при ОНМК (Дорожная карта)', 'Алгоритм ведения пациента с инсультом на этапе СМП',
                '📌 ДИАГНОСТИКА И БАЗОВАЯ ТЕРАПИЯ НА СМП:\n• Уточнить ТОЧНОЕ ВРЕМЯ появления первых симптомов (для оценки окна ТЛТ 4,5 ч);\n• Оценить неврологический статус (лицо, речь, парез руки/ноги, зрачки, ШКГ), снять ЭКГ, измерить гликемию и SpO2;\n• Строгий постельный режим с приподнятым на 30° головным концом;\n• Оксигенотерапия при SpO2 < 94%, при коме <= 8 баллов по ШКГ — интубация трахеи и ИВЛ;\n\n📌 КОРРЕКЦИЯ АД ПРИ ОНМК (КРАЙНЕ ВАЖНО!):\n• Резкое снижение АД противопоказано (ухудшает перфузию пенумбры)!\n• Снижать АД в первые сутки следует при САД >= 220 мм рт. ст. или ДАД >= 120 мм рт. ст. (а если пациент попадает в окно ТЛТ 4,5 ч — плавно снизить до < 185/110 мм рт. ст.);\n• Целевое снижение — не более чем на 10–15% от исходного уровня (препараты выбора: Урапидил в/в, Каптоприл внутрь, Магния сульфат в/в);\n• При гипотонии (САД < 90 мм рт. ст.) — инфузия NaCl 0,9% 250–500 мл, при неэффективности — Допамин в/в капельно;\n• При судорогах и возбуждении — Диазепам 0,5% 2 мл в/в; при рвоте — Метоклопрамид 2 мл в/в; при Т > 38 °C — Метамизол 50% 2 мл в/в;\n• Экстренная доставка в инсультное отделение / нейрореанимацию с предварительным оповещением стационара!'],
            ['👶 Постановление МЗ РБ № 45 — Инородные тела ДП у детей', 'Стадии стеноза и алгоритм извлечения инородного тела (post45.html)',
                '📌 СТАДИИ СТЕНОЗА ДЫХАТЕЛЬНЫХ ПУТЕЙ:\n• I ст. (компенсации): одышка при нагрузке, укорочение пауз между вдохом и выдохом.\n• II ст. (субкомпенсации): одышка в покое, втяжение межреберий и яремной ямки на вдохе, стридор, бледность, тахикардия.\n• III ст. (декомпенсации): дыхание частое поверхностное, вынужденное полусидячее положение с запрокинутой головой, цианоз, липкий пот, нитевидный пульс, падение АД.\n• IV ст. (терминальная): асфиксия, потеря сознания, брадикардия → остановка сердца.\n\n🚨 НЕОТЛОЖНАЯ ПОМОЩЬ ДЕТЯМ ДО 1 ГОДА (при жизнеугрожающей обструкции):\n1. Положить ребенка животиком на предплечье левой руки лицом вниз (голова ниже туловища — позиция «всадника»).\n2. Нанести основанием ладони 5 коротких скользящих ударов между лопатками.\n3. Если эффекта нет — перевернуть ребенка на спину (голова ниже туловища) и произвести 5 толчков двумя пальцами в нижнюю треть грудины (на 1 палец ниже линии сосков; ⚠️ на живот грудничкам давить нельзя!).\n4. Осмотреть ротовую полость, при визуализации ИТ — удалить. Повторять циклы 5 ударов + 5 толчков.\n\n🚨 НЕОТЛОЖНАЯ ПОМОЩЬ ДЕТЯМ СТАРШЕ 1 ГОДА:\n1. Прием Геймлиха: встать сзади, обхватить руками вокруг талии, кулак установить по средней линии живота между пупком и мечевидным отростком и произвести до 5 резких толчков внутрь и вверх.\n2. При потере сознания — начать СЛР, при полной обструкции гортани — экстренная коникотомия. Транспортировка детей при частичной проходимости — строго в положении сидя с подачей увлажненного кислорода!'],
            ['🤪 Постановление МЗ РБ № 126 (в ред. № 91) — Психиатрическая помощь', 'Инструкция о порядке оказания скорой психиатрической помощи (post126.html)',
                '• Скорая психиатрическая помощь подразделяется на ЭКСТРЕННУЮ и НЕОТЛОЖНУЮ.\n• ЭКСТРЕННАЯ психиатрическая помощь оказывается при внезапном возникновении или обострении психического расстройства, которое может привести к совершению пациентом действий, представляющих угрозу для жизни самого пациента и/или окружающих.\n• При оказании экстренной психиатрической помощи пациент НЕЗАВИСИМО от его согласия или согласия законного представителя направляется медработником СМП в психиатрический стационар (ст. 29 Закона РБ «Об оказании психиатрической помощи»).\n• Медработник бригады СМП вправе вызвать сотрудников РОВД (милиции) для оказания содействия в обеспечении безопасности и доставке пациента в психиатрический стационар, если пациент представляет непосредственную опасность для себя или окружающих.\n• Если пациент был предварительно задержан сотрудниками милиции, медработник бригады СМП должен быть ознакомлен с письменным рапортом сотрудника милиции об обстоятельствах и причинах задержания.\n• Оформляются: карта вызова СМП, приложение для психиатрической бригады и сопроводительный лист.'],
            ['🚑 Приказ № 1215 и Постановление № 64 — Транспортировка и Этика', 'Правила медицинской транспортировки пациентов и деонтология',
                '📌 ПРАВИЛА ТРАНСПОРТИРОВКИ ПАЦИЕНТОВ НА СМП (Приказ № 1215):\n• Переноска строго НА НОСИЛКАХ обязательна при:\n  — ОКС и остром инфаркте миокарда, жизнеугрожающих аритмиях;\n  — ОНМК (инсульте) и ЧМТ;\n  — Шоке любой этиологии, коллапсе, массивной кровопотере, ЖКК;\n  — Острой патологии органов брюшной полости с перитонитом, внематочной беременности;\n  — Переломах позвоночника, костей таза и нижних конечностей (на щите / вакуумном матрасе);\n  — Родах (II период), эклампсии, маточном кровотечении, выпадении петель пуповины;\n  — Коматозных состояниях и ОДН II–III ст.\n• Положение во время транспортировки:\n  — Отек легких, приступ БА, ОДН — сидя или полусидя (ортопноэ);\n  — ОНМК, ЗЧМТ — лежа с приподнятым на 30° головным концом;\n  — Шок, кровопотеря, коллапс — лежа с приподнятым ножным концом;\n  — Кома (без интубации) — стабильное боковое положение (профилактика аспирации).\n• При категорическом отказе пациента с ОКС/ОНМК от носилок в карте вызова обязательно делается отметка о причине способа передвижения!']
        ];

        openIosSheet('📜 Приказы МЗ РБ, Протоколы, ТЛТ и ЧКВ', '#37474F', (body) => {
            body.innerHTML = `
                <input id="ord-inp" type="text" placeholder="Поиск (ТЛТ, ЧКВ, ОКС, ОНМК, 179, анафилаксия, дети)..." style="width:100%;box-sizing:border-box;padding:11px 13px;border-radius:12px;border:1.5px solid #CFD8DC;background:#ECEFF1;color:#111;font-size:14px;margin-bottom:10px;outline:none;">
                <div id="ord-list" style="display:flex;flex-direction:column;gap:8px;"></div>
            `;
            const inp = body.querySelector('#ord-inp'), list = body.querySelector('#ord-list');
            const render = () => {
                const q = inp.value.trim().toLowerCase();
                list.innerHTML = '';
                orders.filter(([t, s, c]) => !q || t.toLowerCase().includes(q) || s.toLowerCase().includes(q) || c.toLowerCase().includes(q)).forEach(([t, s, c]) => {
                    const d = document.createElement('div');
                    d.style.cssText = 'padding:13px 14px;border-radius:13px;background:#F5F7F8;border:1px solid #CFD8DC;color:#263238;font-size:13.5px;line-height:1.45;cursor:pointer;';
                    d.innerHTML = `<b>${escapeHtml(t)}</b><br><span style="color:#546E7A;font-size:12.5px;">• ${escapeHtml(s)}</span>`;
                    d.onclick = () => showDetailDialog(t, c, '#37474F');
                    list.appendChild(d);
                });
            };
            inp.oninput = render; render();
        });
    }

    // ==========================================
    // 9. ПЛАВАЮЩАЯ ПАНЕЛЬ КНОПОК
    // ==========================================
    function isInsideCard() {
        return Boolean(document.querySelector('.MuiTabs-root, [role="tablist"]'));
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
        const m = document.getElementById('btn-main-menu');
        const s = document.getElementById('btn-save-db');
        const f = document.getElementById('btn-fill-db');
        if (m && s && f) {
            m.style.display = inCard ? 'none' : 'flex';
            s.style.display = inCard ? 'flex' : 'none';
            f.style.display = inCard ? 'flex' : 'none';
        }
        if (!inCard) {
            window.activeAutofillData = null;
            window.b103ScreenCache = {};
        }
    }

    ensureToolbar();
    updateVisibility();
    setInterval(updateVisibility, 400);
})();
