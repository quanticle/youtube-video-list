(ns youtube-video-list.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
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
    (is (= (get-video-data {:items [{:snippet {:title "Test video"}
                                     :contentDetails {:videoId "test_id"
                                                      :videoPublishedAt "test date"}}]})
           '({:video-title "Test video"
              :video-id "test_id"
              :upload-date "test date"}))))
  (testing "Multiple videos"
    (is (= (get-video-data {:items
                            [{:snippet {:title "Test video 1"}
                              :contentDetails {:videoId "test_id_1"
                                               :videoPublishedAt "test date 1"}}
                             {:snippet {:title "Test video 2"}
                              :contentDetails {:videoId "test_id_2"
                                               :videoPublishedAt "test date 2"}}]})
           '({:video-title "Test video 1"
              :video-id "test_id_1"
              :upload-date "test date 1"}
             {:video-title "Test video 2"
              :video-id "test_id_2"
              :upload-date "test date 2"})))))
