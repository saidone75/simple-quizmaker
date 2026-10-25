#!/usr/bin/env bb
;; Alice's Simple Quiz Maker - Copyright (C) 2026 Miss Alice & Saidone
;; SPDX-License-Identifier: GPL-3.0-or-later

(require '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn env [key default] (or (System/getenv key) default))
(def base-url (str/replace (env "BASE_URL" "http://localhost:8080") #"/+$" ""))
(def workdir (.toFile (java.nio.file.Files/createTempDirectory "quizmaker-smoke-" (make-array java.nio.file.attribute.FileAttribute 0))))
(def app-log (io/file workdir "app.log"))
(def app (atom nil))
(def created (atom {}))

(defn check! [condition message]
  (when-not condition (throw (ex-info message {}))))

(defn request! [session method path & [options]]
  (let [{:keys [form data headers]} options
        cookie (str (io/file workdir (str (name session) ".cookies")))
        body-file (str (io/file workdir (str (random-uuid) ".body")))
        args (vec (concat ["curl" "--silent" "--show-error" "--connect-timeout" "3"
                           "--max-time" "15" "--cookie" cookie "--cookie-jar" cookie
                           "--output" body-file "--write-out" "%{http_code}"
                           "--request" (name method)]
                          (mapcat (fn [[k v]] ["--header" (str k ": " v)]) headers)
                          (when form (mapcat (fn [[k v]] ["--data-urlencode" (str (name k) "=" v)]) form))
                          (when data ["--header" "Content-Type: application/json" "--data-binary" (json/generate-string data)])
                          [(str base-url path)]))
        result @(process/process args {:out :string :err :string})]
    (check! (zero? (:exit result)) (str method " " path ": connessione HTTP fallita"))
    (let [status (parse-long (:out result))
          body (slurp body-file)]
      (io/delete-file body-file)
      (check! (<= 200 status 399) (str method " " path ": HTTP " status))
      {:status status :body body})))

(defn attribute [tag attr]
  (second (re-find (re-pattern (str "\\b" attr "=[\"']([^\"']*)[\"']")) tag)))

(defn hidden-csrf [html]
  (or (some #(when (= "_csrf" (attribute % "name")) (attribute % "value"))
            (re-seq #"<input\b[^>]*>" html))
      (throw (ex-info "Token CSRF del form assente" {}))))

(defn csrf-headers [session page]
  (let [html (:body (request! session :GET page))
        metas (into {} (map (fn [tag] [(attribute tag "name") (attribute tag "content")])
                           (re-seq #"<meta\b[^>]*>" html)))
        token (get metas "quizmaker-csrf-token")
        header (get metas "quizmaker-csrf-header")]
    (check! (and (seq token) (seq header)) (str "Meta CSRF assenti: " page))
    {header token "Accept" "application/json"}))

(defn api! [session method path & [data]]
  (let [response (request! session method path
                           {:headers (csrf-headers session (if (= session :teacher) "/teacher/students" "/"))
                            :data data})]
    (check! (<= 200 (:status response) 299) (str method " " path ": redirect inatteso"))
    (when (seq (:body response))
      (try (json/parse-string (:body response) true)
           (catch Exception _ (throw (ex-info (str method " " path ": risposta non JSON") {})))))))

(defn wait-for-app! []
  (let [deadline (+ (System/currentTimeMillis) 90000)]
    (loop []
      (let [ready? (try (= 200 (:status (request! :teacher :GET "/teacher/login")))
                        (catch Exception _ false))]
        (when-not ready?
          (check! (< (System/currentTimeMillis) deadline) "Applicazione non disponibile entro 90 secondi")
          (Thread/sleep 1000)
          (recur))))))

(defn start-app! []
  (when (= "1" (env "START_APP" "1"))
    (println "Avvio applicazione...")
    (let [profile (env "SPRING_PROFILE" "dev")
          args (cond-> ["mvn" "-q" (str "-P" profile) "spring-boot:run"
                        (str "-Dspring-boot.run.profiles=" profile)]
                 (= "1" (env "DISABLE_TURNSTILE" "1"))
                 (conj "-Dspring-boot.run.arguments=--app.turnstile.enabled=false"))]
      (reset! app (process/process args {:out app-log :err app-log}))))
  (wait-for-app!))

(defn smoke! []
  (start-app!)
  (println "Login insegnante...")
  (let [token (hidden-csrf (:body (request! :teacher :GET "/teacher/login")))]
    (request! :teacher :POST "/teacher/login"
              {:form {:username (env "ADMIN_USERNAME" "admin")
                      :password (env "ADMIN_PASSWORD" "changeme") :_csrf token}}))
  (check! (= 200 (:status (request! :teacher :GET "/teacher")))
          "Login insegnante fallito: controllare ADMIN_USERNAME e ADMIN_PASSWORD")
  (println "Creazione studente e quiz...")
  (let [student (api! :teacher :POST "/api/students" {:fullName "Smoke Student"})]
    (check! (and (:id student) (seq (:loginKeyword student))) "Risposta creazione studente incompleta")
    (swap! created assoc :student (:id student))
    (let [quiz (api! :teacher :POST "/api/quizzes"
                     {:title (str "Smoke Quiz " (random-uuid)) :emoji "🧪"
                      :questions [{:text "2+2?" :emoji "➕" :options ["3" "4" "5" "6"]
                                   :answer 1 :feedback "2+2=4"}]})
          id (:id quiz)]
      (check! (some? id) "ID quiz assente")
      (swap! created assoc :quiz id)
      (api! :teacher :PUT (str "/api/quizzes/" id "/publication") {:published true})
      (println "Login studente e compilazione...")
      (let [token (hidden-csrf (:body (request! :student :GET "/")))]
        (request! :student :POST "/student/login" {:form {:keyword (:loginKeyword student) :_csrf token}}))
      (let [quizzes (api! :student :GET "/api/quizzes")]
        (check! (and (sequential? quizzes) (some #(= id (:id %)) quizzes))
                "Il quiz pubblicato non compare nell'elenco dello studente"))
      (let [result (api! :student :POST (str "/api/quizzes/" id "/submit") {:answers [1]})]
        (check! (= {:quizId id :score 1 :total 1 :locked true}
                   (select-keys result [:quizId :score :total :locked]))
                (str "Risultato inatteso: " (pr-str result)))
        (println "Punteggio verificato: 1/1, quiz bloccato.")))))

(defn cleanup! []
  (let [errors (atom [])]
    (doseq [[kind path] [[:quiz "/api/quizzes/"] [:student "/api/students/"]]
            :let [id (get @created kind)] :when id]
      (try (api! :teacher :DELETE (str path id))
           (catch Exception e (swap! errors conj (.getMessage e)))))
    (when-let [p @app] (process/destroy-tree p))
    (if (seq @errors)
      (do (binding [*out* *err*] (println "Pulizia incompleta:" (str/join "; " @errors))) false)
      true)))

(defn main []
  (let [success? (try (smoke!) true
                      (catch Exception e
                        (binding [*out* *err*]
                          (println "SMOKE FALLITO:" (.getMessage e))
                          (when (.exists app-log)
                            (println "Ultimi log applicazione:")
                            (doseq [line (take-last 60 (str/split-lines (slurp app-log)))] (println line))))
                        false))
        cleaned? (cleanup!)]
    (doseq [file (reverse (file-seq workdir))] (io/delete-file file true))
    (if (and success? cleaned?)
      (println "✅ SMOKE E2E COMPLETATO")
      (System/exit 1))))

(main)
