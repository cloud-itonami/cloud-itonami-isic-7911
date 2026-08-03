(ns travelagency.governor
  "TravelAgencyGovernor -- the independent compliance layer that earns
  the TravelAgencyAdvisor the right to commit. The advisor has no
  notion of whether a booking/client record is actually registered and
  verified, whether its own proposed `:effect` secretly claims a
  direct actuation instead of a mere proposal, or whether it has
  silently drifted into a permanently out-of-scope decision area, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (booking/itinerary/payment-status record logging, booking/
  confirmation scheduling, airline/hotel/vendor settlement
  coordination, payment-dispute/cancellation/fraud concern flagging).
  It NEVER performs or authorizes:
    - directly finalizing a payment-dispute resolution
    - directly finalizing a refund/cancellation-policy override
    - any other consumer-payment or cancellation-policy authority
      action

  This is the Wave 4 person-facing-service safety guardrail
  (ADR-2607152500): travel-agency booking touches consumer-payment and
  cancellation-policy decisions, so the closed op allowlist NEVER
  includes any op that directly finalizes a payment-dispute resolution
  or a refund/cancellation-policy override -- those are always either
  a hard permanent block or an always-escalate op, never
  auto-commit-eligible.

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Booking unverified          -- the target booking/client record
                                       must exist AND be independently
                                       confirmed `:registered?`/
                                       `:verified?` in the store before
                                       ANY proposal for it may commit
                                       or even escalate. Never trusts a
                                       proposal's own claim about the
                                       booking -- re-derived from the
                                       booking's own store record, the
                                       same 'ground truth, not
                                       self-report' discipline every
                                       sibling actor's governor uses.
    2. Effect not :propose         -- every proposal's `:effect` MUST
                                       be `:propose`. Any other effect
                                       value is, by construction, a
                                       claim to directly actuate/commit
                                       outside governance -- HARD block,
                                       not merely low-confidence.
    3. Scope exclusion             -- ANY proposal (regardless of op)
                                       whose op is outside the closed
                                       four-op allowlist, or whose
                                       rationale, summary, citations or
                                       draft value touches directly
                                       finalizing a payment-dispute
                                       resolution or directly finalizing
                                       a refund/cancellation-policy
                                       override, is a HARD, PERMANENT
                                       block -- this actor's charter
                                       excludes that territory
                                       structurally, not as a rollout
                                       milestone. Evaluated
                                       UNCONDITIONALLY on every
                                       proposal. An op outside the
                                       closed four-op allowlist is the
                                       SAME failure mode (an advisor
                                       proposing something it was never
                                       authorized to propose) and is
                                       folded into this same check.

  Three further HARD checks apply to the SHOPPING op (`:search-fares`),
  and they are the same kind of check as the settlement recompute below
  -- ground-truth RECOMPUTES, never restatements of what the advisor
  claimed:

    4. Itinerary illegal           -- re-run `kotoba.itinerary/violations`
                                       on the proposed leg sequence,
                                       resolved from the BOOKING'S OWN
                                       schedule, against the booking's own
                                       minimum-connect-time table. A
                                       language model reading a timetable
                                       will propose a 15-minute interline
                                       connection without hesitating.
    5. Fare price mismatch         -- re-run `kotoba.fare/search` over
                                       that one itinerary and reject a
                                       stated total that is not what the
                                       filed fares actually add to.
    6. Not the cheapest fare       -- when, and only when, the proposal
                                       CLAIMS `:cheapest? true`, re-run
                                       the search over every itinerary
                                       the schedule supports and reject
                                       the claim if anything prices lower.

  Checks 5 and 6 are deliberately separate. A traveler may legitimately
  be quoted a nonstop that is not the cheapest thing in the market --
  that is a preference, not a lie. Quoting an arithmetic that does not
  hold, or asserting a superlative that is false, are the two things an
  intermediary must not do, and only the second one is about the fares
  that were NOT chosen (ADR-2608039960).

  For all three, a check that CANNOT be performed -- no shopping record
  on the booking, a proposed leg id absent from the schedule, no stated
  total -- is itself a HARD violation. This governor does not assume
  compliance when it is structurally unable to verify it.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-transaction-concern` (ALWAYS escalates to a human,
  regardless of confidence, regardless of how clean the proposal
  otherwise is), OR a `:coordinate-vendor-settlement` proposal whose
  estimated settlement amount exceeds `high-value-threshold`.
  `travelagency.phase` independently agrees: `:flag-transaction-concern`
  is never a member of any phase's `:auto` set either -- two layers,
  not one. `:search-fares` is likewise never a member of any `:auto`
  set: it moves nothing, but a quoted fare is a commercial statement a
  traveler will rely on."
  (:require [clojure.string :as str]
            [kotoba.fare :as fare]
            [kotoba.itinerary :as itin]
            [kotoba.reservation :as res]
            [travelagency.store :as store]))

(def confidence-floor 0.6)

(def high-value-threshold
  "A `:coordinate-vendor-settlement` proposal whose `:value
  :estimated-amount` exceeds this amount (USD) ALWAYS escalates to a
  human, regardless of confidence -- routine per-booking airline/
  hotel/vendor settlements sit well under this, so this only catches
  unusually large settlement runs."
  5000)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`).
  Per the Wave 4 person-facing-service safety guardrail
  (ADR-2607152500), NO op in this set may directly finalize a
  payment-dispute resolution or a refund/cancellation-policy override
  -- every op here is `:effect :propose` only, and
  `:flag-transaction-concern` always escalates rather than ever
  auto-committing."
  #{:log-booking-record :schedule-booking-operation
    :coordinate-vendor-settlement :flag-transaction-concern
    :search-fares})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-transaction-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  payment-dispute resolution or directly finalizing a refund/
  cancellation-policy override. Scanned across the proposal's
  op/summary/rationale/cites/value, never trusting the advisor's own
  framing of its intent.

  Deliberately phrased as FINALIZATION/EXECUTION ACTION phrases (verb +
  object), not bare nouns like \"payment\"/\"dispute\"/\"refund\"/
  \"cancellation\" alone -- a legitimate `:flag-transaction-concern`
  proposal must be free to *describe* a payment dispute, a suspected
  fraud pattern, or a cancellation-policy question without tripping
  this gate (see `travelagency.governor-test`'s own
  `legitimate-transaction-concern-is-not-scope-excluded`); only a
  proposal that claims to *actually finalize* the payment-dispute
  resolution or the refund/cancellation-policy override is blocked
  here."
  ["finalize the payment dispute resolution" "finalize payment dispute resolution"
   "finalize the payment-dispute resolution" "resolve the payment dispute directly"
   "issue a chargeback determination" "determine the chargeback outcome"
   "close out the payment dispute" "close the payment dispute resolution"
   "finalize the refund override" "finalize refund override"
   "grant the refund override" "grant a refund override"
   "finalize the cancellation policy override" "grant the cancellation policy override"
   "override the cancellation policy" "override cancellation policy"
   "bypass the cancellation policy" "bypass cancellation policy"
   ;; Added with `:search-fares`. Shopping a fare is this agency's core
   ;; business; ISSUING the ticket is the airline's or the settlement
   ;; system's, and a search proposal that claims to have done it has
   ;; drifted exactly as far out of scope as one claiming to have closed
   ;; a chargeback. Phrased as actions for the same reason every entry
   ;; above is: a legitimate proposal must stay free to *mention* a
   ;; ticket, a fare or a booking class.
   "issue the ticket" "issue the tickets" "issue a ticket for"
   "finalize the ticket issuance" "finalize ticket issuance"
   "confirm the ticket issuance" "complete the ticket issuance"
   "file the fare with" "file this fare with"
   "支払い紛争解決を確定" "決済紛争の解決を確定" "支払い紛争を確定的に解決"
   "返金の上書きを確定" "返金上書きを確定" "キャンセルポリシーの上書きを確定" "キャンセルポリシーを上書き"
   "発券を確定" "発券を完了" "航空券を発券する" "運賃を届け出る"])

;; ----------------------------- checks -----------------------------

(defn- booking-unverified-violations
  "The target booking/client record must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:booking-id` claim without a store lookup."
  [{:keys [booking-id]} st]
  (let [b (store/booking st booking-id)]
    (when-not (and b (:registered? b) (:verified? b))
      [{:rule :booking-unverified
        :detail (str booking-id " は未登録または未検証の予約/顧客記録 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches directly finalizing a payment-dispute
  resolution or directly finalizing a refund/cancellation-policy
  override, regardless of confidence or how clean every other check
  is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "支払い紛争解決の確定/返金・キャンセルポリシー上書きの確定は永久に禁止"}])))

(defn recomputed-settlement
  "The settlement amount for `booking-id`, recomputed from the entity's
  OWN filed rate plan and billable-unit count. nil when it cannot be
  recomputed.

  This is the number the high-value gate and the mismatch gate both
  read. Neither reads the advisor's claim."
  [store id]
  (let [e (when store (store/booking store id))
        plan (:rate-plan e)
        units (:billable-units e)]
    (when (and plan (integer? units) (pos? units))
      (res/quote-total (res/quote-for plan {:dates [nil] :qty units})))))

(defn- settlement-recompute-violations
  "RECOMPUTE a `:coordinate-vendor-settlement` amount from the entity's
  own filed rate plan and reject a claimed amount that does not match.

  This closes a hole rather than adding a nicety. The high-value gate
  used to read `:value :estimated-amount` STRAIGHT OUT OF THE ADVISOR'S
  OWN PROPOSAL: an advisor stating a figure just under the threshold for
  a far larger settlement bypassed the human escalation entirely,
  because the gate's only input was the thing it existed to guard
  against. And `some->` meant OMITTING the field skipped the gate too --
  a settlement proposal with no amount at all escalated to nobody.

  A check that cannot be performed is a violation, not a pass."
  [proposal store]
  (when (= :coordinate-vendor-settlement (:op proposal))
    (let [id (:booking-id proposal)
          claimed (get-in proposal [:value :estimated-amount])
          truth (recomputed-settlement store id)]
      (cond
        (nil? truth)
        [{:rule :settlement-not-recomputable
          :detail (str id " に届出精算レート/請求単位が無い -- 提示精算額を独立に再計算できない")}]

        (nil? claimed)
        [{:rule :settlement-not-recomputable
          :detail "提案に :estimated-amount が無い -- 金額の無い精算調整は受け付けない(旧実装では高額ゲートを素通りしていた)"}]

        (not= claimed truth)
        [{:rule :settlement-mismatch
          :detail (str "提示精算額 " claimed " は届出レートからの再計算結果 " truth " と一致しない")}]))))

;; ------------------- shopping recompute (`:search-fares`) -------------------

(defn shopping-record
  "The booking's OWN schedule / fares / MCT table / pricing context.
  nil when the booking carries no shopping record, which makes every
  check below un-performable and therefore a violation."
  [store id]
  (when store (:shopping (store/booking store id))))

(def ^:private shopping-of shopping-record)

(defn resolve-itinerary
  "Rebuild the proposed itinerary from the BOOKING'S OWN schedule.

  The proposal names leg ids; it does not get to supply leg data. An
  advisor that could hand over its own departure times would be
  supplying the very facts the legality check exists to test, and the
  check would be verifying the advisor against itself. Returns nil when
  any named leg is absent from the schedule -- an itinerary built from
  flights this agency was never given a schedule for cannot be
  verified, so it is not accepted."
  [shopping leg-ids]
  (let [by-id (into {} (map (juxt :leg/id identity)) (:schedule shopping))
        legs (mapv #(get by-id %) leg-ids)]
    (when (and (seq leg-ids) (every? some? legs))
      (itin/itinerary legs))))

(defn- search-opts [shopping]
  (:pricing shopping))

(defn recomputed-market
  "Every itinerary the booking's own schedule legally supports, priced
  from its own filed fares. `{:itineraries [..] :search {..}}`, or nil
  when there is nothing to recompute from.

  This is the market the `:cheapest?` claim is judged against. It is
  derived here, from the store, and never read off the proposal."
  [store id]
  (when-let [sh (shopping-of store id)]
    (let [{:keys [origin destination]} (:journey sh)]
      (when (and origin destination (seq (:schedule sh)) (seq (:fares sh)))
        (let [built (itin/build (:schedule sh)
                                {:origin origin :destination destination
                                 :mct-table (:mct sh)})
              its (:itin/results built)]
          {:itineraries its
           :search (fare/search its (:fares sh) (search-opts sh))})))))

(defn- fare-search-violations
  "RECOMPUTE a `:search-fares` proposal: is the itinerary legal, does
  the stated total match what the filed fares add to, and -- only if
  the proposal says so -- is it really the cheapest.

  Every input comes from the booking's own record. A check that cannot
  be run is a violation, never a pass."
  [proposal store]
  (when (= :search-fares (:op proposal))
    (let [id (:booking-id proposal)
          sh (shopping-of store id)
          {:keys [itinerary total cheapest?]} (:value proposal)
          it (when sh (resolve-itinerary sh itinerary))
          journey (:journey sh)]
      (cond
        (nil? sh)
        [{:rule :fare-search-not-recomputable
          :detail (str id " に時刻表/届出運賃(shopping record)が無い -- 提示運賃を独立に再計算できない")}]

        (or (nil? itinerary) (nil? total))
        [{:rule :fare-search-not-recomputable
          :detail "提案に :itinerary(leg id 列)または :total が無い -- 独立検証できない運賃は承認しない"}]

        (nil? it)
        [{:rule :fare-search-not-recomputable
          :detail (str "提案の leg " (pr-str itinerary) " は " id
                       " の時刻表に無い -- 与えられていない便の旅程は検証できない")}]

        :else
        (let [violations (itin/violations it {:origin (:origin journey)
                                              :destination (:destination journey)
                                              :mct-table (:mct sh)})
              market (recomputed-market store id)
              this-one (fare/cheapest (fare/search [it] (:fares sh) (search-opts sh)))]
          (cond
            (seq violations)
            [{:rule :itinerary-illegal
              :detail (str "提案された旅程は成立しない: "
                           (str/join " / " (map itin/describe-violation violations)))}]

            (nil? this-one)
            [{:rule :fare-search-not-recomputable
              :detail "提案された旅程に適用可能な届出運賃が無い -- 価格を再計算できない"}]

            (not= total (:price/total this-one))
            [{:rule :fare-price-mismatch
              :detail (str "提示総額 " total " は届出運賃からの再計算結果 "
                           (:price/total this-one) " と一致しない")}]

            ;; Only checked when the advisor actually asserts it. A
            ;; traveler may be quoted a non-cheapest itinerary on
            ;; purpose; asserting falsely that it is the cheapest is
            ;; the thing that is not allowed.
            (and cheapest?
                 (not (fare/cheapest-matches-claim?
                       (:itineraries market) (:fares sh) (search-opts sh) total)))
            [{:rule :not-the-cheapest-fare
              :detail (str "最安と主張された " total " より安い "
                           (:price/total (fare/cheapest (:search market)))
                           " が同じ時刻表・同じ届出運賃から成立する")}]))))))

(defn- high-value-vendor-settlement?
  "A `:coordinate-vendor-settlement` whose RECOMPUTED amount exceeds
  `high-value-threshold` ALWAYS escalates, regardless of confidence.
  Reads the recomputed amount, never the advisor's claim."
  [proposal store]
  (and (= :coordinate-vendor-settlement (:op proposal))
       (some-> (recomputed-settlement store (:booking-id proposal))
               (> high-value-threshold))))

(defn check
  "Censors a TravelAgencyAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [booking-id (or (:booking-id proposal) (:booking-id request))
        hard (into []
                   (concat (booking-unverified-violations {:booking-id booking-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)
                           (settlement-recompute-violations proposal store)
                           (fare-search-violations proposal store)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-value-vendor-settlement? proposal store)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :booking-id (:booking-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
