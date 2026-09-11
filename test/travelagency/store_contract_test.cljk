(ns travelagency.store-contract-test
  "Contract tests for `travelagency.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [travelagency.store :as store]))

(deftest mem-store-booking-lookup
  (testing "MemStore can store and retrieve bookings by ID (string keys)"
    (let [bookings {"b1" {:booking-id "b1" :name "Booking 1" :registered? true :verified? true}}
          s (store/mem-store bookings)]
      (is (some? (store/booking s "b1")))
      (is (nil? (store/booking s "b99"))))))

(deftest mem-store-all-bookings
  (testing "MemStore returns all bookings in sorted order"
    (let [bookings {"b2" {:booking-id "b2" :name "Booking 2"}
                    "b1" {:booking-id "b1" :name "Booking 1"}
                    "b3" {:booking-id "b3" :name "Booking 3"}}
          s (store/mem-store bookings)
          all-b (store/all-bookings s)]
      (is (= 3 (count all-b)))
      (is (= "b1" (:booking-id (first all-b))))
      (is (= "b3" (:booking-id (last all-b)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-booking-record :booking-id "b1" :value {:traveler "test"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-bookings
  (testing "MemStore with-bookings replaces the booking directory"
    (let [s (store/mem-store {})
          new-bookings {"b1" {:booking-id "b1" :name "Booking 1"}}]
      (is (= 0 (count (store/all-bookings s))))
      (store/with-bookings s new-bookings)
      (is (= 1 (count (store/all-bookings s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo bookings"
    (let [s (store/seed-db)]
      (is (> (count (store/all-bookings s)) 0))
      (is (some? (store/booking s "bkg-1")))
      (is (some? (store/booking s "bkg-2")))
      (is (some? (store/booking s "bkg-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for booking-id"
    (let [demo (store/demo-data)
          bookings (:bookings demo)]
      (doseq [[k v] bookings]
        (is (string? k) "keys must be strings")
        (is (string? (:booking-id v)) "booking-id must be string")
        (is (= k (:booking-id v)) "key must match booking-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
