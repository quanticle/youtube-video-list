(ns youtube-video-list.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http-client]
            [clojure.data.json :as json]
            [clojure.instant :as time]
            [clojure.tools.cli :refer [parse-opts]])
  (:import [java.time Duration]
           [java.time.format DateTimeFormatter])
  (:gen-class))

(def client-key-file-name "client-key")
(def youtube-link-width 28)
(def video-upload-time-width 19)
(def wide-terminal-width 80)
(def cli-options
  [["-o" "--output TYPE" "Output type"
    :required false
    :default "multi"
    :validate #{"multi" "single" "tsv"}]])

(defrecord video-info [upload-date video-title video-id duration])

(defn load-client-key
  "Loads the client key from the given file. The file is assumed to have just
   the API key, with a possible trailing newline."
  [file-name]
  (str/trim (slurp (io/resource file-name))))

(defn get-uploads-playlist-id
  "Get the ID of the playlist representing the full list of uploaded video for
   the given channel. The channel URL can either be one that contains a user ID
   (i.e. https://www.youtube.com/user/nismotv2013/videos) or, one that contains
   the channel ID (i.e.
   https://www.youtube.com/channel/UCy0tKL1T7wFoYcxCe0xjN6Q/videos)"
  [api-key channel-url]
  (let [channel-id (re-find #"/channel/([^/]+)/?" channel-url)
        user-id (re-find #"/user/([^/]+)/?" channel-url)
        query-params (cond
                       channel-id {:key api-key
                                   :id (channel-id 1)
                                   :part "contentDetails"}
                       user-id {:key api-key
                                :forUsername (user-id 1)
                                :part "contentDetails"}
                       :else (throw (Exception.
                                     (str "Could not determine either user ID or channel ID from " channel-url))))
        http-response (http-client/get
                       "https://www.googleapis.com/youtube/v3/channels"
                       {:accept :json
                        :query-params query-params})]
    (if (= (:status http-response) 200)
      (-> http-response
          :body
          (json/read-str :key-fn keyword)
          :items
          (get 0)
          :contentDetails
          :relatedPlaylists
          :uploads)
      (throw (Exception. (str "Did not get a valid HTTP response from the YouTube API: " (:status http-response)))))))

(defn get-video-data
  "Extracts the fields we're interested in (video title, upload date and video
   ID) from the PlaylistItems API response"
  [playlist-items-response]
  (map #(map->video-info {:video-title (get-in % [:snippet :title])
                          :video-id (get-in % [:contentDetails :videoId])
                          :upload-date (time/read-instant-date (get-in % [:contentDetails :videoPublishedAt]))})
       (:items playlist-items-response)))

(defn get-video-info-from-playlist
  "Gets a list of all the videos from the given playlist ID"
  [api-key playlist-id]
  (loop [current-results []
         next-page-token nil]
    (let [http-response (http-client/get
                         "https://www.googleapis.com/youtube/v3/playlistItems"
                         {:accept :json
                          :query-params (merge
                                         {:key api-key
                                          :playlistId playlist-id
                                          :part "snippet,contentDetails"
                                          :maxResults 50}
                                         (when next-page-token
                                           {:pageToken next-page-token}))})
          response-data (if (= (:status http-response) 200)
                          (json/read-str (:body http-response) :key-fn keyword))]
      (if (= (:status http-response) 200)
        (if (:nextPageToken response-data)
          (recur
           (into current-results (get-video-data response-data))
           (:nextPageToken response-data))
          (into current-results (get-video-data response-data)))
        (throw (Exception. (str "Did not get a valid response from the YouTube API: " (:status http-response))))))))

(defn parse-video-length
  "Take an ISO-8601 duration string and turn it into a more-readable
  hours:minutes:seconds format."
  [video-length-str]
  (let [duration (Duration/parse video-length-str)]
    (format "%02d:%02d:%02d" (.toHoursPart duration) (.toMinutesPart duration) (.toSecondsPart duration))))

(defn get-video-length-for-partition
  "Launch a future to get video info for a single partition of (up to) 50 videos"
  [api-key video-info-partition]
  (let [video-ids (map #(:video-id %) video-info-partition)]
    (future
      (let [http-response (http-client/get
                           "https://www.googleapis.com/youtube/v3/videos"
                           {:accept :json
                            :query-params {:key api-key
                                           :id (str/join "," video-ids)
                                           :part "id,contentDetails"}})
            response-data (if (= (:status http-response) 200)
                            (json/read-str (:body http-response) :key-fn keyword))]
        (map (fn [video-details]
               {:id (:id video-details)
                :duration (parse-video-length (get-in video-details [:contentDetails :duration]))})
             (:items response-data))))))

(defn get-all-video-lengths
  "Get the video lengths for all the videos in a playlist and set the duration
   field of the video record."
  [api-key video-infos]
  (let [video-info-partitions (partition-all 50 video-infos)
        video-info-map (apply hash-map (flatten (map #(vector (:video-id %) (atom %)) video-infos)))
        video-lengths (flatten (map #(deref (get-video-length-for-partition api-key %)) video-info-partitions))]
    (sort-by :upload-date
             (into [] (map (fn [video-length]
                             (assoc @(video-info-map (:id video-length)) :duration (:duration video-length))))
                   video-lengths))))

(defn unformatted-output
  "Prints video info in a simple tab-delimited, newline separated format
   suitable for file output"
  [video-info]
  (dorun (map (fn [vid]
                (printf "%s\t%s\t%s\n"
                        (format "%1$TF %1$TT" (:upload-date vid))
                        (:video-title vid)
                        (format "https://youtu.be/%s" (:video-id vid))))
              video-info)))

(defn split-video-title
  "Splits the title string into a number of strings, each of which is shorter
   than max width"
  [title max-width]
  (let [words (str/split title #" ")
        [lines last-line _] (reduce (fn [[lines cur-line cur-line-len] next-word]
                                      (if (> (+ cur-line-len 1 (count next-word)) max-width)
                                        [(conj lines cur-line) [next-word] (count next-word)]
                                        [lines (conj cur-line next-word) (+ cur-line-len (count next-word) 1)]))
                                    [[] [] 0]
                                    words)]
    (map #(str/join " " %) (conj lines last-line))))

(defn single-column-format
  "Output the video info in a single column, for narrow displays"
  [video-list]
  (str/join "\n" (map (fn [video-info] (format "%s\n%s\n%s\n"
                                              (format "%1$TF %1$TT" (:upload-date video-info))
                                              (:video-title video-info)
                                              (format "https://youtu.be/%s" (:video-id video-info))))
                     video-list)))

(defn three-column-format
  "Output the video info in a three column format for wider displays"
  [video-list title-width]
  (let [split-titles (map #(split-video-title (:video-title %) title-width) video-list)
        max-title-line-width (apply max (flatten (map #(map count %) split-titles)))
        format-string (format "%%-%ds  %%-%ds  %%-%ds"
                              video-upload-time-width
                              max-title-line-width
                              youtube-link-width)]
    (str/join "\n" (map (fn [video-info]
                          (let [split-title (split-video-title (:video-title video-info) title-width)
                                first-line (format format-string
                                                   (format "%1$TF %1$TT" (:upload-date video-info))
                                                   (first split-title)
                                                   (format "https://youtu.be/%s" (:video-id video-info)))
                                remaining-lines (map #(format format-string " " % " ")
                                                     (rest split-title))]
                            (str/join "\n" (concat [first-line] remaining-lines))))
                        video-list))))

(defn get-env-var
  "Simple wrapper function around System/getenv. This makes it easier to mock
   calls to System/getenv in unit tests."
  [var-name]
  (System/getenv var-name))

(defn print-video-info
  "Prints the video info to STDOUT. OUTPUT-TYPE specifies the format in which
   the output is written. A value of MULTI specifies the multi-column format.
   SINGLE specifies the single-column format. TSV specifies the TSV format
   (suitable for redirecting into a file)."
  [video-list output-type]
  (if (= (Integer/parseInt (get-env-var "FILE_OUTPUT")) 1)
    (unformatted-output video-list)
    (if (>= (Integer/parseInt (get-env-var "COLUMNS")) wide-terminal-width)
      (let [title-width (- (Integer/parseInt (get-env-var "COLUMNS")) youtube-link-width video-upload-time-width 4)]
        (println (three-column-format video-list title-width)))
      (println (single-column-format video-list)))))

(defn print-help []
  (println "Please provide a YouTube channel URL"))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [parsed-options (parse-opts args cli-options)]))
