(ns shadow.cljs.devtools.client.node
  (:require
    ["ws" :as ws]
    [cljs.reader :as reader]
    [goog.object :as gobj]
    [shadow.remote.runtime.shared :as shared]
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.remote.runtime.api :as api]))

(defn node-eval [{:keys [js source-map-json] :as msg}]
  (let [result (js/SHADOW_NODE_EVAL js source-map-json)]
    result))

(defn is-loaded? [src]
  (true? (gobj/get js/SHADOW_IMPORTED src)))

(defn closure-import [src]
  {:pre [(string? src)]}
  (js/SHADOW_IMPORT src))

(defn handle-build-complete
  [runtime {:keys [info reload-info] :as msg}]
  (let [{:keys [sources compiled warnings]} info]

    (when (and env/autoload
               (or (empty? warnings) env/ignore-warnings))

      (let [files-to-require
            (->> sources
                 (remove (fn [{:keys [ns]}]
                           (contains? (:never-load reload-info) ns)))
                 (filter (fn [{:keys [ns resource-id]}]
                           (or (contains? compiled resource-id)
                               (contains? (:always-load reload-info) ns))))
                 (map :output-name)
                 (into []))]

        (when (seq files-to-require)
          (env/do-js-reload
            msg
            #(doseq [src files-to-require]
               (env/before-load-src src)
               (closure-import src))
            ))))))

(def client-info
  {:host :node
   :desc (str "Node " js/process.version)})

(defn start [runtime]
  (let [ws-url
        (env/get-ws-relay-url)

        socket
        (ws. ws-url #js {:rejectUnauthorized false})

        ws-active-ref
        (atom true)]

    (.on socket "message"
      (fn [data]
        (when @ws-active-ref
          (cljs-shared/remote-msg runtime data))))

    (.on socket "open"
      (fn [e]
        (when @ws-active-ref
          (cljs-shared/remote-open runtime e))))

    (.on socket "close"
      (fn [e]
        (when @ws-active-ref
          (cljs-shared/remote-close runtime e ws-url))))

    (.on socket "error"
      (fn [e]
        (when @ws-active-ref
          (cljs-shared/remote-error runtime e))))

    {:socket socket
     :ws-active-ref ws-active-ref}))

(defn send [{:keys [socket]} msg]
  (.send socket msg))

(defn stop [{:keys [socket ws-active-ref]}]
  (reset! ws-active-ref false)
  (.close socket))

;; want things to start when this ns is in :preloads
(when (pos? env/worker-client-id)

  (extend-type cljs-shared/Runtime
    api/IEvalJS
    (-js-eval [this code]
      (js/SHADOW_NODE_EVAL code))

    cljs-shared/IHostSpecific
    (do-invoke [this ns msg]
      (node-eval msg))

    (do-repl-init [runtime {:keys [repl-sources]} done error]
      (try
        (doseq [{:keys [output-name] :as src} repl-sources
                :when (not (is-loaded? output-name))]
          (closure-import output-name))

        (done)
        (catch :default e
          (error e))))

    (do-repl-require [this {:keys [sources reload-namespaces] :as msg} done error]
      (try
        (doseq [{:keys [provides output-name] :as src} sources]
          (when (or (not (is-loaded? output-name))
                    (some reload-namespaces provides))
            (closure-import output-name)))

        (done)
        (catch :default e
          (error e)))))

  (cljs-shared/add-plugin! ::client #{}
    (fn [{:keys [runtime] :as env}]
      (let [svc {:runtime runtime}]
        (api/add-extension runtime ::client
          {:on-welcome
           (fn []
             ;; FIXME: why does this break stuff when done when the namespace is loaded?
             ;; why does it have to wait until the websocket is connected?
             (env/patch-goog!)
             (when env/log
               (js/console.log (str "shadow-cljs - #" (-> runtime :state-ref deref :client-id) " ready!"))))

           :on-disconnect
           (fn []
             (js/console.warn "The shadow-cljs Websocket was disconnected."))

           :ops
           {:access-denied
            (fn [msg]
              (js/console.error
                (str "Stale Output! Your loaded JS was not produced by the running shadow-cljs instance."
                     " Is the watch for this build running?")))

            :cljs-build-configure
            (fn [msg])

            :cljs-build-start
            (fn [msg]
              ;; (js/console.log "cljs-build-start" msg)
              (env/run-custom-notify! (assoc msg :type :build-start)))

            :cljs-build-complete
            (fn [msg]
              ;; (js/console.log "cljs-build-complete" msg)
              (let [msg (env/add-warnings-to-info msg)]
                (handle-build-complete runtime msg)
                (env/run-custom-notify! (assoc msg :type :build-complete))))

            :cljs-build-failure
            (fn [msg]
              ;; (js/console.log "cljs-build-failure" msg)
              (env/run-custom-notify! (assoc msg :type :build-failure)))

            ::env/worker-notify
            (fn [{:keys [event-op client-id]}]
              (cond
                (and (= :client-disconnect event-op)
                     (= client-id env/worker-client-id))
                (js/console.warn "shadow-cljs - The watch for this build was stopped!")

                (= :client-connect event-op)
                (js/console.warn "shadow-cljs - A new watch for this build was started, restart of this process required!")

                :else
                nil))
            }})
        svc))

    (fn [{:keys [runtime] :as svc}]
      (api/del-extension runtime ::client)))

  (cljs-shared/init-runtime! client-info start send stop))
