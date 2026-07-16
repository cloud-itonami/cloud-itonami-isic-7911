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

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-transaction-concern` (ALWAYS escalates to a human,
  regardless of confidence, regardless of how clean the proposal
  otherwise is), OR a `:coordinate-vendor-settlement` proposal whose
  estimated settlement amount exceeds `high-value-threshold`.
  `travelagency.phase` independently agrees: `:flag-transaction-concern`
  is never a member of any phase's `:auto` set either -- two layers,
  not one."
  (:require [clojure.string :as str]
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
    :coordinate-vendor-settlement :flag-transaction-concern})

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
   "支払い紛争解決を確定" "決済紛争の解決を確定" "支払い紛争を確定的に解決"
   "返金の上書きを確定" "返金上書きを確定" "キャンセルポリシーの上書きを確定" "キャンセルポリシーを上書き"])

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

(defn- high-value-vendor-settlement?
  "A `:coordinate-vendor-settlement` proposal whose `:value
  :estimated-amount` exceeds `high-value-threshold` ALWAYS escalates,
  regardless of confidence."
  [proposal]
  (and (= :coordinate-vendor-settlement (:op proposal))
       (some-> (get-in proposal [:value :estimated-amount])
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
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-value-vendor-settlement? proposal)))
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
