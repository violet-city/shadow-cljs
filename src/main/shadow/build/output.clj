(ns shadow.build.output
  (:require
    [clojure.java.io :as io]
    [cljs.source-map :as sm]
    [clojure.string :as str]
    [cljs.compiler :as comp]
    [cljs.analyzer :as ana]
    [clojure.data.json :as json]
    [shadow.build.data :as data]
    [shadow.build.resource :as rc]
    [shadow.build.async :as async]
    [shadow.cljs.util :as util])
  (:import (java.io StringReader File)
           (java.util Base64)))

(defn clean-dir [dir]
  (when (.exists dir)
    (doseq [file (.listFiles dir)]
      (.delete file)
      )))

(defn has-source-map? [output]
  (or (contains? output :source-map-compact)
      (contains? output :source-map-json)
      (contains? output :source-map)))

(defn closure-defines-json [state]
  (let [closure-defines
        (reduce-kv
          (fn [def key value]
            (if (nil? value)
              def
              (let [key (if (symbol? key) (str (comp/munge key)) key)]
                (assoc def key value))))
          {}
          (get-in state [:compiler-options :closure-defines] {}))]

    (json/write-str closure-defines :escape-slashes false)))

(defn closure-defines [{:keys [build-options] :as state}]
  (let [{:keys [asset-path cljs-runtime-path]} build-options]
    (str "var CLOSURE_NO_DEPS = true;\n"
         ;; goog.findBasePath_() requires a base.js which we dont have
         ;; this is usually only needed for unoptimized builds anyways
         "var CLOSURE_BASE_PATH = '" asset-path "/" cljs-runtime-path "/';\n"
         "var CLOSURE_DEFINES = " (closure-defines-json state) ";\n")))

(def goog-base-id
  ;; can't alias due to cyclic dependency, this sucks
  ;; goog/base.js is treated special in several cases
  [:shadow.build.classpath/resource "goog/base.js"])

(defn closure-defines-and-base [{:keys [asset-path cljs-runtime-path] :as state}]
  (let [goog-rc (get-in state [:sources goog-base-id])
        goog-base (get-in state [:output goog-base-id :js])]

    (when-not (seq goog-base)
      (throw (ex-info "no goog/base.js" {})))

    ;; FIXME: work arround for older cljs versions that used broked closure release, remove.
    (when (< (count goog-base) 500)
      (throw (ex-info "probably not the goog/base.js you were expecting"
               (get-in state [:sources goog-base-id]))))

    (str (closure-defines state)
         goog-base
         "\n")))

(defn ns-only [sym]
  {:pre [(qualified-symbol? sym)]}
  (symbol (namespace sym)))

(defn fn-call [{:shadow.build/keys [mode] :as state} sym]
  {:pre [(qualified-symbol? sym)]}
  (if (= :release mode)
    (str "\n" (comp/munge sym) "();")
    (str "\ntry { " (comp/munge sym) "(); } catch (e) { console.error(\"An error occurred when calling (" sym ")\"); throw(e); }")))

(defn- ns-list-string [coll]
  (->> coll
       (map #(str "'" (comp/munge %) "'"))
       (str/join ",")))

(defn directory? [^File x]
  (and (instance? File x)
       (or (not (.exists x))
           (.isDirectory x))))

(defn closure-goog-deps
  ([state]
   (closure-goog-deps state (-> state :sources keys)))
  ([{:keys [dead-js-deps] :as state} source-ids]
   (->> source-ids
        (remove #{goog-base-id})
        (map #(get-in state [:sources %]))
        (map (fn [{:keys [output-name deps provides] :as rc}]
               (str "goog.addDependency(\"" output-name "\",["
                    (ns-list-string provides)
                    "],["
                    (->> (data/deps->syms state rc)
                         (remove '#{goog})
                         (remove dead-js-deps)
                         (ns-list-string))
                    "]);")))
        (str/join "\n"))))

;; FIXME: this could inline everything from a jar since they will never be live-reloaded
;; but it would need to create a proper source-map for the module file
;; since we need that for CLJS files
(defn inlineable? [{:keys [type from-jar provides requires] :as src}]
  ;; only inline things from jars
  (and from-jar
       ;; only js is inlineable since we want proper source maps for cljs
       (= :js type)
       ;; only inline goog for now
       (every? #(str/starts-with? (str %) "goog") requires)
       (every? #(str/starts-with? (str %) "goog") provides)
       ))

(defn line-count [text]
  (with-open [rdr (io/reader (StringReader. text))]
    (count (line-seq rdr))))

(defn library-source? [src]
  (or (:from-jar src)
      ;; generated sources
      (:virtual src)
      ;; npm shadow-js
      (:shadow.build.npm/package src)
      ;; dev related stuff
      (str/starts-with? (:resource-name src) "shadow/cljs/devtools/")))

(defn encode-source-map
  [state
   {:keys [resource-name prepend output-name] :as src}
   {:keys [source source-map-compact source-map] :as output}]
  (let [prepend-lines
        (if-not (seq prepend)
          ""
          (->> (repeat (line-count prepend) ";")
               (str/join "")))

        sm
        (or source-map-compact
            (-> {output-name source-map}
                (sm/encode* {})
                (select-keys ["names" "mappings"])))]

    (-> {"version" 3
         "sources" [resource-name]}
        (merge sm)
        ;; prepend one ; for every line in prepend
        (cond->
          (get-in state [:compiler-options :source-map-include-sources-content])
          (assoc "sourcesContent" [source])

          ;; https://developer.chrome.com/blog/devtools-better-angular-debugging/#the-x_google_ignorelist-source-map-extension
          (library-source? src)
          (assoc "x_google_ignoreList" [0])

          (seq prepend-lines)
          (update "mappings" (fn [s] (str prepend-lines s)))))))

(defn encode-source-map-json
  [state src {:keys [source-map-json] :as output}]
  (or source-map-json
      (-> (encode-source-map state src output)
          (json/write-str :escape-slash false))))

(defn generate-source-map-inline
  [state
   src
   output
   prepend]
  (when (has-source-map? output)

    (let [source-map-json
          (encode-source-map-json state
            (assoc src :prepend prepend)
            output)

          b64
          (-> (Base64/getEncoder)
              (.encodeToString (.getBytes source-map-json)))]

      (str "\n//# sourceMappingURL=data:application/json;charset=utf-8;base64," b64 "\n"))))

(defn generate-source-map-regular
  [state
   {:keys [resource-name output-name file] :as src}
   output
   js-file
   prepend]
  {:pre [(rc/valid-resource? src)
         (map? output)]}
  (when (has-source-map? output)
    (let [sm-suffix
          (get-in state [:compiler-options :source-map-suffix] ".map")

          sm-text
          (str "\n//# sourceMappingURL=" output-name sm-suffix "\n")

          src-map-file
          (io/file (str (.getAbsolutePath js-file) sm-suffix))

          use-fs-path?
          (true? (get-in state [:compiler-options :source-map-use-fs-paths]))

          source-name
          (if (and use-fs-path? file)
            (.getAbsolutePath file)
            resource-name)

          ;; FIXME: make this use encode-source-map from above
          source-map-json
          (encode-source-map-json
            state
            ;; ugly hack to change the "sources":["/absolute/path/to/file.cljs"] in source maps
            (assoc src :resource-name source-name :prepend prepend)
            output)]

      (io/make-parents src-map-file)
      (spit src-map-file source-map-json)

      sm-text)))

(defn generate-source-map
  [state
   src
   output
   js-file
   prepend]
  {:pre [(rc/valid-resource? src)
         (map? output)]}
  (if (true? (get-in state [:compiler-options :source-map-inline]))
    (generate-source-map-inline state src output prepend)
    (generate-source-map-regular state src output js-file prepend)))

(defn flush-source [state src-id]
  (let [{:keys [resource-name output-name last-modified] :as src}
        (data/get-source-by-id state src-id)

        {:keys [js compiled-at] :as output}
        (data/get-output! state src)

        js-file
        (if-some [sub-path (get-in state [:build-options :cljs-runtime-path])]
          (data/output-file state sub-path output-name)
          (data/output-file state output-name))]

    ;; skip files we already have
    (when (or (not (.exists js-file))
              (zero? last-modified)
              ;; js is not compiled but maybe modified
              (> (or compiled-at last-modified)
                 (.lastModified js-file)))

      (io/make-parents js-file)

      (util/with-logged-time
        [state {:type :flush-source
                :resource-name resource-name}]

        (let [output (str js (generate-source-map state src output js-file ""))]
          (spit js-file output))))))

(defmulti flush-optimized-module
  (fn [state mod]
    (get mod :output-type ::default)))

(defn finalize-module-output
  [{:keys [polyfill-js] :as state}
   {:keys [goog-base prepend output append sources] :as mod}]
  (let [shadow-js-outputs
        (->> sources
             (map #(data/get-source-by-id state %))
             (filter #(= :shadow-js (:type %)))
             (map #(data/get-output! state %))
             (into []))

        shadow-js-prepend
        (when (seq shadow-js-outputs)
          (let [provides
                (->> shadow-js-outputs
                     (map :js)
                     (str/join ";\n"))]
            (-> (str provides
                     (when (seq provides)
                       ";\n")))))

        base-prepend
        (when goog-base
          ;; when using :output-wrapper the closure compiler will for some reason use $jscomp without declaring it
          ;; this is the quickest way I can think of to work around that. should figure out why GCC is going that.
          ;; special case for node targets that use
          ;; prepend to prepend hashbang which must be first
          ;; FIXME: ugly hack, make this cleaner
          (str (when (or (nil? prepend)
                         (not (str/includes? prepend "shadow$provide")))
                 "var shadow$provide = {};\n")
               (when (seq polyfill-js)
                 (str polyfill-js "\n"))))

        final-output
        (str base-prepend
             ;; FIXME: shadow$provide must come before prepend
             ;; since output-wrapper uses prepend and we need this
             ;; to be available cross module, we should however try to avoid
             ;; leaking a global like that. just not sure how.
             prepend
             shadow-js-prepend
             output
             append)]

    {:output final-output
     ;; for source maps
     :shadow-js-outputs shadow-js-outputs
     :prepend-offset
     (-> 0
         (cond->
           (seq base-prepend)
           (+ (line-count base-prepend)) ;; var shadow$provide ...
           (seq prepend)
           (+ (line-count prepend))
           ))}))

(defmethod flush-optimized-module ::default
  [state {:keys [dead source-map-json module-id output-name] :as mod}]
  (let [{:keys [output prepend-offset shadow-js-outputs]}
        (finalize-module-output state mod)

        add-source-mapping-url?
        (not (false? (get-in state [:compiler-options :source-map-comment])))

        target
        (data/output-file state output-name)]

    (io/make-parents target)

    (if dead
      ;; always create the file even if empty
      ;; otherwise breaks html which expects to load the modules and wasn't optimized
      ;; to skip dead modules
      (spit target "")

      (let [source-map-name
            (str output-name (get-in state [:compiler-options :source-map-suffix] ".map"))

            final-output
            (str output
                 (when (and add-source-mapping-url? source-map-json)
                   (str "\n//# sourceMappingURL=" source-map-name "\n")))]

        (spit target final-output)

        (util/log state {:type :flush-module
                         :module-id module-id
                         :output-name output-name
                         :js-size (count final-output)})

        (when source-map-json
          (let [sm-index
                (-> {:version 3
                     :file output-name
                     :offset prepend-offset
                     :sections []}
                    (util/reduce->
                      (fn [{:keys [offset] :as sm-index}
                           {:keys [js source-map-json] :as src}]

                        (let [sm
                              (json/read-str (or source-map-json "{}"))

                              lines
                              (line-count js)]
                          (-> sm-index
                              (update :offset + lines)
                              (update :sections conj {:offset {:line offset :column 0}
                                                      :map sm}))))

                      shadow-js-outputs))

                {:strs [sources] :as sm}
                (json/read-str source-map-json)

                ignore
                (reduce-kv
                  (fn [ignore idx source-name]
                    (let [id (get-in state [:name->id source-name])]
                      (if-not id
                        (conj ignore idx)
                        (let [src (get-in state [:sources id])]
                          (if-not (library-source? src)
                            ignore
                            (conj ignore idx))))))
                  []
                  sources)

                sm
                (assoc sm "x_google_ignoreList" ignore)

                sm-index
                (-> sm-index
                    (update :sections conj {:offset {:line (:offset sm-index) :column 0} :map sm})
                    (dissoc :offset))

                target
                (data/output-file state source-map-name)]
            (spit target
              (json/write-str sm-index))))))))

(defn flush-optimized
  ;; FIXME: can't alias this due to cyclic dependency
  [{modules :shadow.build.closure/modules
    :keys [build-options]
    :as state}]

  (when-not (seq modules)
    (throw (ex-info "flush before optimize?" {})))

  (util/with-logged-time
    [state {:type :flush-optimized}]

    (doseq [mod modules]
      (flush-optimized-module state mod))

    ;; with-logged-time expects that we return the compiler-state
    state
    ))

(defn js-module-root [sym]
  (let [s (comp/munge (str sym))]
    (if-let [idx (str/index-of s ".")]
      (subs s 0 idx)
      s)))

(defn js-module-src-prepend [state {:keys [resource-name output-name] :as src}]
  (let [dep-syms
        (data/deps->syms state src)

        roots
        (->> (get-in state [:compiler-env :shadow/ns-roots])
             (map comp/munge))]

    (str "var $CLJS = require(\"./cljs_env\");\n"
         "var $jscomp = $CLJS.$jscomp;\n"

         ;; :npm-module is picky about this
         "var COMPILED = false;\n"

         ;; emit requires to actual files to ensure that they were loaded properly
         ;; can't ensure that the files were loaded before this as goog.require would
         (->> dep-syms
              (remove #{'goog})
              (map #(data/get-source-id-by-provide state %))
              (distinct)
              (map #(data/get-source-by-id state %))
              (remove :goog-src)
              (map (fn [{:keys [output-name] :as x}]
                     (str "require(\"./" output-name "\");")))
              (str/join "\n"))
         "\n"

         ;; have all project files load the worker inject automatically
         ;; removes the need for the user to handle this from the JS side
         (when (and (= :cljs (:type src))
                    (:worker-info state)
                    (not (:from-jar src))
                    (not (str/starts-with? resource-name "shadow/"))
                    (not (str/starts-with? resource-name "cljs/")))
           (case (:runtime (:shadow.build/config state))
             :browser
             "require(\"./shadow.cljs.devtools.client.browser.js\");\n"
             :node
             "require(\"./shadow.cljs.devtools.client.npm_module.js\");\n"
             nil))

         ;; take existing roots or create in case they don't exist yet
         (->> roots
              (map (fn [root]
                     (str "var " root "=$CLJS." root " || ($CLJS." root " = {});")))
              (str/join "\n"))
         "\n"
         "\n$CLJS.SHADOW_ENV.setLoaded(" (pr-str output-name) ");\n"
         "\n")))

(defn js-module-src-append [state {:keys [ns] :as src}]
  (if-not ns
    ;; none-cljs, export the shortest name always, some goog files have multiple provides
    (str "\nmodule.exports = "
         (->> (:provides src)
              (map str)
              (sort)
              (map comp/munge)
              (first))
         ";\n")
    ;; cljs ns, export vars.
    ;; enumarable only if tagged with :export or :export-as
    (->> (get-in state [:compiler-env ::ana/namespaces ns :defs])
         (vals)
         (map (fn [def]
                (let [{:keys [export export-as] :as def-meta}
                      (:meta def)

                      export-name
                      (if (string? export-as)
                        export-as

                        (let [def-name (symbol (name (:name def)))]
                          (if (= 'default def-name) ;; avoid munge to default$
                            "default"
                            (comp/munge def-name))))]

                  (str "Object.defineProperty(module.exports, \"" export-name "\", { "
                       "enumerable: " (or (:export def-meta false)
                                          (string? export-as))
                       ", "
                       "get: function() { return " (comp/munge (:name def)) "; }"
                       " });"))))
         (str/join "\n"))))

(def goog-global-snippet
  "goog.global =\n    // Check `this` first for backwards compatibility.\n    // Valid unless running as an ES module or in a function wrapper called\n    //   without setting `this` properly.\n    // Note that base.js can't usefully be imported as an ES module, but it may\n    // be compiled into bundles that are loadable as ES modules.\n    this ||\n    // https://developer.mozilla.org/en-US/docs/Web/API/Window/self\n    // For in-page browser environments and workers.\n    self;")

(defn js-module-env
  [{:keys [polyfill-js] :as state} {:keys [runtime] :or {runtime :node} :as config}]
  (str "var $CLJS = {};\n"

       (case runtime
         :node
         "var CLJS_GLOBAL = global;\n"
         :browser
         "var CLJS_GLOBAL = globalThis;\n"
         ;; default, tries to figure it out on its own
         "var CLJS_GLOBAL = (typeof(globalThis) != 'undefined') ? globalThis : global;\n")

       ;; closure accesses these defines via goog.global.CLOSURE_DEFINES
       "var CLOSURE_DEFINES = CLJS_GLOBAL.CLOSURE_DEFINES = $CLJS.CLOSURE_DEFINES = " (closure-defines-json state) ";\n"
       "CLJS_GLOBAL.CLOSURE_NO_DEPS = true;\n"
       ;; so devtools can access it
       "CLJS_GLOBAL.$CLJS = $CLJS;\n"

       ;; in case of commonjs on the classpath
       "global.shadow$provide = {};\n"

       "var goog = $CLJS.goog = {};\n"
       ;; the global must be overriden in goog/base.js since it contains some
       ;; goog.define(...) which would otherwise be exported to "this"
       ;; but we need it on $CLJS
       (-> (data/get-output! state {:resource-id goog-base-id})
           (get :js)
           ;; FIXME: this is using the compiled variant of goog/base.js
           ;; don't use goog-global-snippet, that is used for uncompiled goog/base.js
           ;; keeping both variants for now until I can make this cleaner
           (str/replace "goog.global = this || self;" "goog.global = $CLJS;"))

       (if (seq polyfill-js)
         (str "\n" polyfill-js
              "\n$CLJS.$jscomp = $jscomp;")
         (str "\n$CLJS.$jscomp = {};"))

       ;; set global back to actual global so things like setTimeout work
       "\ngoog.global = CLJS_GLOBAL;"

       (slurp (io/resource "shadow/boot/static.js"))
       (slurp (io/resource "shadow/build/targets/npm_module_goog_overrides.js"))
       "\nmodule.exports = $CLJS;\n"
       ))

(defn flush-dev-js-module-source [state {:keys [output-name last-modified] :as src}]
  (let [{:keys [js] :as output}
        (data/get-output! state src)

        target
        (data/output-file state output-name)]

    ;; flush everything if env was modified, otherwise only flush modified
    (when (or (not (.exists target)) (>= last-modified (.lastModified target)))

      (let [prepend
            (js-module-src-prepend state src)

            content
            (str prepend
                 js
                 (js-module-src-append state src)
                 (generate-source-map state src output target prepend))]

        (spit target content)
        ))))

;; called from shadow.cljs.repl AND targets
;; need it to be npm-module aware so it doesn't end up flushing cljs-runtime sources
;; which are useless for npm-module. rather do this here than in shadow.cljs.repl
(defn flush-sources
  ([{:keys [build-sources] :as state}]
   (flush-sources state build-sources))
  ([state source-ids]

   (if (not= :js (get-in state [:build-options :module-format]))
     ;; regular goog style output
     (doseq [src-id source-ids]
       (async/queue-task state #(flush-source state src-id)))
     ;; npm-module style output
     (doseq [src-id source-ids
             :when (not= src-id goog-base-id)
             :let [src (get-in state [:sources src-id])]
             :when (not (:goog-src src))]

       (async/queue-task state #(flush-dev-js-module-source state src))))

   state))

(defn flush-dev-js-modules
  [state mode config]
  (util/with-logged-time [state {:type :npm-flush
                                 :output-path (.getAbsolutePath (get-in state [:build-options :output-dir]))}]
    (let [env-file
          (data/output-file state "cljs_env.js")

          env-content
          (str (js-module-env state config)
               "\n"
               (->> (:build-sources state)
                    (remove #{goog-base-id})
                    (map #(data/get-source-by-id state %))
                    (filter :goog-src)
                    (map (fn [{:keys [output-name] :as src}]
                           (let [{:keys [js]} (data/get-output! state src)]
                             (str js
                                  "\n$CLJS.SHADOW_ENV.setLoaded(" (pr-str output-name) ");\n"))))
                    (str/join "\n")))

          env-modified?
          (or (not (.exists env-file))
              (not= env-content (slurp env-file)))]

      ;; the goal is to avoid touching the env file as much as possible
      ;; other tools might trigger a reload and reloading the env destroys everything
      (when env-modified?
        (io/make-parents env-file)
        (spit env-file env-content))))

  (flush-sources state)

  state)



