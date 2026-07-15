# cloud-itonami-isic-1621: Manufacture of veneer sheets and wood-based panels

Open Business Blueprint for **ISIC Rev.5 1621**: manufacture of veneer sheets and wood-based panels — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office wood-panel **plant operations**: production-batch data logging (panel/veneer grade/volume/output-quality), veneer-lathe/hot-press/glue-spreader maintenance scheduling, safety-concern flagging, and outbound panel shipment coordination.

This repository designs a forkable OSS business for wood-panel plant
operations: run by a qualified operator so a veneer/plywood/
particleboard/MDF/OSB plant keeps its own operating records instead of
renting a closed SaaS.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — veneer/panel batch grade/thickness/output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — veneer-peeling/pressing/gluing-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a formaldehyde-emission/equipment-safety/fire-hazard concern (always escalates)
- `:coordinate-shipment` — outbound panel shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain** (veneer
lathes and rotary/slicing knives, hot-press platens, urea-/phenol-
formaldehyde resin fume and fire hazard):

- Does NOT control veneer-peeling, pressing, or gluing-line equipment directly
- Does NOT make plant-safety or hazard decisions (that's the plant supervisor's exclusive human authority)
- Does NOT authorize or finalize a press-cycle run (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`veneerpanel.operation/build`, a langgraph-clj StateGraph):
1. **`veneerpanel.advisor`** (sealed intelligence node, `VeneerPanelAdvisor`): proposes decisions only, never commits
2. **`veneerpanel.governor`** (independent, `Wood Panel Plant Operations Governor`): validates against domain rules, re-derived from `veneerpanel.registry`'s pure functions and `veneerpanel.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct veneer-peeling/pressing/gluing-line-equipment control)
     - Finalizing a press-cycle run (`:finalize? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped volume past its own logged production volume (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:grade` value on a production-batch patch
     - No physically implausible `:output-quality-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`veneerpanel.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`veneerpanel.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
