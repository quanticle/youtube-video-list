(ns youtube-video-list.core-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.instant :as time]
            [clojure.string :as str]
            [mockery.core :refer :all]
            [youtube-video-list.core :refer :all])
  (:import [java.time Period LocalDate]))

(deftest test-get-channel-ids-from-search-response
  (testing "Get channel IDs from search response"
    (is (= ["UC6jZ3uNWJKtJokkJxPLA88g" "UCvZC2hMobpo-FOYY6cfgOZg"] 
           (get-channel-ids-from-search-response {:body (slurp (io/resource "search_list_output.txt"))})))))

(deftest test-get-custom-urls-from-channel-response
  (testing "Get custom URLs from channel response, all channels have custom URLs"
    (is (= [{:id "UCvZC2hMobpo-FOYY6cfgOZg" :customUrl "@flamuclips"}
            {:id "UC6jZ3uNWJKtJokkJxPLA88g" :customUrl "@flamuu"}]
           (get-custom-urls-from-channel-data {:body (slurp (io/resource "channels_list_output_allCustomUrls.txt"))}))))
  (testing "Get custom URLs from channel response, some channels don't have custom URLs"
    (is (= [{:id "UCvZC2hMobpo-FOYY6cfgOZg" :customUrl nil}
            {:id "UC6jZ3uNWJKtJokkJxPLA88g" :customUrl "@flamuu"}]
           (get-custom-urls-from-channel-data {:body (slurp (io/resource "channels_list_output_missingCustomUrl.txt"))})))))

(deftest test-get-channel-id-for-custom-url
  (let [mock-valid-http-response {:status 200
                                  :body "Valid response"}
        mock-invalid-http-response {:status 400
                                    :body "Oh no! An error!"}]
    (testing "Get channel ID for custom URL, no errors"
      (with-mocks [mock-http {:target :clj-http.client/get
                              :return mock-valid-http-response}
                   mock-get-channel-ids-from-search-response {:target :youtube-video-list.core/get-channel-ids-from-search-response
                                                              :return ["test_channel_id_1" "test_channel_id_2"]}
                   mock-get-custom-urls-from-channel-data {:target :youtube-video-list.core/get-custom-urls-from-channel-data
                                                           :return [{:id "test_channel_id_1" :customUrl "@custom_url_1"}
                                                                    {:id "test_channel_id_2" :customUrl "@custom_url_2"}]}]
        (is (= "test_channel_id_1" (get-channel-id-for-custom-url "mock-api-key" "Custom_Url_1")))
        (is (= [mock-valid-http-response] (:call-args @mock-get-channel-ids-from-search-response)))
        (is (= [mock-valid-http-response] (:call-args @mock-get-custom-urls-from-channel-data)))
        (is (= [["https://www.googleapis.com/youtube/v3/search" 
                 {:accept :json
                  :query-params {:key "mock-api-key"
                                 :q "@custom_url_1"
                                 :part "snippet"
                                 :maxResults 50
                                 :order "relevance"
                                 :type "channel"}}]
                ["https://www.googleapis.com/youtube/v3/channels"
                 {:accept :json
                  :query-params {:key "mock-api-key"
                                 :id "test_channel_id_1,test_channel_id_2"
                                 :part "snippet"}}]]
               (:call-args-list @mock-http)))))
    (testing "Get channel ID for custom URL, channel ID not found"
      (with-mocks [mock-http {:target :clj-http.client/get
                              :return mock-valid-http-response}
                   mock-get-channel-ids-from-search-response {:target :youtube-video-list.core/get-channel-ids-from-search-response
                                                              :return ["test_channel_id_1" "test_channel_id_2"]}
                   mock-get-custom-urls-from-channel-data {:target :youtube-video-list.core/get-custom-urls-from-channel-data
                                                           :return [{:id "test_channel_id_1" :customUrl "@custom_url_3"}
                                                                    {:id "test_channel_id_2" :customUrl "@custom_url_4"}]}]
        (is (= nil (get-channel-id-for-custom-url "mock-api-key" "custom_url_1")))
        (is (= [mock-valid-http-response] (:call-args @mock-get-channel-ids-from-search-response)))
        (is (= [mock-valid-http-response] (:call-args @mock-get-custom-urls-from-channel-data)))))))

(deftest test-get-uploads-playlist-id
  (testing "Get uploads playlist for username"
    (with-mock mock-http
      {:target :clj-http.client/get
       :return {:body (slurp (io/resource "channels_forUsername_output.txt"))
                :status 200}}
      (is (= (get-uploads-playlist-id "test-api-key" "https://www.youtube.com/user/nismotv2013/videos") "UUaTxfj0BzL-MaCy-YUqPRoQ"))
      (is (= (:call-args @mock-http)
             ["https://www.googleapis.com/youtube/v3/channels"
              {:accept :json
               :query-params {
                              :key "test-api-key"
                              :forUsername "nismotv2013"
                              :part "contentDetails"}}]))))
  (testing "Get uploads playlist for channel ID"
    (with-mock mock-http
      {:target :clj-http.client/get
       :return {:body (slurp (io/resource "channels_channelId_output.txt"))
                :status 200}}
      (is (= (get-uploads-playlist-id "test-api-key" "https://www.youtube.com/channel/UCoSrY_IQQVpmIRZ9Xf-y93g/videos") "UUoSrY_IQQVpmIRZ9Xf-y93g"))
      (is (= (:call-args @mock-http)
             ["https://www.googleapis.com/youtube/v3/channels"
              {:accept :json
               :query-params {
                              :key "test-api-key"
                              :id "UCoSrY_IQQVpmIRZ9Xf-y93g"
                              :part "contentDetails"}}]))))
  (testing "Get uploads playlist for custom URL"
    (with-mocks [mock-get-channel-id-for-custom-url {:target :youtube-video-list.core/get-channel-id-for-custom-url
                                                     :return "UCoSrY_IQQVpmIRZ9Xf-y93g"}
                 mock-http {:target :clj-http.client/get
                            :return {:body (slurp (io/resource "channels_channelId_output.txt"))
                                     :status 200}}]
      (is (= "UUoSrY_IQQVpmIRZ9Xf-y93g" (get-uploads-playlist-id "test-api-key" "https://www.youtube.com/c/Flamuu")))
      (is (= ["https://www.googleapis.com/youtube/v3/channels"
              {:accept :json
               :query-params {
                              :key "test-api-key"
                              :id "UCoSrY_IQQVpmIRZ9Xf-y93g"
                              :part "contentDetails"}}]
             (:call-args @mock-http)))
      (is (= ["test-api-key" "Flamuu"] (:call-args @mock-get-channel-id-for-custom-url)))))
  (testing "Get uploads playlist for Twitter-style URL"
    (with-mocks [mock-get-channel-id-for-custom-url {:target :youtube-video-list.core/get-channel-id-for-custom-url
                                                     :return "UCoSrY_IQQVpmIRZ9Xf-y93g"}
                 mock-http {:target :clj-http.client/get
                            :return {:body (slurp (io/resource "channels_channelId_output.txt"))
                                     :status 200}}]
      (is (= "UUoSrY_IQQVpmIRZ9Xf-y93g" (get-uploads-playlist-id "test-api-key" "https://www.youtube.com/@Flamuu")))
      (is (= ["https://www.googleapis.com/youtube/v3/channels"
              {:accept :json
               :query-params {
                              :key "test-api-key"
                              :id "UCoSrY_IQQVpmIRZ9Xf-y93g"
                              :part "contentDetails"}}]
             (:call-args @mock-http)))
      (is (= ["test-api-key" "Flamuu"] (:call-args @mock-get-channel-id-for-custom-url)))))
  (testing "Invalid URL"
    (is (thrown-with-msg? Exception #"Could not determine" (get-uploads-playlist-id "test-api-key" "https://www.google.com")))))


(deftest test-get-video-data
  (testing "No videos"
    (is (= (get-video-data {:items []}) [])))
  (testing "One video"
    (with-mock mock-date {:target :clojure.instant/read-instant-date
                          :return "foo"}
      (is (= (get-video-data {:items [{:snippet {:title "Test video"}
                                       :contentDetails {:videoId "test_id"
                                                        :videoPublishedAt "test_date"}}]})
             [(map->video-info {:video-title "Test video"
                :video-id "test_id"
                :upload-date "foo"})]))
      (is (= (:call-args @mock-date) ["test_date"]))))
  (testing "Multiple videos"
    (with-mock mock-date {:target :clojure.instant/read-instant-date
                          :return "foo"}
      (is (= (get-video-data {:items
                              [{:snippet {:title "Test video 1"}
                                :contentDetails {:videoId "test_id_1"
                                                 :videoPublishedAt "test date 1"}}
                               {:snippet {:title "Test video 2"}
                                :contentDetails {:videoId "test_id_2"
                                                 :videoPublishedAt "test date 2"}}]})
             [(map->video-info {:video-title "Test video 1"
                                :video-id "test_id_1"
                                :upload-date "foo"})
              (map->video-info {:video-title "Test video 2"
                                :video-id "test_id_2"
                                :upload-date "foo"})]))
      (is (= (:call-args-list @mock-date) [["test date 1"] ["test date 2"]])))))

(deftest test-get-video-info-from-playlist
  (testing "No next page token"
    (with-mocks [mock-http {:target :clj-http.client/get
                            :return {:status 200
                                     :body "{\"info\": \"test data\"}"}}
                 mock-get-video-data {:target :youtube-video-list.core/get-video-data
                                      :return ["test video info"]}]
      (is (= (get-video-info-from-playlist "test api key" "test playlist id") ["test video info"]))
      (is (= (:call-args @mock-http) ["https://www.googleapis.com/youtube/v3/playlistItems"
                                     {:accept :json
                                      :query-params {:key "test api key"
                                                     :playlistId "test playlist id"
                                                     :part "snippet,contentDetails"
                                                     :maxResults 50}}]))))
  (testing "Multiple pages"
    (let [http-response-data (atom [{:status 200
                                     :body "{\"nextPageToken\": \"page-2\",
                                      \"data\": \"data-1\"}"}
                                    {:status 200
                                     :body "{\"nextPageToken\": \"page-3\",
                                      \"data\": \"data-2\"}"}
                                    {:status 200
                                     :body "{\"data\": \"data-3\"}"}])]
      (with-mocks [mock-http {:target :clj-http.client/get
                              :return (fn [& _] (first (first (swap-vals! http-response-data rest))))}
                   mock-get-video-data {:target :youtube-video-list.core/get-video-data}]
        (get-video-info-from-playlist "test api key" "test playlist id")
        (is (= (:call-args-list @mock-http) [["https://www.googleapis.com/youtube/v3/playlistItems"
                                              {:accept :json
                                             :query-params {:key "test api key"
                                                            :playlistId "test playlist id"
                                                            :part "snippet,contentDetails"
                                                            :maxResults 50}}]
                                            ["https://www.googleapis.com/youtube/v3/playlistItems"
                                             {:accept :json
                                             :query-params {:key "test api key"
                                                            :playlistId "test playlist id"
                                                            :part "snippet,contentDetails"
                                                            :maxResults 50
                                                            :pageToken "page-2"}}]
                                            ["https://www.googleapis.com/youtube/v3/playlistItems"
                                             {:accept :json
                                             :query-params {:key "test api key"
                                                            :playlistId "test playlist id"
                                                            :part "snippet,contentDetails"
                                                            :maxResults 50
                                                            :pageToken "page-3"}}]]))
        (is (= (:call-args-list @mock-get-video-data) [[{:nextPageToken "page-2" :data "data-1"}]
                                                       [{:nextPageToken "page-3" :data "data-2"}]
                                                       [{:data "data-3"}]]))))))

(deftest test-parse-video-length
  (testing "Less than one minute long"
    (is (= (parse-video-length "PT36S") "00:00:36")))
  (testing "Less than one hour long"
    (is (= (parse-video-length "PT5M36S") "00:05:36")))
  (testing "More than one hour long"
    (is (= (parse-video-length "PT10H15M24S") "10:15:24"))))

(deftest test-get-video-length-for-partition
  (testing "Get video durations"
    (let [mock-parse-video-lengths-result (atom ["duration 1" "duration 2"])]
      (with-mocks [mock-http {:target :clj-http.client/get
                              :return {:status 200
                                       :body (json/json-str {:items [{:id "test-id-1"
                                                                      :contentDetails {:duration "test-duration-1"}}
                                                                     {:id "test-id-2"
                                                                      :contentDetails {:duration "test-duration-2"}}]}
                                                            :key-fn name)}}
                   mock-parse-video-length {:target :youtube-video-list.core/parse-video-length
                                            :return (fn [& _] (first (first (swap-vals! mock-parse-video-lengths-result rest))))}]
        (let [get-durations-result (get-video-length-for-partition "mock api key"
                                                                   [(->video-info "test upload date 1" "test video title 1" "test video id 1" nil)
                                                                    (->video-info "test upload date 2" "test video title 2" "test video id 2" nil)])]
          (is (= [{:id "test-id-1"
                   :duration "duration 1"}
                  {:id "test-id-2"
                   :duration "duration 2"}]
                 @get-durations-result))
          (is (= ["https://www.googleapis.com/youtube/v3/videos"
                  {:accept :json
                   :query-params {:key "mock api key"
                                  :id "test video id 1,test video id 2"
                                  :part "id,contentDetails"}}]
                 (:call-args @mock-http)))
          (is (= [["test-duration-1"] ["test-duration-2"]]
                 (:call-args-list @mock-parse-video-length))))))))

(defn generate-video-data
  "Returns a lazy-seq of videos with random data whose upload date starts with
   the given date and increments by one day per video"
  ([n ^LocalDate upload-date]
   (lazy-seq (cons (map->video-info {:upload-date upload-date
                                     :video-id (format "test-id-%d" n)
                                     :video-title (format "Test Title %d" n)})
                   (generate-video-data (inc n) (.plus upload-date (Period/ofDays 1))))))
  ([^LocalDate upload-date]
   (generate-video-data 1 upload-date)))

(deftest test-get-all-video-lengths
  (testing "Get all video lengths, one partition"
    (let [mock-api-key "mock-api-key"
          video-data (take 20 (generate-video-data (LocalDate/parse "2023-06-02")))]
      (with-mock mock-get-video-length-for-partition {:target :youtube-video-list.core/get-video-length-for-partition
                                                      :return (future (map #(hash-map :id (:video-id %) :duration "00:01:00") video-data))}
        (let [video-data-with-durations (set-video-lengths mock-api-key video-data)]
          (is (true? (every? #(= (:duration %) "00:01:00") video-data-with-durations)))
          (is (= [mock-api-key video-data] (:call-args @mock-get-video-length-for-partition)))))))
  (testing "Get all video lengths, multiple partitions"
    (let [mock-api-key "mock-api-key"
          video-data (take 100 (generate-video-data (LocalDate/parse "2023-06-02")))
          video-lengths (atom ["00:01:00" "00:02:00"])]
      (with-mock mock-get-video-length-for-partition {:target :youtube-video-list.core/get-video-length-for-partition
                                                      :return (fn [_ video-data]
                                                                (let [video-length (first (first (swap-vals! video-lengths rest)))]
                                                                  (future (map #(hash-map :id (:video-id %) :duration video-length) video-data))))}
        (let [video-data-with-durations-partitioned (partition-all 50 (set-video-lengths mock-api-key video-data))]
          (is (true? (every? #(= (:duration %) "00:01:00") (first video-data-with-durations-partitioned))))
          (is (true? (every? #(= (:duration %) "00:02:00") (second video-data-with-durations-partitioned))))
          (is (= [[mock-api-key (take 50 video-data)]
                  [mock-api-key (take 50 (drop 50 video-data))]]
                 (:call-args-list @mock-get-video-length-for-partition))))))))

(deftest test-tsv-output
  (testing "Test tsv output"
    (with-mock mock-printf {:target :clojure.core/printf}
      (is (= (str/join "\n" [(format "%s\t%s\t%s\t%s"
                                     (format "%1$TF %1$TT" (time/read-instant-date "2022-12-23T17:32:25Z"))
                                     "Vid Title 1"
                                     "00:01:00"
                                     "https://www.youtube.com/watch?v=vid_id_1")
                             (format "%s\t%s\t%s\t%s"
                                     (format "%1$TF %1$TT" (time/read-instant-date "2022-12-11T11:43:17Z"))
                                     "Vid Title 2"
                                     "00:02:00"
                                     "https://www.youtube.com/watch?v=vid_id_2")])
           (tsv-format [{:video-id "vid_id_1"
                         :video-title "Vid Title 1"
                         :upload-date (time/read-instant-date "2022-12-23T17:32:25Z")
                         :duration "00:01:00"}
                        {:video-id "vid_id_2"
                         :video-title "Vid Title 2"
                         :upload-date (time/read-instant-date "2022-12-11T11:43:17Z")
                         :duration "00:02:00"}]))))))

(deftest test-single-column
  (testing "Single column display"
    (is (= (format "Video Title:\t%s\nUpload date:\t%s\nVideo Length:\t%s\nVideo URL:\t%s\n"
                   "Test video title"
                   (format "%1$TF %1$TT" (time/read-instant-date "2022-12-17T15:34:27Z"))
                   "00:01:00"
                   "https://www.youtube.com/watch?v=test_video_id")
           (single-column-format [(map->video-info {:video-id "test_video_id"
                                                    :video-title "Test video title"
                                                    :upload-date (time/read-instant-date "2022-12-17T15:34:27Z")
                                                    :duration "00:01:00"})])))))

(deftest test-validation
  (testing "No URL supplied"
    (is (= {:error "Must specify a channel URL"}
           (validate-args []))))
  (testing "Invalid output format"
    (is (= {:error "Failed to validate \"-o invalid\": Output type must be either \"screen\" or \"tsv\" if specified."}
           (validate-args ["-o" "invalid" "test-url"]))))
  (testing "Invalid option"
    (is (= {:error "Unknown option: \"-t\""}
           (validate-args ["-t" "invalid" "test-url"]))))
  (testing "Valid arguments, no output type specified"
    (is (= {:url "test url" :output-type :screen}
           (validate-args ["test url"]))))
  (testing "Valid arguments, output type specified"
    (is (= {:url "test url" :output-type :tsv}
           (validate-args ["-o" "tsv" "test url"])))))
