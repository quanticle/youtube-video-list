(ns build
  (:require [clojure.tools.build.api :as b]))
(def build-folder "target")
(def jar-content (str build-folder "/classes"))
(def app-name "youtube-video-list")
(def version "0.0.1")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file-name (format "%s/%s-%s-standalone.jar" build-folder app-name version))

(defn clean [_]
  (b/delete {:path build-folder})
  (println (format "Build folder %s deleted" build-folder)))

(defn uberjar [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir jar-content})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir jar-content})
  (b/uber {:class-dir jar-content
           :uber-file uber-file-name
           :basis basis
           :main 'youtube-video-list})
  (println (format "Uber file created: %s" uber-file-name)))
