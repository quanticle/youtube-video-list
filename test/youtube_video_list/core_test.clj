(ns youtube-video-list.core-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.instant :as time]
            [clojure.string :as str]
            [mockery.core :refer :all]
            [youtube-video-list.core :refer :all]))

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
                   :key "mock api key"
                   :id "test video id 1,test video id 2"
                   :part "id,contentDetails"}]
                 (:call-args @mock-http)))
          (is (= [["test-duration-1"] ["test-duration-2"]]
                 (:call-args-list @mock-parse-video-length))))))))

(deftest test-unformatted-output
  (testing "Test unformatted output"
    (with-mock mock-printf {:target :clojure.core/printf}
      (unformatted-output [{:video-id "vid_id_1"
                            :video-title "Vid Title 1"
                            :upload-date (time/read-instant-date "2022-12-23T17:32:25Z")}
                           {:video-id "vid_id_2"
                            :video-title "Vid Title 2"
                            :upload-date (time/read-instant-date "2022-12-11T11:43:17Z")}])
      (is (= (:call-args-list @mock-printf)
             [["%s\t%s\t%s\n" (format "%1$TF %1$TT" (time/read-instant-date "2022-12-23T17:32:25Z")) "Vid Title 1" "https://youtu.be/vid_id_1"]
              ["%s\t%s\t%s\n" (format "%1$TF %1$TT" (time/read-instant-date "2022-12-11T11:43:17Z")) "Vid Title 2" "https://youtu.be/vid_id_2"]])))))

(deftest test-split-video-title
  (testing "Split video title"
    (is (= (split-video-title "The quick brown fox jumps over the lazy dog" 20) ["The quick brown fox" "jumps over the lazy" "dog"]))))

(deftest test-single-column
  (testing "Single column display"
    (is (= (single-column-format [(map->video-info {:video-id "test_video_id"
                                                    :video-title "Test video title"
                                                    :upload-date (time/read-instant-date "2022-12-17T15:34:27Z")})])
           (format "%s\n%s\n%s\n"
                   (format "%1$TF %1$TT" (time/read-instant-date "2022-12-17T15:34:27Z"))
                   "Test video title"
                   "https://youtu.be/test_video_id")))))

(deftest test-three-column
  (testing "Three column output"
    (is (= (three-column-format [(map->video-info {:video-id "test_video_id"
                                                   :video-title "The quick brown fox jumps over the lazy dog"
                                                   :upload-date (time/read-instant-date "2022-12-17T17:43:45Z")})]
                                20)
           (str/join "\n"
            [(format "%s  %-19s  %s"
                    (format "%1$TF %1TT" (time/read-instant-date "2022-12-17T17:43:45Z"))
                    "The quick brown fox"
                    "https://youtu.be/test_video_id")
             (format "%19s  %-19s  %28s" " " "jumps over the lazy" " ")
             (format "%19s  %-19s  %28s" " " "dog" " ")]))))
  (testing "Three column output, max width adequate"
    (is (= (three-column-format [(map->video-info {:video-id "test_video_id"
                                                   :video-title "The quick brown fox jumps over the lazy dog"
                                                   :upload-date (time/read-instant-date "2022-12-17T17:43:45Z")})]
                                80)
           (format "%s  %s  %s"
                   (format "%1$TF %1$TT" (time/read-instant-date "2022-12-17T17:43:45Z"))
                   "The quick brown fox jumps over the lazy dog"
                   "https://youtu.be/test_video_id")))))

(deftest test-print-video-info
  (testing "Verify that print-video-info accesses the correct environment variables"
    (with-mocks [mock-env {:target :youtube-video-list.core/get-env-var
                           :return (fn [env-var]
                                     (cond (= env-var "COLUMNS") "80"
                                           (= env-var "FILE_OUTPUT") "0"))}
                 mock-three-column-format {:target :youtube-video-list.core/three-column-format}]
      (print-video-info ["test"] nil)
      (is (= (:call-args-list @mock-three-column-format) ['(["test"] 29)])))))
