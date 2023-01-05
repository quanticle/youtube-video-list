(defproject youtube-video-list "0.1.0-SNAPSHOT"
  :description "Script to list all the videos on a YouTube channel"
  :url "https://github.com/quanticle/youtube-video-fix"
  :license {:name "GPL 3.0"
            :url "https://www.gnu.org/licenses/gpl-3.0.txt"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [clj-http "3.12.3"]
                 [mockery "0.1.4"]
                 [org.clojure/data.json "2.4.0"]]
  :main ^:skip-aot youtube-video-list.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
