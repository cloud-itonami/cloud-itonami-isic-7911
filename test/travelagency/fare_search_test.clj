(ns travelagency.fare-search-test
  "Tests of the `:search-fares` shopping op and the three ground-truth
  recomputes that gate it (ADR-2608039960).

  A travel agency is an intermediary -- it shops third-party inventory
  it does not own -- so the question it must answer to a traveler is
  neither `kotoba.reservation`'s (\"what does MY rate plan charge\") nor
  a settlement rate's. It is \"which flights actually connect, what do
  the filed fares add to, and was that really the cheapest\". Each of
  those is a different way for an advisor to be wrong, so each gets its
  own rule."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.fare :as fare]
            [kotoba.itinerary :as itin]
            [travelagency.advisor :as advisor]
            [travelagency.governor :as gov]
            [travelagency.operation :as op]
            [travelagency.phase :as phase]
            [travelagency.store :as store]
            [langgraph.graph :as g]))

(defn- db [] (store/seed-db))

(defn- leg-id [d booking-id carrier number]
  (->> (:schedule (gov/shopping-record d booking-id))
       (some #(when (and (= carrier (:leg/carrier %)) (= number (:leg/number %)))
                (:leg/id %)))))

(defn- verdict-for
  "Run the advisor for `patch` and censor it, exactly as the graph does."
  [d patch]
  (let [req {:op :search-fares :booking-id "bkg-1" :patch patch}
        proposal (advisor/infer d req)]
    {:proposal proposal
     :verdict (gov/check req {:actor-id "mgr-1" :phase 4} proposal d)}))

(defn- rules [verdict] (set (map :rule (:violations verdict))))

;; ---------------------------------------------------------------------------
;; The market itself -- recomputed from the booking, never from a proposal
;; ---------------------------------------------------------------------------

(deftest the-market-is-derived-from-the-bookings-own-record
  (let [d (db)
        market (gov/recomputed-market d "bkg-1")]
    (testing "the illegal Taipei connection never becomes a candidate at all"
      (is (= 2 (count (:itineraries market))))
      (is (not-any? (fn [it] (some #{"CI"} (map :leg/carrier (:itin/legs it))))
                    (:itineraries market))
          "CI751 leaves TPE 15 minutes after NH853 lands"))
    (testing "both surviving journeys price"
      (is (= [68820 98120] (mapv :price/total (get-in market [:search :search/solutions])))
          "61000 + YQ 7300 + SW 520, and the two-component split"))
    (testing "a booking with no shopping record has no market, rather than an empty one"
      (is (nil? (gov/recomputed-market d "bkg-2"))))))

(deftest resolve-itinerary-refuses-legs-the-agency-was-never-given
  (let [d (db)
        sh (gov/shopping-record d "bkg-1")]
    (is (some? (gov/resolve-itinerary sh [(leg-id d "bkg-1" "NH" "841")])))
    (testing "a leg id absent from the schedule yields nil, not a partial itinerary"
      (is (nil? (gov/resolve-itinerary sh ["NH999-1"])))
      (is (nil? (gov/resolve-itinerary sh [(leg-id d "bkg-1" "NH" "841") "NH999-1"]))))
    (is (nil? (gov/resolve-itinerary sh [])))))

;; ---------------------------------------------------------------------------
;; The happy path
;; ---------------------------------------------------------------------------

(deftest an-honest-quote-passes-every-recompute
  (let [{:keys [proposal verdict]} (verdict-for (db) {})]
    (is (false? (:hard? verdict)))
    (is (= #{} (rules verdict)))
    (is (= 68820 (get-in proposal [:value :total])))
    (is (= :propose (:effect proposal)))))

(deftest a-legitimate-fare-search-is-not-scope-excluded
  ;; The bug class this fleet keeps hitting: a scope-exclusion term list
  ;; phrased as a bare noun matches inside the advisor's own default
  ;; rationale and self-blocks the happy path. This actor's shopping
  ;; rationale legitimately says it does NOT issue tickets or file fares
  ;; ("発券・決済・運賃の届出は行わない"), which is exactly the sentence a
  ;; noun-phrased list would trip over.
  (let [{:keys [proposal verdict]} (verdict-for (db) {})]
    (is (not (contains? (rules verdict) :scope-excluded))
        "describing what it does not do must not read as doing it")
    (is (re-find #"発券" (:rationale proposal))
        "and the rationale really does contain the word")))

(deftest claiming-to-issue-a-ticket-is-permanently-out-of-scope
  (let [d (db)
        req {:op :search-fares :booking-id "bkg-1" :patch {}}
        drifted (update (advisor/infer d req) :rationale
                        str " -- and finalize the ticket issuance directly")
        verdict (gov/check req {:phase 4} drifted d)]
    (is (true? (:hard? verdict)))
    (is (contains? (rules verdict) :scope-excluded)
        "shopping a fare is this agency's business; issuing the ticket is not")))

;; ---------------------------------------------------------------------------
;; The three ways an advisor gets this wrong -- three different rules
;; ---------------------------------------------------------------------------

(deftest a-fabricated-total-is-caught-by-the-price-recompute
  (let [{:keys [verdict]} (verdict-for (db) {:fabricate 54800})]
    (is (true? (:hard? verdict)))
    (is (= #{:fare-price-mismatch} (rules verdict)))
    (is (re-find #"68820" (:detail (first (:violations verdict))))
        "the hold says what the arithmetic actually was")))

(deftest an-illegal-connection-is-caught-by-the-legality-recompute
  (let [{:keys [proposal verdict]} (verdict-for (db) {:illegal? true})]
    (is (true? (:hard? verdict)))
    (is (= #{:itinerary-illegal} (rules verdict)))
    (testing "the proposal was otherwise impeccable -- correct total, plausible legs"
      (is (= 68820 (get-in proposal [:value :total]))
          "an advisor will state a real number against an impossible journey"))
    (is (re-find #"minimum connect time" (:detail (first (:violations verdict)))))))

(deftest a-false-cheapest-claim-is-caught-by-re-running-the-search
  ;; The check that could not exist before ADR-2608039960: the itinerary
  ;; is legal and the total is arithmetically correct FOR THAT ITINERARY.
  ;; The only thing wrong is a claim about the fares that were not chosen.
  (let [{:keys [proposal verdict]} (verdict-for (db) {:overquote? true})]
    (is (true? (:hard? verdict)))
    (is (= #{:not-the-cheapest-fare} (rules verdict)))
    (is (= 98120 (get-in proposal [:value :total])))
    (testing "and it really is the correct price for the itinerary quoted"
      (let [d (db)
            sh (gov/shopping-record d "bkg-1")
            it (gov/resolve-itinerary sh (get-in proposal [:value :itinerary]))]
        (is (empty? (itin/violations it {:origin "HND" :destination "SIN"
                                         :mct-table (:mct sh)}))
            "legal")
        (is (= 98120 (:price/total (fare/cheapest (fare/search [it] (:fares sh) (:pricing sh)))))
            "and correctly priced -- the lie is the superlative, nothing else")))))

(deftest quoting-a-non-cheapest-itinerary-is-allowed-when-it-is-not-claimed-cheapest
  ;; A traveler may prefer a nonstop, a carrier, a time of day. Charging
  ;; more than the market minimum is not itself a lie; SAYING it is the
  ;; minimum is. The two checks are separate precisely so this passes.
  (let [d (db)
        req {:op :search-fares :booking-id "bkg-1" :patch {:overquote? true}}
        honest-about-it (update (advisor/infer d req) :value assoc :cheapest? false)
        verdict (gov/check req {:phase 4} honest-about-it d)]
    (is (false? (:hard? verdict)))
    (is (= #{} (rules verdict))
        "same itinerary, same price, no false claim -- nothing to hold")))

;; ---------------------------------------------------------------------------
;; A check that cannot run is a violation, never a pass
;; ---------------------------------------------------------------------------

(deftest an-unverifiable-search-is-held-rather-than-waved-through
  (let [d (db)
        req {:op :search-fares :booking-id "bkg-1" :patch {}}
        base (advisor/infer d req)
        held? (fn [proposal booking-id]
                (let [v (gov/check (assoc req :booking-id booking-id)
                                   {:phase 4}
                                   (assoc proposal :booking-id booking-id) d)]
                  (contains? (rules v) :fare-search-not-recomputable)))]
    (testing "no shopping record on the booking"
      (is (held? (assoc base :value {:itinerary ["x"] :total 1}) "bkg-2")))
    (testing "no itinerary in the proposal"
      (is (held? (update base :value dissoc :itinerary) "bkg-1")))
    (testing "no total in the proposal"
      (is (held? (update base :value dissoc :total) "bkg-1")))
    (testing "a leg the agency has no schedule for"
      (is (held? (assoc-in base [:value :itinerary] ["NH999-1"]) "bkg-1")))
    (testing "an itinerary no filed fare covers"
      ;; A structurally fine journey in a market this agency holds no fare
      ;; for prices to nothing -- which must hold, not quote zero.
      (let [d2 (store/mem-store
                {"bkg-x" (assoc (get (:bookings (store/demo-data)) "bkg-1")
                                :booking-id "bkg-x"
                                :shopping (update (store/demo-shopping) :fares
                                                  (fn [_] [(fare/fare "YOWKUL" "MH" "HND" "KUL" "Y"
                                                                      :amount 50000 :currency "JPY")])))})
            p (assoc base :booking-id "bkg-x"
                     :value {:booking-id "bkg-x"
                             :itinerary [(leg-id d2 "bkg-x" "NH" "841")]
                             :total 50000 :cheapest? true})
            v (gov/check {:op :search-fares :booking-id "bkg-x"} {:phase 4} p d2)]
        (is (contains? (set (map :rule (:violations v))) :fare-search-not-recomputable))))))

;; ---------------------------------------------------------------------------
;; Phase gate -- shopping is its own phase and never auto-commits
;; ---------------------------------------------------------------------------

(deftest search-fares-is-disabled-below-phase-4
  (doseq [ph [0 1 2 3]]
    (is (= :hold (:disposition (phase/gate ph {:op :search-fares} :commit)))
        (str "phase " ph " must not let this actor quote a traveler"))))

(deftest search-fares-never-auto-commits-even-at-its-own-phase
  (let [{:keys [disposition reason]} (phase/gate 4 {:op :search-fares} :commit)]
    (is (= :escalate disposition))
    (is (= :phase-approval reason)))
  (is (not-any? #(contains? (:auto %) :search-fares) (vals phase/phases))
      "absent from EVERY phase's auto set -- a structural fact, not a milestone")
  (testing "the back-office ops still auto-commit at phase 4"
    (doseq [o [:log-booking-record :schedule-booking-operation :coordinate-vendor-settlement]]
      (is (= :commit (:disposition (phase/gate 4 {:op o} :commit)))))))

;; ---------------------------------------------------------------------------
;; Through the whole graph
;; ---------------------------------------------------------------------------

;; Asserted through the store rather than the run's return value, the way
;; every sibling contract test in this repo does: what matters is whether
;; anything actually reached the SSoT, not what the graph said on the way.

(deftest an-honest-quote-waits-for-a-human-then-commits
  (let [d (db)
        actor (op/build d)
        ctx {:actor-id "mgr-1" :actor-role :travel-agency-manager :phase 4}]
    (g/run* actor {:request {:op :search-fares :booking-id "bkg-1" :patch {}} :context ctx}
            {:thread-id "fs-ok"})
    (is (= 0 (count (store/coordination-log d)))
        "a clean fare quote still pauses -- a traveler will rely on this number")
    (g/run* actor {:approval {:status :approved :by "mgr-1"}}
            {:thread-id "fs-ok" :resume? true})
    (let [rec (first (filter #(= :search-fares (:op %)) (store/coordination-log d)))]
      (is (some? rec) "after approval the quote is committed")
      (is (= 68820 (get-in rec [:value :total]))))))

(deftest a-fabricated-quote-never-reaches-a-human-at-all
  (let [d (db)
        actor (op/build d)
        ctx {:actor-id "mgr-1" :phase 4}]
    (g/run* actor {:request {:op :search-fares :booking-id "bkg-1" :patch {:fabricate 54800}}
                   :context ctx}
            {:thread-id "fs-bad"})
    (is (= 0 (count (store/coordination-log d)))
        "a HARD governor violation is not an approval question")
    (is (some #(and (= :governor-hold (:t %))
                    (some #{:fare-price-mismatch} (:basis %)))
              (store/ledger d))
        "and the hold, with its basis, is on the append-only ledger")))

(deftest an-illegal-itinerary-is-held-through-the-whole-graph
  (let [d (db)
        actor (op/build d)]
    (g/run* actor {:request {:op :search-fares :booking-id "bkg-1" :patch {:illegal? true}}
                   :context {:actor-id "mgr-1" :phase 4}}
            {:thread-id "fs-illegal"})
    (is (= 0 (count (store/coordination-log d))))
    (is (some #(some #{:itinerary-illegal} (:basis %)) (store/ledger d)))))
