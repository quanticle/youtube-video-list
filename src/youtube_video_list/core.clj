(ns youtube-video-list.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http-client]
            [clojure.data.json :as json]
            [clojure.instant :as time])
  (:gen-class))

(def client-key-file-name "client-key")

(defrecord video-info [upload-date video-title video-id])

(defn load-client-key [file-name]
  "Loads the client key from the given file. The file is assumed to have just 
   the API key, with a possible trailing newline."
  (str/trim (slurp (io/resource file-name))))

(defn get-uploads-playlist-id [api-key channel-url]
  "Get the ID of the playlist representing the full list of uploaded video for 
   the given channel. The channel URL can either be one that contains a user ID
   (i.e. https://www.youtube.com/user/nismotv2013/videos) or, one that contains
   the channel ID (i.e. 
   https://www.youtube.com/channel/UCy0tKL1T7wFoYcxCe0xjN6Q/videos)"
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

(defn get-video-data [playlist-items-response]
  "Extracts the fields we're interested in (video title, upload date and video
   ID) from the PlaylistItems API response"
  (map #(map->video-info {:video-title (get-in % [:snippet :title])
                          :video-id (get-in % [:contentDetails :videoId])
                          :upload-date (time/read-instant-date (get-in % [:contentDetails :videoPublishedAt]))})
       (:items playlist-items-response)))

(defn get-video-info-from-playlist [api-key playlist-id]
  "Gets a list of all the videos from the given playlist ID"
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

(defn unformatted-output [video-info]
  "Prints video info in a simple tab-delimited, newline separated format
   suitable for file output"
  (dorun (map (fn [vid]
                (printf "%s\t%s\t%s\n"
                        (format "%1$TF %1$TT" (:upload-date vid))
                        (:video-title vid)
                        (format "https://youtu.be/%s" (:video-id vid))))
              video-info)))

(defn split-video-title [title max-width]
  "Splits the title string into a number of strings, each of which is shorter than max width"
  (let [words (str/split title #" ")
        [lines last-line _] (reduce (fn [[lines cur-line cur-line-len] next-word]
                                      (if (> (+ cur-line-len 1 (count next-word)) max-width)
                                        [(conj lines cur-line) [next-word] (count next-word)]
                                        [lines (conj cur-line next-word) (+ cur-line-len (count next-word) 1)]))
                                    [[] [] 0]
                                    words)]
    (map #(str/join " " %) (conj lines last-line))))

(defn single-column-format [video-info]
  "Output the video info in a single column, for narrow displays"
  (format "%s\n%s\n%s\n"
          (format "%1$TF %1$TT" (:upload-date video-info))
          (:video-title video-info)
          (format "https://youtu.be/%s" (:video-id video-info))))

(defn three-column-format [video-info title-width]
  "Output the video info in a three column format for wider displays"
  (let [split-title (split-video-title (:video-title video-info) title-width)
        first-line (format "%s %s %s"
                           (format "%1$TF %1$TT" (:upload-date video-info))
                           (first split-title)
                           (format "https://youtu.be/%s" (:video-id video-info)))
        remaining-lines (map #(format "%s %s %s"
                                      (str/join (repeat 19 " "))
                                      %
                                      (str/join (repeat 28 " ")))
                             (rest split-title))]
    (str/join "\n" (concat [first-line] remaining-lines))))



(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
