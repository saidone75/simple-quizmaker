(function () {
    const list = document.getElementById('quiz-badges-list');
    const addButton = document.getElementById('add-quiz-badge-btn');
    const emptyMessage = document.getElementById('quiz-badges-empty');
    if (!list || !addButton) return;

    function updateControls() {
        addButton.disabled = list.children.length >= 8;
        emptyMessage.hidden = list.children.length !== 0;
    }

    function addBadge(badge) {
        if (list.children.length >= 8) return;
        const id = badge?.id || (globalThis.crypto?.randomUUID?.()
            ?? `badge-${Date.now()}-${Math.random().toString(36).slice(2)}`);
        const row = document.createElement('div');
        row.className = 'quiz-badge-editor-row';
        row.dataset.badgeId = id;

        const fields = [
            { key: 'name', label: 'Nome del premio', type: 'text', maxLength: 80,
                placeholder: 'Genio dei quiz', value: badge?.name || '' },
            { key: 'emoji', label: 'Emoji', type: 'text', maxLength: 16,
                placeholder: '🗺️', value: badge?.emoji || '🏅' },
            { key: 'minimumPercentage', label: 'Risposte corrette (%)', type: 'number',
                value: badge?.minimumPercentage ?? 80 }
        ];
        for (const field of fields) {
            const wrapper = document.createElement('div');
            const label = document.createElement('label');
            const input = document.createElement('input');
            input.id = `badge-${id}-${field.key}`;
            input.dataset.field = field.key;
            input.type = field.type;
            input.value = field.value;
            input.className = 'input-field';
            input.required = true;
            if (field.maxLength) input.maxLength = field.maxLength;
            if (field.placeholder) input.placeholder = field.placeholder;
            if (field.type === 'number') {
                input.min = '0';
                input.max = '100';
                input.step = '1';
            }
            label.htmlFor = input.id;
            label.className = 'q-row-label';
            label.textContent = field.label;
            wrapper.append(label, input);
            row.appendChild(wrapper);
        }
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'btn-icon-action btn-delete-quiz';
        remove.textContent = '🗑';
        remove.setAttribute('aria-label', 'Rimuovi questo premio');
        remove.addEventListener('click', () => {
            row.remove();
            updateControls();
        });
        row.appendChild(remove);
        list.appendChild(row);
        updateControls();
        return row;
    }

    const initial = JSON.parse(document.getElementById('quiz-editor-data')?.dataset.initialBadgesJson || '[]');
    initial.forEach(addBadge);
    updateControls();
    addButton.addEventListener('click', () => {
        addBadge()?.querySelector('input')?.focus();
    });

    globalThis.readQuizBadges = function () {
        return Array.from(list.children).map(row => {
            for (const input of row.querySelectorAll('input')) {
                if (!input.checkValidity() || !input.value.trim()) {
                    input.focus();
                    input.reportValidity();
                    throw new Error('Completa ogni badge con nome, emoji e una soglia intera tra 0 e 100.');
                }
            }
            return {
                id: row.dataset.badgeId,
                name: row.querySelector('[data-field="name"]').value.trim(),
                emoji: row.querySelector('[data-field="emoji"]').value.trim(),
                minimumPercentage: Number(row.querySelector('[data-field="minimumPercentage"]').value)
            };
        });
    };

    const generateButton = document.getElementById('generate-quiz-badges-btn');
    const generationMessage = document.getElementById('badge-generation-msg');
    generateButton?.addEventListener('click', async () => {
        const title = document.getElementById('quiz-title-input').value.trim();
        generationMessage.hidden = false;
        if (!title) {
            generationMessage.textContent = 'Inserisci prima il titolo del quiz.';
            return;
        }
        if (list.children.length >= 8) {
            generationMessage.textContent = 'Hai già 8 premi. Rimuovine qualcuno per aggiungere le proposte AI.';
            return;
        }
        generateButton.disabled = true;
        const saveButton = document.getElementById('save-btn');
        if (saveButton) saveButton.disabled = true;
        generationMessage.textContent = 'Sto preparando premi a tema, anche per l’impegno…';
        try {
            const questionTexts = (typeof currentQuestions !== 'undefined' ? currentQuestions : [])
                .map(q => q.text.trim()).filter(Boolean).slice(0, 30).map(text => text.slice(0, 500));
            const response = await apiFetch('/api/quizzes/badges/generate', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ title: title.slice(0, 200), questionTexts })
            });
            const payload = await response.json();
            if (!response.ok) throw new Error(payload.message || 'Non riesco a generare i premi. Riprova tra poco.');
            if (!Array.isArray(payload)) throw new Error('Le proposte ricevute non sono valide.');
            const available = 8 - list.children.length;
            const proposals = payload.slice(0, available);
            proposals.forEach(addBadge);
            generationMessage.textContent = `${proposals.length} proposte aggiunte: controlla nomi e soglie prima di salvare.`
                + (payload.length > available ? ' È stato raggiunto il limite di 8 premi.' : '');
        } catch (error) {
            generationMessage.textContent = error.message || 'Non riesco a generare i premi. Puoi crearli manualmente.';
        } finally {
            generateButton.disabled = false;
            if (saveButton) saveButton.disabled = false;
        }
    });
})();
