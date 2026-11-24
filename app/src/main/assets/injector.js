(function() {
    if (!location.hostname.endsWith('103brest.by')) return;

    window.lastLoadedApiCard = window.lastLoadedApiCard || null;
    window.activeAutofillData = window.activeAutofillData || null;
    window.b103ScreenCache = window.b103ScreenCache || {};
    window.b103IsApplying = false;
    window.b103AuthHeader = window.b103AuthHeader || '';

    const MED_CATALOG_KEY = 'b103_med_catalog_v1';
    const ROUTE_CATALOG_KEY = 'b103_route_catalog_v1';
    const TEAM_KEY = 'b103_team_number_v1';
    const MKB_SITE_KEY = 'b103_site_mkb_v1';
    const MKB_ICD_URL_KEY = 'b103_icd_url_v1';

    function loadJsonStore(key, defVal) {
        try {
            const raw = localStorage.getItem(key);
            if (!raw) return defVal;
            const p = JSON.parse(raw);
            return Array.isArray(p) ? p : defVal;
        } catch (e) { return defVal; }
    }
    function saveJsonStore(key, val) {
        try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) {}
    }
    function getTeamNumber() {
        const fromCard = window.lastLoadedApiCard?.teamNumber;
        if (fromCard) {
            try { localStorage.setItem(TEAM_KEY, String(fromCard)); } catch (e) {}
            return String(fromCard);
        }
        try {
            const saved = localStorage.getItem(TEAM_KEY);
            if (saved) return saved;
        } catch (e) {}
        return '34';
    }
    function saveTeamNumber(num) {
        if (!num) return;
        try { localStorage.setItem(TEAM_KEY, String(num)); } catch (e) {}
    }

    window.b103MedCatalog = window.b103MedCatalog || loadJsonStore(MED_CATALOG_KEY, []);
    window.b103RouteCatalog = window.b103RouteCatalog || loadJsonStore(ROUTE_CATALOG_KEY, []);
    window.b103SiteMkb = window.b103SiteMkb || loadJsonStore(MKB_SITE_KEY, []);

    // ==========================================
    // 0. БАЗОВЫЕ УТИЛИТЫ И ГРАНИЦА «ЗАКЛЮЧЕНИЕ»
    // ==========================================
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

    function mergeMedCatalog(newMeds, newRoutes) {
        let medsChanged = false;
        let routesChanged = false;

        if (Array.isArray(newMeds) && newMeds.length > 0) {
            const map = new Map();
            for (const item of window.b103MedCatalog) {
                if (item && item.n) map.set(item.n.toLowerCase(), item);
            }
            for (const m of newMeds) {
                if (!m || !m.n) continue;
                const k = m.n.toLowerCase();
                const ex = map.get(k);
                if (!ex || (!ex.u && m.u)) {
                    map.set(k, { n: m.n, u: m.u || '' });
                    medsChanged = true;
                }
            }
            if (medsChanged) {
                window.b103MedCatalog = Array.from(map.values()).sort((a, b) => a.n.localeCompare(b.n, 'ru'));
                saveJsonStore(MED_CATALOG_KEY, window.b103MedCatalog);
            }
        }

        if (Array.isArray(newRoutes) && newRoutes.length > 0) {
            const set = new Set(window.b103RouteCatalog.map(r => String(r).trim()).filter(Boolean));
            const before = set.size;
            for (const r of newRoutes) {
                const s = String(r || '').trim();
                if (s && s.length > 1) set.add(s);
            }
            if (set.size !== before) {
                window.b103RouteCatalog = Array.from(set).sort((a, b) => a.localeCompare(b, 'ru'));
                saveJsonStore(ROUTE_CATALOG_KEY, window.b103RouteCatalog);
                routesChanged = true;
            }
        }

        if ((medsChanged || routesChanged) && window.AndroidBridge?.cacheMedCatalog) {
            try {
                window.AndroidBridge.cacheMedCatalog(JSON.stringify({
                    meds: window.b103MedCatalog,
                    routes: window.b103RouteCatalog
                }));
            } catch (e) {}
        }
    }

    function extractMedsAndRoutesFromPayload(data, urlHint = '') {
        const foundMeds = [];
        const foundRoutes = [];
        if (!data || typeof data !== 'object') return { foundMeds, foundRoutes };

        const uL = String(urlHint || '').toLowerCase();

        // 1. Внутри открытой карты
        if (Array.isArray(data.medications)) {
            for (const m of data.medications) {
                if (!m || typeof m !== 'object') continue;
                const medObj = m.medication || m.medicine;
                if (medObj && typeof medObj.name === 'string' && medObj.name.trim()) {
                    foundMeds.push({ n: medObj.name.trim(), u: cleanVal(medObj.type?.name || medObj.unitsType?.name) });
                }
                const rd = cleanVal(m.routeDict?.display);
                if (rd) foundRoutes.push(rd);
            }
        }

        // 2. Справочники brigade-amount и dictionary/route (массив или Page { content: [...] })
        const arr = Array.isArray(data)
            ? data
            : (Array.isArray(data.content) ? data.content : (Array.isArray(data.data) ? data.data : null));

        if (arr && arr.length > 0) {
            const isBrigadeUrl = uL.includes('brigade-amount') || uL.includes('medication') || uL.includes('medicine');
            const isRouteUrl = uL.includes('/dictionary/route');

            for (const x of arr) {
                if (!x || typeof x !== 'object') continue;

                // Формат /api/brigade-amount/34 -> { medicine: { name, type: { name }, active }, amount, ... }
                const med = (x.medicine && typeof x.medicine === 'object')
                    ? x.medicine
                    : ((x.medication && typeof x.medication === 'object') ? x.medication : null);

                if (med && typeof med.name === 'string' && med.name.trim() && med.active !== false) {
                    foundMeds.push({ n: med.name.trim(), u: cleanVal(med.type?.name || med.unitsType?.name) });
                    continue;
                }

                // Прямой формат препарата
                if (typeof x.name === 'string' && x.name.trim() && x.active !== false &&
                    (isBrigadeUrl || 'isMedicalSupplies' in x || 'isNarcotic' in x || 'isAccountable' in x || 'activeIngredient' in x)) {
                    foundMeds.push({ n: x.name.trim(), u: cleanVal(x.type?.name || x.unitsType?.name) });
                }

                // Формат /api/dictionary/route -> { id, code, display }
                if (typeof x.display === 'string' && x.display.trim() && (isRouteUrl || 'code' in x)) {
                    foundRoutes.push(x.display.trim());
                } else if (isRouteUrl && typeof x.type === 'string' && x.type.trim()) {
                    foundRoutes.push(x.type.trim());
                }
            }
        }

        return { foundMeds, foundRoutes };
    }

    function mergeSiteMkb(newItems) {
        if (!Array.isArray(newItems) || !newItems.length) return false;
        const map = new Map();
        for (const it of window.b103SiteMkb) {
            if (it && it.c) map.set(String(it.c).toUpperCase(), { c: String(it.c).toUpperCase(), n: String(it.n || '') });
        }
        let changed = false;
        for (const it of newItems) {
            if (!it || !it.c) continue;
            const code = String(it.c).trim().toUpperCase();
            const name = String(it.n || '').trim();
            if (!/^[A-ZА-Я]\d{2}(?:\.\d+)?$/.test(code)) continue;
            const ex = map.get(code);
            if (!ex || (!ex.n && name) || (name && name.length > ex.n.length)) {
                map.set(code, { c: code, n: name });
                changed = true;
            }
        }
        if (changed) {
            window.b103SiteMkb = Array.from(map.values()).sort((a, b) => a.c.localeCompare(b.c));
            saveJsonStore(MKB_SITE_KEY, window.b103SiteMkb);
            if (window.AndroidBridge?.cacheSiteMkb) {
                try { window.AndroidBridge.cacheSiteMkb(JSON.stringify(window.b103SiteMkb)); } catch (e) {}
            }
        }
        return changed;
    }

    function extractMkbFromPayload(data, urlHint = '') {
        const found = [];
        if (!data || typeof data !== 'object') return found;
        const uL = String(urlHint || '').toLowerCase();
        const isIcdUrl = uL.includes('/icd') || uL.includes('mkb');

        if (data.icd && typeof data.icd === 'object' && typeof data.icd.code === 'string') {
            const c = data.icd.code.trim().toUpperCase();
            const n = cleanVal(data.icd.type || data.icd.name || data.icd.title);
            if (/^[A-ZА-Я]\d{2}(?:\.\d+)?$/.test(c)) found.push({ c, n });
        }

        const arr = Array.isArray(data)
            ? data
            : (Array.isArray(data.content) ? data.content : (Array.isArray(data.data) ? data.data : null));

        if (arr && arr.length > 0) {
            for (const x of arr) {
                if (!x || typeof x !== 'object') continue;
                const c = typeof x.code === 'string' ? x.code.trim().toUpperCase() : '';
                if (/^[A-Z]\d{2}(?:\.\d+)?$/.test(c)) {
                    const n = cleanVal(x.type || x.name || x.title || x.display);
                    if (n || isIcdUrl) found.push({ c, n });
                }
            }
            if (isIcdUrl && found.length > 0 && urlHint) {
                try {
                    const base = String(urlHint).split('?')[0];
                    if (base.includes('/api/')) localStorage.setItem(MKB_ICD_URL_KEY, base);
                } catch (e) {}
            }
        }
        return found;
    }

    function harvestCatalogFromPayload(data, urlHint = '') {
        const { foundMeds, foundRoutes } = extractMedsAndRoutesFromPayload(data, urlHint);
        if (foundMeds.length || foundRoutes.length) {
            mergeMedCatalog(foundMeds, foundRoutes);
        }
        const foundMkb = extractMkbFromPayload(data, urlHint);
        if (foundMkb.length) mergeSiteMkb(foundMkb);
    }

    if ((window.b103MedCatalog.length || window.b103RouteCatalog.length) && window.AndroidBridge?.cacheMedCatalog) {
        try {
            window.AndroidBridge.cacheMedCatalog(JSON.stringify({
                meds: window.b103MedCatalog,
                routes: window.b103RouteCatalog
            }));
        } catch (e) {}
    }
    if (window.b103SiteMkb.length && window.AndroidBridge?.cacheSiteMkb) {
        try { window.AndroidBridge.cacheSiteMkb(JSON.stringify(window.b103SiteMkb)); } catch (e) {}
    }

    let cachedAnchor = null;
    let anchorTime = 0;
    function getConclusionAnchor() {
        const now = (window.performance && performance.now) ? performance.now() : Date.now();
        if (cachedAnchor && (now - anchorTime < 2500) && cachedAnchor.isConnected) {
            return cachedAnchor;
        }
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
        if (!anchor) return false;
        return Boolean(anchor.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING);
    }
    window.b103IsInConclusion = isInConclusion;

    // ==========================================
    // 1. СЕТЕВОЙ ПЕРЕХВАТЧИК КАРТЫ, ТОКЕНА И СПРАВОЧНИКОВ
    // ==========================================
    const rawFetch = window.b103OrigFetch || window.fetch.bind(window);
    window.b103OrigFetch = rawFetch;

    if (!window.b103NetHooked) {
        window.b103NetHooked = true;
        window.fetch = async function(...args) {
            try {
                const opts = args[1] || {};
                if (opts.headers) {
                    const h = opts.headers;
                    const auth = typeof h.get === 'function' ? (h.get('Authorization') || h.get('authorization')) : (h.Authorization || h.authorization);
                    if (auth) window.b103AuthHeader = String(auth);
                }
                if (opts.body && typeof opts.body === 'string' && opts.body.includes('"patient"')) {
                    const s = JSON.parse(opts.body);
                    if (s && s.patient) window.lastLoadedApiCard = Object.assign(window.lastLoadedApiCard || {}, s);
                }
            } catch (e) {}
            const res = await rawFetch.apply(window, args);
            try {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] ? args[0].url : '');
                const tm = url && url.match(/brigade-amount\/(\d+)/i);
                if (tm) saveTeamNumber(tm[1]);
                if (url && url.includes('/api/')) {
                    res.clone().json().then(d => {
                        if (!d) return;
                        if (d.teamNumber) saveTeamNumber(d.teamNumber);
                        harvestCatalogFromPayload(d, url);
                        if (url.includes('/api/card/') && !url.includes('/short') && d.patient) {
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
        const origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
        const origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(m, u) {
            this._url = u;
            try {
                const tm = u && String(u).match(/brigade-amount\/(\d+)/i);
                if (tm) saveTeamNumber(tm[1]);
            } catch (e) {}
            return origOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
            try {
                if (k && String(k).toLowerCase() === 'authorization' && v) {
                    window.b103AuthHeader = String(v);
                }
            } catch (e) {}
            return origSetHeader.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function(body) {
            try {
                if (body && typeof body === 'string' && body.includes('"patient"')) {
                    const s = JSON.parse(body);
                    if (s && s.patient) window.lastLoadedApiCard = Object.assign(window.lastLoadedApiCard || {}, s);
                }
            } catch (e) {}
            this.addEventListener('load', function() {
                try {
                    if (this._url && this._url.includes('/api/') && this.responseText && (this.responseText[0] === '{' || this.responseText[0] === '[')) {
                        const d = JSON.parse(this.responseText);
                        if (!d) return;
                        if (d.teamNumber) saveTeamNumber(d.teamNumber);
                        harvestCatalogFromPayload(d, this._url);
                        if (this._url.includes('/api/card/') && !this._url.includes('/short') && d.patient) {
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
    }

    // Прямой живой поиск препаратов и путей на сервере СМП (использует сессию/токен сайта)
    function buildAuthHeaders() {
        const headers = { 'Accept': 'application/json' };
        if (window.b103AuthHeader) {
            headers['Authorization'] = window.b103AuthHeader;
        } else {
            try {
                for (const key of ['token', 'accessToken', 'access_token', 'jwt', 'authToken']) {
                    const v = localStorage.getItem(key) || sessionStorage.getItem(key);
                    if (v && v.length > 20) {
                        headers['Authorization'] = v.startsWith('Bearer ') ? v : ('Bearer ' + v.replace(/^"|"$/g, ''));
                        break;
                    }
                }
            } catch (e) {}
        }
        return headers;
    }

    window.searchMedsOnline = async function(query, reqId) {
        const q = String(query || '').trim();
        const team = getTeamNumber();
        try {
            const url = `/ssmp_proxy_api/ssmp/api/brigade-amount/${encodeURIComponent(team)}?search=${encodeURIComponent(q)}&sort=zerosLast%2Casc&sort=medicine.name%2Casc&medicine.active=true&size=60`;
            const resp = await rawFetch(url, { credentials: 'include', headers: buildAuthHeaders() });
            if (resp.ok) {
                const data = await resp.json();
                const { foundMeds } = extractMedsAndRoutesFromPayload(data, url);
                if (foundMeds.length > 0) mergeMedCatalog(foundMeds, []);
                const seen = new Set();
                const uniq = foundMeds.filter(m => {
                    const k = m.n.toLowerCase();
                    if (seen.has(k)) return false;
                    seen.add(k); return true;
                });
                if (reqId && window.AndroidBridge?.onMedSearchResult) {
                    window.AndroidBridge.onMedSearchResult(String(reqId), JSON.stringify(uniq));
                }
                return uniq;
            }
        } catch (e) {}
        if (reqId && window.AndroidBridge?.onMedSearchResult) {
            window.AndroidBridge.onMedSearchResult(String(reqId), '[]');
        }
        return [];
    };

    window.syncFullMedCatalog = async function() {
        const team = getTeamNumber();
        let total = 0;
        try {
            const medUrl = `/ssmp_proxy_api/ssmp/api/brigade-amount/${encodeURIComponent(team)}?search=&sort=zerosLast%2Casc&sort=medicine.name%2Casc&medicine.active=true&size=500`;
            const r1 = await rawFetch(medUrl, { credentials: 'include', headers: buildAuthHeaders() });
            if (r1.ok) {
                const d1 = await r1.json();
                const { foundMeds } = extractMedsAndRoutesFromPayload(d1, medUrl);
                if (foundMeds.length) { mergeMedCatalog(foundMeds, []); total = foundMeds.length; }
            }
        } catch (e) {}
        try {
            const rtUrl = `/ssmp_proxy_api/ssmp/api/dictionary/route?search=&size=100`;
            const r2 = await rawFetch(rtUrl, { credentials: 'include', headers: buildAuthHeaders() });
            if (r2.ok) {
                const d2 = await r2.json();
                const { foundRoutes } = extractMedsAndRoutesFromPayload(d2, rtUrl);
                if (foundRoutes.length) mergeMedCatalog([], foundRoutes);
            }
        } catch (e) {}
        return total;
    };

    window.searchMkbOnline = async function(query, reqId) {
        const q = String(query || '').trim();
        const learned = (() => { try { return localStorage.getItem(MKB_ICD_URL_KEY) || ''; } catch (e) { return ''; } })();
        const bases = Array.from(new Set([
            learned,
            '/ssmp_proxy_api/ssmp/api/icd',
            '/ssmp_proxy_api/ssmp/api/dictionary/icd',
            '/ssmp_proxy_api/ssmp/api/mkb'
        ].filter(Boolean)));

        let result = [];
        for (const base of bases) {
            try {
                const url = `${base}?search=${encodeURIComponent(q)}&size=60`;
                const resp = await rawFetch(url, { credentials: 'include', headers: buildAuthHeaders() });
                if (!resp.ok) continue;
                const data = await resp.json();
                const found = extractMkbFromPayload(data, url);
                if (found.length > 0) {
                    mergeSiteMkb(found);
                    const seen = new Set();
                    result = found.filter(it => {
                        if (seen.has(it.c)) return false;
                        seen.add(it.c); return true;
                    });
                    break;
                }
            } catch (e) {}
        }
        if (reqId && window.AndroidBridge?.onMkbSearchResult) {
            window.AndroidBridge.onMkbSearchResult(String(reqId), JSON.stringify(result));
        }
        return result;
    };

    window.getSiteMkbJson = function() {
        try { return JSON.stringify(window.b103SiteMkb || []); } catch (e) { return '[]'; }
    };

    // ==========================================
    // 2. DOM-СБОР КЭША С ЭКРАНА (💾, ШАБЛОНЫ, ПРЕПАРАТЫ И АУДИТ)
    // ==========================================
    function getRealInputs(container) {
        return Array.from(container.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])'))
            .filter(el => !el.classList.contains('MuiSelect-nativeInput') && !el.classList.contains('MuiTextareaAutosize-shadow') && el.getAttribute('aria-hidden') !== 'true' && el.tabIndex !== -1 && !isInConclusion(el));
    }

    function findInputByKw(keywords, exact = false) {
        const all = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
        for (let kw of keywords) {
            const kwL = kw.toLowerCase();
            const target = all.find(el => {
                if (!el.textContent || isInConclusion(el)) return false;
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

    function findMedicationsSection() {
        const lbl = Array.from(document.querySelectorAll('label'))
            .find(e => e.textContent && e.textContent.trim().toLowerCase() === 'лекарственные средства' && !isInConclusion(e));
        return lbl ? lbl.parentElement : null;
    }

    function cleanQtyNum(raw) {
        const m = String(raw || '').replace(',', '.').match(/\d+(?:\.\d+)?/);
        if (!m) return '';
        const n = parseFloat(m[0]);
        return Number.isNaN(n) ? m[0] : String(n);
    }

    function scrapeScreenMedications() {
        const sec = findMedicationsSection();
        if (!sec) return [];
        const cards = Array.from(sec.querySelectorAll('.MuiCard-root'));
        const items = [];
        const discoveredMeds = [];
        const discoveredRoutes = [];
        for (const card of cards) {
            const name = cleanVal(card.querySelector('.MuiCardHeader-title')?.innerText);
            if (!name) continue;
            let qty = '';
            let unit = '';
            let route = '';
            const spans = Array.from(card.querySelectorAll('span, p'));
            for (let i = 0; i < spans.length; i++) {
                const t = (spans[i].innerText || spans[i].textContent || '').trim();
                if (t === 'Количество:' && spans[i].nextElementSibling) {
                    const rawQ = (spans[i].nextElementSibling.innerText || spans[i].nextElementSibling.textContent || '').trim();
                    qty = cleanQtyNum(rawQ);
                    unit = rawQ.replace(/^[\d.,\s]+/, '').trim();
                } else if (t === 'Путь введения:' && spans[i].nextElementSibling) {
                    route = cleanVal(spans[i].nextElementSibling.innerText || spans[i].nextElementSibling.textContent);
                }
            }
            discoveredMeds.push({ n: name, u: unit });
            if (route) discoveredRoutes.push(route);
            items.push({ name, qty: qty || '1', route, note: '' });
        }
        if (discoveredMeds.length || discoveredRoutes.length) {
            mergeMedCatalog(discoveredMeds, discoveredRoutes);
        }
        return items;
    }

    function scrapeCache() {
        const c = window.b103ScreenCache;
        const setValIfFound = (kw, fieldName, exact = false) => {
            const inp = findInputByKw(Array.isArray(kw) ? kw : [kw], exact);
            if (!inp) return;
            const v = (inp.value || '').trim();
            if (!v.startsWith('__')) c[fieldName] = v;
        };

        const fioInp = findInputByKw(["фио"], true);
        if (fioInp && fioInp.value.trim()) c.initialFullName = fioInp.value.trim();

        setValIfFound("гражданство", "citizenship", true);
        setValIfFound("нас. пункт", "settlement");
        setValIfFound("улица", "street");
        setValIfFound("дом", "building", true);
        setValIfFound("квартира", "flat", true);
        setValIfFound("уточнение места жительства", "clarificationAddress");
        setValIfFound("место жительства (пребывания)", "address");
        setValIfFound("экг до оказания", "electrocardiogramBefore");
        setValIfFound("экг после оказания", "electrocardiogramAfter");
        setValIfFound("предварительный диагноз", "diagnosis");
        setValIfFound("жалобы", "complaints");
        setValIfFound("анамнез заболевания", "anamnesis");
        setValIfFound("перенесенные заболевания", "pastIllnesses");
        setValIfFound("локальный статус", "localStatus");
        setValIfFound("возраст", "age", true);
        setValIfFound(["комментарий (пульс", "пульс, ударов в минуту"], "pulse");
        setValIfFound("частота дыхания (в минуту)", "breathCount");
        setValIfFound("температура тела (°c)", "skinTemp");
        setValIfFound("сатурация 1 (%)", "saturation1");
        setValIfFound("гликемия 1 (ммоль/л)", "glycemia1");
        setValIfFound("шкала комы глазго (баллы)", "comaScale");
        setValIfFound("тонус мышц", "muscleTone");
        setValIfFound("патологические рефлексы", "pathologicalReflexes");
        setValIfFound("менингиальные знаки", "meningealSymptoms");
        setValIfFound("плегии, парезы", "plegia");
        setValIfFound("язык", "tongue");
        setValIfFound("слизистые", "mucosa");
        setValIfFound("комментарий (вынужденное)", "positionDescription");
        setValIfFound("комментарий (периферические отеки)", "peripheralEdemaDescription");

        const mkbInp = findInputByKw(["код по мкб"]);
        if (mkbInp) {
            const mkbVal = (mkbInp.value || '').trim();
            if (!mkbVal) {
                c.icd = null;
            } else {
                const m = mkbVal.match(/^\[?([A-ZА-Я]\d{2}(?:\.\d+)?)\]?\s*(.*)$/i);
                if (m) c.icd = { code: m[1].toUpperCase(), name: m[2].trim() };
            }
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
                    if (tx.includes("адаптированное")) c.arterialPressureAdaptive = { sys: s, dia: d };
                    else if (tx.includes("ад (мм") || tx.includes("артериальн")) c.arterialPressure = { sys: s, dia: d };
                    break;
                }
                pr = pr.parentElement;
            }
        }

        const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
        const getSelectText = (kw) => {
            const kwL = kw.toLowerCase();
            const l = labels.find(e => e.textContent && !isInConclusion(e) && (e.textContent.trim().toLowerCase() === kwL || e.textContent.trim().toLowerCase().includes(kwL)) && e.textContent.trim().length < kwL.length + 35);
            if (!l) return null;
            let p = l.closest('.MuiFormControl-root') || l.parentElement;
            for (let i = 0; i < 3 && p && p !== document.body; i++) {
                const b = p.querySelector('.MuiSelect-root, [role="button"]');
                if (b) {
                    const t = (b.innerText || '').trim();
                    return (t && t.toLowerCase() !== 'не выбрано') ? t : '';
                }
                p = p.parentElement;
            }
            return null;
        };
        const gs = getSelectText("общее состояние"); if (gs !== null) c.generalState = gs ? { type: gs } : null;
        const cs = getSelectText("сознание"); if (cs !== null) c.consciousness = cs ? { type: cs } : null;
        const au = getSelectText("возраст (ед.)"); if (au) c.ageType = { type: au };
        const ap = getSelectText("территориальная апо") || getSelectText("апо по месту жительства"); if (ap) c.apo = ap;
        const toArr = (kw, key) => {
            const v = getSelectText(kw);
            if (v !== null) c[key] = v ? v.split(',').map(s => ({ type: s.trim() })).filter(x => x.type) : [];
        };
        toArr("кожа", "skins");
        toArr("тоны сердца", "heartSounds");
        toArr("дыхание", "breaths");
        toArr("перкуссия легких", "percussionOfLungs");
        toArr("живот", "stomachs");

        const getActiveBtnText = (kw) => {
            const kwL = kw.toLowerCase();
            const l = labels.find(e => e.textContent && !isInConclusion(e) && (e.textContent.trim().toLowerCase() === kwL || e.textContent.trim().toLowerCase().includes(kwL)) && e.textContent.trim().length < kwL.length + 35);
            if (!l) return null;
            let p = l.closest('.MuiFormControl-root') || l.parentElement;
            for (let i = 0; i < 4 && p && p !== document.body; i++) {
                const btns = Array.from(p.querySelectorAll('button, div[role="button"]')).filter(b => !isInConclusion(b));
                if (btns.length >= 2 && btns.length <= 6) {
                    const act = btns.find(b => {
                        if (b.className && b.className.includes('MuiButton-contained')) return true;
                        const s = window.getComputedStyle(b);
                        return s.color.includes('255, 255, 255') && !s.backgroundColor.includes('rgba(0, 0, 0, 0)') && !s.backgroundColor.includes('255, 255, 255');
                    });
                    return act ? (act.innerText || act.textContent || '').trim().toUpperCase() : '';
                }
                p = p.parentElement;
            }
            return null;
        };
        const gnd = getActiveBtnText("пол"); if (gnd !== null) c._screenGender = gnd;
        const pup = getActiveBtnText("зрачки"); if (pup !== null) c.pupils = pup ? { type: pup } : null;
        const pos = getActiveBtnText("положение"); if (pos !== null) c.position = pos ? { type: pos } : null;
        const beh = getActiveBtnText("поведение"); if (beh !== null) c.behavior = beh ? { type: beh } : null;
        const spc = getActiveBtnText("речь"); if (spc !== null) c.speech = spc ? { type: spc } : null;
        const fac = getActiveBtnText("лицо"); if (fac !== null) c.face = fac ? { type: fac } : null;
        const gat = getActiveBtnText("походка"); if (gat !== null) c.gait = gat ? { type: gat } : null;
        const nys = getActiveBtnText("нистагм"); if (nys !== null) c.nystagmus = nys ? { type: nys } : null;
        const stu = getActiveBtnText("стул"); if (stu !== null) c.defecatio = stu ? { type: stu } : null;
        const uri = getActiveBtnText("мочеиспускание"); if (uri !== null) c.urinations = uri ? [{ type: uri }] : [];
        const liv = getActiveBtnText("печень"); if (liv !== null) c.liver = liv ? { type: liv } : null;
        const alr = getActiveBtnText("аллергия"); if (alr) c.allergy = (alr === "ДА");
        const lgt = getActiveBtnText("реакция на свет"); if (lgt) c.pupilsLight = (lgt === "ДА");
        const rsh = getActiveBtnText("высыпания"); if (rsh) c.skinRashes = (rsh === "ДА");
        const asc = getActiveBtnText("асцит"); if (asc) c.ascites = (asc === "ДА");
        const edm = getActiveBtnText("периферические отеки"); if (edm) c.peripheralEdema = (edm === "ДА");
        const prt = getActiveBtnText("симптомы раздражения брюшины"); if (prt) c.stomachSymptoms = (prt === "ДА");
        const prs = getActiveBtnText("перистальтика"); if (prs) c.peristalsis = (prs === "ДА");
        const alc = getActiveBtnText("запах алкоголя"); if (alc) c.alcoholSmell = (alc === "ДА");
        const act = getActiveBtnText("запах ацетона"); if (act) c.acetoneSmell = (act === "ДА");
        const pls = getActiveBtnText("пульс, ударов в минуту"); if (pls) c.pulseExists = (pls === "ДА");
        const cns = getActiveBtnText("получено устное согласие на простое медицинское вмешательство") ?? getActiveBtnText("получено устное согласие");
        if (cns !== null) c.acceptsSimpleMedProcedure = (cns === "ДА");

        const medItems = scrapeScreenMedications();
        if (medItems.length > 0) {
            c._domMedications = medItems.map(m => {
                const rtStr = (m.route && !m.route.includes('не определен')) ? ` [${m.route}]` : '';
                return `${m.name} — ${m.qty}${rtStr}`;
            }).join(";\n• ");
        }
    }

    function isInsideCard() {
        return Boolean(document.querySelector('.MuiTabs-root, [role="tablist"]'));
    }

    window.getCurrentCardRawJson = function() {
        try {
            if (!isInsideCard()) return '';
            scrapeCache();
            if (!window.lastLoadedApiCard && Object.keys(window.b103ScreenCache).length === 0) return '';
            return JSON.stringify(Object.assign({}, window.lastLoadedApiCard || {}, window.b103ScreenCache));
        } catch (e) {
            return '';
        }
    };

    window.getCurrentMedicationsJson = function() {
        try {
            if (!isInsideCard()) return '';
            let items = scrapeScreenMedications();
            if (!items.length && Array.isArray(window.lastLoadedApiCard?.medications)) {
                items = window.lastLoadedApiCard.medications.map(m => {
                    const name = cleanVal(m?.medication?.name || m?.medicine?.name || m?.medication || m?.name);
                    if (!name) return null;
                    const qty = cleanQtyNum(m.number || m.count || m.amount || m.quantity || '1') || '1';
                    const route = cleanVal(m?.routeDict?.display || m?.wayOfAdministration?.type || m?.way);
                    const note = cleanVal(m?.route);
                    return { name, qty, route, note };
                }).filter(Boolean);
            }
            return items.length ? JSON.stringify({ items }) : '';
        } catch (e) {
            return '';
        }
    };

    window.getMedCatalogJson = function() {
        try {
            return JSON.stringify({
                meds: window.b103MedCatalog || [],
                routes: window.b103RouteCatalog || []
            });
        } catch (e) {
            return '{"meds":[],"routes":[]}';
        }
    };

    // ==========================================
    // 2.5 КОМПАКТНАЯ ПЕРЕТАСКИВАЕМАЯ КАПСУЛА КНОПОК
    // ==========================================
    const DOCK_KEY = 'b103_dock_state_v1';
    function loadDockState() {
        try {
            const raw = localStorage.getItem(DOCK_KEY);
            if (raw) {
                const s = JSON.parse(raw);
                return {
                    side: s.side === 'left' ? 'left' : 'right',
                    topPct: (typeof s.topPct === 'number' && s.topPct >= 0.08 && s.topPct <= 0.88) ? s.topPct : 0.84,
                    collapsed: Boolean(s.collapsed)
                };
            }
        } catch (e) {}
        return { side: 'right', topPct: 0.84, collapsed: false };
    }
    function saveDockState(st) {
        try { localStorage.setItem(DOCK_KEY, JSON.stringify(st)); } catch (e) {}
    }

    window.b103DockState = window.b103DockState || loadDockState();
    let isTypingInField = false;
    let forceExpandWhileTyping = false;
    let suppressClickUntil = 0;
    let lastInCardState = null;

    function isEditableEl(el) {
        if (!el) return false;
        const tag = (el.tagName || '').toUpperCase();
        if (tag === 'TEXTAREA') return true;
        if (tag === 'INPUT') {
            const tp = (el.type || 'text').toLowerCase();
            return !['button', 'submit', 'checkbox', 'radio', 'file', 'hidden'].includes(tp);
        }
        return Boolean(el.isContentEditable);
    }

    function applyDockLayout() {
        const bar = document.getElementById('b103-toolbar');
        if (!bar) return;

        const inCard = isInsideCard();
        const st = window.b103DockState;
        const effectiveCollapsed = inCard && (st.collapsed || (isTypingInField && !forceExpandWhileTyping));

        const menuBtn = document.getElementById('btn-main-menu');
        const pill = document.getElementById('b103-card-pill');
        const tabBtn = document.getElementById('b103-edge-tab');
        const collapseBtn = document.getElementById('btn-collapse-dock');

        if (menuBtn) menuBtn.style.display = inCard ? 'none' : 'flex';
        if (pill) {
            pill.style.display = (inCard && !effectiveCollapsed) ? 'flex' : 'none';
            pill.style.flexDirection = st.side === 'left' ? 'row-reverse' : 'row';
        }
        if (collapseBtn) collapseBtn.innerText = st.side === 'left' ? '‹' : '›';
        if (tabBtn) {
            tabBtn.style.display = (inCard && effectiveCollapsed) ? 'flex' : 'none';
            tabBtn.style.borderRadius = st.side === 'left' ? '0 14px 14px 0' : '14px 0 0 14px';
            tabBtn.style.opacity = isTypingInField ? '0.52' : '0.84';
        }

        const vh = window.innerHeight || 700;
        const barH = bar.offsetHeight || 44;
        const topPx = Math.max(54, Math.min(vh - barH - 12, Math.round(vh * st.topPct)));

        bar.style.top = topPx + 'px';
        bar.style.bottom = 'auto';
        if (st.side === 'left') {
            bar.style.left = effectiveCollapsed ? '0px' : '8px';
            bar.style.right = 'auto';
        } else {
            bar.style.right = effectiveCollapsed ? '0px' : '8px';
            bar.style.left = 'auto';
        }
    }

    function attachDragBehavior(bar) {
        let dragging = false, moved = false;
        let startX = 0, startY = 0, startLeft = 0, startTop = 0;

        function onStart(clientX, clientY) {
            const rect = bar.getBoundingClientRect();
            dragging = true;
            moved = false;
            startX = clientX;
            startY = clientY;
            startLeft = rect.left;
            startTop = rect.top;
            bar.style.transition = 'none';
        }

        function onMove(clientX, clientY, e) {
            if (!dragging) return;
            const dx = clientX - startX;
            const dy = clientY - startY;
            if (!moved && Math.hypot(dx, dy) > 8) moved = true;
            if (moved) {
                if (e && e.cancelable) e.preventDefault();
                const vw = window.innerWidth || 360;
                const vh = window.innerHeight || 700;
                const w = bar.offsetWidth || 48;
                const h = bar.offsetHeight || 44;
                const nx = Math.max(0, Math.min(vw - w, startLeft + dx));
                const ny = Math.max(48, Math.min(vh - h - 8, startTop + dy));
                bar.style.left = nx + 'px';
                bar.style.right = 'auto';
                bar.style.top = ny + 'px';
                bar.style.bottom = 'auto';
            }
        }

        function onEnd() {
            if (!dragging) return;
            dragging = false;
            bar.style.transition = 'left 0.18s ease, right 0.18s ease, top 0.18s ease, opacity 0.2s ease';
            if (moved) {
                suppressClickUntil = Date.now() + 320;
                const rect = bar.getBoundingClientRect();
                const vw = window.innerWidth || 360;
                const vh = window.innerHeight || 700;
                window.b103DockState.side = (rect.left + rect.width / 2) < (vw / 2) ? 'left' : 'right';
                window.b103DockState.topPct = Math.max(0.08, Math.min(0.88, rect.top / vh));
                saveDockState(window.b103DockState);
                applyDockLayout();
            }
        }

        bar.addEventListener('touchstart', (e) => {
            if (e.touches.length === 1) onStart(e.touches[0].clientX, e.touches[0].clientY);
        }, { passive: true });
        bar.addEventListener('touchmove', (e) => {
            if (dragging && e.touches.length === 1) onMove(e.touches[0].clientX, e.touches[0].clientY, e);
        }, { passive: false });
        bar.addEventListener('touchend', onEnd, { passive: true });
        bar.addEventListener('touchcancel', onEnd, { passive: true });

        bar.addEventListener('mousedown', (e) => { if (e.button === 0) onStart(e.clientX, e.clientY); });
        window.addEventListener('mousemove', (e) => { if (dragging) onMove(e.clientX, e.clientY, e); });
        window.addEventListener('mouseup', onEnd);
    }

    function ensureToolbar() {
        if (!document.body || document.getElementById('b103-toolbar')) return;
        const bar = document.createElement('div');
        bar.id = 'b103-toolbar';
        bar.style.cssText = 'position:fixed;z-index:99999;touch-action:none;user-select:none;-webkit-user-select:none;transition:left 0.18s ease, right 0.18s ease, top 0.18s ease, opacity 0.2s ease;';
        const btnBase = 'width:34px;height:34px;border-radius:50%;border:none;color:#fff;font-size:16px;display:flex;align-items:center;justify-content:center;cursor:pointer;padding:0;box-shadow:0 2px 6px rgba(0,0,0,0.22);';

        bar.innerHTML = `
            <button id="btn-main-menu" style="width:46px;height:46px;border-radius:50%;border:none;background:#008577;color:#fff;font-size:20px;display:none;align-items:center;justify-content:center;cursor:pointer;box-shadow:0 4px 12px rgba(0,0,0,0.3);">☰</button>
            <div id="b103-card-pill" style="display:none;align-items:center;gap:6px;padding:3px 6px;border-radius:22px;background:rgba(30,43,51,0.88);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);box-shadow:0 4px 14px rgba(0,0,0,0.28);border:1px solid rgba(255,255,255,0.16);">
                <span style="color:rgba(255,255,255,0.55);font-size:13px;padding:0 2px;letter-spacing:-1px;cursor:grab;">⋮⋮</span>
                <button id="btn-save-db" title="Сохранить в архив" style="background:#00897B;${btnBase}">💾</button>
                <button id="btn-fill-db" title="Автозаполнение и Помощник" style="background:#0288D1;${btnBase}">⚡</button>
                <button id="btn-collapse-dock" title="Свернуть к краю" style="width:24px;height:32px;border-radius:12px;border:none;background:rgba(255,255,255,0.12);color:#fff;font-size:16px;font-weight:700;display:flex;align-items:center;justify-content:center;cursor:pointer;padding:0;">›</button>
            </div>
            <button id="b103-edge-tab" title="Развернуть панель SSMP Plus" style="display:none;width:26px;height:46px;border:none;background:rgba(2,136,209,0.92);color:#fff;font-size:15px;align-items:center;justify-content:center;cursor:pointer;box-shadow:0 3px 10px rgba(0,0,0,0.28);padding:0;">⚡</button>
        `;
        document.body.appendChild(bar);
        attachDragBehavior(bar);

        document.getElementById('btn-main-menu').onclick = () => {
            if (Date.now() < suppressClickUntil) return;
            window.AndroidBridge?.openMainMenu();
        };
        document.getElementById('btn-save-db').onclick = () => {
            if (Date.now() < suppressClickUntil) return;
            const raw = window.getCurrentCardRawJson();
            if (raw && window.AndroidBridge) {
                window.AndroidBridge.saveApiCardForced(raw);
            } else {
                window.AndroidBridge?.notifyUser("Откройте карту для сохранения");
            }
        };
        document.getElementById('btn-fill-db').onclick = () => {
            if (Date.now() < suppressClickUntil) return;
            const fio = findInputByKw(["фио"], true)?.value?.trim() || "";
            window.AndroidBridge?.requestAutofill(fio);
        };
        document.getElementById('btn-collapse-dock').onclick = () => {
            if (Date.now() < suppressClickUntil) return;
            window.b103DockState.collapsed = true;
            forceExpandWhileTyping = false;
            saveDockState(window.b103DockState);
            applyDockLayout();
        };
        document.getElementById('b103-edge-tab').onclick = () => {
            if (Date.now() < suppressClickUntil) return;
            if (isTypingInField) {
                forceExpandWhileTyping = true;
            }
            window.b103DockState.collapsed = false;
            saveDockState(window.b103DockState);
            applyDockLayout();
        };

        if (!window.b103DockListenersAdded) {
            window.b103DockListenersAdded = true;
            document.addEventListener('focusin', (e) => {
                if (isEditableEl(e.target)) {
                    isTypingInField = true;
                    forceExpandWhileTyping = false;
                    applyDockLayout();
                }
            }, true);
            document.addEventListener('focusout', () => {
                setTimeout(() => {
                    if (!isEditableEl(document.activeElement)) {
                        isTypingInField = false;
                        forceExpandWhileTyping = false;
                        applyDockLayout();
                    }
                }, 140);
            }, true);
            window.addEventListener('resize', applyDockLayout);
        }

        applyDockLayout();
    }

    function updateVisibility() {
        ensureToolbar();
        const inCard = isInsideCard();
        if (inCard !== lastInCardState) {
            lastInCardState = inCard;
            applyDockLayout();
        }
        if (!inCard) {
            window.activeAutofillData = null;
            window.b103ScreenCache = {};
        }
    }

    ensureToolbar();
    updateVisibility();
    if (!window.b103VisTimer) window.b103VisTimer = setInterval(updateVisibility, 400);

    // ==========================================
    // 3. ТОЧНЫЙ АВТОПИЛОТ (ВНУТРИ ТОЙ ЖЕ ОБЛАСТИ ВИДИМОСТИ)
    // ==========================================
    window.setAndApplyAutofill = function(jsonStr) {
        try {
            window.activeAutofillData = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            window.applyAutofill(window.activeAutofillData);
        } catch (e) {}
    };

    window.applyAutofill = async function(jsonStr) {
        if (window.b103IsApplying) return;

        const savedScrollWinY = window.scrollY || document.documentElement.scrollTop || 0;
        const savedScrollContainers = Array.from(document.querySelectorAll('main, .MuiDialog-paper, [role="tabpanel"], .MuiContainer-root'))
            .filter(el => el.scrollTop > 0)
            .map(el => ({ el, top: el.scrollTop }));

        const activeTabBefore = Array.from(document.querySelectorAll('[role="tab"], button'))
            .find(b => b.getBoundingClientRect().top < 140 && (b.getAttribute('aria-selected') === 'true' || b.className.includes('Mui-selected')));

        if (activeTabBefore && (activeTabBefore.innerText || '').toUpperCase().includes('ЗАКЛЮЧЕНИЕ')) {
            return;
        }

        function restoreScreenPosition() {
            window.scrollTo(0, savedScrollWinY);
            document.documentElement.scrollTop = savedScrollWinY;
            for (let item of savedScrollContainers) {
                item.el.scrollTop = item.top;
            }
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

        function isElemVisible(el) {
            if (!el || !el.isConnected) return false;
            if (el.getAttribute('aria-hidden') === 'true') return false;
            const st = window.getComputedStyle(el);
            if (st.display === 'none' || st.visibility === 'hidden') return false;
            const r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
        }

        function getActivePopovers() {
            return Array.from(document.querySelectorAll('.MuiPopover-root, .MuiModal-root, .MuiAutocomplete-popper'))
                .filter(isElemVisible);
        }

        async function closeAnyOpenMenu() {
            for (let attempt = 0; attempt < 5; attempt++) {
                const popovers = getActivePopovers();
                const expandedBtns = Array.from(document.querySelectorAll('[aria-expanded="true"]'));
                if (popovers.length === 0 && expandedBtns.length === 0) break;

                const escInit = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
                if (document.activeElement && document.activeElement !== document.body) {
                    document.activeElement.dispatchEvent(new KeyboardEvent('keydown', escInit));
                    document.activeElement.dispatchEvent(new KeyboardEvent('keyup', escInit));
                    if (document.activeElement.blur) document.activeElement.blur();
                }

                for (let pop of popovers) {
                    const list = pop.querySelector('[role="listbox"], [role="menu"], ul, .MuiMenu-list') || pop;
                    list.dispatchEvent(new KeyboardEvent('keydown', escInit));
                    list.dispatchEvent(new KeyboardEvent('keyup', escInit));

                    const backdrop = pop.querySelector('.MuiBackdrop-root') || (pop.firstElementChild && pop.firstElementChild.classList.contains('MuiBackdrop-root') ? pop.firstElementChild : null);
                    if (backdrop) {
                        backdrop.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                        backdrop.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window, button: 0 }));
                        backdrop.click();
                    }
                }

                for (let b of expandedBtns) {
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

                for (let kw of keywords) {
                    const kwL = kw.toLowerCase();
                    for (let l of labels) {
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
                                    if (inps.length === 1) {
                                        foundInp = inps[0];
                                        break;
                                    } else if (inps.length > 1) {
                                        break;
                                    }
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
                if (!rawDateStr) return { dateStr: "", ageStr: "", ageUnitGuess: "" };
                let clean = String(rawDateStr).trim();
                if (clean.startsWith("__")) return { dateStr: "", ageStr: "", ageUnitGuess: "" };

                let datePart = "", timePart = "00:00";
                if (clean.includes("-")) {
                    const p = clean.split("T"); const d = p[0].split("-");
                    if (d.length === 3) datePart = `${d[2]}.${d[1]}.${d[0]}`;
                    if (p[1] && /^\d{2}:\d{2}/.test(p[1].trim())) timePart = p[1].trim().substring(0, 5);
                } else if (clean.includes(".")) {
                    const p = clean.split("/"); datePart = p[0].trim();
                    if (p[1] && /^\d{2}:\d{2}/.test(p[1].trim())) timePart = p[1].trim().substring(0, 5);
                }
                if (!datePart || datePart.includes("_")) return { dateStr: "", ageStr: "", ageUnitGuess: "" };

                const now = new Date();
                const todayStr = `${String(now.getDate()).padStart(2,'0')}.${String(now.getMonth()+1).padStart(2,'0')}.${now.getFullYear()}`;
                let calcAge = "", ageUnitGuess = "";
                const dp = datePart.split(".");
                if (dp.length === 3) {
                    const bd = parseInt(dp[0], 10), bm = parseInt(dp[1], 10), by = parseInt(dp[2], 10);
                    const bObj = new Date(by, bm - 1, bd);
                    if (by > 1900 && bObj <= now) {
                        let yrs = now.getFullYear() - by;
                        if ((now.getMonth() + 1) < bm || ((now.getMonth() + 1) === bm && now.getDate() < bd)) yrs--;
                        if (yrs >= 1) {
                            calcAge = String(yrs); ageUnitGuess = "Лет";
                        } else {
                            let mos = (now.getFullYear() - by) * 12 + ((now.getMonth() + 1) - bm);
                            if (now.getDate() < bd) mos--;
                            if (mos >= 1) {
                                calcAge = String(mos); ageUnitGuess = "Месяцев";
                            } else {
                                const days = Math.floor((now - bObj) / 86400000);
                                calcAge = String(Math.max(1, days)); ageUnitGuess = "Дней";
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
                for (let kw of keywords) {
                    const kwL = kw.toLowerCase();
                    for (let l of labels) {
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

                for (let c of ctrls) {
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
                            for (let v of vals) {
                                const m = opts.find(o => matchVal(cleanVal(o.innerText).toLowerCase(), v));
                                if (m) wanted.add(m);
                            }
                            for (let o of opts) {
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

                const mkbInputs = findInputs(["код по мкб"]);
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
                for (let kw of rowKeywords) {
                    const kwL = kw.toLowerCase();
                    const targets = labels.filter(el => {
                        if (!el.textContent || el.children.length > 2 || isInConclusion(el)) return false;
                        const t = el.textContent.trim().toLowerCase();
                        return (t === kwL || t.includes(kwL)) && t.length < kwL.length + 35;
                    });

                    for (let target of targets) {
                        let p = target.closest('.MuiFormControl-root') || target.parentElement;
                        for (let i = 0; i < 4 && p && p !== document.body && !isInConclusion(p); i++) {
                            const btns = Array.from(p.querySelectorAll('button, div[role="button"]')).filter(b => !b.disabled && !isInConclusion(b));
                            const desired = btns.find(b => {
                                const bt = cleanVal(b.innerText || b.textContent).toUpperCase();
                                return choices.some(ch => bt === ch || (ch.includes("СИММЕТР") && ch.startsWith("А") && bt.startsWith("А") && bt.includes("СИМ")));
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

                for (let sl of slashNodes) {
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

            if (data.fio) setAll(["фио"], data.fio, true);

            if (data.gender) {
                const gUp = cleanVal(data.gender).toUpperCase();
                const gTarget = (gUp.startsWith("М") || gUp === "TRUE") ? ["МУЖЧИНА", "МУЖСКОЙ"] : ["ЖЕНЩИНА", "ЖЕНСКИЙ"];
                clickButtonInRow(["пол"], gTarget);
            }

            const initAgeRaw = cleanVal(window.lastLoadedApiCard?.initialAge);
            const initAgeM = initAgeRaw.match(/^(\d+)\s+([А-Яа-яЁё]+)/);
            let finAge = cleanVal(data.age) || cleanVal(window.lastLoadedApiCard?.age) || (initAgeM ? initAgeM[1] : "");
            let unitGuess = "";

            const bDateInps = findInputs(["дата рождения"]);
            if (bDateInps.length > 0) {
                const now = new Date();
                const todayIso = `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
                const todayDot = `${String(now.getDate()).padStart(2,'0')}.${String(now.getMonth()+1).padStart(2,'0')}.${now.getFullYear()}`;
                const regDateIso = (window.lastLoadedApiCard?.dateOfRegistration || "").split("T")[0];

                let servB = cleanVal(window.lastLoadedApiCard?.patient?.birthDate);
                if (servB && (servB.startsWith(todayIso) || (regDateIso && servB.startsWith(regDateIso)))) servB = "";
                let archB = cleanVal(data.birthDate);
                if (archB && (archB.includes(todayIso) || archB.includes(todayDot))) archB = "";
                let scrB = cleanVal(bDateInps[0].value);
                let scrDateOnly = scrB.split("/")[0].trim();
                if (scrDateOnly.includes("_") || scrDateOnly === todayDot) scrB = "";

                const rawB = servB || archB || scrB;
                if (rawB) {
                    const parsed = formatBirthDateAndCalcAge(rawB);
                    if (parsed.dateStr) setVal(bDateInps[0], parsed.dateStr);
                    if (!finAge && parsed.ageStr) finAge = parsed.ageStr;
                    if (parsed.ageUnitGuess) unitGuess = parsed.ageUnitGuess;
                }
            }

            const unitVal = cleanVal(data.ageUnit) || cleanVal(window.lastLoadedApiCard?.ageType) || (initAgeM ? initAgeM[2] : "") || unitGuess || "Лет";
            await selectDropdown(["возраст (ед.)"], unitVal, false);
            if (finAge) setAll(["возраст"], finAge, true);

            const getInpVal = (kws, exact = false) => cleanVal(findInputs(kws, exact)[0]?.value);
            const savedSettl = cleanVal(data.settlement) || getInpVal(['нас. пункт']) || cleanVal(window.lastLoadedApiCard?.patient?.settlement) || cleanVal(window.lastLoadedApiCard?.settlement);
            const savedStreet = cleanVal(data.street) || getInpVal(['улица']) || cleanVal(window.lastLoadedApiCard?.patient?.street) || cleanVal(window.lastLoadedApiCard?.street);
            const savedBld = cleanVal(data.building) || getInpVal(['дом'], true) || cleanVal(window.lastLoadedApiCard?.patient?.building) || cleanVal(window.lastLoadedApiCard?.building);
            const savedFlat = cleanVal(data.flat) || getInpVal(['квартира'], true) || cleanVal(window.lastLoadedApiCard?.patient?.flat) || cleanVal(window.lastLoadedApiCard?.flat);
            const savedClar = cleanVal(data.clarificationAddress) || getInpVal(['уточнение места жительства']) || cleanVal(window.lastLoadedApiCard?.patient?.clarificationAddress);
            const savedAddr = cleanVal(data.address) || getInpVal(['место жительства (пребывания)']) || cleanVal(window.lastLoadedApiCard?.patient?.placeOfRegistration) || cleanVal(window.lastLoadedApiCard?.address);

            const ctzVal = cleanVal(data.citizenship) || cleanVal(window.lastLoadedApiCard?.patient?.countryOfRegistration) || '';
            if (ctzVal) await fillSimpleAutocomplete(["гражданство"], ctzVal, !data.citizenship);
            if (savedSettl) await fillSimpleAutocomplete(["нас. пункт"], savedSettl, false);
            if (savedStreet) await fillSimpleAutocomplete(["улица"], savedStreet, false);
            if (savedBld) setAll(["дом"], savedBld, true);
            if (savedFlat) setAll(["квартира"], savedFlat, true);
            if (savedClar) setAll(["уточнение места жительства"], savedClar);
            if (savedAddr) setAll(["место жительства (пребывания)"], savedAddr);

            if (data.apo) await selectDropdown(["территориальная апо", "апо по месту жительства"], data.apo, false);

            setAll(["жалобы"], data.complaints ?? "", false, true);
            setAll(["анамнез заболевания"], data.anamnesis ?? "", false, true);
            setAll(["перенесенные заболевания"], data.pastDiseases ?? "", false, true);
            setAll(["предварительный диагноз"], data.diagnosis ?? "", false, true);
            setAll(["локальный статус"], data.localStatus ?? "", false, true);
            if (data.mkb) await fillMkbAutocomplete(data.mkb);

            fillBP(true, data.bpAdapted ?? "", true);
            fillBP(false, data.bp ?? "", true);
            setAll(["комментарий (пульс", "пульс, ударов в минуту"], data.pulse ?? "", false, true);
            setAll(["частота дыхания (в минуту)"], data.rr ?? "", false, true);
            setAll(["температура тела (°c)"], data.temp !== undefined && data.temp !== null && String(data.temp) !== "" ? String(data.temp).replace(',', '.') : "", false, true);
            setAll(["сатурация 1 (%)"], data.saturation ?? "", false, true);
            setAll(["гликемия 1 (ммоль/л)"], data.glucose !== undefined && data.glucose !== null && String(data.glucose) !== "" ? String(data.glucose).replace(',', '.') : "", false, true);

            setAll(["экг до оказания"], data.ecgBefore ?? "", false, true);
            setAll(["экг после оказания"], data.ecgAfter ?? "", false, true);
            setAll(["шкала комы глазго (баллы)"], cleanVal(data.glasgowScore) || "15", false, true);
            setAll(["тонус мышц"], cleanVal(data.muscleTone) || "Нормальный", false, true);
            setAll(["патологические рефлексы"], cleanVal(data.pathologicalReflexes) || "Отрицательные", false, true);
            setAll(["менингиальные знаки"], cleanVal(data.meningealSymptoms) || "Отрицательные", false, true);
            setAll(["плегии, парезы"], cleanVal(data.plegia) || "Нет", false, true);
            setAll(["язык"], cleanVal(data.tongue) || "Чистые", false, true);
            setAll(["слизистые"], cleanVal(data.mucous) || "Бледно-розовые", false, true);

            clickButtonInRow(["положение"], data.position || "АКТИВНОЕ");
            clickButtonInRow(["поведение"], data.behavior || "СПОКОЙНОЕ");
            if (data.pupils) {
                clickButtonInRow(["зрачки"], [data.pupils, data.pupils.replace("АНИЗОКОРИЯ", "АНИЗОКАРИЯ")]);
            } else {
                const pupLbl = Array.from(document.querySelectorAll('label')).find(e => e.textContent && e.textContent.trim().toLowerCase() === 'зрачки' && !isInConclusion(e));
                if (pupLbl && pupLbl.parentElement) {
                    Array.from(pupLbl.parentElement.querySelectorAll('button'))
                        .filter(b => b.className.includes('MuiButton-contained') && !b.disabled)
                        .forEach(b => b.click());
                }
            }
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

            clickButtonInRow(["запах алкоголя"], data.alcohol || "НЕТ");
            clickButtonInRow(["запах ацетона"], data.acetone || "НЕТ");
            clickButtonInRow(["симптомы раздражения брюшины"], data.peritoneal || "НЕТ");
            clickButtonInRow(["перистальтика"], data.peristalsis || "ДА");
            clickButtonInRow(["получено устное согласие на простое медицинское вмешательство", "получено устное согласие"], "ДА");

            await new Promise(r => setTimeout(r, 50));
            setAll(["комментарий (вынужденное)"], data.positionDescription ?? "", false, true);
            setAll(["комментарий (периферические отеки)"], data.edemaDescription ?? "", false, true);

            await selectDropdown(["общее состояние"], data.generalState || "Удовлетворительное", false);
            await selectDropdown(["сознание"], data.consciousness || "Ясное", false);

            await selectDropdown(["кожа"], data.skin ?? "", true, true);
            await selectDropdown(["тоны сердца"], data.heartSounds ?? "", true, true);
            await selectDropdown(["дыхание"], data.lungs ?? "", true, true);
            await selectDropdown(["перкуссия легких"], data.percussion ?? "", true, true);
            await selectDropdown(["живот"], data.abdomen ?? "", true, true);

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
            if (fillBtn) fillBtn.innerText = "⚡";
            setTimeout(() => { window.b103IsApplying = false; }, 150);
        }
    };

    // ==========================================
    // 3.5 АВТОПИЛОТ ДОБАВЛЕНИЯ ПРЕПАРАТОВ И РАСХОДНИКОВ (СЕРВЕРНЫЙ ПОИСК)
    // ==========================================
    window.applyMedicationsPreset = async function(jsonStr) {
        if (window.b103IsApplying) return;
        const sleep = (ms) => new Promise(r => setTimeout(r, ms));

        let items = [];
        try {
            const parsed = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            items = Array.isArray(parsed) ? parsed : (Array.isArray(parsed?.items) ? parsed.items : []);
        } catch (e) { return; }
        items = items.filter(it => it && cleanVal(it.name));
        if (!items.length) return;

        // Авто-переход на вкладку «МЕДИЦИНСКАЯ ПОМОЩЬ», если раздел не виден
        let sec = findMedicationsSection();
        let addBtn = sec ? Array.from(sec.querySelectorAll('button'))
            .find(b => (b.innerText || b.textContent || '').trim().toUpperCase() === 'ДОБАВИТЬ') : null;

        if (!addBtn || addBtn.offsetParent === null) {
            const helpTab = Array.from(document.querySelectorAll('[role="tab"], button'))
                .find(b => b.getBoundingClientRect().top < 150 && (b.innerText || '').toUpperCase().includes('МЕДИЦИНСКАЯ ПОМОЩЬ'));
            if (helpTab) {
                helpTab.click();
                for (let w = 0; w < 12; w++) {
                    await sleep(100);
                    sec = findMedicationsSection();
                    addBtn = sec ? Array.from(sec.querySelectorAll('button'))
                        .find(b => (b.innerText || b.textContent || '').trim().toUpperCase() === 'ДОБАВИТЬ') : null;
                    if (addBtn && addBtn.offsetParent !== null) break;
                }
            }
        }

        if (!addBtn) {
            window.AndroidBridge?.notifyUser("Перейдите на вкладку «Медицинская помощь» и повторите");
            return;
        }

        window.b103IsApplying = true;
        const fillBtn = document.getElementById('btn-fill-db');
        if (fillBtn) fillBtn.innerText = "⏳";

        let hideStyle = document.getElementById('b103-hide-meds');
        if (!hideStyle) {
            hideStyle = document.createElement('style');
            hideStyle.id = 'b103-hide-meds';
            hideStyle.textContent = '.MuiDialog-root, .MuiAutocomplete-popper { opacity: 0 !important; transition: none !important; }';
            document.head.appendChild(hideStyle);
        }

        const normX = (s) => cleanVal(s).toLowerCase()
            .replace(/x/g, 'х').replace(/c/g, 'с').replace(/a/g, 'а').replace(/e/g, 'е')
            .replace(/o/g, 'о').replace(/p/g, 'р').replace(/k/g, 'к').replace(/m/g, 'м')
            .replace(/t/g, 'т').replace(/h/g, 'н').replace(/b/g, 'в')
            .replace(/,/g, '.').replace(/\s+/g, ' ');
        const setReactVal = (inp, val) => {
            if (!inp) return;
            const str = String(val ?? '');
            const prev = String(inp.value ?? '');
            const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
            if (setter) setter.call(inp, str); else inp.value = str;
            if (inp._valueTracker) inp._valueTracker.setValue(prev + '__force_diff');
            try {
                inp.dispatchEvent(new InputEvent('input', { bubbles: true, data: str, inputType: str ? 'insertText' : 'deleteContentBackward' }));
            } catch (e) {
                inp.dispatchEvent(new Event('input', { bubbles: true }));
            }
            inp.dispatchEvent(new Event('change', { bubbles: true }));
        };

        const isPopVis = (el) => {
            if (!el || !el.isConnected || el.getAttribute('aria-hidden') === 'true') return false;
            const st = window.getComputedStyle(el);
            if (st.display === 'none' || st.visibility === 'hidden') return false;
            const r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
        };

        const diagLog = [];

        async function tryPickOnce(autoRoot, queryText, fullTargetNorm, requireStrongMatch = false, tag = 'q') {
            const inp = autoRoot?.querySelector('input');
            if (!inp || !queryText || queryText.length < 2) {
                diagLog.push(`  [${tag}] skip: inp=${!!inp}, q="${queryText}"`);
                return false;
            }
            const popupBtn = autoRoot.querySelector('.MuiAutocomplete-popupIndicator');
            const clearBtn = autoRoot.querySelector('.MuiAutocomplete-clearIndicator');

            try { inp.focus({ preventScroll: true }); } catch (e) { inp.focus(); }
            inp.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
            inp.click();
            if (inp.value) {
                if (clearBtn) clearBtn.click();
                else setReactVal(inp, '');
                await sleep(100);
            }
            setReactVal(inp, queryText);
            // Ждём debounce React + начало сетевого запроса
            await sleep(420);

            let targetOpt = null;
            let noOptTicks = 0;
            let lastPopSummary = 'none';
            let lastOpts = [];
            for (let w = 0; w < 38; w++) {
                const allPops = Array.from(document.querySelectorAll('.MuiAutocomplete-popper, [role="presentation"].MuiPopper-root'));
                const poppers = allPops.filter(isPopVis);
                const popper = poppers[poppers.length - 1];
                if (!popper && (w === 3 || w === 10) && popupBtn) {
                    popupBtn.click();
                }
                if (popper) {
                    const popText = cleanVal(popper.innerText).toLowerCase();
                    lastPopSummary = popText.slice(0, 80);
                    const isLoading = popText.includes('загрузка') || popText.includes('loading');
                    const opts = Array.from(popper.querySelectorAll('li[role="option"], .MuiAutocomplete-option, .MuiMenuItem-root'))
                        .filter(o => {
                            const tx = cleanVal(o.innerText).toLowerCase();
                            return tx && !tx.includes('загрузка') && !tx.includes('loading') && !tx.includes('нет вариантов') && !tx.includes('no options');
                        });
                    if (opts.length > 0) {
                        noOptTicks = 0;
                        lastOpts = opts.slice(0, 3).map(o => cleanVal(o.innerText).slice(0, 45));
                        const qNorm = normX(queryText);
                        targetOpt = opts.find(o => normX(o.innerText) === fullTargetNorm)
                            || opts.find(o => normX(o.innerText).startsWith(fullTargetNorm))
                            || opts.find(o => normX(o.innerText).includes(fullTargetNorm))
                            || (requireStrongMatch ? null : (opts.find(o => normX(o.innerText).includes(qNorm)) || opts[0]));
                        if (targetOpt) break;
                        if (requireStrongMatch && !isLoading && w >= 8) break;
                    } else if (isLoading) {
                        noOptTicks = 0;
                    } else if (popText.includes('нет вариантов') || popText.includes('no options')) {
                        if (w >= 14) {
                            noOptTicks++;
                            if (noOptTicks >= 6) break;
                        }
                    }
                } else {
                    lastPopSummary = `no-vis(all=${allPops.length},lb=${document.querySelectorAll('[role="listbox"]').length})`;
                }
                await sleep(100);
            }
            if (targetOpt) {
                targetOpt.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window, button: 0 }));
                targetOpt.click();
                await sleep(140);
                return true;
            }
            diagLog.push(`  [${tag}] q="${queryText}" -> fail; inp="${inp.value}"; pop="${lastPopSummary}"; opts=[${lastOpts.join(' | ')}]`);
            return false;
        }

        async function pickAutocomplete(autoRoot, searchStr) {
            const cleanTarget = cleanVal(searchStr);
            if (!autoRoot || cleanTarget.length < 2) {
                diagLog.push(`  pick skip: auto=${!!autoRoot}, s="${cleanTarget}"`);
                return false;
            }
            const fullNorm = normX(cleanTarget);

            // 1. Полное название
            if (await tryPickOnce(autoRoot, cleanTarget, fullNorm, false, 'full')) return true;

            // 2. Замена латинской x <-> кириллической х
            const swapped = /[xX]/.test(cleanTarget)
                ? cleanTarget.replace(/[xX]/g, 'х')
                : cleanTarget.replace(/[хХ]/g, 'x');
            if (swapped !== cleanTarget && await tryPickOnce(autoRoot, swapped, fullNorm, false, 'swap')) return true;

            // 3. Поиск по первому слову (устойчиво к %, №, пробелам и единицам), затем точный выбор нужной строки
            const firstWord = cleanTarget.split(/[\s,/%]+/)[0];
            if (firstWord && firstWord.length >= 2 && firstWord !== cleanTarget) {
                if (await tryPickOnce(autoRoot, firstWord, fullNorm, true, 'word')) return true;
                if (firstWord.length >= 4 && await tryPickOnce(autoRoot, firstWord.slice(0, 4), fullNorm, true, 'p4')) return true;
            }
            return false;
        }

        function findOpenMedDialog() {
            const dialogs = Array.from(document.querySelectorAll('.MuiDialog-root'));
            for (let i = dialogs.length - 1; i >= 0; i--) {
                const d = dialogs[i];
                const h2 = (d.querySelector('h2')?.innerText || '').toLowerCase();
                if (d.querySelector('input[name="number"]') && (h2.includes('добав') || d.querySelector('.MuiAutocomplete-root'))) {
                    return d;
                }
            }
            return null;
        }

        let added = 0;
        let skipped = 0;
        let failed = 0;

        try {
            diagLog.push(`team=${getTeamNumber()}, url=${location.pathname}, items=${items.length}`);
            const existingNames = new Set(scrapeScreenMedications().map(m => normX(m.name)));

            for (const item of items) {
                const itemName = cleanVal(item.name);
                if (!itemName) continue;
                if (existingNames.has(normX(itemName))) {
                    skipped++;
                    continue;
                }

                addBtn.click();
                let dlg = null;
                for (let w = 0; w < 20; w++) {
                    dlg = findOpenMedDialog();
                    if (dlg) break;
                    await sleep(60);
                }
                if (!dlg) {
                    const dlgs = Array.from(document.querySelectorAll('.MuiDialog-root')).map(d => cleanVal(d.querySelector('h2')?.innerText || d.innerText).slice(0, 40));
                    diagLog.push(`ITEM "${itemName}": dialog NOT found; dlgs=[${dlgs.join(' | ')}]`);
                    failed++; continue;
                }

                const autos = Array.from(dlg.querySelectorAll('.MuiAutocomplete-root'));
                const medAuto = autos.find(a => (a.innerText || '').toLowerCase().includes('лекарственные')) || autos[0];
                const routeAuto = autos.find(a => (a.innerText || '').toLowerCase().includes('путь введения')) || autos[1];

                const pickedMed = await pickAutocomplete(medAuto, itemName);
                if (!pickedMed) {
                    diagLog.push(`ITEM "${itemName}": medAuto fail (autos=${autos.length})`);
                    const cancelBtn = Array.from(dlg.querySelectorAll('.MuiDialogActions-root button'))
                        .find(b => (b.innerText || '').toLowerCase().includes('отмен'));
                    cancelBtn?.click();
                    await sleep(160);
                    failed++;
                    continue;
                }

                const qtyInp = dlg.querySelector('input[name="number"]');
                const qtyVal = cleanQtyNum(item.qty) || '1';
                if (qtyInp) {
                    try { qtyInp.focus({ preventScroll: true }); } catch (e) { qtyInp.focus(); }
                    setReactVal(qtyInp, qtyVal);
                    qtyInp.dispatchEvent(new Event('blur', { bubbles: true }));
                }
                await sleep(80);

                const routeStr = cleanVal(item.route);
                const curRouteVal = cleanVal(routeAuto?.querySelector('input')?.value);
                let routeOk = true;
                if (routeAuto) {
                    if (routeStr && !routeStr.toLowerCase().includes('не определен')) {
                        routeOk = await pickAutocomplete(routeAuto, routeStr);
                        if (!routeOk && !cleanVal(routeAuto.querySelector('input')?.value)) {
                            await pickAutocomplete(routeAuto, 'путь введения не определен');
                        }
                    } else if (!curRouteVal) {
                        routeOk = await pickAutocomplete(routeAuto, routeStr || 'путь введения не определен');
                    }
                }

                const noteInp = dlg.querySelector('input[name="route"]');
                if (noteInp && cleanVal(item.note)) {
                    setReactVal(noteInp, cleanVal(item.note));
                    noteInp.dispatchEvent(new Event('blur', { bubbles: true }));
                }

                const saveBtn = Array.from(dlg.querySelectorAll('.MuiDialogActions-root button'))
                    .find(b => (b.innerText || '').toLowerCase().includes('сохранить'));
                for (let w = 0; w < 12 && saveBtn && saveBtn.disabled; w++) {
                    await sleep(80);
                }
                if (saveBtn && !saveBtn.disabled) {
                    saveBtn.click();
                    let closed = false;
                    for (let w = 0; w < 45; w++) {
                        await sleep(100);
                        if (!findOpenMedDialog()) { closed = true; break; }
                    }
                    if (!closed) {
                        const nowOnScreen = scrapeScreenMedications().some(m => normX(m.name) === normX(itemName));
                        if (nowOnScreen) closed = true;
                    }
                    if (closed) {
                        added++;
                        existingNames.add(normX(itemName));
                    } else {
                        const errs = Array.from(dlg.querySelectorAll('.MuiFormHelperText-root.Mui-error, .MuiAlert-message')).map(e => cleanVal(e.innerText)).filter(Boolean);
                        const errFields = Array.from(dlg.querySelectorAll('.MuiFormControl-root'))
                            .filter(fc => fc.querySelector('.Mui-error'))
                            .map(fc => `${cleanVal(fc.querySelector('label')?.innerText)}="${cleanVal(fc.querySelector('input')?.value)}"`)
                            .filter(Boolean);
                        diagLog.push(`ITEM "${itemName}": save timeout (routeOk=${routeOk}, med="${cleanVal(medAuto?.querySelector('input')?.value)}", rt="${cleanVal(routeAuto?.querySelector('input')?.value)}", qty="${cleanVal(qtyInp?.value)}", errs=[${errs.join('; ')}], fields=[${errFields.join('; ')}])`);
                        const cancelBtn = Array.from(dlg.querySelectorAll('.MuiDialogActions-root button'))
                            .find(b => (b.innerText || '').toLowerCase().includes('отмен'));
                        cancelBtn?.click();
                        await sleep(140);
                        failed++;
                    }
                } else {
                    const errs = Array.from(dlg.querySelectorAll('.MuiFormHelperText-root.Mui-error, .MuiAlert-message')).map(e => cleanVal(e.innerText)).filter(Boolean);
                    const errFields = Array.from(dlg.querySelectorAll('.MuiFormControl-root'))
                        .filter(fc => fc.querySelector('.Mui-error'))
                        .map(fc => `${cleanVal(fc.querySelector('label')?.innerText)}="${cleanVal(fc.querySelector('input')?.value)}"`)
                        .filter(Boolean);
                    diagLog.push(`ITEM "${itemName}": saveBtn=${!!saveBtn}, disabled=${saveBtn?.disabled}, med="${cleanVal(medAuto?.querySelector('input')?.value)}", rt="${cleanVal(routeAuto?.querySelector('input')?.value)}", qty="${cleanVal(qtyInp?.value)}", errs=[${errs.join('; ')}], fields=[${errFields.join('; ')}]`);
                    const cancelBtn = Array.from(dlg.querySelectorAll('.MuiDialogActions-root button'))
                        .find(b => (b.innerText || '').toLowerCase().includes('отмен'));
                    cancelBtn?.click();
                    await sleep(140);
                    failed++;
                }
                await sleep(120);
            }

            const msg = `💊 Добавлено: ${added}` +
                (skipped ? `, уже в карте: ${skipped}` : '') +
                (failed ? `, не найдено: ${failed}` : '');
            window.AndroidBridge?.notifyUser(msg);
            if (failed > 0 && diagLog.length) {
                window.AndroidBridge?.showDebugDialog?.('🩺 Диагностика препаратов', diagLog.join('\n'));
            }
        } catch (e) {
            console.error(e);
        } finally {
            document.getElementById('b103-hide-meds')?.remove();
            if (fillBtn) fillBtn.innerText = "⚡";
            setTimeout(() => { window.b103IsApplying = false; }, 150);
        }
    };

    // ==========================================
    // 4. БЫСТРАЯ ТОЧЕЧНАЯ ВСТАВКА В ПОЛЯ КАРТЫ
    // ==========================================
    window.insertIntoCardField = function(fieldKeyword, textToInsert, appendMode) {
        try {
            const kwL = String(fieldKeyword).toLowerCase();
            const isInConclusion = window.b103IsInConclusion;
            const labels = Array.from(document.querySelectorAll('label, .MuiFormLabel-root, .MuiInputLabel-root, p'));
            
            for (let l of labels) {
                if (!l.innerText || l.offsetParent === null || l.children.length > 3 || isInConclusion(l)) continue;
                
                const t = l.innerText.trim().toLowerCase();
                if ((t === kwL || t.includes(kwL)) && t.length < kwL.length + 35) {
                    const fc = l.closest('.MuiFormControl-root') || l.parentElement?.parentElement;
                    if (!fc || isInConclusion(fc)) continue;

                    const inps = Array.from(fc.querySelectorAll('textarea:not([disabled]), input:not([type="hidden"]):not([disabled])'))
                        .filter(el => !el.classList.contains('MuiSelect-nativeInput') && !el.classList.contains('MuiTextareaAutosize-shadow') && el.getAttribute('aria-hidden') !== 'true' && el.tabIndex !== -1 && !isInConclusion(el));

                    if (inps.length > 0) {
                        const inp = inps[0];
                        const cur = (inp.value || '').trim();
                        const finalVal = (appendMode && cur) ? (cur + "\n" + textToInsert) : textToInsert;
                        const proto = inp.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                        const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                        if (setter) setter.call(inp, finalVal); else inp.value = finalVal;
                        if (inp._valueTracker) inp._valueTracker.setValue(cur + '__force_diff');
                        inp.dispatchEvent(new Event('input', { bubbles: true }));
                        inp.dispatchEvent(new Event('change', { bubbles: true }));
                        return true;
                    }
                }
            }
        } catch (e) {}
        return false;
    };
})();