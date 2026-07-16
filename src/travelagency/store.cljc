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

  The ledger stays append-only: which booking a proposal targeted,
  which operation, on what basis, committed/held/escalated and approved
  by whom is always a query over an immutable log.")

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

(defn demo-data
  "A small, self-contained booking directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:bookings
   {"bkg-1" {:booking-id "bkg-1" :name "Round-trip flight + hotel package, Tanaka family, 2026-08-01"
             :kind :customer-booking :registered? true :verified? true}
    "bkg-2" {:booking-id "bkg-2" :name "Vendor hotel-B settlement contract, quarterly reconciliation"
             :kind :vendor-contract :registered? true :verified? true}
    "bkg-3" {:booking-id "bkg-3" :name "Multi-city flight itinerary, awaiting payment verification"
             :kind :customer-booking :registered? true :verified? false}}})

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
