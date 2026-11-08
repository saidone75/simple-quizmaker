# API REST

Documentazione delle API di Alice's Simple Quiz Maker. Torna al [README](README.md).

**Quiz (`/api/quizzes`)**

- `GET /api/quizzes` elenco quiz pubblicati per studente autenticato.
- `GET /api/quizzes/{id}` dettaglio quiz pubblicato.
- `POST /api/quizzes/{id}/submit` invio risposte studente.
- `POST /api/quizzes` creazione quiz (insegnante).
- `PUT /api/quizzes/{id}` modifica quiz (insegnante).
- `DELETE /api/quizzes/{id}` eliminazione quiz (insegnante).
- `PUT /api/quizzes/{id}/publication` pubblicazione/depubblicazione.
- `PUT /api/quizzes/{id}/archived` archiviazione/riattivazione quiz.
- `POST /api/quizzes/{id}/share` condivisione quiz a più insegnanti.
- `POST /api/quizzes/{quizId}/unlock/{studentId}` sblocco tentativo singolo.
- `POST /api/quizzes/{quizId}/unlock-all` sblocco massivo tentativi.
- `POST /api/quizzes/generate` generazione quiz via AI (multipart, allegato opzionale, topic anche da URL Wikipedia).
- `POST /api/quizzes/images` upload immagine domanda (insegnante con upload abilitato).
- `GET /api/quizzes/images/{imageId}` download consentito al proprietario o agli insegnanti con un quiz che usa l'immagine; gli studenti accedono solo tramite quiz pubblicati e non archiviati del proprio insegnante.
- `DELETE /api/quizzes/images/{imageId}` eliminazione consentita al proprietario (per immagini precedenti alla migrazione, all'insegnante del quiz che le usa), purché nessun quiz di altri insegnanti usi l'immagine. Accessi non consentiti restituiscono HTTP 403.

La condivisione di un quiz duplica anche i record delle immagini caricate:
ogni destinatario riceve UUID e proprietà separati, mantenendo lo stesso file
binario su disco. Eliminare un record immagine non cancella le altre copie;
il file viene rimosso soltanto dopo la cancellazione dell'ultimo record che
lo usa. Le immagini esterne mantengono il loro URL. Le copie di quiz già
condivise non vengono modificate retroattivamente.

**Badge dei quiz (`/api/quizzes`)**

- `POST /api/quizzes/badges/generate` propone badge a tema via AI da titolo e domande (insegnante con AI abilitata), senza salvarli.
- `POST /api/quizzes` e `PUT /api/quizzes/{id}` salvano il campo `badges`: fino a 8 premi con nome, emoji e soglia 0–100%; `[]` disabilita i premi specifici.
- `GET /api/quizzes` e `GET /api/quizzes/{id}` includono le definizioni dei badge.
- `POST /api/quizzes/{id}/submit` restituisce i premi appena guadagnati nel campo `newBadges`.

**Studenti (`/api/students`)**

- `GET /api/students` elenco studenti dell'insegnante corrente.
- `POST /api/students` creazione studente.
- `DELETE /api/students/{id}` eliminazione studente.
- `POST /api/students/{id}/regenerate-password` rigenera parola chiave singolo studente.
- `POST /api/students/regenerate-passwords` rigenera parole chiave in massa.

**Log (`/api/teacher/logs`)**

- `GET /api/teacher/logs/tail?lines=200` ultime righe log applicazione (max 1000, solo amministratore).
