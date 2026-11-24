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

(function () {
    const qrPanel = document.getElementById('login-qr-panel');
    const qrTrigger = document.getElementById('login-qr-trigger');
    const qrImage = document.getElementById('login-qr-image');
    const qrError = document.getElementById('login-qr-error');
    const setQrVisible = (visible) => {
        qrPanel.hidden = !visible;
        qrTrigger.setAttribute('aria-expanded', String(visible));
        qrTrigger.setAttribute('aria-label', visible ? 'Nascondi QR code' : 'Mostra il QR code per accedere al sito');
        qrTrigger.title = visible ? 'Nascondi QR code' : 'Mostra QR code';
    };
    qrTrigger?.addEventListener('click', () => {
        const opening = qrPanel.hidden;
        if (opening) {
            qrError.hidden = true;
            qrImage.hidden = false;
            qrImage.src = qrImage.dataset.src;
        }
        setQrVisible(opening);
    });
    document.getElementById('login-qr-close')?.addEventListener('click', () => {
        setQrVisible(false);
        qrTrigger.focus();
    });
    qrImage?.addEventListener('error', () => {
        qrImage.hidden = true;
        qrError.hidden = false;
    });

    const form = document.getElementById('student-login-form');
    const hiddenKeyword = document.getElementById('keyword-hidden');
    const otpInputs = Array.from(document.querySelectorAll('.otp-input'));

    const syncKeyword = () => {
        hiddenKeyword.value = otpInputs.map((input) => input.value).join('');
    };

    const maybeAutoSubmit = () => {
        syncKeyword();
        if (hiddenKeyword.value.length === 5) {
            form.requestSubmit();
        }
    };

    otpInputs.forEach((input, index) => {
        input.addEventListener('input', (event) => {
            const normalized = event.target.value.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
            event.target.value = normalized.slice(-1);
            syncKeyword();

            if (event.target.value && index < otpInputs.length - 1) {
                otpInputs[index + 1].focus();
            }

            if (index === otpInputs.length - 1) {
                maybeAutoSubmit();
            }
        });

        input.addEventListener('keydown', (event) => {
            if (event.key === 'Backspace' && !input.value && index > 0) {
                otpInputs[index - 1].focus();
            }
        });

        input.addEventListener('paste', (event) => {
            event.preventDefault();
            const chars = (event.clipboardData?.getData('text') || '')
                .replace(/[^a-zA-Z0-9]/g, '')
                .toUpperCase()
                .slice(0, otpInputs.length)
                .split('');

            otpInputs.forEach((otpInput, otpIndex) => {
                otpInput.value = chars[otpIndex] || '';
            });

            syncKeyword();

            const nextIndex = Math.min(chars.length, otpInputs.length - 1);
            otpInputs[nextIndex].focus();
            if (chars.length === otpInputs.length) {
                form.requestSubmit();
            }
        });
    });

    form?.addEventListener('submit', () => {
        hiddenKeyword.value = otpInputs.map((input) => input.value).join('');
    });

    otpInputs[0]?.focus();
})();
