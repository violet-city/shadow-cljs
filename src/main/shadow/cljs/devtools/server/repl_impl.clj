(ns shadow.cljs.devtools.server.repl-impl
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.build.log :as build-log]
            [shadow.jvm-log :as log]
            [shadow.build.warnings :as warnings]
            [shadow.cljs.devtools.errors :as errors]
            [shadow.remote.relay.api :as relay])
  (:import (java.io File InputStreamReader BufferedReader IOException)))

;; (defn repl-prompt [repl-state])
;; (defn repl-read-ex [repl-state ex])
;; (defn repl-result [repl-state result-as-printed-string])

(defn do-repl
  [{:keys [proc-stop] :as worker}
   relay
   input-stream
   close-signal
   {:keys [init-state
           repl-prompt
           repl-read-ex
           repl-result
           repl-stdout
           repl-stderr]}]
  {:pre [(some? worker)
         (some? proc-stop)
         (some? close-signal)]}

  (let [to-relay
        (async/chan 10)

        from-relay
        (async/chan 256)

        connection-stop
        (relay/connect relay to-relay from-relay {})

        {:keys [client-id] :as welcome-msg}
        (<!! from-relay)

        stdin
        (async/chan)

        _
        (>!! to-relay
          {:op :hello
           :client-info {:type :repl-session
                         :build-id (:build-id worker)
                         :proc-id (:proc-id worker)}})

        proc-stdio-available?
        (get-in worker [:cli-opts ::node-repl])

        proc-stdio
        (async/chan
          (async/sliding-buffer 100)
          (filter #(contains? #{:proc/stdout :proc/stderr} (:type %))))

        read-lock
        (async/chan)


        init-ns
        (or (:ns init-state)
            (some-> worker :state-ref deref :build-config :devtools :repl-init-ns)
            'cljs.user)

        repl-timeout
        (or (some-> worker :state-ref deref :build-config :devtools :repl-timeout) 30000)

        init-state
        (assoc init-state
          :ns init-ns
          :stage :read
          :client-id client-id)]

    (when proc-stdio-available?
      (worker/watch worker proc-stdio))

    ;; read loop, blocking IO
    ;; cannot block main loop or we'll never receive async events
    (async/thread
      (try
        (loop []
          ;; wait until told to read
          (when (some? (<!! read-lock))
            (let [{:keys [eof?] :as next} (repl/dummy-read-one input-stream)]
              (if eof?
                (async/close! stdin)
                ;; don't recur in case stdin was closed while in blocking read
                (when (>!! stdin next)
                  (recur))))))
        (catch Exception e
          (log/debug-ex e ::read-ex)))
      (async/close! stdin))

    (>!! read-lock 1)

    ;; initial prompt
    (repl-prompt init-state)

    (let [result
          (loop [repl-state init-state]
            (async/alt!!
              proc-stop
              ([_] ::worker-stop)

              close-signal
              ([_] ::close-signal)

              stdin
              ([read-result]
               ;; (tap> [:repl-from-stdin read-result repl-state])
               (when (some? read-result)
                 (let [{:keys [eof? error? ex source]} read-result]
                   (cond
                     eof?
                     :eof

                     error?
                     (do (repl-read-ex repl-state ex)
                         (repl-prompt repl-state)
                         (>!! read-lock 1)
                         (recur repl-state))

                     (= ":repl/quit" source)
                     :repl/quit

                     (= ":cljs/quit" source)
                     :cljs/quit

                     :else
                     (let [runtime-id
                           (or (:runtime-id repl-state)
                               ;; no previously picked runtime, pick new one from worker when available
                               (when-some [runtime-id (-> worker :state-ref deref :default-runtime-id)]
                                 ;; don't capture client side prints when
                                 ;; already getting proc/stdout|err from worker
                                 ;; only available when managing the actual process (eg. node-repl)
                                 (when-not proc-stdio-available?
                                   (>!! to-relay {:op :runtime-print-sub
                                                  :to runtime-id}))
                                 (>!! to-relay {:op :request-notify
                                                :notify-op ::runtime-disconnect
                                                :query [:eq :client-id runtime-id]})
                                 runtime-id))]

                       (if-not runtime-id
                         (do (repl-stderr repl-state "No available JS runtime.\nSee https://shadow-cljs.github.io/docs/UsersGuide.html#repl-troubleshooting")
                             (repl-result repl-state nil)
                             (repl-prompt repl-state)
                             (>!! read-lock 1)
                             (recur repl-state))

                         (let [msg {:op :cljs-eval
                                    :to runtime-id
                                    :input {:code source
                                            :ns (:ns repl-state)
                                            :repl true}}]

                           (>!! to-relay msg)
                           (-> repl-state
                               (assoc :stage :eval :runtime-id runtime-id :read-result read-result)
                               (recur)))))))))

              from-relay
              ([msg]
               ;; (tap> [:repl-from-relay msg repl-state])
               (when (some? msg)
                 (case (:op msg)
                   (::runtime-disconnect :client-not-found)
                   (do (repl-stderr repl-state "The previously used runtime disappeared. Will attempt to pick a new one when available but your state might be gone.\n")
                       (repl-prompt repl-state)
                       ;; may be in blocking read so read-lock is full
                       ;; must not use >!! since that would deadlock
                       ;; only offer! and discard when not in blocking read anyways
                       (async/offer! read-lock 1)
                       (-> repl-state
                           (dissoc :runtime-id)
                           (recur)))

                   :eval-result-ref
                   (let [{:keys [from ref-oid eval-ns]} msg]

                     (>!! to-relay
                       {:op :obj-edn
                        :to from
                        :oid ref-oid})

                     (-> repl-state
                         (assoc :ns eval-ns
                                :stage :print
                                :eval-result msg)
                         (recur)))

                   :obj-request-failed
                   (let [{:keys [from ex-oid]} msg]
                     (if (:print-failed repl-state)
                       (do (repl-stderr repl-state "The result failed to print and printing the exception also failed. No clue whats going on.")
                           (repl-prompt repl-state)
                           (>!! read-lock 1)
                           (-> repl-state
                               (dissoc :print-failed)
                               (recur)))

                       (do (>!! to-relay
                             {:op :obj-as-str
                              :to from
                              :oid ex-oid})

                           (-> repl-state
                               (assoc :print-failed true)
                               (recur)))))

                   :obj-result
                   (let [{:keys [result]} msg]
                     (cond
                       (= :error (:stage repl-state))
                       (do (repl-stderr repl-state (str "\n" result))
                           ;; FIXME: should there be an actual result? looks annoying on the streaming REPLs
                           ;; this was only here for nREPL right?
                           (repl-result repl-state ":repl/exception!"))

                       (not (:print-failed repl-state))
                       (repl-result repl-state result)

                       :else
                       (do (repl-stderr repl-state "The result object failed to print. It is available via *1 if you want to interact with it.\n")
                           (repl-stderr repl-state "The exception was: \n")
                           (repl-stderr repl-state (str result "\n"))
                           (repl-result repl-state ":repl/print-error!")))

                     (repl-prompt repl-state)

                     (>!! read-lock 1)
                     (-> repl-state
                         (assoc :stage :read)
                         (dissoc :print-failed)
                         (recur)))

                   :eval-compile-warnings
                   (let [{:keys [warnings]} msg]
                     (doseq [warning warnings]
                       (repl-stderr repl-state
                         (binding [warnings/*color* false]
                           (with-out-str
                             (warnings/print-short-warning (assoc warning :resource-name "<eval>"))
                             (println)))))
                     (repl-result repl-state nil)
                     (repl-prompt repl-state)
                     (>!! read-lock 1)
                     (recur (assoc repl-state :stage :read)))

                   :eval-compile-error
                   (let [{:keys [report]} msg]
                     (repl-stderr repl-state (str report "\n"))
                     (repl-result repl-state nil)
                     (repl-prompt repl-state)
                     (>!! read-lock 1)
                     (recur (assoc repl-state :stage :read)))

                   :eval-runtime-error
                   (let [{:keys [from ex-oid]} msg]
                     (>!! to-relay
                       {:op :obj-ex-str
                        :to from
                        :oid ex-oid})
                     (recur (assoc repl-state :stage :error)))

                   :runtime-print
                   (let [{:keys [stream text]} msg]
                     (case stream
                       :stdout
                       (repl-stdout repl-state text)
                       :stderr
                       (repl-stderr repl-state text))
                     (recur repl-state))

                   (do (tap> [:unexpected-from-relay msg repl-state worker relay])
                       (repl-stderr repl-state "INTERNAL REPL ERROR: Got an unexpected reply from relay, check Inspect")
                       (recur repl-state)))))

              proc-stdio
              ([msg]
               (when (some? msg)
                 (let [{:keys [type text]} msg]
                   (case type
                     :proc/stdout
                     (repl-stdout repl-state text)
                     :proc/stderr
                     (repl-stderr repl-state text))

                   (recur repl-state))))

              (async/timeout repl-timeout)
              ([_]
               ;; fine to wait long time while reading
               (if (= :read (:stage repl-state))
                 (recur repl-state)
                 ;; should time out eventually while waiting for eval/print so you can retry
                 (do (>!! read-lock 1)
                     (repl-stderr repl-state (str "Timeout while waiting for result.\n"))
                     (-> repl-state
                         (assoc :stage :read)
                         (recur)))))))]

      (async/close! to-relay)
      (async/close! read-lock)
      (async/close! proc-stdio)
      (async/close! stdin)

      result)))

(defn stdin-takeover!
  [worker
   {:keys [relay] :as app}
   {:keys [prompt] :as opts}]

  (do-repl
    worker
    relay
    *in*
    (async/chan)
    {:init-state
     {:runtime-id (:runtime-id opts)}

     :repl-prompt
     (fn repl-prompt [{:keys [ns] :as repl-state}]
       (when-not (false? prompt)
         (locking build-log/stdout-lock
           (print (format "%s=> " ns))
           (flush))))

     :repl-read-ex
     (fn repl-read-ex [repl-state ex]
       (locking build-log/stdout-lock
         (println (str "Failed to read: " ex))
         (flush)))

     :repl-result
     (fn repl-result [repl-state result-as-printed-string]
       (when result-as-printed-string
         (locking build-log/stdout-lock
           (println result-as-printed-string)
           (flush))))

     :repl-stderr
     (fn repl-stderr [repl-state text]
       (binding [*out* *err*]
         (print text)
         (flush)))

     :repl-stdout
     (fn repl-stdout [repl-state text]
       (print text)
       (flush))
     }))

(defn pipe [^Process proc in {:keys [output] :as worker} type]
  ;; we really do want system-default encoding here
  (with-open [^java.io.Reader in (-> in InputStreamReader. BufferedReader.)]
    (loop [buf (char-array 1024)]
      (when (.isAlive proc)
        (try
          (let [len (.read in buf)]
            (when-not (neg? len)
              (async/offer! output {:type type
                                    :text (String. buf 0 len)})))
          (catch IOException e
            (when (and (.isAlive proc) (not (.contains (.getMessage e) "Stream closed")))
              (log/warn-ex e ::node-repl-pipe)
              )))
        (recur buf)))))

(defn node-repl*
  [{:keys [supervisor config] :as app}
   {:keys [via
           verbose
           build-id
           node-args
           node-command
           pwd]
    :or {node-args []
         build-id :node-repl
         node-command "node"}
    :as opts}]
  (let [script-name
        (str (:cache-root config) File/separatorChar "shadow-node-repl.js")

        build-config
        {:build-id build-id
         :target :node-script
         :main 'shadow.cljs.devtools.client.node-repl/main
         :hashbang false
         :output-to script-name}

        {:keys [proc-stop] :as worker}
        (super/start-worker supervisor build-config (assoc opts ::node-repl true))

        result
        (worker/compile! worker)

        node-script
        (doto (io/file script-name)
          ;; just to ensure it is removed, should this crash for some reason
          (.deleteOnExit))]

    ;; FIXME: validate that compilation succeeded

    (assoc worker
      :node-script node-script
      :node-proc
      (async/thread
        (loop []
          (let [crash
                (async/promise-chan)

                node-proc
                (-> (ProcessBuilder.
                      (into-array
                        (into [node-command] node-args)))
                    (.directory
                      ;; nil defaults to JVM working dir
                      (when pwd
                        (io/file pwd)))
                    (.start))]

            ;; FIXME: validate that proc started properly

            (.start (Thread. (bound-fn [] (pipe node-proc (.getInputStream node-proc) worker :proc/stdout))))
            (.start (Thread. (bound-fn [] (pipe node-proc (.getErrorStream node-proc) worker :proc/stderr))))

            ;; piping the script into node-proc instead of using command line arg
            ;; as node will otherwise adopt the path of the script as the require reference point
            ;; we want to control that via pwd

            (let [out (.getOutputStream node-proc)]
              (io/copy (slurp node-script) out)
              (.close out))

            ;; node process might crash and we should restart if that happens
            (async/thread
              (let [code (.waitFor node-proc)]
                (log/info ::node-repl-exit {:code code})
                (when-not (zero? code)
                  (async/close! crash))))

            ;; worker stop should kill the node process
            (alt!!
              proc-stop
              ([_]
               (try
                 (when (.isAlive node-proc)
                   (.destroyForcibly node-proc))
                 (catch Exception e)))
              crash
              ([_]
               ;; just restart
               (recur)))))))))
