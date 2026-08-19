/*
 * Capture-tool workspace.
 *
 * Everything the author draws is expressed in the capture's own pixel coordinates: the overlay
 * SVG carries a viewBox of the capture's size, so a rectangle at (x,y,w,h) in this file is the
 * same (x,y,w,h) the registry stores and the engine matches. The only conversion in the whole
 * file is pointer -> SVG user space, done once in `point()`.
 */
(function () {
    'use strict';

    const root = document.getElementById('workspace');
    const csrfToken = document.querySelector('meta[name="csrf-token"]').content;
    const csrfHeader = document.querySelector('meta[name="csrf-header"]').content;

    const state = {
        captureId: root.dataset.captureId,
        workerId: root.dataset.workerId,
        width: Number(root.dataset.width),
        height: Number(root.dataset.height),
        uiScale: Number(root.dataset.uiScale),
        defaultThreshold: Number(root.dataset.defaultThreshold),
        s1Version: root.dataset.s1Version,
        mode: 'template',
        selection: null,      // {x,y,width,height} of the pending template
        target: null,         // {x,y} the author clicked, for an offset locator
        crops: [],            // template definitions cut from this capture
        located: new Map(),   // templateRef -> LocateResult drawn on the overlay
        anchor: null,         // LocateResult of the chosen anchor
        templates: [],
        locators: [],
        test: null            // active test result; while set, the stage shows the fresh capture
    };

    const el = {
        stage: document.getElementById('stage'),
        shot: document.getElementById('shot'),
        overlay: document.getElementById('overlay'),
        banner: document.getElementById('banner'),
        hint: document.getElementById('hint'),
        selectionReadout: document.getElementById('selection-readout'),
        cropPreview: document.getElementById('crop-preview'),
        cropImg: document.getElementById('crop-img'),
        tplName: document.getElementById('tpl-name'),
        tplThreshold: document.getElementById('tpl-threshold'),
        tplContext: document.getElementById('tpl-context'),
        tplS1: document.getElementById('tpl-s1'),
        saveTemplate: document.getElementById('save-template'),
        templateList: document.getElementById('template-list'),
        locatorList: document.getElementById('locator-list'),
        anchorSelect: document.getElementById('anchor-select'),
        sizingSelect: document.getElementById('sizing-select'),
        anchorReadout: document.getElementById('anchor-readout'),
        locName: document.getElementById('loc-name'),
        locThreshold: document.getElementById('loc-threshold'),
        saveLocator: document.getElementById('save-locator'),
        locatorJson: document.getElementById('locator-json'),
        testResult: document.getElementById('test-result'),
        testActivate: document.getElementById('test-activate'),
        backToCapture: document.getElementById('back-to-capture')
    };

    // ---- plumbing ----

    async function api(method, url, body) {
        const headers = {'Accept': 'application/json'};
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        if (csrfToken) headers[csrfHeader] = csrfToken;
        const response = await fetch(url, {
            method, headers, body: body === undefined ? undefined : JSON.stringify(body)
        });
        const text = await response.text();
        const payload = text ? JSON.parse(text) : null;
        if (!response.ok) {
            throw new Error(payload && payload.error ? payload.error : method + ' ' + url + ' failed (' + response.status + ')');
        }
        return payload;
    }

    function say(message, kind) {
        el.banner.hidden = !message;
        el.banner.textContent = message || '';
        el.banner.className = 'banner' + (kind ? ' banner-' + kind : '');
    }

    function busy(on, message) {
        document.body.classList.toggle('busy', on);
        if (message) say(message, 'info');
    }

    /** An IMAGE LocatorDefinition, with every JAB field left null. */
    function imageLocator(fields) {
        return Object.assign({
            type: 'IMAGE', role: null, name: null, nameMatch: null, description: null, value: null,
            index: null, ancestorPath: null, templateRef: null, threshold: null,
            anchorTemplateRef: null, offsetX: null, offsetY: null
        }, fields);
    }

    const fmt = (n) => Number(n).toFixed(3);

    /** The threshold a template will be held to, as registered. */
    function thresholdFor(ref) {
        const template = state.templates.find(t => t.name === ref);
        return template && template.threshold ? template.threshold : state.defaultThreshold;
    }

    /** 'match' | 'ambiguous' | 'miss' — the same three outcomes the engine distinguishes. */
    function judge(result, ref) {
        if (!result.found) return 'miss';
        if (result.score < thresholdFor(ref)) return 'miss';
        return result.ambiguous ? 'ambiguous' : 'match';
    }

    // ---- overlay ----

    const SVG_NS = 'http://www.w3.org/2000/svg';

    function svg(tag, attrs) {
        const node = document.createElementNS(SVG_NS, tag);
        for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
        return node;
    }

    function box(rect, cls, label) {
        const group = document.createElementNS(SVG_NS, 'g');
        group.setAttribute('class', 'ov ' + cls);
        group.appendChild(svg('rect', {x: rect.x, y: rect.y, width: rect.width, height: rect.height}));
        if (label) {
            // Labels above the box, or inside it when the box is at the very top of the screen.
            const above = rect.y > 18;
            const text = svg('text', {x: rect.x, y: above ? rect.y - 5 : rect.y + 14});
            text.textContent = label;
            group.appendChild(text);
        }
        return group;
    }

    function crosshair(x, y, cls, label) {
        const group = document.createElementNS(SVG_NS, 'g');
        group.setAttribute('class', 'ov ' + cls);
        group.appendChild(svg('line', {x1: x - 12, y1: y, x2: x + 12, y2: y}));
        group.appendChild(svg('line', {x1: x, y1: y - 12, x2: x, y2: y + 12}));
        group.appendChild(svg('circle', {cx: x, cy: y, r: 4}));
        if (label) {
            const text = svg('text', {x: x + 10, y: y - 8});
            text.textContent = label;
            group.appendChild(text);
        }
        return group;
    }

    function render() {
        el.overlay.setAttribute('viewBox', '0 0 ' + state.width + ' ' + state.height);
        el.overlay.replaceChildren();

        if (state.test) {
            renderTestOverlay();
        } else {
            // Template definitions cut from this capture: exact by construction, no matching involved.
            state.crops.forEach(c => el.overlay.appendChild(
                box(c, 'ov-crop', c.templateName)));

            // Templates located by matching. A "best match" is not the same as a match: the
            // resolver always returns its best candidate, so the box is drawn against the
            // template's own threshold — the bar the engine will hold it to.
            state.located.forEach((result, ref) => {
                if (!result.found) return;
                const verdict = judge(result, ref);
                el.overlay.appendChild(box(result, 'ov-' + verdict, ref + '  ' + fmt(result.score)
                    + (verdict === 'ambiguous' ? '  ambiguous' : verdict === 'miss' ? '  under threshold' : '')));
            });

            if (state.anchor && state.anchor.found) {
                el.overlay.appendChild(box(state.anchor, 'ov-anchor',
                    'anchor ' + state.anchor.templateRef + '  ' + fmt(state.anchor.score)));
            }
            if (state.target) {
                el.overlay.appendChild(crosshair(state.target.x, state.target.y, 'ov-target',
                    'target ' + state.target.x + ',' + state.target.y));
            }
            if (state.selection) {
                el.overlay.appendChild(box(state.selection, 'ov-selection',
                    state.selection.width + 'x' + state.selection.height));
            }
        }
        updateControls();
    }

    function renderTestOverlay() {
        const t = state.test;
        if (t.match && (t.match.found || t.match.width > 0)) {
            el.overlay.appendChild(box(t.match, t.resolved ? 'ov-match' : 'ov-miss',
                t.match.templateRef + '  ' + fmt(t.match.score)));
        }
        if (t.region) {
            el.overlay.appendChild(box(t.region, 'ov-target', 'locator target'));
        }
        if (t.clickX !== null && t.clickX !== undefined) {
            el.overlay.appendChild(crosshair(t.clickX, t.clickY, 'ov-target',
                'acts here ' + t.clickX + ',' + t.clickY));
        }
    }

    function updateControls() {
        el.saveTemplate.disabled = !state.selection || state.test !== null;
        const anchorUsable = state.anchor && judge(state.anchor, state.anchor.templateRef) === 'match';
        el.saveLocator.disabled = !(anchorUsable && state.target) || state.test !== null;
        el.hint.textContent = state.test
            ? 'Showing the screenshot the test ran against. The working capture is unchanged.'
            : state.mode === 'template'
                ? 'Drag over a control to select it. Small, distinctive regions match best.'
                : 'Click the point the bot should act on. The offset is measured from the located anchor.';
    }

    // ---- pointer handling ----

    /** Pointer position in capture pixels. */
    function point(event) {
        const rect = el.shot.getBoundingClientRect();
        const x = (event.clientX - rect.left) / rect.width * state.width;
        const y = (event.clientY - rect.top) / rect.height * state.height;
        return {
            x: Math.max(0, Math.min(state.width - 1, Math.round(x))),
            y: Math.max(0, Math.min(state.height - 1, Math.round(y)))
        };
    }

    let dragStart = null;

    el.stage.addEventListener('pointerdown', (event) => {
        if (state.test) return;
        if (state.mode === 'locator') {
            state.target = point(event);
            render();
            return;
        }
        dragStart = point(event);
        state.selection = null;
        el.stage.setPointerCapture(event.pointerId);
    });

    el.stage.addEventListener('pointermove', (event) => {
        if (!dragStart) return;
        const now = point(event);
        state.selection = {
            x: Math.min(dragStart.x, now.x),
            y: Math.min(dragStart.y, now.y),
            width: Math.abs(now.x - dragStart.x),
            height: Math.abs(now.y - dragStart.y)
        };
        render();
    });

    el.stage.addEventListener('pointerup', (event) => {
        if (!dragStart) return;
        dragStart = null;
        el.stage.releasePointerCapture(event.pointerId);
        showSelection();
    });

    function showSelection() {
        const s = state.selection;
        if (!s || s.width < 4 || s.height < 4) {
            state.selection = null;
            el.cropPreview.hidden = true;
            el.selectionReadout.textContent = 'That selection is too small — drag at least 4x4 pixels.';
            render();
            return;
        }
        el.selectionReadout.textContent = s.width + 'x' + s.height + ' at (' + s.x + ',' + s.y + ')';
        // Preview by shifting the full screenshot inside a window the size of the selection: no
        // second request, and it is literally the pixels that will be uploaded.
        el.cropPreview.hidden = false;
        const window_ = el.cropPreview.querySelector('.crop-window');
        window_.style.width = s.width + 'px';
        window_.style.height = s.height + 'px';
        el.cropImg.src = el.shot.src;
        el.cropImg.style.width = state.width + 'px';
        el.cropImg.style.marginLeft = (-s.x) + 'px';
        el.cropImg.style.marginTop = (-s.y) + 'px';
        render();
    }

    document.querySelectorAll('input[name="mode"]').forEach(radio =>
        radio.addEventListener('change', () => {
            state.mode = radio.value;
            updateControls();
        }));

    // ---- templates ----

    async function loadTemplates(refresh) {
        state.templates = await api('GET', '/authoring/api/templates' + (refresh ? '?refresh=true' : ''));
        renderTemplateList();
        fillSelects();
    }

    function renderTemplateList() {
        el.templateList.replaceChildren();
        if (!state.templates.length) {
            const li = document.createElement('li');
            li.className = 'muted';
            li.textContent = 'No templates registered yet. Cut the first one from this screenshot.';
            el.templateList.appendChild(li);
            return;
        }
        state.templates.forEach(t => {
            const li = document.createElement('li');
            const located = state.located.get(t.name);

            const show = document.createElement('input');
            show.type = 'checkbox';
            show.checked = !!located;
            show.addEventListener('change', () => show.checked ? locate([t.name]) : unlocate(t.name));

            const label = document.createElement('label');
            label.appendChild(show);
            label.appendChild(document.createTextNode(' ' + t.name));

            const meta = document.createElement('span');
            meta.className = 'muted meta';
            meta.textContent = ' ' + t.bytes + ' B · threshold ' + t.threshold
                + (t.s1Version ? ' · S1 ' + t.s1Version : '');

            const score = document.createElement('span');
            if (located) {
                const verdict = judge(located, t.name);
                score.className = 'score score-' + (verdict === 'match' ? 'ok' : verdict === 'ambiguous' ? 'warn' : 'bad');
                score.textContent = located.found
                    ? fmt(located.score) + (verdict === 'miss' ? ' < ' + thresholdFor(t.name) : '')
                    : 'not found';
                score.title = located.diagnostic
                    || (verdict === 'miss' ? 'Best match is below this template’s threshold' : '');
            }

            const test = document.createElement('button');
            test.type = 'button';
            test.className = 'link-btn';
            test.textContent = 'Test';
            test.addEventListener('click', () => runTest({
                label: 'template ' + t.name,
                locator: imageLocator({templateRef: t.name, threshold: t.threshold})
            }));

            li.append(label, meta, score, test);
            el.templateList.appendChild(li);
        });
    }

    function fillSelects() {
        [el.anchorSelect, el.sizingSelect].forEach(select => {
            const keep = select.value;
            const first = select.querySelector('option');
            select.replaceChildren(first);
            state.templates.forEach(t => {
                const option = document.createElement('option');
                option.value = t.name;
                option.textContent = t.name;
                select.appendChild(option);
            });
            select.value = keep;
        });
    }

    async function locate(refs) {
        busy(true, 'Matching ' + refs.length + ' template(s) against this screenshot…');
        try {
            const results = await api('POST', '/authoring/api/captures/' + state.captureId + '/locate',
                {templateRefs: refs});
            results.forEach(r => state.located.set(r.templateRef, r));
            const missed = results.filter(r => judge(r, r.templateRef) === 'miss');
            say(missed.length
                ? missed.length + ' of ' + results.length + ' would not resolve here — '
                    + (missed[0].diagnostic || missed[0].templateRef + ' scores only ' + fmt(missed[0].score))
                : results.length + ' template(s) located', missed.length ? 'warn' : 'ok');
            renderTemplateList();
            render();
        } catch (e) {
            say(e.message, 'error');
        } finally {
            busy(false);
        }
    }

    function unlocate(ref) {
        state.located.delete(ref);
        renderTemplateList();
        render();
    }

    el.saveTemplate.addEventListener('click', async () => {
        const s = state.selection;
        if (!s) return;
        busy(true, 'Saving template…');
        try {
            const saved = await api('POST', '/authoring/api/templates', {
                captureId: state.captureId,
                name: el.tplName.value,
                x: s.x, y: s.y, width: s.width, height: s.height,
                threshold: Number(el.tplThreshold.value) || state.defaultThreshold,
                screenContext: el.tplContext.value || null,
                s1Version: el.tplS1.value || state.s1Version
            });
            state.crops = (await api('GET', '/authoring/api/captures/' + state.captureId)).crops;
            state.located.set(saved.templateRef, saved);
            state.selection = null;
            el.cropPreview.hidden = true;
            el.tplName.value = '';
            el.selectionReadout.textContent = 'Saved. Drag another rectangle to define the next template.';
            say('Template "' + saved.templateRef + '" saved to the registry', 'ok');
            await loadTemplates(false);
            render();
        } catch (e) {
            say(e.message, 'error');
        } finally {
            busy(false);
        }
    });

    // ---- offset locators ----

    document.getElementById('locate-anchor').addEventListener('click', async () => {
        const ref = el.anchorSelect.value;
        if (!ref) {
            say('Choose an anchor template first', 'warn');
            return;
        }
        busy(true, 'Locating anchor…');
        try {
            const [result] = await api('POST', '/authoring/api/captures/' + state.captureId + '/locate',
                {templateRefs: [ref]});
            state.anchor = result;
            state.located.set(ref, result);
            const usable = judge(result, ref) === 'match';
            el.anchorReadout.textContent = usable
                ? 'Anchor found at (' + result.x + ',' + result.y + ') scoring ' + fmt(result.score)
                    + '. Now click the target point on the screenshot.'
                : result.found
                    ? 'Best match for the anchor scores only ' + fmt(result.score) + ', under its '
                        + thresholdFor(ref) + ' threshold — capture the screen it is really on, or re-crop it.'
                    : 'Anchor not found on this screenshot: ' + result.diagnostic;
            say(usable ? '' : 'That anchor would not resolve on this screen', usable ? null : 'warn');
            document.querySelector('input[name="mode"][value="locator"]').checked = true;
            state.mode = 'locator';
            renderTemplateList();
            render();
        } catch (e) {
            say(e.message, 'error');
        } finally {
            busy(false);
        }
    });

    el.saveLocator.addEventListener('click', async () => {
        busy(true, 'Deriving and saving the locator…');
        try {
            const saved = await api('POST', '/authoring/api/locators', {
                name: el.locName.value,
                captureId: state.captureId,
                anchorTemplateRef: el.anchorSelect.value,
                sizingTemplateRef: el.sizingSelect.value || null,
                targetX: state.target.x,
                targetY: state.target.y,
                threshold: Number(el.locThreshold.value) || state.defaultThreshold,
                s1Version: state.s1Version
            });
            const d = saved.definition;
            el.locatorJson.hidden = false;
            el.locatorJson.textContent = JSON.stringify({
                type: 'IMAGE',
                anchorTemplateRef: d.anchorTemplateRef,
                templateRef: d.templateRef,
                threshold: d.threshold,
                offsetX: d.offsetX,
                offsetY: d.offsetY
            }, null, 2);
            // Draw what the engine will do with the stored numbers, not the raw click.
            state.target = {x: saved.targetX, y: saved.targetY};
            say('Locator "' + saved.name + '" saved — offset (' + d.offsetX + ',' + d.offsetY
                + ') from anchor "' + d.anchorTemplateRef + '"', 'ok');
            await loadLocators();
            render();
        } catch (e) {
            say(e.message, 'error');
        } finally {
            busy(false);
        }
    });

    async function loadLocators() {
        state.locators = await api('GET', '/authoring/api/locators');
        el.locatorList.replaceChildren();
        if (!state.locators.length) {
            const li = document.createElement('li');
            li.className = 'muted';
            li.textContent = 'No locators saved yet.';
            el.locatorList.appendChild(li);
            return;
        }
        state.locators.forEach(l => {
            const d = l.definition || {};
            const li = document.createElement('li');

            const name = document.createElement('strong');
            name.textContent = l.name;

            const meta = document.createElement('span');
            meta.className = 'muted meta';
            meta.textContent = d.anchorTemplateRef
                ? ' anchor ' + d.anchorTemplateRef + ' offset (' + d.offsetX + ',' + d.offsetY + ')'
                    + (d.templateRef ? ' sized by ' + d.templateRef : '')
                : ' template ' + d.templateRef;

            const status = document.createElement('span');
            status.className = 'status status-' + (l.status === 'VERIFIED' ? 'SUCCEEDED' : 'SKIPPED');
            status.textContent = l.status;

            const show = document.createElement('button');
            show.type = 'button';
            show.className = 'link-btn';
            show.textContent = 'Show here';
            show.addEventListener('click', () => showLocatorOnCapture(l));

            const test = document.createElement('button');
            test.type = 'button';
            test.className = 'link-btn';
            test.textContent = 'Test';
            test.addEventListener('click', () => runTest({
                label: 'locator ' + l.name, locator: d, threshold: d.threshold
            }));

            li.append(name, meta, status, show, test);
            el.locatorList.appendChild(li);
        });
    }

    /**
     * Draw a saved locator on the working capture: its anchor located here, and the point its
     * stored offsets resolve to. Deliberately computed by the same arithmetic the engine uses.
     */
    async function showLocatorOnCapture(locator) {
        const d = locator.definition || {};
        const ref = d.anchorTemplateRef || d.templateRef;
        if (!ref) return;
        busy(true, 'Locating ' + ref + '…');
        try {
            const [result] = await api('POST', '/authoring/api/captures/' + state.captureId + '/locate',
                {templateRefs: [ref]});
            state.located.set(ref, result);
            if (!result.found) {
                say('"' + ref + '" is not on this screenshot: ' + result.diagnostic, 'warn');
                state.target = null;
            } else if (d.anchorTemplateRef) {
                state.anchor = result;
                const ox = Math.round((d.offsetX || 0) * result.factor);
                const oy = Math.round((d.offsetY || 0) * result.factor);
                let w = 1, h = 1;
                if (d.templateRef) {
                    const sizing = state.templates.find(t => t.name === d.templateRef);
                    // Sizing dimensions are not in the template list, so ask the server to locate
                    // it and use the matched extent — the same size the engine derives.
                    if (sizing) {
                        const [sized] = await api('POST',
                            '/authoring/api/captures/' + state.captureId + '/locate',
                            {templateRefs: [d.templateRef]});
                        if (sized.found) { w = sized.width; h = sized.height; }
                    }
                }
                state.target = {x: result.x + ox + Math.floor(w / 2), y: result.y + oy + Math.floor(h / 2)};
                say('"' + locator.name + '" resolves to (' + state.target.x + ',' + state.target.y
                    + ') on this screenshot', 'ok');
            }
            renderTemplateList();
            render();
        } catch (e) {
            say(e.message, 'error');
        } finally {
            busy(false);
        }
    }

    // ---- test against a fresh capture ----

    async function runTest(request) {
        busy(true, 'Capturing a fresh screenshot from ' + state.workerId + ' and resolving…');
        try {
            const result = await api('POST', '/authoring/api/test', {
                workerId: state.workerId,
                activateWindow: el.testActivate.checked,
                label: request.label,
                locator: request.locator,
                threshold: request.threshold || null
            });
            state.test = result;
            // Show the screen the test actually ran against — the point of the exercise.
            el.shot.src = result.capture.imageUrl;
            state.width = result.capture.width;
            state.height = result.capture.height;
            renderTestReport(result);
            el.backToCapture.hidden = false;
            render();
        } catch (e) {
            say(e.message, 'error');
        } finally {
            busy(false);
        }
    }

    function renderTestReport(result) {
        const rows = [];
        rows.push(['Result', result.resolved ? 'RESOLVED' : 'NOT RESOLVED']);
        rows.push(['Tested', result.label]);
        if (result.match) {
            rows.push(['Matched', result.match.templateRef]);
            rows.push(['Score', fmt(result.match.score) + ' (threshold ' + result.threshold + ')']);
            rows.push(['Runner-up', fmt(result.match.runnerUp)]);
            if (result.match.factor !== 1) rows.push(['Matched at scale', 'x' + fmt(result.match.factor)]);
            if (result.match.found) {
                rows.push(['Found at', result.match.x + ',' + result.match.y
                    + ' (' + result.match.width + 'x' + result.match.height + ')']);
            }
        }
        if (result.resolved) rows.push(['Acts at', result.clickX + ',' + result.clickY]);
        rows.push(['Resolution time', result.elapsedMs + ' ms']);

        el.testResult.hidden = false;
        el.testResult.className = 'test-result ' + (result.resolved ? 'ok' : 'bad');
        el.testResult.replaceChildren();
        const table = document.createElement('table');
        table.className = 'kv';
        rows.forEach(([k, v]) => {
            const tr = document.createElement('tr');
            const th = document.createElement('th');
            th.textContent = k;
            const td = document.createElement('td');
            td.textContent = v;
            tr.append(th, td);
            table.appendChild(tr);
        });
        el.testResult.appendChild(table);
        if (result.diagnostic) {
            const why = document.createElement('p');
            why.className = 'err';
            why.textContent = result.diagnostic;
            el.testResult.appendChild(why);
        }
        say(result.resolved
            ? 'Resolved on a fresh screenshot in ' + result.elapsedMs + ' ms'
            : 'Did not resolve on a fresh screenshot', result.resolved ? 'ok' : 'warn');
    }

    el.backToCapture.addEventListener('click', () => {
        state.test = null;
        el.backToCapture.hidden = true;
        el.shot.src = root.dataset.imageUrl;
        state.width = Number(root.dataset.width);
        state.height = Number(root.dataset.height);
        say('');
        render();
    });

    document.getElementById('recapture').addEventListener('click', async () => {
        busy(true, 'Capturing from ' + state.workerId + '…');
        try {
            const capture = await api('POST', '/authoring/api/captures',
                {workerId: state.workerId, activateWindow: el.testActivate.checked});
            window.location.href = '/captures/' + capture.id;
        } catch (e) {
            say(e.message, 'error');
            busy(false);
        }
    });

    document.getElementById('locate-all').addEventListener('click', () => {
        if (state.templates.length) locate(state.templates.map(t => t.name));
    });
    document.getElementById('refresh-templates').addEventListener('click', () => loadTemplates(true));
    document.getElementById('refresh-locators').addEventListener('click', () => loadLocators());

    // ---- start ----

    el.tplThreshold.value = state.defaultThreshold;
    el.locThreshold.value = state.defaultThreshold;
    el.tplS1.value = state.s1Version;

    (async function init() {
        try {
            const capture = await api('GET', '/authoring/api/captures/' + state.captureId);
            state.crops = capture.crops;
            await loadTemplates(false);
            await loadLocators();
            render();
        } catch (e) {
            say(e.message, 'error');
        }
    })();
})();
