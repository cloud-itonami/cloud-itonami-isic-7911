(ns travelagency.advisor
  "TravelAgencyAdvisor -- the *contained intelligence node* for the
  ISIC-7911 (Travel agency activities -- booking and selling
  third-party travel products such as flights, accommodation and
  transfers as an intermediary on behalf of travelers) operations-
  coordination actor.

  It drafts exactly five kinds of proposal from a closed allowlist:
  booking/itinerary/payment-status record logging, booking/confirmation
  scheduling, airline/hotel/vendor settlement coordination,
  payment-dispute/cancellation/fraud concern flagging, and -- the one
  op that faces the traveler rather than the back office -- a fare
  search quoting an itinerary and a price. CRITICAL: it is a
  smart-but-untrusted advisor. It returns
  a *proposal* (with a rationale + the fields it cited), never a
  committed record and NEVER a direct actuation -- every proposal's
  `:effect` is always `:propose`. Every output is censored downstream
  by `travelagency.governor` before anything touches the SSoT.

  This advisor NEVER drafts a direct finalization of a payment-dispute
  resolution, nor a direct finalization of a refund/cancellation-
  policy override -- those are permanently out of scope for this
  actor, not merely un-implemented (Wave 4 person-facing-service
  safety guardrail, ADR-2607152500). `travelagency.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal
  for exactly this failure mode (a compromised or confused advisor
  drifting into scope it must never touch) and HARD-holds it,
  regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :booking-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}"
  (:require [travelagency.governor :as governor]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-booking-record
  "Draft a reservation/itinerary/payment-status log entry (booking
  created / amended, itinerary segment confirmed, or payment status
  update). Pure logging of observed booking state -- never a
  payment-dispute or refund/cancellation-policy decision."
  [_db {:keys [booking-id patch]}]
  {:op         :log-booking-record
   :booking-id booking-id
   :summary    (str booking-id " の予約/旅程/支払状況記録を記録: " (pr-str (keys patch)))
   :rationale  "予約作成/変更、旅程確定または支払状況の記録のみ。決済紛争や返金/キャンセルポリシーの判断は含まない。"
   :cites      [booking-id]
   :effect     :propose
   :value      (merge {:booking-id booking-id} patch)
   :confidence 0.94})

(defn- propose-booking-operation
  "Draft a booking/confirmation scheduling PROPOSAL only (never a
  direct booking-confirmation release or ticket-issuance actuation)."
  [_db {:keys [booking-id patch]}]
  {:op         :schedule-booking-operation
   :booking-id booking-id
   :summary    (str booking-id " に関連する予約/確認スケジュール提案: " (pr-str (keys patch)))
   :rationale  "予約確認/発券準備の日程調整の提案のみ。確認の確定は人間の旅行代理店担当者が判断する。"
   :cites      [booking-id]
   :effect     :propose
   :value      (merge {:booking-id booking-id} patch)
   :confidence 0.89})

(defn- propose-vendor-settlement
  "Draft an airline/hotel/vendor settlement coordination proposal
  (never a direct fund transfer or settlement finalization)."
  [_db {:keys [booking-id patch understate?]}]
  (let [truth (governor/recomputed-settlement _db booking-id)
        ;; `understate?` states an amount just under the escalation
        ;; threshold for a settlement that is actually far above it -- the
        ;; bypass the recompute gate exists to close.
        amount (if understate? (dec governor/high-value-threshold) truth)]
    {:op         :coordinate-vendor-settlement
     :booking-id booking-id
     :summary    (str booking-id " に関連する航空会社/ホテル/ベンダー精算調整: " (pr-str (keys patch)))
     :rationale  "航空会社/ホテル/ベンダーとの精算調整の提案のみ。精算確定は人間の旅行代理店担当者が判断する。"
     :cites      [booking-id]
     :effect     :propose
     :value      (cond-> (merge {:booking-id booking-id} patch)
                   amount (assoc :estimated-amount amount))
     :confidence 0.87}))

(defn- propose-transaction-concern
  "Surface a payment-dispute/cancellation/fraud concern for HUMAN
  triage. This op ALWAYS escalates in `travelagency.governor` -- never
  auto-committed at any phase -- regardless of how confident the
  advisor is that the concern is real, and it never itself finalizes a
  payment-dispute resolution or a refund/cancellation-policy
  override."
  [_db {:keys [booking-id patch]}]
  {:op         :flag-transaction-concern
   :booking-id booking-id
   :summary    (str booking-id " の取引懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "決済/キャンセル/不正に関する懸念の観察事実の報告のみ。常に人間の確認・対応が必要。"
   :cites      [booking-id]
   :effect     :propose
   :value      (merge {:booking-id booking-id} patch)
   :confidence (or (:confidence patch) 0.85)})

(defn- propose-fare-search
  "Draft a fare quote for a traveler: an itinerary (as leg ids off the
  booking's own schedule) plus the total it prices at.

  This is the op that made `kotoba-lang/itinerary` and
  `kotoba-lang/fare` necessary (ADR-2608039960). A travel agency is an
  intermediary -- it shops third-party inventory it does not own -- so
  neither `kotoba.reservation` (one operator's own rate plan) nor a
  settlement rate answers what it needs to say to a traveler.

  The mock prices honestly by default: it re-runs the search over the
  booking's own record and quotes the result. `patch` carries three
  deliberate failure hooks, each of which the governor catches with a
  DIFFERENT rule, because they are genuinely different lies:

    :fabricate  -- state a plausible total nobody's filed fares support
    :illegal?   -- propose the 15-minute Taipei interline connection
    :overquote? -- quote a legal, correctly-priced, non-cheapest
                   itinerary while asserting it is the cheapest"
  [db {:keys [booking-id patch]}]
  (let [{:keys [fabricate illegal? overquote?]} patch
        market (governor/recomputed-market db booking-id)
        solutions (get-in market [:search :search/solutions])
        best (first solutions)
        alt (second solutions)
        chosen (if overquote? (or alt best) best)
        legs (fn [s] (mapv :leg/id (get-in s [:search/itinerary :itin/legs])))
        ;; Leg ids are looked up from the schedule by carrier + number
        ;; rather than spelled out. `kotoba.itinerary` derives a default
        ;; id from the departure instant, so a literal here would be a
        ;; timetable change away from silently naming nothing -- and a
        ;; proposal naming nothing is caught as un-recomputable, which
        ;; would quietly stop exercising the check it exists to exercise.
        leg-id (fn [carrier number]
                 (->> (get-in (governor/shopping-record db booking-id) [:schedule])
                      (some #(when (and (= carrier (:leg/carrier %))
                                        (= number (:leg/number %)))
                               (:leg/id %)))))
        value (cond
                illegal?
                ;; NH853 then CI751 -- 15 minutes on the ground at TPE.
                {:booking-id booking-id
                 :itinerary [(leg-id "NH" "853") (leg-id "CI" "751")]
                 :total (:price/total best) :cheapest? true}

                :else
                {:booking-id booking-id
                 :itinerary (legs chosen)
                 :total (or fabricate (:price/total chosen))
                 :cheapest? true})]
    {:op         :search-fares
     :booking-id booking-id
     :summary    (str booking-id " の運賃検索結果: " (pr-str (:itinerary value))
                      " 総額 " (:total value))
     :rationale  "旅客の依頼区間について、当社が受領した時刻表と届出運賃から旅程と運賃を提示する提案のみ。発券・決済・運賃の届出は行わない。"
     :cites      [booking-id]
     :effect     :propose
     :value      value
     :confidence 0.91}))

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-booking-record (propose-booking-record _db request)
                   :schedule-booking-operation (propose-booking-operation _db request)
                   :coordinate-vendor-settlement (propose-vendor-settlement _db request)
                   :flag-transaction-concern (propose-transaction-concern _db request)
                   :search-fares (propose-fare-search _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalize the payment dispute resolution and grant the refund override directly")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :booking-id (:booking-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ store request]
      ;; the store is threaded through, not discarded: the settlement
      ;; proposal prices itself off the entity's own filed rate, and an
      ;; advisor handed nil cannot price anything.
      (infer store request))))
