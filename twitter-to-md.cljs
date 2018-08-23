#!/usr/bin/env lumo
(ns twitter-to-md.core
  (:require
   [cljs.core :refer [*command-line-args*]]
   [cljs.reader :as edn]
   [clojure.set :refer [rename-keys]]
   [clojure.string :as string]
   ["fs" :as fs]
   ["http" :as http]
   ["https" :as https]
   ["twitter" :as twitter]))

(defn exit-with-error [error]
  (js/console.error error)
  (js/process.exit 1))

(defn find-config []
  (or (first *command-line-args*)
      (-> js/process .-env .-TWITTER_BOT_CONFIG)
      "config.edn"))

(def config (-> (find-config) (fs/readFileSync #js {:encoding "UTF-8"}) edn/read-string))

(def access-keys (or (:access-keys config clj->js)
                     (exit-with-error "missing Twitter client configuration!")))

(def content-filter-regexes (mapv re-pattern (:content-filters config)))

(defn blocked-content? [text]
 (boolean (some #(re-find % text) content-filter-regexes)))

(defn js->edn [data]
  (js->clj data :keywordize-keys true))

(defn format-date [date-str]
  (.toLocaleDateString (js/Date. date-str) "en-US" #js {:year "numeric" :month "short" :day "numeric"}))

(defn format-links [links]
  (string/join
   (for [link (keep #(when (= (:type %) "photo") (:media_url_https %)) links)]
    (str "\n![" link "](" link ")"))))

(defn parse-tweet [{created-at            :created_at
                    text                  :text
                    {:keys [media]}       :extended_entities
                    {:keys [screen_name]} :user :as tweet}]
  (str "### " screen_name " - " (format-date created-at) "\n" text (format-links media)))

(defn post-items [items]
  (.writeFileSync fs (:output-file config) (string/join "\n\n" items)))

(defn post-tweets []
  (fn [error tweets response]
    (->> (js->edn tweets)
         (map parse-tweet)
         (post-items))))

(defn twitter-client []
  (try
    (twitter. (clj->js access-keys))
    (catch js/Error e
      (exit-with-error
       (str "failed to connect to Twitter: " (.-message e))))))

(let [{:keys [account include-replies? include-rts?]} config
     client (twitter-client)]
     (.get client
           "statuses/user_timeline"
           #js {:screen_name account
                :include_rts (boolean include-replies?)
                :exclude_replies (boolean include-rts?)}
           (post-tweets)))
