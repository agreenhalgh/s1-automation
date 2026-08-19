/* Capture-tool landing page: request a screenshot from a worker, or discard held ones. */
(function () {
    'use strict';

    const csrfToken = document.querySelector('meta[name="csrf-token"]').content;
    const csrfHeader = document.querySelector('meta[name="csrf-header"]').content;
    const status = document.getElementById('capture-status');

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
            throw new Error(payload && payload.error ? payload.error : method + ' ' + url + ' failed');
        }
        return payload;
    }

    function report(message, isError) {
        status.textContent = message;
        status.className = isError ? 'err' : 'muted';
    }

    document.querySelectorAll('.capture-btn').forEach(button =>
        button.addEventListener('click', async () => {
            const workerId = button.dataset.worker;
            button.disabled = true;
            report('Asking ' + workerId + ' for a screenshot…', false);
            try {
                const capture = await api('POST', '/authoring/api/captures', {
                    workerId,
                    activateWindow: document.getElementById('activate-window').checked
                });
                window.location.href = '/captures/' + capture.id;
            } catch (e) {
                report(e.message, true);
                button.disabled = false;
            }
        }));

    document.querySelectorAll('.discard-btn').forEach(button =>
        button.addEventListener('click', async () => {
            try {
                await api('DELETE', '/authoring/api/captures/' + button.dataset.capture);
                window.location.reload();
            } catch (e) {
                report(e.message, true);
            }
        }));

    const discardAll = document.getElementById('discard-all');
    if (discardAll) {
        discardAll.addEventListener('click', async () => {
            try {
                await api('DELETE', '/authoring/api/captures');
                window.location.reload();
            } catch (e) {
                report(e.message, true);
            }
        });
    }
})();
