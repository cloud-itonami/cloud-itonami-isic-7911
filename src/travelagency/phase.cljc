(ns travelagency.phase
  "Phase 0->3 staged rollout for the ISIC-7911 (Travel agency
  activities) operations-coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-logging     -- booking-record logging allowed,
                                      every write needs human approval.
    Phase 2  assisted-coordination-- adds booking-operation scheduling
                                      and vendor-settlement
                                      coordination, still approval.
    Phase 3  supervised auto      -- governor-clean, high-confidence
                                      `:log-booking-record`/
                                      `:schedule-booking-operation`/
                                      `:coordinate-vendor-settlement`
                                      may auto-commit.
                                      `:flag-transaction-concern` NEVER
                                      auto-commits, at any phase.
    Phase 4  supervised shopping  -- adds `:search-fares` (quoting a
                                      traveler an itinerary and a fare).
                                      Still never auto-commits.

  `:flag-transaction-concern` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come (Wave 4 person-facing-service safety
  guardrail, ADR-2607152500). Flagging a payment-dispute/cancellation/
  fraud concern always needs a human to actually look at it.
  `travelagency.governor`'s own `always-escalate-ops` enforces the same
  invariant independently -- two layers, not one, agree on this.

  `:search-fares` sits in a phase of its own, ABOVE the back-office ops
  rather than beside them, so an agency can run this actor's
  coordination side without ever enabling it to quote a traveler
  (the same shape ADR-2800002200 gave `cloud-itonami-isic-5110`'s
  commercial ops). It is likewise absent from every `:auto` set: it
  moves no money and holds no inventory, but a quoted fare is a
  commercial statement a traveler will rely on, so a human sees it."
  (:require [travelagency.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-transaction-concern` and `:search-fares`
;; are members of `write-ops` (governor-gated like any write) but are
;; NEVER members of any phase's `:auto` set below. Do not add them.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops
  allowed to auto-commit when governor-clean>}."
  {0 {:label "read-only"              :writes #{}                                                                :auto #{}}
   1 {:label "assisted-logging"       :writes #{:log-booking-record}                                             :auto #{}}
   2 {:label "assisted-coordination"  :writes #{:log-booking-record :schedule-booking-operation
                                                :coordinate-vendor-settlement}                                    :auto #{}}
   3 {:label "supervised-auto"        :writes #{:log-booking-record :schedule-booking-operation
                                                :coordinate-vendor-settlement :flag-transaction-concern}
      :auto #{:log-booking-record :schedule-booking-operation :coordinate-vendor-settlement}}
   4 {:label "supervised-shopping"    :writes write-ops
      :auto #{:log-booking-record :schedule-booking-operation :coordinate-vendor-settlement}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean.
  - `:flag-transaction-concern` is never auto-eligible at any phase, so
    it always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a TravelAgencyGovernor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
