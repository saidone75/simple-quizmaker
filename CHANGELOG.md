# Changelog

Tutte le modifiche rilevanti a questo progetto saranno documentate in questo file.

Il formato è basato su [Keep a Changelog](https://keepachangelog.com/it-IT/1.1.0/),
e questo progetto aderisce al [Semantic Versioning](https://semver.org/lang/it/).

Le release sono elencate dalla più recente alla più vecchia; date e contenuti
si riferiscono ai tag Git. Le modifiche successive all'ultimo tag sono raccolte
in **Non rilasciato**.

## [Non rilasciato]

### Corretto

- Risolto il conflitto di convergenza tra Liquibase e Apache POI allineando `commons-collections4` alla versione 4.6.0.

## [1.2.0] - 2026-10-25

### Aggiunto

- Selezione del formato dei quiz: scelta multipla con 2, 3 o 4 risposte oppure vero/falso.

### Modificato

- Migliorata la spaziatura dei campi nell'editor dei quiz.
- Semplificato il codice Java e JavaScript.
- Aggiornato il nome dell'applicazione Spring da `quizmaker` a `simple-quizmaker`.
- Aggiornati Deep Java Library (DJL) alla versione 0.39.0, PyTorch JNI alla versione 2.7.1-0.39.0 e Apache POI alla versione 6.0.0.
- Aggiornati Error Prone Annotations alla versione 2.50.0, Versions Maven Plugin alla versione 2.23.0 e il workflow CodeQL alla versione 4.39.0.

### Corretto

- Creazione di backup SQLite coerenti tramite `VACUUM INTO`.
- Resa esplicita la gestione delle Promise di visualizzazione dei risultati nel player dei quiz.

## [1.1.0] - 2026-10-13

### Aggiunto

- Badge studente per risultati, percorso e costanza, con premi personalizzabili per ogni quiz.
- Generazione dei badge assistita dall'AI e raccolta personale consultabile dallo studente e dal suo insegnante.
- Pagina pubblica `/discover` per la sezione Discover.
- Metadati SEO, `robots.txt` e `sitemap.xml`.

### Modificato

- Aggiornata la pagina di login e uniformati footer, colori dei temi e stile della pagina Discover.
- Aggiornate le dipendenze e gli strumenti di build e CI.
- Ampliata la documentazione delle funzionalità e della configurazione.

### Corretto

- Migliorata la normalizzazione delle parole chiave per le immagini nella generazione AI dei quiz.
- Aggiornate le verifiche del reindirizzamento al login per i badge studente.

## [1.0.5] - 2026-10-10

### Modificato

- Aggiornato il modello OpenAI predefinito a `gpt-6-luna` e rimosso il parametro `temperature` dalle richieste di generazione quiz.
- Aggiornato Apache Tika alla versione 4.1.0 e aggiornati i workflow CI.

### Corretto

- Corretti i controlli di autorizzazione e proprietà delle immagini delle domande nei quiz condivisi, inclusa la pulizia delle immagini orfane.

## [1.0.4] - 2026-09-20

### Modificato

- Aggiornata Deep Java Library (DJL) alla versione 0.38.0.

## [1.0.3] - 2026-08-26

### Modificato

- Aggiornati Spring Boot alla versione 4.1.1 e Apache Tika alla versione 4.0.0.

### Corretto

- Reso esplicito il fuso orario di sistema per le date di quiz, condivisioni, backup e risposte di errore.

## [1.0.2] - 2026-08-11

### Modificato

- Aggiornati Spring Boot alla versione 4.1.0, Apache Tika alla versione 3.3.2, PDFBox alla versione 3.0.8 e Datafaker alla versione 2.7.0.
- Aggiornato Maven Enforcer Plugin alla versione 3.6.3.

## [1.0.1] - 2026-05-24

### Modificato

- Migliorata l'animazione del coniglio durante il caricamento.

### Corretto

- Impostata la data di ultima modifica dei file di backup al momento della loro creazione.

## [1.0.0] - 2026-05-07

### Aggiunto

- Prima release pubblica di **Alice's Simple Quiz Maker**.
- Area insegnante con autenticazione, dashboard e gestione del profilo.
- Editor quiz con gestione di domande e opzioni di risposta.
- Area studente per accesso e compilazione dei quiz.
- Gestione studenti, tracciamento risultati e revisione tentativi quiz.
- API REST per quiz, studenti e sottomissioni.
- Generazione quiz assistita dall'AI tramite integrazione OpenAI (opzionale).
- Estrazione testo da documenti per supportare la generazione assistita dei quiz.
- Supporto alla ricerca immagini Wikimedia (semplice o semantica).
- Supporto al caricamento e alla gestione immagini delle domande.
- Protezioni di login (rate limiting e mitigazione brute-force).
- Supporto opzionale a Cloudflare Turnstile CAPTCHA.
- Migrazioni database e versionamento schema con Liquibase.
- Profili di esecuzione per ambienti `dev`, `docker` e `prod`.
- Backup schedulato di SQLite per l'ambiente di produzione.

### Stack tecnologico

- Java 21, Spring Boot 4, Spring Security e Thymeleaf.
- JPA/Hibernate e Liquibase.
- Database H2 per `dev`/`docker`, SQLite per `prod`.
- Runtime embedded Jetty.

[Non rilasciato]: https://github.com/saidone75/simple-quizmaker/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/saidone75/simple-quizmaker/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/saidone75/simple-quizmaker/compare/v1.0.5...v1.1.0
[1.0.5]: https://github.com/saidone75/simple-quizmaker/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/saidone75/simple-quizmaker/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/saidone75/simple-quizmaker/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/saidone75/simple-quizmaker/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/saidone75/simple-quizmaker/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/saidone75/simple-quizmaker/releases/tag/v1.0.0
