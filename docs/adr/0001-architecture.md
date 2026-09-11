# ADR-0001: VeneerPanelAdvisor ⊣ Wood Panel Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-1621` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-1621` publishes an OSS blueprint for wood-panel
**plant operations coordination** (production-batch panel/veneer-
grade/volume/output-quality data logging, veneer-lathe/hot-press/
glue-spreader maintenance scheduling, safety-concern flagging, and
outbound panel shipment coordination). Like every actor in this
fleet, the blueprint alone is not an implementation: this ADR records
the governed-actor architecture that promotes it to real, tested
code, following the same langgraph StateGraph + independent Governor
+ Phase 0->3 rollout pattern established across the cloud-itonami
fleet.

The closest domain analog is `cloud-itonami-isic-1610` (Sawmilling and
planing of wood): both are back-office coordination actors for heavy
wood-processing plant equipment with a real physical safety
dimension. 1621 differs in one structural respect that shapes this
design: 1610's central process is size reduction (sawing/planing a
log into dimension lumber, kiln-dried); 1621's central process is
sheet/panel formation (peeling or slicing a log into veneer, then
gluing/laminating and pressing sheets into plywood, particleboard,
MDF or OSB). The central ground-truth entity is therefore a
**production batch of veneer/panels** (grade/volume/output-quality,
not grade/volume/moisture-content) moving through a **plant** with
veneer-lathe/hot-press/glue-spreader equipment (not saw/planer/kiln
equipment) -- the domain's HARD invariant about pre-verification
still applies to the SAME two independent entity kinds (the
referenced equipment unit for maintenance scheduling, and the
referenced batch for shipment coordination), inherited unchanged from
1610's own Decision 4.

This vertical has NO pre-existing `kotoba-lang/veneerpanel`-style
capability library to wrap (verified: no such repo exists). This
build therefore uses self-contained domain logic — pure functions in
`veneerpanel.registry` (equipment/batch verification, shipment-volume
recompute, panel/veneer-grade validation, output-quality plausibility
validation) are re-verified independently by the governor, the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-1610`'s
`sawmilling.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:wood-panel-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "wood-panel-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external veneer/panel capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
wood-panel vertical has NO pre-existing capability library to wrap.
The equipment/batch-verification / shipment-volume / grade / output-
quality validation functions live as pure functions in
`veneerpanel.registry` and are re-verified independently by
`veneerpanel.governor` — the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-1610`'s `sawmilling.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of wood-panel
plant operations. It does NOT:
- Control veneer-peeling, pressing, or gluing-line equipment directly
- Make plant-safety or hazard decisions (exclusive to the human plant supervisor)
- Authorize or finalize a press-cycle run

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: wood-panel manufacturing is a safety-
critical domain (veneer-lathe/rotary-knife injury risk, hot-press
platen burn/crush risk, urea-/phenol-formaldehyde resin fume
inhalation hazard, wood-dust fire/explosion hazard). Safety-concern
flagging NEVER auto-commits. All safety concerns escalate immediately
to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (formaldehyde-emission concern, equipment
hazard, fire hazard, crew fatigue) ALWAYS escalates, never
auto-commits. This is not a "low-stakes proposal" — it is a
circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Unlike a single-ground-truth-entity domain, this vertical has TWO
entity kinds each gating a different op, inherited unchanged from
`cloud-itonami-isic-1610`'s own Decision 4:
`:schedule-maintenance` independently verifies the referenced
**equipment** unit's own `:verified?`/`:registered?` fields;
`:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields. Both are the same
"plant/batch record must be independently verified/registered before
any action" HARD invariant applied to the two distinct record kinds
this domain actually has. `:coordinate-shipment` additionally
independently recomputes whether a batch's own recorded
shipped-to-date volume plus the proposal's own claimed volume would
exceed the batch's own recorded production volume — never taken on
the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`veneerpanel.governor`, mirroring `cloud-itonami-isic-1610`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's volume must independently recompute within the batch's own logged production volume
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct veneer-peeling/pressing/gluing-line-equipment control or press-cycle finalization is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Wood-panel plant operations back-office now has a documented,
governed, auditable coordination layer that funnels all decisions
through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation or press-cycle finalization. Safety
concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and press-cycle execution remain
human-controlled via external channels.

(-) No integration with real mill-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-1621`: `kbb -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `kbb -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-volume-exceeded, press-finalize-blocked,
  already-scheduled, invalid-grade, invalid-output-quality).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `kbb -M:test` resolves offline inside the monorepo checkout.
