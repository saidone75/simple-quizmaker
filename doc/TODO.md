# TODO

- [x] **P1 — Eliminare l'accumulo permanente delle chiavi nel rate limiter.**
  Sostituite le mappe con cache Caffeine: massimo 10.000 chiavi ciascuna, scadenza dopo 6 minuti per i login e 11 minuti per le registrazioni, pulizia ogni minuto. Conteggio delle registrazioni atomico per chiave. Test su scadenza, limite di dimensione, durata dei blocchi e richieste concorrenti. In caso di saturazione, l'evizione per capienza può rimuovere stati ancora attivi.

- [ ] **P2 — Chiudere lo stream degli upload immagini.**
  In `QuestionImageStorageService.store()`, `Files.copy(file.getInputStream(), ...)` lascia aperto lo stream ricevuto. Con multipart su disco, upload ripetuti possono trattenere descrittori e interferire con la pulizia dei file temporanei fino al recupero delle risorse. Usare `try-with-resources` e verificare la chiusura anche in caso di errore nella copia.

- [ ] **P2 — Limitare la coda delle ricerche immagini e gestire le attese.**
  In `OpenAiQuizGenerationService`, `Executors.newFixedThreadPool(3)` usa una coda senza limite. Con Wikimedia lento e più generazioni contemporanee, possono accumularsi attività, domande e richieste in attesa. `task.get()` non impone una scadenza complessiva e l'interruzione non cancella i lavori pendenti. Introdurre una coda limitata, un comportamento esplicito in caso di saturazione, una scadenza complessiva e la cancellazione delle attività residue. Verificare il comportamento con ricerche lente e richieste concorrenti.

- [ ] **P2 — Elaborare a lotti la pulizia notturna delle immagini.**
  In `OrphanQuestionImageCleanupScheduler.cleanupOrphanImages()`, `quizRepository.findAll()` materializza tutti i quiz, comprese le domande JSON e i quiz archiviati; successivamente `uploadedImageRepository.findAll()` carica tutte le immagini. Il picco di memoria cresce con l'intero archivio e può causare GC intenso o esaurimento dell'heap. Elaborare i dati a pagine o con proiezioni adeguate, liberando ogni lotto e preservando il controllo globale dei riferimenti alle immagini.

- [ ] **P2 — Limitare l'estrazione del testo durante la lettura degli allegati.**
  In `DocumentTextExtractorService`, PDF e DOCX vengono caricati e convertiti integralmente in testo; il taglio a 60.000 caratteri avviene soltanto dopo, in `QuizGenerationApplicationService`. Il limite multipart di 8 MB non limita la dimensione del documento decompresso o delle strutture del parser. Allegati complessi e richieste simultanee possono produrre picchi importanti. Introdurre limiti durante l'estrazione e verificare il consumo con documenti compressi, voluminosi e concorrenti.
