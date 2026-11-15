/*
 * Alice's Simple Quiz Maker - fun quizzes for curious minds
 * Copyright (C) 2026 Miss Alice & Saidone
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

(() => {
    const screen = document.getElementById('screen-result');
    const confetti = document.getElementById('result-confetti');
    if (!screen || !confetti) return;

    const sounds = globalThis.createGameSounds?.({ storageKey: null });
    const reducedMotion = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)');
    let cleanupTimer;

    function clearConfetti() {
        clearTimeout(cleanupTimer);
        confetti.replaceChildren();
    }

    function stop() {
        sounds?.stop();
        clearConfetti();
    }

    function play() {
        stop();
        if (document.hidden || !screen.classList.contains('active')) return;
        sounds?.play('complete');
        if (reducedMotion?.matches) return;

        const colors = ['--amber', '--blue', '--green', '--red', '--green-dark'];
        const distance = confetti.parentElement.getBoundingClientRect().height + 40;
        confetti.style.setProperty('--confetti-distance', `${distance}px`);
        for (let i = 0; i < 32; i++) {
            const piece = document.createElement('span');
            piece.style.setProperty('--confetti-x', `${Math.random() * 100}%`);
            piece.style.setProperty('--confetti-delay', `${Math.random() * .7}s`);
            piece.style.setProperty('--confetti-color', `var(${colors[i % colors.length]})`);
            piece.style.setProperty('--confetti-turn', `${Math.random() * 720 - 360}deg`);
            confetti.appendChild(piece);
        }
        cleanupTimer = setTimeout(clearConfetti, 4000);
    }

    new MutationObserver(() => {
        if (!screen.classList.contains('active')) stop();
    }).observe(screen, { attributes: true, attributeFilter: ['class'] });
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) stop();
    });
    globalThis.addEventListener('pagehide', stop);
    reducedMotion?.addEventListener('change', () => {
        if (reducedMotion.matches) clearConfetti();
    });

    globalThis.quizCelebration = { unlock: () => sounds?.unlock(), play, stop };
})();
