(ns veneerpanel.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`veneerpanel.operation` ->
  `veneerpanel.governor` -> `veneerpanel.store`) through a scenario
  adapted from this repo's own `veneerpanel.sim` demo driver
  (`clojure -M:dev:run`, confirmed to run correctly against the real
  seeded batch/equipment directory before this file was written),
  trimmed to a representative subset (one clean phase-3 auto-commit,
  one always-escalate maintenance schedule, one always-escalate
  safety-concern flag, one always-escalate shipment coordination, and
  three distinct HARD-hold reasons) and rendered deterministically --
  no invented numbers, no timestamps in the page content,
  byte-identical across reruns against the same seed.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [veneerpanel.store :as store]
            [veneerpanel.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: batch-001 clears a clean production-batch intake
  patch (phase-3 auto-commit -- a valid grade and a plausible last-
  assessed date, nothing to reject); a maintenance window (mnt-1)
  scheduled against equip-001 (verified+registered hot-press) ALWAYS
  escalates -- `:schedule-maintenance` is permanently absent from every
  phase's `:auto` set -- approved by a human plant supervisor; a safety
  concern (concern-1) flagged against equip-001 ALWAYS escalates
  (governor `high-stakes` `:coordination/safety-concern`) -- approved;
  a shipment (ship-1) coordinating 50 m³ of batch-001 (within its own
  logged 500 m³ production volume, 100 already shipped) ALWAYS
  escalates (shipment coordination is also never auto-eligible) --
  approved. Then three distinct HARD-hold reasons that never reach a
  human: a maintenance window (mnt-2) against equip-002
  (UNVERIFIED/unregistered glue-spreader) HARD-holds on
  `:equipment-not-verified`; a shipment (ship-2) against batch-003
  (UNVERIFIED/unregistered MDF batch) HARD-holds on
  `:batch-not-verified`; a shipment (ship-3) claiming 10 more m³ of
  batch-002 (80 m³ logged, 75 already shipped -- 10 more would exceed
  the batch's own recorded volume by 5) HARD-holds on
  `:shipment-volume-exceeded`. Returns the resulting store -- every
  field read by `render` below is real governor/store output, not a
  hand-typed copy."
  []
  (let [db (store/mem-store)
        _ (store/sample-data! db)
        actor (op/build db)]

    (exec! actor "batch1-intake" {:op :log-production-batch :effect :propose :subject "batch-001"
                                   :patch {:grade :veneer-a :last-assessed "2026-07-14"}})

    (exec! actor "mnt1-schedule" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                   :value {:equipment-id "equip-001" :maintenance-type :press-platen-service
                                           :scheduled-date "2026-08-01" :finalize? false}})
    (approve! actor "mnt1-schedule")

    (exec! actor "concern1-flag" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                                   :value {:equipment-id "equip-001" :severity :moderate
                                           :description "プレス周辺のホルムアルデヒド濃度上昇"}})
    (approve! actor "concern1-flag")

    (exec! actor "ship1-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                                      :value {:batch-id "batch-001" :volume-cubic-m 50.0
                                              :destination "buyer-yard-north"}})
    (approve! actor "ship1-coordinate")

    (exec! actor "mnt2-schedule" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                                   :value {:equipment-id "equip-002" :maintenance-type :glue-line-calibration
                                           :scheduled-date "2026-08-01" :finalize? false}})

    (exec! actor "ship2-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                                      :value {:batch-id "batch-003" :volume-cubic-m 10.0
                                              :destination "buyer-yard-south"}})

    (exec! actor "ship3-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                                      :value {:batch-id "batch-002" :volume-cubic-m 10.0
                                              :destination "buyer-yard-east"}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (or (-> f :violations first :rule)
                     (first (:basis f)))]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [ledger {:keys [id grade panel-type thickness-mm volume-cubic-m
                                  output-quality-percent verified? registered?
                                  shipped-volume-cubic-m]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s mm</td><td>%s / %s m³</td><td>%s%%</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or grade :n-a))) (esc (name (or panel-type :n-a)))
          (esc thickness-mm)
          (esc shipped-volume-cubic-m) (esc volume-cubic-m) (esc output-quality-percent)
          (if (and verified? registered?) "<span class=\"ok\">verified &amp; registered</span>"
              "<span class=\"warn\">unverified / unregistered</span>")
          (status-cell ledger id)))

(defn- equipment-row [{:keys [id kind verified? registered? last-maintenance-date
                               last-scheduled-maintenance-date]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or kind :n-a)))
          (if (and verified? registered?) "<span class=\"ok\">verified &amp; registered</span>"
              "<span class=\"warn\">unverified / unregistered</span>")
          (esc (or last-maintenance-date "never"))
          (esc (or last-scheduled-maintenance-date "none scheduled"))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `What
  ;; this actor does`, `veneerpanel.governor`/`veneerpanel.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no physical/financial risk</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; equipment verified/registered ground-truth checked, press-cycle finalize permanently blocked</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; high-stakes, never auto regardless of phase</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; batch verified/registered ground-truth checked, shipment volume independently recomputed</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        batch-rows (str/join "\n" (map (partial batch-row ledger) batches))
        equipment-rows (str/join "\n" (map equipment-row equipment))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-1621 &middot; veneer sheets and wood-based panels</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of veneer sheets and wood-based panels (ISIC 1621) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance/shipment coordination always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>veneerpanel.store</code> via <code>veneerpanel.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Grade</th><th>Panel type</th><th>Thickness</th><th>Shipped / Volume</th><th>Output quality</th><th>QC status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     batch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Plant equipment</h2>\n"
     "    <p class=\"muted\">Veneer lathe / hot-press / glue-spreader units. Maintenance may only ever be scheduled (drafted), never actuated by this actor.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Commission status</th><th>Last maintenance date</th><th>Last scheduled maintenance date</th></tr></thead>\n"
     "      <tbody>\n"
     equipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Wood Panel Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Equipment/batch verified &amp; registered status and shipment volumes are independently recomputed, never trusted from the proposal; veneer-peeling/pressing/gluing-line-equipment actuation and press-cycle finalization are permanently blocked.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        f (java.io.File. out)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts )")))
