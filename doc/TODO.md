# TODO

1. [x] **P1 — Eliminare l'accumulo permanente delle chiavi nel rate limiter.**
   Sostituite le mappe con cache Caffeine: massimo 10.000 chiavi ciascuna, scadenza dopo 6 minuti per i login e 11 minuti per le registrazioni, pulizia ogni minuto. Conteggio delle registrazioni atomico per chiave. Test su scadenza, limite di dimensione, durata dei blocchi e richieste concorrenti. In caso di saturazione, l'evizione per capienza può rimuovere stati ancora attivi.

2. [x] **P2 — Chiudere lo stream degli upload immagini.**
   Aggiunto `try-with-resources` in `QuestionImageStorageService.store()`. Verificata la chiusura degli stream di validazione e copia sia dopo un upload riuscito sia in caso di errore di lettura, senza salvare il record nel database quando la copia fallisce.

3. [x] **P2 — Limitare la coda delle ricerche immagini e gestire le attese.**
   Pool gestito dal servizio con 3 worker e massimo 60 attività in coda. Budget complessivo di 5 minuti per quiz, configurabile con `AI_IMAGE_SEARCH_TIMEOUT`. A coda piena si omettono le ricerche non accettate; a scadenza o interruzione si cancellano le attività residue e si rimuovono quelle annullate dalla coda. I worker non modificano il quiz restituito. Aggiunti test per saturazione, timeout, risultati tardivi, interruzione e richieste concorrenti.

4. [ ] **P2 — Elaborare a lotti la pulizia notturna delle immagini.**
   In `OrphanQuestionImageCleanupScheduler.cleanupOrphanImages()`, `quizRepository.findAll()` materializza tutti i quiz, comprese le domande JSON e i quiz archiviati; successivamente `uploadedImageRepository.findAll()` carica tutte le immagini. Il picco di memoria cresce con l'intero archivio e può causare GC intenso o esaurimento dell'heap. Elaborare i dati a pagine o con proiezioni adeguate, liberando ogni lotto e preservando il controllo globale dei riferimenti alle immagini.

5. [ ] **P2 — Limitare l'estrazione del testo durante la lettura degli allegati.**
   In `DocumentTextExtractorService`, PDF e DOCX vengono caricati e convertiti integralmente in testo; il taglio a 60.000 caratteri avviene soltanto dopo, in `QuizGenerationApplicationService`. Il limite multipart di 8 MB non limita la dimensione del documento decompresso o delle strutture del parser. Allegati complessi e richieste simultanee possono produrre picchi importanti. Introdurre limiti durante l'estrazione e verificare il consumo con documenti compressi, voluminosi e concorrenti.
