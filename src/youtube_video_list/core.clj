(ns youtube-video-list.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http-client]
            [clojure.data.json :as json]
            [clojure.instant :as time]
            [clojure.tools.cli :refer [parse-opts]])
  (:import [java.time Duration]
           [java.time.format DateTimeFormatter]
           [java.text Normalizer Normalizer$Form])
  (:gen-class))

(def client-key-file-name "client-key")
(def youtube-link-width 28)
(def video-upload-time-width 19)
(def video-duration-width 8)
(def cli-options
  [["-o" "--output-type TYPE" "Output Type"
    :parse-fn keyword
    :default :screen
    :validate [#{:screen :tsv} "Output type must be either \"screen\" or \"tsv\" if specified."]]])

(defrecord video-info [upload-date video-title video-id duration])

(defn load-client-key
  "Loads the client key from the given file. The file is assumed to have just
   the API key, with a possible trailing newline."
  [file-name]
  (str/trim (slurp (io/resource file-name))))

(defn get-channel-ids-from-search-response
  "Gets the channel IDs from a search response. Used as part of retrieving the
   channel ID for a custom URL.

   See also: GET-CHANNEL-ID-FOR-CUSTOM-URL"
  [search-response]
  (map (fn [search-result-item]
         (-> search-result-item
             :snippet
             :channelId))
       (-> search-response
             :body
             (json/read-str :key-fn keyword)
             :items)))

(defn get-custom-urls-from-channel-data
  "Gets the custom URLs from the channel info response. Used as part of
   retrieving the channel ID for a custom URL. Returns a sequence of hashmaps,
   where each map has an :ID field, with the channel ID, and a :CUSTOM-URL field
   with the custom URL. If the channel does not have a custom URL, :CUSTOM-URL
   will be NIL."
  [channels-response]
  (map (fn [channel-result-item]
         {:id (:id channel-result-item)
          :customUrl (-> channel-result-item
                         :snippet
                         :customUrl)})
       (-> channels-response
           :body
           (json/read-str :key-fn keyword)
           :items)))

(defn get-channel-id-for-custom-url
  "I hate that this function has to exist. Ideally, the YouTube channels API
   would allow the caller to search by custom URL, just as it allows for
   searching by user ID or channel ID. But no, there's no direct way to retrieve
   the channel ID if all you have is a custom URL.

   Instead, what you need to do is *search* for the custom name, *pray* that the
   channel you want comes back in the first page of results (prayer is left as 
   an exercise for the reader), then query the channel API with the IDs of the
   channels returned by the search. Channels with custom URLs will have a
   :customUrl parameter inside the :snippet.

   Okay, so all we have to do is write a simple filter function that compares
   the values of :customUrl against the value of the custom URL, right? Hahaha
   no. Did you really think that Google would make it this easy? The value of
   :customUrl is of the format \"@name\", all lowercase. But when a custom URL
   shows up in the address bar, it's capitalized and doesn't have the @
   prepended. So, for example: https://www.youtube.com/c/Flamuu translates to
   a :customUrl value of \"@flamuu\". So before we can compare, we need to make
   sure to lowercase the URL value and prepend it with \"@\".

   In conclusion: can I have some of whatever it was the designers and
   implementers of this API were smoking?

   References:
   - https://stackoverflow.com/questions/37267324#65218781
   - https://issuetracker.google.com/issues/174903934?pli=1"
  [api-key custom-url]
  (let [custom-url-api-version (format "@%s" (str/lower-case custom-url))
        search-response (http-client/get "https://www.googleapis.com/youtube/v3/search"
                                         {:accept :json
                                          :query-params {:key api-key
                                                         :q custom-url-api-version
                                                         :part "snippet"
                                                         :maxResults 50
                                                         :order "relevance"
                                                         :type "channel"}})]
    (if (= (:status search-response) 200)
      (let [channel-ids (get-channel-ids-from-search-response search-response)
            channel-data-response (http-client/get "https://www.googleapis.com/youtube/v3/channels"
                                                   {:accept :json
                                                    :query-params {:key api-key
                                                                   :id (str/join "," channel-ids)
                                                                   :part "snippet"}})]
        (if (= (:status channel-data-response) 200)
          (->> channel-data-response
               get-custom-urls-from-channel-data
               (filter #(= custom-url-api-version (:customUrl %)))
               first
               :id)
          (throw (Exception. (format "Error retrieving custom URLs for channels: %d\n%s" 
                                     (:status channel-data-response)
                                     (:body channel-data-response))))))
      (throw (Exception. (format "Error searching for channels for the given custom URL: %s: %d\n%s"
                                 custom-url-api-version
                                 (:status search-response)
                                 (:body search-response)))))))

(defn get-uploads-playlist-id
  "Get the ID of the playlist representing the full list of uploaded video for
   the given channel. The channel URL can either be one that contains a user ID
   (i.e. https://www.youtube.com/user/nismotv2013/videos) or, one that contains
   the channel ID (i.e.
   https://www.youtube.com/channel/UCy0tKL1T7wFoYcxCe0xjN6Q/videos)"
  [api-key channel-url]
  (let [user-id (re-find #"/user/([^/]+)/?" channel-url)
        custom-url (or (re-find #"/c/([^/]+)/?" channel-url)
                       (re-find #"/@([^@]+)/?" channel-url))
        channel-id (if custom-url
                     [nil (get-channel-id-for-custom-url api-key (custom-url 1))]
                     (re-find #"/channel/([^/]+)/?" channel-url))
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
             (filter #(get-in % [:contentDetails :duration] false) (:items response-data)))))))

(defn set-video-lengths
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

(defn tsv-format
  "Prints video info in a simple tab-delimited, newline separated format
   suitable for file output"
  [video-info]
  (str/join "\n" (map (fn [vid]
                        (format "%s\t%s\t%s\t%s"
                                (format "%1$TF %1$TT" (:upload-date vid))
                                (:video-title vid)
                                (:duration vid)
                                (format "https://www.youtube.com/watch?v=%s" (:video-id vid))))
                      video-info)))

(defn single-column-format
  "Output the video info in a single column, for screen output"
  [video-list]
  (str/join "\n" (map (fn [video-info] (format "Video Title:\t%s\nUpload date:\t%s\nVideo Length:\t%s\nVideo URL:\t%s\n"
                                               (:video-title video-info)
                                               (format "%1$TF %1$TT" (:upload-date video-info))
                                               (:duration video-info)
                                               (format "https://www.youtube.com/watch?v=%s" (:video-id video-info))))
                     video-list)))


(defn print-video-info
  "Prints video info to STDOUT with PRINTLN. If OUTPUT-TYPE is :SCREEN, print
   the video data in single column format. If OUTPUT-TYPE is :TSV, print the
   video data in tab-separated format. OUTPUT-TYPE is guaranteed by the
   validation code to be one of :SCREEN or :TSV."
  [video-list output-type]
  (case output-type
    :screen (println (single-column-format video-list))
    :tsv (println (tsv-format video-list))))

(defn print-help-and-error 
  "Prints the error message and a short usage summary."
  [error]
  (println error)
  (println "Usage: java -jar youtube-video-list-0.1.0-SNAPSHOT-standalone.jar [-o OUTPUT-TYPE] <youtube channel URL>")
  (println "OUTPUT-TYPE may be one of \"screen\" or \"tsv\". If \"screen\" the program will list the videos from the channel. If \"tsv\" the program will output video information in TSV format, suitable for writing to a file.")
  (println "By default OUTPUT-TYPE is \"screen\"."))

(defn validate-args 
  "Validates command line arguments."
  [args]
  (let [parsed-options (parse-opts args cli-options)]
    (cond 
      (> 1 (count (:arguments parsed-options))) {:error "Must specify a channel URL"}
      (:errors parsed-options) {:error (first (:errors parsed-options))}
      :else {:url (first (:arguments parsed-options))
             :output-type (:output-type (:options parsed-options))})))

(defn get-videos-from-youtube-channel 
  "The actual entry point for the program. When testing from the REPL, call this
   instead of -MAIN."
  [& args]
  (let [validation-result (validate-args args)]
    (if (:error validation-result)
      (print-help-and-error (:error validation-result))
      (let [api-key (load-client-key client-key-file-name)
            channel-url (:url validation-result)
            uploads-playlist-id (get-uploads-playlist-id api-key channel-url)
            video-data (set-video-lengths api-key (get-video-info-from-playlist api-key uploads-playlist-id))]
        (print-video-info video-data (:output-type validation-result))))))

(defn -main
  "Stub entry point for command-line usage. DO NOT call this from the REPL, as
   the (shutdown-agents) shuts down all background threads, including the ones
   that are required for nREPL to communicate with emacs.

   See also: https://clojure.atlassian.net/browse/CLJ-124 for why the
   (shutdown-agents) call is necessary."
  [& args]
  (apply get-videos-from-youtube-channel args)
  (shutdown-agents))
