(ns travelagency.store
  "SSoT for the ISIC-7911 (Travel agency activities -- booking and
  selling third-party travel products such as flights, accommodation
  and transfers as an intermediary on behalf of travelers)
  OPERATIONS-COORDINATION actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a travel
  agency: booking/itinerary/payment-status data logging, booking/
  confirmation scheduling, airline/hotel/vendor settlement
  coordination, and payment-dispute/cancellation/fraud concern
  flagging. It NEVER directly finalizes a payment-dispute resolution
  or a refund/cancellation-policy override -- see
  `travelagency.governor`'s `scope-exclusion-violations`, a HARD,
  permanent, un-overridable block, per this fleet's Wave 4
  person-facing-service safety guardrail (ADR-2607152500).

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). A `bookings` directory keyed by `:booking-id` STRING
  (never a keyword -- consistent keying from the start, avoiding the
  silent-miss bug that plagued an earlier shepherd attempt). Each
  entry covers either a traveler's booking/itinerary or an airline/
  hotel/vendor settlement contract -- both share the same registered/
  verified lifecycle before any coordination proposal may touch them.

  A registered/verified booking-or-vendor-contract record must exist
  before ANY proposal for it may ever commit or escalate --
  `travelagency.governor`'s `booking-unverified-violations` re-derives
  this from the record's own `:registered?`/`:verified?` fields, never
  from a proposal's self-report, the SAME 'ground truth, not
  self-report' discipline every sibling actor's own governor uses.

  A booking may additionally carry a SHOPPING record -- the schedule,
  the filed fares, the minimum-connect-time table and the pricing
  context for a journey the traveler asked this agency to price. That
  is the ground truth `travelagency.governor` re-runs
  `kotoba.itinerary/build` and `kotoba.fare/search` against. It lives on
  the booking rather than in the proposal for the same reason
  `:registered?` does: a governor that priced from the advisor's own
  numbers would be checking the advisor against itself.

  The ledger stays append-only: which booking a proposal targeted,
  which operation, on what basis, committed/held/escalated and approved
  by whom is always a query over an immutable log."
  (:require [kotoba.fare :as fare]
            [kotoba.itinerary :as itin]
            [kotoba.reservation :as res]))

(defprotocol Store
  (booking [s booking-id] "Registered booking/vendor-contract
    record, or nil. Map: {:booking-id .. :name .. :kind ..
    :registered? bool :verified? bool}.")
  (all-bookings [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-bookings [s bookings] "replace/seed the booking directory (map booking-id->booking)"))

;; ----------------------------- demo data -----------------------------

(defn- settlement-plan
  "One filed per-unit settlement rate -- `kotoba.reservation` ground
  truth the governor recomputes a settlement amount from. Integer minor
  units (USD cents)."
  [id per-unit]
  (res/rate-plan id :unit per-unit "USD" :min-units 1))

;; ----------------------------- shopping ground truth -----------------------------

(defn- at [s off] (itin/datetime->minutes s off))

(defn demo-shopping
  "A self-contained HND->SIN shopping record: the schedule this agency
  was given, the fares filed for that market, the MCT table it applies
  and the pricing context.

  Deliberately includes a leg pair that is a sequence of flights but not
  an itinerary -- CI751 leaves Taipei 15 minutes after NH853 lands,
  which no interline minimum connect time permits. It is here so the
  governor's legality recompute has something real to reject: an advisor
  reading this same schedule will happily propose it."
  []
  (let [travel-date "2026-09-01"]
    {:journey  {:origin "HND" :destination "SIN"
                :travel-date travel-date :sale-date "2026-08-11"}
     :schedule [(itin/leg "NH" "841" "HND" "SIN" (at "2026-09-01T11:35" 540) (at "2026-09-01T18:05" 480)
                          :classes {"Y" 9} :miles 3312)
                (itin/leg "NH" "853" "HND" "TPE" (at "2026-09-01T09:25" 540) (at "2026-09-01T12:25" 480)
                          :classes {"Y" 9} :miles 1330)
                (itin/leg "BR" "225" "TPE" "SIN" (at "2026-09-01T14:00" 480) (at "2026-09-01T18:45" 480)
                          :classes {"Y" 7} :miles 2005)
                (itin/leg "CI" "751" "TPE" "SIN" (at "2026-09-01T12:40" 480) (at "2026-09-01T17:20" 480)
                          :classes {"Y" 9} :miles 2005)]
     :mct      (itin/mct-table {["TPE" :interline] 75 ["TPE" :online] 45 [:default] 60})
     :fares    [(fare/fare "YOWSIN"  "NH" "HND" "SIN" "Y" :amount 98000 :currency "JPY")
                (fare/fare "VLXAP21" "NH" "HND" "SIN" "Y" :amount 61000 :currency "JPY"
                           :rules (fare/rules :advance-days 21 :min-stay-days 3))
                (fare/fare "YOWTPE"  "NH" "HND" "TPE" "Y" :amount 44000 :currency "JPY")
                (fare/fare "YOWSGN"  "BR" "TPE" "SIN" "Y" :amount 39000 :currency "JPY")]
     :pricing  {:currency   "JPY"
                ;; Every date fact is derived with kotoba.itinerary's own
                ;; pure functions, so advisor and governor cannot end up
                ;; disagreeing about what day it was.
                :ctx        {:travel-date  travel-date
                             :weekday      (itin/weekday-of travel-date)
                             :advance-days (itin/days-between "2026-08-11" travel-date)
                             :stay-days    7
                             :sale-date    "2026-08-11"
                             :point-of-sale "JP"}
                :surcharges [(fare/surcharge "YQ" 7300 :per :segment)]
                :taxes      [(fare/tax "SW" :flat 520 :per :itinerary)]}}))

(defn demo-data
  "A small, self-contained booking directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:bookings
   {"bkg-1" {:booking-id "bkg-1" :name "Round-trip flight + hotel package, Tanaka family, 2026-08-01"
             :kind :customer-booking :registered? true :verified? true
             :billable-units 40 :rate-plan (settlement-plan "bkg-1-rate" 2500)
             :shopping (demo-shopping)}
    "bkg-2" {:booking-id "bkg-2" :name "Vendor hotel-B settlement contract, quarterly reconciliation"
             :kind :vendor-contract :registered? true :verified? true
             :billable-units 400 :rate-plan (settlement-plan "bkg-2-rate" 6000)}
    "bkg-3" {:booking-id "bkg-3" :name "Multi-city flight itinerary, awaiting payment verification"
             :kind :customer-booking :registered? true :verified? false
             :billable-units 10 :rate-plan (settlement-plan "bkg-3-rate" 3000)}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (booking [_ booking-id] (get-in @a [:bookings booking-id]))
  (all-bookings [_] (sort-by :booking-id (vals (:bookings @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-bookings [s bookings] (when (seq bookings) (swap! a assoc :bookings bookings)) s))

(defn seed-db
  "A MemStore seeded with the demo booking directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `bookings` map (booking-id
  string -> booking map) -- the primary test/dev entry point.
  `bookings` may be empty (an unregistered-everywhere store)."
  [bookings]
  (->MemStore (atom {:bookings (or bookings {}) :ledger [] :coordination-log []})))
