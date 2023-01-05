(ns youtube-video-list.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http-client]
            [clojure.data.json :as json])
  (:gen-class))

(def client-key-file-name "client-key")

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
  (map #(hash-map :video-title (get-in % [:snippet :title])
          :video-id (get-in % [:contentDetails :videoId])
          :upload-date (get-in % [:contentDetails :videoPublishedAt]))
       (:items playlist-items-response)))


          
      

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
