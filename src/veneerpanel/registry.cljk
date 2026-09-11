(ns veneerpanel.registry
  "Pure-function domain logic for the wood-panel plant-operations
  coordination actor -- equipment/batch verification, shipment-volume
  recompute, panel/veneer-grade validation, output-quality
  plausibility validation, and draft maintenance-schedule/shipment-
  coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/veneerpanel`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `veneerpanel.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `sawmilling.registry/shipment-volume-exceeded?`,
  `logging.registry/permit-allowance-exceeded?`): never trust a
  proposal's own self-reported volume/status when the inputs needed to
  recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real mill-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating veneer-peeling/
  pressing/gluing-line equipment or dispatching a real freight carrier
  (this actor NEVER does either -- see README `What this actor does
  NOT do`).")

;; ----------------------------- constants -----------------------------

(def valid-grades
  "The closed set of panel/veneer-grade values a production-batch
  record may declare, spanning the product range ISIC 1621 covers
  (veneer sheets, plywood, particleboard, MDF, OSB): US HPVA-informed
  face/back veneer grades (A/B/C/D), APA-informed structural-panel
  ratings, an EN 300-informed OSB class and an EN 622-informed MDF
  class. Anything else is a fabricated/unrecognized grade -- the
  governor HARD-holds rather than let an invented grade pass through."
  #{:veneer-a :veneer-b :veneer-c :veneer-d
    :structural-i :structural-ii :osb-3 :mdf-standard})

(def output-quality-min-percent
  "Physical floor for a panel/veneer batch's output-quality reading (a
  defect-free/first-pass yield percentage -- 0% means nothing in the
  batch passed inspection)."
  0.0)

(def output-quality-max-percent
  "Physical ceiling for a panel/veneer batch's output-quality reading
  -- a yield percentage can never exceed 100% of the batch inspected."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its grade/volume/output-quality claims have actually been
  QC-inspected, not merely logged from an unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-volume-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-cubic-m` + `new-volume-cubic-m` exceed
  `batch`'s own recorded `:volume-cubic-m` (the batch's own logged
  production volume)? Needs no proposal inspection or stored-verdict
  lookup -- its inputs are permanent fields already on the batch's
  own permit record, the same shape every sibling actor's own cost/
  total-matching check uses."
  [batch new-volume-cubic-m]
  (let [capacity (:volume-cubic-m batch)
        so-far (:shipped-volume-cubic-m batch 0.0)]
    (and (number? capacity)
         (number? new-volume-cubic-m)
         (number? so-far)
         ;; Compared at 1/10000 of a unit, not on raw doubles. A shipment
         ;; that fills a batch EXACTLY to its recorded capacity is legal,
         ;; and comparing the raw sum flagged such shipments as over
         ;; because the sum is not the double nearest the true total.
         (> (Math/round (* 10000 (+ (double so-far) (double new-volume-cubic-m))))
            (Math/round (* 10000 (double capacity))))))) 

(defn shipment-volume-exceeded-checkable?
  "Can `batch`'s headroom actually be computed for `new-volume-cubic-m`?

  `shipment-volume-exceeded?` answers only `over` / `not over`, and its
  `(and (number? ...) ...)` guard made every un-checkable case fall
  through as `not over` -- a batch with no recorded capacity, or a
  shipment stating no amount, passed the over-capacity check silently.
  Callers must ask this first: un-checkable is not headroom."
  [batch new-volume-cubic-m]
  (boolean (and (map? batch)
                (number? (:volume-cubic-m batch))
                (number? (:shipped-volume-cubic-m batch 0.0))
                (number? new-volume-cubic-m))))

(defn grade-valid?
  "Is `grade` one of the closed, known panel/veneer-grade values?
  nil/blank is treated as invalid (a production-batch patch must
  declare a real grade, not omit it silently)."
  [grade]
  (contains? valid-grades grade))

(defn output-quality-valid?
  "Is `percent` a physically plausible panel/veneer output-quality
  (defect-free/first-pass yield) reading? Rejects nil, non-numbers,
  negative values, and values beyond `output-quality-max-percent` -- a
  fabricated or sensor/inspection-error reading, never let through as
  a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) output-quality-min-percent)
       (<= (double percent) output-quality-max-percent)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  veneer-peeling/pressing/gluing-line maintenance window against a
  verified, registered piece of equipment. Pure function -- does not
  actuate veneer-peeling/pressing/gluing equipment or execute any
  maintenance; it builds the RECORD a plant coordinator would keep.
  `veneerpanel.governor` independently re-verifies the equipment's own
  verified/registered ground truth, and permanently blocks any attempt
  to set `:finalize? true` on a press-cycle schedule (see README
  `Actuation`), before this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound veneer/panel shipment against a verified, registered
  production batch. Pure function -- does not dispatch any real
  freight carrier; it builds the RECORD a plant coordinator would
  keep. `veneerpanel.governor` independently re-verifies the
  shipment's own claimed volume against `shipment-volume-exceeded?`,
  before this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
