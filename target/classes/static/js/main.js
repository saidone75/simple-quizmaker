// ===== SCREEN NAVIGATION =====
function goTo(screenId) {
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
    const target = document.getElementById('screen-' + screenId);
    if (target) target.classList.add('active');
}

// ===== LOADING OVERLAY =====
function showLoading(msg) {
    let el = document.getElementById('loading-overlay');
    if (!el) {
        el = document.createElement('div');
        el.id = 'loading-overlay';
        el.innerHTML = '<div class="loading-icon">⏳</div><p></p>';
        document.body.appendChild(el);
    }
    el.querySelector('p').textContent = msg || 'Caricamento...';
    el.style.display = 'flex';
}

function hideLoading() {
    const el = document.getElementById('loading-overlay');
    if (el) el.style.display = 'none';
}

// ===== TOAST =====
function showToast(msg) {
    let t = document.getElementById('toast');
    if (!t) {
        t = document.createElement('div');
        t.id = 'toast';
        document.body.appendChild(t);
    }
    t.textContent = '✅ ' + msg;
    t.style.opacity = '1';
    setTimeout(() => { t.style.opacity = '0'; }, 2500);
}

// ===== HTML ESCAPE =====
function escHtml(str) {
    return (str || '')
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}
