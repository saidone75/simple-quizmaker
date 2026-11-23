# API REST

Documentazione delle API di Alice's Simple Quiz Maker. Torna al [README](README.md).

**Quiz (`/api/quizzes`)**

- `GET /api/quizzes` elenco quiz pubblicati per studente autenticato.
- `GET /api/quizzes/{id}` dettaglio quiz pubblicato.
- `GET /api/quizzes/{id}/review` riepilogo della propria consegna: punteggio, data, `version`, `currentVersion` e `obsolete`. Per una consegna obsoleta, `answers` è vuoto e la revisione delle risposte non è disponibile.
- `POST /api/quizzes/{id}/submit` invio risposte studente.
- `POST /api/quizzes` creazione quiz (insegnante).
- `PUT /api/quizzes/{id}` modifica quiz non pubblicato (insegnante). Se il quiz è pubblicato per la classe, restituisce HTTP 400: spubblicarlo prima della modifica. L’accesso pubblico non impedisce le modifiche.
- `DELETE /api/quizzes/{id}` eliminazione quiz (insegnante).
- `PUT /api/quizzes/{id}/public-access` attiva/disattiva il link pubblico (l’attivazione richiede la preferenza `publicQuizzesEnabled` nel profilo) con body `{"enabled": true|false}` (solo proprietario).
- `PUT /api/quizzes/{id}/publication` pubblicazione/depubblicazione.
- `PUT /api/quizzes/{id}/archived` archiviazione/riattivazione quiz.
- `POST /api/quizzes/{id}/share` condivisione quiz a più insegnanti.
- `POST /api/quizzes/{quizId}/unlock/{studentId}` sblocco tentativo singolo.
- `POST /api/quizzes/{quizId}/unlock-all` sblocco massivo tentativi.
- `POST /api/quizzes/generate` generazione quiz via AI (multipart, allegato opzionale, topic anche da URL Wikipedia).
- `POST /api/quizzes/images` upload immagine domanda (insegnante con upload abilitato).
- `GET /api/quizzes/images/{imageId}` download consentito al proprietario o agli insegnanti con un quiz che usa l'immagine; gli studenti accedono solo tramite quiz pubblicati e non archiviati del proprio insegnante.
- `DELETE /api/quizzes/images/{imageId}` eliminazione consentita al proprietario (per immagini precedenti alla migrazione, all'insegnante del quiz che le usa), purché nessun quiz salvato usi l'immagine. Accessi non consentiti restituiscono HTTP 403.

Il quiz espone `version`, inizialmente 1, incrementata quando cambia il contenuto delle domande.
La consegna salva la versione attuale sul server; non è richiesto un numero di versione nella richiesta di invio.
Modifiche a titolo, badge o pubblicazione non incrementano la versione.

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

**Quiz pubblici**

- `GET /play/quizzes/{id}` player anonimo per un quiz con `publicAccess=true`, non archiviato e appartenente a un insegnante abilitato; altrimenti HTTP 404.
- `GET /play/quizzes/{quizId}/images/{imageId}` immagine locale solo se referenziata da quel quiz pubblico; altrimenti HTTP 404.
- `GET /teacher/quiz/{id}/public` configurazione del link (proprietario).
- `POST /teacher/quiz/{id}/public` salva l’impostazione `enabled` tramite form con CSRF.
- `GET /teacher/quiz/{id}/public-qr` QR code PNG del link attivo (proprietario), generato localmente.

Il player pubblico non usa l’endpoint `/submit`: calcola il punteggio nel browser e non crea partecipazioni, consegne, tentativi o badge. Il DTO pubblico contiene soltanto ID, titolo, emoji e domande, senza dati dell’insegnante. L’archiviazione revoca l’accesso pubblico; la riattivazione non lo ripristina automaticamente.

- `POST /teacher/profile/public-quizzes` aggiorna la preferenza personale `publicQuizzesEnabled` tramite form con CSRF; il pulsante Accesso pubblico è visibile solo se la preferenza è attiva. I link già pubblicati non vengono modificati.
