# DEVLOG — history, key decisions, current status

## 2026-09-06 — the migration gate caught what I did not think about

V47 originally renamed `ingredient_usage_day` to `ingredient_stock_day`, because the name no longer
described a table holding receipts, corrections and a closing balance. Its header reasoned at length
about whether the rename was safe — checked the Debezium connector, confirmed only `public.outbox` is
captured, confirmed RENAME preserves data, indexes, constraints and the RLS policy — and concluded it
was fine.

It never considered app-tier rollback. `scripts/check-migration-safety.sh` refused it: the fleet
deploys by ROLLING update with an automatic rollback on a failed health gate (ADR 0057), so old and
new app versions run against this schema at the same time, and a rollback puts the previous image in
front of a table whose name it has never heard of. Every safety property I checked was real, and I
checked the wrong axis.

The ledger's entire value is in the added columns, so V47 is now purely additive and the table keeps
its historical name. The precision moved to Java instead: `IngredientStockDay` and `stockDate` map
onto `ingredient_usage_day`/`usage_date`, and reads alias the column back to the projection's name.
A better noun was never worth a broken rollback.

Two things worth keeping from the fix. Reverting the rename by a blanket find-and-replace also renamed
the SQL ALIAS the read projection maps by, so the drill-down returned a null date — the kind of break
a rename-by-sed makes and a compiler cannot see; the integration test caught it. And it was safe to
rewrite V47 in place rather than add a V48 undo only because it had never been applied anywhere real:
UAT is at V42 and prod is behind that, so its only executions were in ephemeral test containers.

## 2026-09-06 — a pack is not a unit you can cook with

An owner reported two things: 60 g of meat could not be entered against a kg ingredient, and the
sauce "is in pack but 1 pack is 1 KG and the use is only tiny". Reading production made the shape of
it obvious, and worse than the report:

    Daging Kebab TIS FOOD   g/kg    1,700 kg    0 recipe lines
    Delmonte saus sambal    g/kg        2 kg    0 recipe lines
    Mentega                 g/kg        2 kg    0 recipe lines
    Delmonte Saus tomat     pack           2    0 recipe lines
    roti / cheese / tortilla / chicken   pcs    1-4 lines, all qty 1

**Every weight-based ingredient is in no recipe at all.** Only `pcs` items are. 1.7 tonnes of meat,
the sauces and the butter contribute nothing to HPP and nothing to the ADR 0074 shortfall detector —
on exactly the ingredients worth stealing. Nobody filed that as a bug; they just stopped writing
recipes, and the only trace was the leak report's own coverage line.

Two distinct causes, and only one of them was a real modelling hole.

**The kg case was ours to make easier.** The unit model is fine — `kg` stores grams with a kg label,
and the input did accept 0.06. But no cook writes a recipe in kilograms, and asking someone to
divide by a thousand on every line, at three decimal places, is not a small friction. Recipes are now
typed in the ingredient's BASE unit for every ingredient: 60, not 0.06. The display unit stays on
STOCK, where "beli 1,5 kg" is how buying is genuinely described, so the purchase surfaces are
untouched. A pleasant side effect: the fraction rule stopped depending on what happens to be on
screen — base units are whole by definition, so it is now one rule instead of a per-ingredient one.

**The pack case was a genuine hole.** `pack` sat in the picker's "Count" group beside `pcs`, as if a
pack were an atomic countable thing. It is not — it is a purchase CONTAINER. With `pack` as the base
unit there is nothing beneath it, so the smallest quantity a recipe can express is one whole pack:
a kilogram of ketchup per kebab. There was no correct number to type. The system already had the
right concept — `pack_size` (V46) says "1 pack = 1000 g" at receiving time — but it only works when
the base unit is the fine one. So `pack` is gone from the picker; buying by the pack stays exactly
as it was.

**Which left the ingredients already stranded.** `Ingredient.update` rightly refuses a bare unit
change while stock remains, because reinterpreting "2" from packs to grams would destroy the figure
rather than convert it. So the fix is a real conversion: `POST /api/v1/ingredients/{id}/convert-unit`
multiplies stock by the factor, divides the per-unit cost by it, and leaves total VALUE untouched —
nothing was bought, sold or lost, only the unit changed.

The part that took the most care is what else has to move in the same transaction. A converted
ingredient whose RECIPES still said "1" would consume one gram per portion instead of a pack's
worth — a thousandfold under-consumption surfacing only as an inexplicable surplus at the next
opname, which the leak report would then read as nothing at all. And a converted ingredient whose
LEDGER was left alone would have "rata-rata pemakaian per hari" averaging grams against packs across
the conversion date. Both are rescaled alongside it, bounded to the one ingredient, in one
transaction. A half-applied conversion would be worse than none.

Two guards worth keeping: the factor must be a positive whole number (a fractional one means
converting toward a COARSER unit, losing the precision this exists to gain), and a result that would
overflow the INTEGER stock column is refused outright — 1.7 tonnes of meat converted to milligrams
needs 1.7 billion, and a silently truncated stock figure would read as catastrophic shrinkage at the
next count. Owner/manager only at the gateway, carved out ahead of the POS ingredients route, since
this rewrites history rather than recording a movement.

## 2026-09-06 — the leak report was reporting the future (code-review fixes, ADR 0074)

`/code-review` came back with fifteen findings on the detection work, and four of them were the same
mistake wearing different clothes. Every detector here reasons about something NOT happening, and I
had measured all of them against the window the caller asked for. The console's default period is the
CURRENT month, so that window ends in the future — and the unelapsed remainder of the month is, to a
detector looking for absence, indistinguishable from evidence. Tonight's dinner service came back as
a dark hour. Today came back as a day that never closed. A session opened this morning came back as
abandoned. A stock count taken yesterday was reported as three weeks old, in the very block whose job
is to tell the owner how much to trust the number.

The fix is two upper bounds instead of one. The requested `to` still bounds every query, so nothing
outside the asked-for period is included; a derived `observedTo = min(to, now)` bounds every
CONCLUSION about absence. The unclosed-day check is capped tighter still, at the last complete
outlet-local day: a day in progress has not failed to close, it has not finished. Worth writing down
because the bug was invisible in tests — every integration test used a window ending in the past.

A fifth finding was the same species one level down: the dark-hour BASELINE took its median only over
days that actually sold at that hour, because a day with no sales leaves no row to average. An outlet
selling at 21:00 on three Mondays in eight got a baseline of "about five", and the five silent Mondays
were each reported as a hole — the exact false positive the minimum-expected floor existed to
prevent. The baseline now builds the full day x hour grid and left-joins the counts, so a quiet hour
contributes a real zero.

The rest, briefly. Per-person rates were being computed over ALL payments including PENDING,
ABANDONED and FAILED, so a cashier generating abandoned QRIS attempts had their void rate diluted
below the bar while a cash-only till was flagged for ordinary behaviour. The refund baseline was
built only from operators who had refunded, quietly dropping every clean operator out of the
comparison and inflating both refunders' rates against each other. The discount check's floor was a
money floor, so one rupiah of discount at an outlet where nobody else discounted was enough to put a
name in front of an owner — a minimum has to be counted in times-it-happened when the numerator is
currency. And the manual-correction count took the calendar date of an EXCLUSIVE instant and compared
it inclusively, folding an extra day into every period.

Two smaller ones worth the change: the recipe-consumer query had the sold-quantity roll-up embedded
inside it, so a chunked `IN` clause re-scanned every order and bill line of the period per chunk, on
top of the identical roll-up the coverage figure already computes — the sales mix is now fetched once
and joined in memory. And the leak report had borrowed `StockLedgerDay.ZONE`, a constant whose own
javadoc scopes it to ingredient-ledger bucketing; it is now `OutletZone`, named for what three
separate features actually mean by it, so the coupling is visible rather than accidental.

Finally, the English copy was interpolating `{{count}}` — i18next's RESERVED plural key — with no
`_one`/`_other` forms, so a single finding rendered as "1 bills were cancelled after items had been
added." Rather than add fifteen pairs of plural forms, the bodies are worded count-last and
interpolate a non-reserved `{{n}}`, which is grammatical at any number in both languages and
sidesteps plural resolution entirely. A test now fails if `{{count}}` ever reappears.

One finding I checked and did not treat as live: a CLOSED session with a null variance being counted
as an exact-zero close is unreachable, because V21's `ck_crs_closed_shape` requires the column to be
present on any CLOSED row. Hardened anyway — treating "never compared" as "agreed exactly" is the
wrong default to leave lying around, and it cost one branch.

## 2026-09-06 — finding the sales that were never rung (ADR 0074)

Every report Native has is derived from what was recorded, so none of them can see the fraud that
actually hurts an F&B SME: the meal that gets served, the cash that gets taken, and nothing rung up.
The books stay perfectly consistent afterwards, because the missing revenue was never an input.

What betrays it is physical — the ingredients still left the kitchen, the bottle still left the
fridge — and restaurant-service already held every piece of that evidence without ever correlating
it. So the new `integrity` feature is a READ-ONLY report over rows that already existed: no table,
no event, no GL impact, one owner-only endpoint. Nine detectors, in two families. Stock that moved
without a sale (tracked items counted short; ingredient shortfall converted into portions through
the recipes). And tills that were not watched (an hour with no sales on a day and hour the outlet's
own history says is busy; sales rung with no register session open; a trading day that never closed;
persistent cash short; unexplained cash over; a session left open; a run of closes that came out to
exactly zero).

Four decisions shaped it more than the SQL did:

**The headline is a range, not a number.** The low bound counts only tracked-item shortfall, where
one missing bottle is one unrecorded sale at one known price. The high bound adds the ingredient
estimate, which is real but has innocent explanations — waste, spoilage, staff meals, over-portioning
— that Native cannot yet record and net out. Collapsing them would hand an owner an inference with
the confidence of a measurement, and they would act on it.

**The report publishes its own blind spots.** Coverage rides in the same response as the estimate,
not behind a second request: how much of what sold was even backed by a recipe, and how long since
anyone counted. At 30% recipe coverage a small number means almost nothing, and without that stated
it reads exactly like a clean bill of health.

**A signal that did not fire is omitted, not returned at zero.** An empty list says "nothing stood
out". Nine zeroes say "the system looked", which is noisier and weaker.

**The estimate never touches the ledger.** No outbox write, no posting. Real shrinkage already posts
through the existing stocktake flow; this only reinterprets it as lost revenue, for the owner's eyes.
The same instinct drove the rest: owner-only at the gateway (a manager can be the subject of a
finding), variance patterns thresholded on RECURRENCE rather than on an amount (three short closes
is a pattern, one is a bad night — and a rupiah threshold would be meaningless in another currency),
and nothing auto-notified to anyone.

The allocation maths turned out prettier than expected. Distributing a missing quantity across the
dishes that consume it, weighted by the real sales mix, reduces to `missing x sold_i / SUM(sold_j x
qty_per_portion_j)` — the per-portion quantity cancels from the numerator but not the denominator,
which is exactly right: a dish using four times as much absorbs four times the shortfall, then
converts its share back into portions at its own heavier rate. All `long`, one integer division at
the end, no float anywhere near it.

The five per-person detectors (void, refund, discount and cash-tender rates; bills cancelled with
items on them) were the part worth being most careful about, because a rate comparison that is subtly
wrong does not crash — it quietly puts somebody's name in front of an owner on a page about theft.
Two decisions came out of that. Each operator is compared against the REST of the outlet, never
against an average including themselves: at three cashiers, the one voiding a third of their sales
lifts the outlet average enough to look ordinary, and small rosters are exactly where this gets used.
And the comparison is cross-multiplied rather than divided, so no rate is ever materialised and the
verdict cannot turn on a rounding step.

Most of that component's tests are about restraint rather than detection — a lone operator is never
an outlier (an owner working their own till is the entire baseline, not an anomaly), a 100% rate over
three sales is ignored, and being merely above average is not enough. One of those tests caught my
own wrong premise: I had written a case asserting that two heavy voiders out of three would both be
flagged, and the code correctly reported neither, because each one's baseline contains the other.
That is the right answer — when most of the roster does something it is the outlet's practice, not
one person's anomaly — so the test became a pin for that restraint instead.

The console page puts its own caveats where they cannot be skipped: the disclaimer sits directly
under the headline number rather than in fine print, and the coverage notes sit ABOVE the findings,
not below them — a reader who sees a reassuring total first has already drawn a conclusion by the
time they learn it was computed over 30% of what sold. A vitest pins that every signal type has copy
in BOTH locales, so adding a detector and forgetting the Indonesian block fails in review instead of
shipping an owner a card titled `EXACT_ZERO_CLOSE_RUN`.

Two things the tests said that I had wrong. The unsessioned-sales figure is the GRAND total the till
took, service charge and PB1 included — which is correct, because what went unreconciled is the cash
in the drawer, not the revenue line under it. And a missing required query parameter returns 500
rather than 400: that is fleet-wide behaviour in `libs/security`, unchanged here, and pinning it in
this endpoint's test would have either codified the wart or made one endpoint diverge from the rest.
Left as a noted gap rather than quietly widened scope.

## 2026-09-06 — the stock figure now has a history, not just a number (ADR 0074)

Groundwork for sales-leak detection, but useful on its own. `ingredient.stock_qty` was a single
number with no story behind it: you could see that 8 kg of flour was left, never that 40 arrived,
6 were consumed by recipes and somebody hand-corrected it twice. `ingredient_usage_day` (V42) held
one bucket — recipe depletion — so shrinkage that had *already been explained* (a delivery, an
opname, a manual fix) was indistinguishable from shrinkage that had not.

V47 renames that table to **`ingredient_stock_day`** and widens it into the full daily movement
ledger: `qty_used`, `received_qty`, a SIGNED `adjustment_qty`, a reserved `waste_qty`, `closing_qty`,
and the two counters an owner actually asks for — `receipt_count` and `adjustment_count` ("berapa
kali stok dikoreksi manual"). Every writer that moves stock now books to it in the same transaction
as the movement: depletion, both priced-receive entry points, create/set/add, and each opname line.
Reads at `GET /api/v1/ingredients/stock-history` (per-ingredient roll-up) and `/{id}/stock-history`
(day by day).

Three decisions that were not obvious:

**A daily aggregate, not a per-movement ledger.** A row per sale x per ingredient is the natural
audit shape and explodes with volume — which is exactly why V42 chose the (ingredient, day) UPSERT.
V47 keeps it and its concurrency discipline intact: one ingredient at a time, ascending UUID order.
The opname path had to be restructured for that — it collects corrections during its line loop and
drains a `TreeMap` afterwards, rather than UPSERTing in request order.

**`closing_qty` is sourced from `ingredient.stock_qty` inside the UPSERT, under
`flushAutomatically = true`.** Entity-path callers (`setStock`, an opname line) have a dirty,
unflushed persistence context, so a native query would otherwise read the figure from *before* the
movement it is supposed to be mirroring. Passing the value in from Java would have worked too, but
sourcing it from the row makes it impossible for the mirror to disagree with what it mirrors.

**A movement is booked to the day it is applied, never back-dated.** The ADR 0072 purchase consumer
can carry a back-dated bill; writing that into a past day's row would overwrite an already-closed
day's `closing_qty` with today's figure. The ledger records when the *figure* moved — the same rule
depletion already follows for an offline sale replayed the next day.

The rename was safe to do at all only because Debezium captures `public.outbox` and nothing else on
this database, so no connector, publication or slot names the table.

One test failed and was right to: `IngredientUsageAtomicityTest` asserted a rolled-back sale leaves
**no ledger row**, and its own arrangement (create an ingredient with opening stock) now legitimately
books one — opening stock is stock arriving, a receipt. The assertion moved to the invariant it
actually meant, `SUM(qty_used) == 0`, which is stricter than counting rows. Worth noting because the
failure was the schema change telling the truth about a test whose premise had quietly expired.

## 2026-09-04 — receipt wording, pack sizes, and a guard that was wrong one step later

Three owner asks on the purchase surfaces, plus the bug the third one uncovered.

**The receipt does not use your inventory's names.** A nota says "AYAM BROILER FROZEN 1KG"; the
item is "Ayam fillet". Storing only the item name loses the tie to the paper an auditor holds;
storing only the receipt text loses which stock moved. A line now keeps BOTH — AP bill lines
already could (V59), company-expense lines gained `description` (V60). Company-expense normalises
blank-or-equal-to-the-item-name to NULL so "differs" stays a real signal; the AP column is NOT NULL
and cannot, which the ADR now states per-surface instead of claiming both behave alike.

**The pack is not the unit.** "Di receipt 1 pcs padahal 1 pcs itu 20 tortilla." A line takes an
optional "isi per kemasan" and multiplies to base units, with the result read back inline before
submit — a mistyped 200 must be visible then, because inflated stock only surfaces at the next
opname. `ingredient.pack_size` (V46) remembers the usual value as a DEFAULT, pre-filled and freely
overridable ("kadang tiap merek isinya beda"), never written back, and nothing derives stock from
it. Deliberately separate from `display_unit`'s fixed 1000x kg/g family.

**"kg bisa desimal" already worked** — and checking it turned up two real defects. The pack-size
input rejected decimals even for kg items. Worse: `Ingredient.update` rewrote the base `unit` with
no conversion, so an owner fixing an item mis-created as `pcs` would reinterpret "10 pcs" as "10
g". The first guard — refuse while stock remains — was **incomplete in exactly the dimension it
existed to protect**: `unit_cost_minor` is a per-BASE-unit figure that survives the zeroing (the
cache recompute is a no-op at qty 0) and the from-empty revaluation prices new stock at it. Walking
the very path the 409 prescribed (opname to zero → switch pcs to kg → re-enter 2 kg) booked Rp
20.000.000 of flour — the same 1000x poisoning, one step later, and my own test had encoded the
wrong behaviour. A base-unit change now clears every per-unit figure hanging off the old unit
(cost, currency, pack size); a priced receive re-establishes them.

Lesson worth keeping: when a guard refuses an operation and tells the user how to proceed, walk
that prescribed path end-to-end — the hole was not in what the guard blocked but in what it let
through afterwards.

## 2026-09-04 — the per-merge image build was feeding nobody (ADR 0073)

Chasing "why does the pipeline run twice per commit", the two-workflows-per-event part turned out
to be by design (PR = `ci`+`ai-gates`; master push = `ci`+`images`; tag = both + `deploy-prod`), and
the tag's runs are **full-scope** (`FORCE_FULL`/`FORCE_ALL` — all 13 images at one SHA, which the
digest-pinned deploy manifest requires) rather than duplicates of the path-scoped master runs.

The real waste was elsewhere and had been sitting in plain sight: **every merge to `master` built
and pushed a set of GHCR images that nothing pulls.** ADR 0053 §2 assumed UAT would consume them,
but `scripts/uat-up.ps1` builds UAT images **locally** (`native-uat/<svc>:latest`) and
`compose.uat.yml` references only third-party images. A repo-wide sweep confirms the only consumer
of GHCR app images is the prod deploy, and it uses the **tag** build's digests. ADR 0053's own
Context had even recorded the symptom — *"Nothing consumes those images."* — and the intended
consumer never arrived.

`images.yml` now runs on `v*` tags + `workflow_dispatch` only. `:latest` moves with it: it used to
follow the last master push, it now follows the last RELEASE, which is the safer thing for the
`compose.prod.yml` fallback to resolve. `ci.yml` on master is untouched and must stay — after a
squash-merge only the master-push run carries the release commit's SHA, and that is exactly what
`verify-ci` requires before a deploy.

## 2026-09-04 — one release line again: `master`, and the drift that cost us twice

ADR 0053 §3 has always said `master` is the trunk and a `vX.Y.Z` tag on it ships prod, with
`feat/*` short-lived. In practice releases had been cut from a long-lived
`feat/business-employee-apps` for months, with fixes ported back and forth. Nothing in the
pipeline ever referenced that branch — `ci`/`images`/`deploy-prod` key off `master` and `v*` tags
only — so the drift was invisible in automation and lived purely in habit.

**It cost us twice, both this week.** (1) `master` fell 112 commits behind, so the CI tag gate we
added only covered tag pushes; `workflow_dispatch` reads the DEFAULT branch, and master's stale
`deploy-prod.yml` had no `verify-ci` — the v0.1.37 redeploy went through ungated (#11 closed it).
(2) The migration-safety gate uses `origin/master` as its proxy for "what is already deployed";
against a stale master, 19 already-shipped migrations looked new, and the only way to silence it
would have been editing applied `.sql` files — breaking Flyway checksums in prod. Both failures
trace to the same root: two branches disagreeing about what exists.

**Fix:** `master` was brought current (#12, conflict-free, tree byte-identical to the release
line), and `feat/business-employee-apps` is retired. Releases go back to the documented flow — PR
→ master → tag master → approve. `verify-ci` now enforces the invariant structurally: the deploy
refuses unless a green `ci` run exists for that exact commit, which only happens if the commit
landed on master first.

**Not a duplicate, contrary to first appearances:** the tag's `ci`/`images` runs are FULL-scope
(`FORCE_FULL` / `FORCE_ALL` — all 13 images pinned at one SHA, which is what the deploy manifest
needs), while master pushes are path-scoped for UAT. Two workflows per event (ci+images on master,
ci+ai-gates on a PR) is likewise by design, not a double-fire. The genuine waste was the two-branch
lineage, and that is what this removes.

**Open, and worth a decision:** `images.yml` still builds on every master push to feed UAT, but
UAT is operated by hand (`scripts/uat-up.ps1`). If UAT is not being refreshed per merge, moving
that build to `workflow_dispatch` is the single largest remaining Actions saving.

## 2026-09-03 — ADR 0072: one-submit purchases (expense input ⇔ inventory), contract first

The owner's ask — "expense input linked with inventory: a purchase updates stock automatically,
synchronized" — turned out to expose two facts worth recording. First, **there is no company
expense input at all**: the "Pengeluaran" page is employee claims; `ExpenseRecorded` has a finance
consumer and *no producer anywhere*; the only money entry for purchases is the AP bill. Second,
nearly all the stock-side machinery already exists dormant behind the ADR 0068 periodic gate
(priced `goods_receipt` + moving-average + `StockReceived`, `bill_line.is_inventory`, the GRNI
split in `BillWriter`).

ADR 0072 (owner decisions 2026-09-03): a NEW finance `companyexpense` feature is the primary
entry; AP bills with ingredient-linked inventory lines join in the same program; under periodic,
ingredient purchases post **5100 HPP** (not 5000 — makes HPP meaningful in the periodic P&L). The
seam is **`InventoryPurchaseRecorded`** — the fleet's first finance→vertical event: emitted in the
same tx as the money's journal entry, consumed by restaurant as one priced goods receipt per line
with `goods_receipt.idempotency_key = line_id` (no redelivery can double-add stock; business
anomalies park in the error inbox, money already safely posted). Void is money-side only
(fix-forward); the priced "Terima" path gets demoted in the console so the form is the only
priced entry (the human double-entry mitigation). P0 ships the `.avsc` + catalog entry + both
contract tests; the feature phases follow.

**P1–P4 landed the same day.** Finance `companyexpense` (V58) posts the money at submit through
the GeneralLedgerWriter door with DERIVED illustrative provenance (a hardcoded flag here would
have flipped every tenant's audit badge); the void is the exact mirror of the STORED journal
(`JournalLineReversalView` — immune to a perpetual activation landing between post and void).
restaurant consumes via the NEW `PricedReceiveWriter` — the priced-receive core extracted from
`IngredientWriter.addStock` because `OutletAccessGuard` reads the HTTP `X-Roles` header, which
does not exist on a Kafka thread. Bills (V59) carry an optional per-line ingredient linkage and
the periodic split now routes inventoryNet to 5100 (the deliberate ADR 0072 behavior change;
`BillInventoryRoutingTest` rewritten as the record; no-inventory bills stay byte-identical).
Console: "Catat pengeluaran" on the Pengeluaran hub + the Perusahaan tab + the demoted Terima.

Two things this build coughed up, worth remembering:
- **The fleet test-executor heap was Gradle's 512m default** — finance OOM'd ("Java heap space")
  when one more cached Spring context landed. `native.java-conventions` now sets
  `maxHeapSize = "1g"`; the heap sibling of the max_connections creep. Raise it THERE next time.
- **A pure accountant (non-owner) hits 403 on two pickers**: `GET /api/v1/expense-categories` is
  HR_ROLES and `GET /api/v1/ingredients` is POS_ROLES. Deliberately NOT widened — those route
  patterns also cover writes, so widening would hand accountants stock mutation; a method-aware
  gate is the real fix. Owner-operators (every current tenant) are unaffected. OPEN.

## 2026-09-03 — the test-Postgres time bomb disarmed fleet-wide, and a phantom submodule removed

**The trap was still armed in 9 services.** The 2026-09-02 entry below fixed restaurant (and
finance followed) but closed with "copy the fix when it fires" — and it was about to.
Ranking the remaining services by test-class count put **employee-service at 94, already past the
~90 that killed restaurant**; org 54, carwash 50, barbershop 44, then the small ones. Because the
failure presents as three *unrelated* tests failing on their own subjects, hitting it again would
have cost another debugging session to reach the same answer, so all 13 remaining Testcontainers
Postgres declarations now carry both halves of the fix:
`postgres -c fsync=off -c max_connections=500` on the container, and
`maximum-pool-size=8` / `minimum-idle=2` on each `@DynamicPropertySource`.

**`fsync=off` is restated deliberately, and that is the subtle part.** `withCommand` *replaces* the
constructor's command rather than appending to it, so a fix that passes only `max_connections`
silently drops Testcontainers' own `fsync=off` default and slows every suite for the rest of time.
The first cut of the restaurant fix had exactly that shape before it was corrected.

Verified: **employee 619 tests green** (the one at the threshold) and restaurant **818 green**
(confirming the original fix holds). org-service's five *standalone* container declarations
(DeviceCredential / MultiCompanyMembership / SecuredCompanyBootstrap / Signup / UserManagement
acceptance tests) were patched too — each starts its own container for a single class so none is
at risk today, but they are the same declaration, and drift between copies is what armed this in
the first place. `gateway` has no Postgres container and needs nothing.

**A phantom submodule was in the tree.** `.claude/worktrees/agent-accbb693f5a9ef84a` had been
committed as a **mode-160000 gitlink** — an agent worktree (a nested git repo) captured by accident,
and the only gitlink in the repo. With no `.gitmodules` to describe it, a fresh clone gets a
submodule it cannot resolve. Removed from the index and the path is now gitignored.

It arrived inside a commit whose message read *"Add unit tests for OrderTotal functionality in
restaurant service"* — a description of work it did not contain (`OrderTotalTest.java` was added in
`bbc04860`, long before). What it actually held was 67 files of Play-Store publishing work: store
assets and screenshots, launcher icons across every mipmap density for both Android shells,
`delete-account.html` / `privacy.html` / `sitemap.xml`, and the e2e lockfile. The commit was
unpushed, so it was reworded to say what it is rather than left as a false record.

## 2026-09-02 — `JournalEntryPosted` ships from the door (ADR 0071 P1), and the outbox finally gets retention

With the door in place (P0 below), P1 is small by construction: `GeneralLedgerWriter.post` now
builds a `JournalEntryPosted` record (`gl/messaging/JournalEntryPostedSchema`, schema in
`libs/contracts/avro/JournalEntryPosted.avsc`) and writes it to the outbox **in the same
transaction** as the entry and its lines — partition key `company_id`, one event per entry, a
supersession's contra and re-post each carrying their own `posting_role` (`PRIMARY`/`REVERSAL`).
Catalog entry added. `JournalEntryPostedContractTest` proves the rule-7 triad;
**`GlOutboxCompletenessTest`** is the numeric belt to the ArchUnit suspenders: it drives the
revenue AND expense flows (two independent writers) and reconciles `journal_entry` against the
outbox — count equality, faithful lines, balanced on the wire. **`GlPostingAtomicityTest`** proves
the rollback half (ENGINEERING-STANDARDS §3.2): a throw AFTER the outbox write rolls entry, lines
and event back together — via a test-only `@Transactional` harness, since the door itself is
MANDATORY-propagation. The consumer contract that matters
later: **idempotency keys on `journal_entry_id` (a claim table), never the outbox event id** — a
replayed event carries a fresh id.

**The retention decision (deferred from P0) is made: option (b), and the ordering is the design.**
`scripts/prod-outbox-prune.sh` runs from `prod-backup.sh` **after** a successful nightly backup, so
every pruned row already exists in tonight's encrypted archive. It prunes a service's outbox only
while that service's **Debezium connector task is RUNNING** (a missing connector may mean unrelayed
rows — exactly the finance-backlog failure shape P0 fixed), and only rows older than
`OUTBOX_KEEP_DAYS` (30). No `@Scheduled` job enters the fleet (ADR 0029 convention holds), nothing
lands on the outbox's hot write path, and the finance initial-snapshot hazard is closed by the
RUNNING guard rather than by hoping about deploy order.

Also in this pass: the stale `sold_by_user_id` docs (`SaleRecorded.avsc`, its catalog copy,
`SaleRecordedSchema`) now state the truth — populated since ADR 0049 P4 for operator-PIN sales,
never set by carwash/barbershop, and `MetricPublished.subject_id` is a *different id space* (no
bridging event). **ADR 0071 written** (`docs/adr/0071-analytics-star-schema.md`) and the ADR index
repaired — 0067/0068/0069 were missing from `docs/adr/README.md`.

## 2026-09-02 — prod tags are no longer CI-ungated, and the restaurant suite stops eating its own Postgres

Two CI holes closed:

1. **Release tags never ran CI** — `ci.yml` triggered on master only, tags are cut from
   `feat/business-employee-apps`, so v0.1.34 shipped while the branch was red. Now `ci.yml` also
   fires on `v*` tag pushes (tag refs escalate to the FULL build — a tag has no paths-filter base),
   and `deploy-prod.yml` gained a **`verify-ci` job ahead of the approval gate**: it resolves the
   tag's SHA and polls for a successful `ci` run on that exact SHA (accepting an already-green
   branch/PR run — no forced double build), failing the deploy if CI failed and waiting up to 30
   min if it is still running. Caveat: tags predating this change have no CI run to find, so a
   `workflow_dispatch` REDEPLOY of an old tag needs a manual `ci.yml` dispatch on that tag first
   (the VPS-side `prod-rollback.sh` path is unaffected).
2. **The 3 "failing restaurant tests" were never about their subjects.** `SelfOrderSweepTest`,
   `StocktakeAtomicityTest`, `StocktakeLineRlsIsolationTest` died on
   `FATAL: remaining connection slots are reserved for roles with the SUPERUSER attribute`: ~90
   test classes share one Postgres container, Spring's context cache keeps up to 32 distinct
   contexts alive, and Hikari's default `minimumIdle == maximumPoolSize == 10` pins 10 idle
   connections per cached context — the suite finally crossed `max_connections=100`. Fix in both
   container bases: `max_connections=500` on the container + `maximum-pool-size=8` /
   `minimum-idle=2` per context. Full suite green locally (818 tests). The same time bomb ticks in
   every service's copy of these bases as their suites grow — copy the fix when it fires.

## 2026-09-02 — the GL gets one persistence door: `GeneralLedgerWriter` (ADR 0071 P0)

Groundwork for the analytics module, but it stands on its own. `JournalPostingService` centralised
how a journal entry is *built* (`buildEntry`, `buildEntryForSale`, `buildEntryFromBreakdown`);
nothing centralised how it is *written*. So **29 call sites across 28 writer classes** each repeated
the identical incantation — stamp the tenant on the entry, `saveAndFlush` it (forcing the FK target
down before the lines, which are `@Transient` and therefore not cascaded), then stamp and save each
line. Five lines, copy-pasted 29 times.

That is the [ADR 0065](adr/0065-gl-derived-dashboard-pnl.md) anti-pattern one level down. ADR 0065's
lesson was that a figure assembled by a hand-picked set of writers diverges silently as writers are
added; here the same shape blocks the thing analytics needs most — a per-entry `JournalEntryPosted`
event. Emitting it would mean copy-pasting the emit 29 times, and the 30th posting writer would
forget. **All five lines now collapse to `generalLedgerWriter.post(entry, companyId)`.**

**The guarantee is structural, not a numeric test.** A new ArchUnit rule
(`onlyTheGeneralLedgerWriterPersistsJournals`) forbids every class except `GeneralLedgerWriter`
from calling a Spring Data WRITE method (`save*`/`delete*`/`flush`) on `JournalEntryRepository` /
`JournalLineRepository`. It is deliberately a **write-side** rule rather than a no-dependency rule:
`GlTrialBalanceReader`, `BalanceSheetReader`, `RegisterCloseWriter`, `ReversalPostingWriter`,
`PayrollLiabilityWriter` and `PayrollSettlementWriter` all legitimately READ those repositories
(trial-balance aggregation; prior-entry lookups for ADR 0064 supersession), and forbidding that
would be wrong. So the 30th posting writer cannot bypass the door — that is not detected after the
fact, it does not survive the test gate.

`post` is `@Transactional(propagation = MANDATORY)` — it never opens its own transaction, it joins
the calling `*Writer`'s, which is where `RlsAutoApplyAspect` has bound the tenant GUC.
`ReversalPostingWriter` already used MANDATORY, so the shape has precedent; the aspect re-applies
the GUC across suspend/resume, so the added proxy changes nothing.

**Three traps, and the third is the one to remember.**

1. A regex that consumes the `setCompanyId` line preceding a save will happily eat one belonging to
   a *different* object — audited by diffing every removed `setCompanyId` and confirming all 35 were
   on the entry (`entry`/`glEntry`/`contra`) or the line variable.
2. A "is this dependency now dead?" scan keyed on `repo.method(` **misses line-wrapped calls**:
   `PayrollSettlementWriter` uses `journalEntryRepository\n.findById(...)`, was misread as dead, and
   had a live dependency removed — caught only because the restored-then-compiled signature
   disagreed. Grep-based deadness is not deadness; the compiler is.
3. **The guard was silently vacuous on the first attempt, and passing tests looked like proof.**
   It was written as `noClasses().that(…).should(customCondition)`. ArchUnit **negates** a
   `noClasses()` condition — it flags a class when the condition reports *satisfied* — so a custom
   `ArchCondition` that only ever emits `SimpleConditionEvent.violated(...)` can never fire there.
   The rule went green against a fully-migrated codebase and would have gone green against a
   completely unmigrated one. Caught by **deliberately pointing the excluded class name at a
   non-existent class**: the rule still passed, which it could only do if it were vacuous. Fixed by
   using `classes()` (the form this file's other custom conditions already use); re-checked, it now
   reports 2 violations with the intended message. **Vacuity-check every new ArchUnit rule by
   inverting its premise — a structural guarantee you have not seen fail is not yet a guarantee.**

In tests, writers are now constructed with a **real** `GeneralLedgerWriter` wrapping the *same*
mock repositories, so existing `verify(journalEntryRepository).saveAndFlush(…)` and
`verifyNoInteractions(…)` assertions keep observing the writes unchanged — the door delegates
straight through.

**Deliberately NOT in this change:** the outbox retention job. `@Scheduled` turns out to be against
a documented fleet convention (`OrderWriter:546` — *"no @Scheduled job in this fleet — see ADR
0029"*, the lazy-sweep idiom), and there is a hard ordering hazard: the new finance connector's
`snapshot.mode: initial` replays the unrelayed outbox backlog to repair `group_trial_balance`, so
pruning finance's outbox before that snapshot runs in prod destroys the repair permanently. It
belongs with `JournalEntryPosted` (the change that actually inflates the outbox), with the design
chosen rather than guessed.

## 2026-09-02 — finance-service had no Debezium connector: two "live" events never reached Kafka

Found while designing the analytics module, not by a failing test — which is the point. finance-service
has wired an `OutboxWriter` since the group-consolidation work (`config/EventsConfig.java:58`), and
two writers use it: `withinclose/WithinCompanyCloseWriter` emits **`ConsolidationClosed`**
(`group_id = NULL`, the within-company kind) plus one **`TrialBalancePublished`** per group the
company is active in, and `consolidation/GroupCloseWriter` emits `ConsolidationClosed` at group
scope. `docs/EVENT-CATALOG.md` lists both as live on both sides. But `docker/debezium/` held **eight**
connectors — barbershop, carwash, employee, entitlement, loyalty, org, restaurant, payment — and
**none for `finance_service`**. The rows were written to a table and relayed nowhere, in every
environment including prod.

The consequence is quietest where it hurts most: `TrialBalancePublished` is consumed by **finance
itself** (the `grouptb` ingest that fills `group_trial_balance`), so group consolidation has been
assembling a consolidated trial balance from a read model that was never fed — the SEAM-2 ingest had
no input, and `GET /api/v1/groups/{id}/consolidation` returned its "no close" 204 for a company that
had genuinely closed. `ConsolidationClosed` never reached notification-service either.

**Why nothing caught it.** Every CDC guard we have — `prod-deploy.sh`'s `recover_cdc`, the ops-watch
"connector TASKS running" job — enumerates the connectors Kafka Connect **already has registered**
and asserts their tasks are RUNNING. A connector that was never registered has no task to be
un-RUNNING, so it is invisible to all of them. ops-watch even errors on *zero* connectors, which
made eight-out-of-nine look like health. The missing assertion is **declared vs registered**: every
`docker/debezium/*.json` should exist in `GET /connectors`. Left as a follow-up here only because
`.github/workflows/ops-watch.yml` has in-flight edits.

**The fix** is `docker/debezium/finance-outbox-connector.json`, structurally identical to the other
eight (verified key-by-key) — only `database.{user,password,dbname}`, `topic.prefix`, `slot.name`
and `publication.name` differ. `finance_service` already had `REPLICATION` on its role
(`docker/postgres/init/01-init-databases.sql:32`), so no DB change was needed. Both registration
paths glob `debezium/*.json` (`scripts/prod-bootstrap.sh` §6, `scripts/uat-up.ps1` §9), so the file
is picked up with no script change.

**`snapshot.mode: initial` is load-bearing here, and means something different than it does on the
other eight.** They were registered against empty outbox tables; finance's has been accumulating
unrelayed rows this whole time (nothing prunes the outbox). The initial snapshot therefore **replays
that backlog**, which is the repair — it backfills `group_trial_balance` from every historical close.
Safe on both consumers: the `grouptb` ingest is `processOnce`-idempotent, and notification-service
sends through `StubNotificationSender` (synthetic receipts, no real email/SMS), so replayed
`ConsolidationClosed` rows cannot deliver stale messages to a human.

**Deploy note.** `prod-deploy.sh` only *restarts* connectors it finds already registered; it never
registers new ones from disk. A running prod therefore needs `prod-bootstrap.sh`'s §6 (idempotent
PUT) or a manual `PUT /connectors/finance-outbox-connector/config` — a rolling deploy alone will not
pick this up. Replication headroom was also raised **10 → 20** (`max_replication_slots`,
`max_wal_senders`) across dev/uat/prod: eight slots were in use of ten, finance makes nine, and
that ceiling is a Postgres restart to change — better done now than mid-incident.

## 2026-09-01 — ADR 0070: the org tree is flat (`company > outlet`); the division level is gone

The tree was `company > business_unit > outlet > team`, but only the OUTLET level ever did anything.
Sales, menus, tables, bills, register sessions, labor allocation and per-outlet revenue all key on
the outlet id (ADR 0012 guaranteed that); the `BUSINESS_UNIT` — the console's **"Division"** —
existed only so outlets had a parent and the `vertical` had somewhere to live, and `TEAM` existed
almost nowhere at all. That layer cost a self-join on the POS's hottest read, a `divisionId` threaded
from `/api/v1/outlets` through the session into three payment modals, per-BU fan-out branches in
Dashboard / HR / Payroll / Expenses, a second name at signup, and a *company-vs-division* decision at
the worst possible moment — and bought nothing. [ADR 0021](adr/0021-multi-company-ownership.md)
already delivers what it stood in for: **one login, N companies**. So a second business is now a
second company, and grouping for reporting is group consolidation's job.

**The vertical moved to the company** (`V14`), REQUIRED and IMMUTABLE like `base_currency` /
`country`. Two traps shaped that migration: the column is **nullable in the DB with the non-null
invariant in the aggregate** (the house rule — and it keeps `V14` expand-only for the ADR 0057
rollback gate, which forbids `SET NOT NULL`); and the backfill is bracketed in `NO FORCE` … `FORCE`
because `company` and `org_unit` are FORCE RLS, so a bare Flyway `UPDATE` matches **zero rows
silently** — the failure that looks exactly like success. `CompanyCreated` gained `vertical` as a
nullable, defaulted, **LAST** field; no other event schema changed, because `type` is a free string
(now always `"OUTLET"`) and `parent_id` was already a nullable union (now always null). That is why
`OrgUnitType` survives as a one-value enum and `org_unit.parent_id` survives as an always-null
column: keeping them meant finance's `org_unit_ref` and employee's `org_unit_projection` needed **no
consumer migration at all**.

**`OrgUnitDeleted` (P1, shipped first and alone)** closes a gap ADR 0018 recorded as a follow-up: a
hard delete emitted nothing, so every consumer kept the deleted node forever as an inert ref. It is
terminal, emitted from the same transaction as the row delete, and consumed by finance + employee as
a PURGE (a dedicated removal command, not an upsert with placeholder fields — a deletion has no
state to project, and modelling it as one would let a malformed event write a ghost row). **Deploy
order is load-bearing**: those consumers must be live before org-service ever emits.

**The flattening itself runs as a one-shot idempotent reconciler, not SQL.** Reparenting and
retiring nodes are state changes, so they publish through the outbox (rule 3), and hand-serialised
Avro in a `.sql` file would be neither maintainable nor testable. The catch: the reconciler boots
with no tenant bound, so under FORCE RLS it **cannot enumerate the affected tenants** — Flyway can,
so `V15` does the discovery into a non-RLS work queue which the reconciler drains one transaction
per tenant, failures isolated and left pending for the next boot. That queue is keyed
`VARCHAR(64)`, matching `org_unit.company_id`: a `::uuid` cast hard-failed the whole migration on a
non-canonical tenant id (caught by `VerticalBackfillMigrationTest`, whose fixture uses
`"pre-v6-company"`).

**Also gone:** the DIVISION rung in payment-service (settings resolve outlet → company; prod carries
zero division-scoped rows, so no data migration), `POST /api/v1/companies/{id}/businesses` ("add"
means add a *company* now), and `firstBusinessName` at signup — the bootstrap seeds one outlet named
after the company, and an old body still sending `firstBusiness` is accepted with its vertical
honoured as a fallback so an in-flight old console tab does not 400.

A **prod pre-flight gated the whole thing** (2026-09-01): one tenant (`Bara Kebab`), one business
unit (`restaurant`), one outlet, **zero** teams, **zero** division-scoped `payment_settings`, empty
`user_outlet_assignment`. A tenant with two business units of *different* verticals would have
silently lost one in the backfill; there was none, and such a tenant must be split into two
companies before the migration runs.

Tests: 1801 backend (org 272, payment 76, finance 834, employee 619) + 599 console, all green.
Commits `81776ec6` (P1) and `9da9e0d5` (P2–P5). **Not yet deployed** — the prod rollout still needs
a DB backup first (the `BUSINESS_UNIT` row is deleted, not deactivated: the reconciler is re-runnable
but that delete is not reversible without the backup), and P1's consumers must go out before P2.

## 2026-08-31 — Follow-ups closed: `PaymentChargeExpired` event (order+bill release) + ArchUnit debt

Cleared the open follow-ups from the money-flow audit below.

**`PaymentChargeExpired` — the un-happy-path counterpart of `PaymentChargeSucceeded` (ADR 0045).**
The audit's TTL self-heal covered the BILL side lazily; the ORDER side (stuck `AWAITING_PAYMENT`)
had no recovery at all, and neither side learned *promptly* that a charge had died. Now
payment-service emits `PaymentChargeExpired` (outbox, rule 3) whenever a **QR_ISSUED** gateway
charge terminates without settling — `EXPIRED` (the lazy `expireIfPast` sweep on the till poll, or
a `/sync`), `CANCELED` (cashier cancel), or `FAILED` (PSP) — carrying a `reason`. Emission is in
the SAME transaction as the terminal transition; `ChargeWriter.terminateIfLive` captures
`wasQrIssued` before the flip and emits ONLY then, so an `INITIATED`-stage failure (dead PSP at
create — the `create()` call already fails synchronously to the caller) emits **nothing** (proven
by `ChargeFlowAcceptanceTest`, which still asserts zero outbox on the dead-PSP path and now asserts
exactly one event, with `reason`, on cancel-of-issued-QR and sync-finds-expired).
restaurant-service consumes it (`PaymentChargeExpiredListener → …Service → …Writer`: dedupe by
event id, vertical filter, PENDING-precondition no-op for the capture-won-the-race case,
park-don't-drop for unknown-payment/order or a true state divergence): a BILL payment releases its
`bill_line` reservation + abandons under the ADR 0069 bill-row lock (a new **guard-free**
`BillPaymentWriter.abandonForExpiredChargeInCurrentTx` — the outlet-access guard scopes *cashier*
actions and would wrongly 403 on the system consumer thread; RLS still scopes every query by the
event's `company_id`); an ORDER payment reverts `AWAITING_PAYMENT → PENDING` (new
`Order.revertAwaitingToPending()`, so the sale is payable again by cash / a fresh QR) + abandons the
tender. No money moves, no `SaleRecorded` (ADR 0006 revenue-at-capture holds). Schema single-sourced
in `libs/contracts`, added to `docs/EVENT-CATALOG.md`; contract tests both sides + a real-Kafka
`PaymentChargeExpiredConsumeAcceptanceTest` (order-revert+redelivery-idempotency, bill-release,
already-captured no-op, wrong-vertical skip, unknown-payment park). *Still deferred, by design:*
true partial refunds (SaleRefunded v2 prorated legs) — an enhancement, not a bug.

**ArchUnit layering debt cleared** (the three long-standing violations): `ClosedSessionSummaryResponse`'s
projection→dto `from()` factory moved to `RegisterSessionWriter.toClosedSummary` (a dto must not
reach into the projection layer); `BillAttachmentWriter.upload` now returns the DTO so
`BillAttachmentController` never touches the `@Entity`; `OfflineReplayGuard` (an `order.service`
class, not a `*Writer/*Service/*Reader`) no longer injects `RegisterSessionRepository` — the open
session's `openedAt` is supplied lazily by `OrderWriter` via a `Supplier`, so the extra query still
runs only on the accepted-backdate replay path. `LayeredArchitectureTest` fully green.

**Inventory shrinkage double-expense** (the third open item) needed no new work — ADR 0068
(periodic-safe default + stock-opname variance guard) was already Accepted AND shipped in
`40901209`; the finance `StocktakeWriter`/`StockReceivedWriter`/`SaleCogsRecordedWriter`/AP
`BillWriter` gates on `PerpetualInventoryReader.isActiveFor` are in place, so a non-activated
tenant's opname no longer posts to the GL.

## 2026-08-31 — Functional money-flow audit → fixes: atomic cancel, full-only refunds, reservation TTL

A three-dimension functional audit (bill lifecycle / money-GL seam / concurrency-idempotency)
found the core posture strong (Money type, round-once pricing, reconciliation identity at both
ends, symmetric reversals, complete CashWindowLock coverage, outbox+processOnce everywhere) and a
handful of real flaws, all fixed:
**C1/H1 (CRITICAL) — cancel TOCTOU.** `Bill.cancel()`'s paid/reserved-line guard read an
in-memory snapshot while a PARTIAL split-pay (`markLinesPaidForCash`) and a gateway reservation
mutate `bill_line` via native UPDATEs that never bump `bill.version` — a racing cancel passed its
guard AND its optimistic check, committing a CANCELLED bill with a recorded sale (or live PSP
reservation) stranded on it. Fix: `BillRepository#findWithLockById` (bill row FOR UPDATE) is now
the serialization point of every bill write path (cancelBill, payBill, initiatePendingPayment,
BillPaymentCaptureWriter.capture); canonical lock order bill → bill_line → payment. Pinned by
`BillCancelRaceTest` (barrier-raced, exactly-one-winner, invariant asserted via BYPASSRLS).
`BillGatewayConcurrencyTest`'s loser-exception set widened: losers now observe the winner's
committed state at their post-lock checks instead of losing at the guarded UPDATE.
**#2 (HIGH) — partial refunds.** `VoidRefundWriter.refund` accepted CASH/QRIS/CARD partials (200
OK, drawer/Z-report updated) while finance DLT'd the event (`PartialRefundNotSupportedException`)
— the GL silently kept full revenue+clearing forever; the old ONLINE-only guard's comment even
documented the mechanism. Refunds are now ALL-OR-NOTHING, once, for every tender
(`RefundEdgeGuardTest`); the two register tests that exercised partials via the real path now
refund in full. Real partial support = SaleRefunded v2 with prorated legs (future).
**#3 (HIGH) — PENDING reservation TTL.** No server-side expiry existed: an abandoned QRIS left
bill lines reserved forever (cash-blocked AND — post-lockdown — uncancellable).
`releaseExpiredPendingReservation` (TTL `native.bill.pending-reservation-ttl`, default PT30M) now
self-heals inside cash `payBill` and `cancelBill` under the bill lock; fresh reservations still
block. (`BillPendingReservationTtlTest` pins it at PT0S. The ORDER-side stuck-AWAITING_PAYMENT +
a `PaymentChargeExpired` event remain a follow-up.)
**#4 — capture-vs-abandon deadlock**: `doAbandon` reordered to release lines BEFORE the payment
update (line → payment, matching capture). **#5 — offline replay orphan**: `OfflineReplayGuard`
clamps a `clientOccurredAt` predating the current OPEN session's `openedAt` to that `openedAt`
(the cash IS in this drawer; no open session = unchanged, the inherent gap)
(`OfflineReplayClampTest`). **#6** — FE group-remove now tolerates per-line 409s instead of
stranding a half-trimmed group. **LOW**: sealed-period quarantine also surfaces the sale's parked
reversals to the error inbox (they stranded silently); dead `Bill.setDiscountMinor` removed (the
bill-level discount column is an always-null legacy — discounts are per-check); CashWindowLock
javadoc participant list completed.
**Known-open, unchanged by design:** inventory shrinkage double-expense (awaits the ADR 0050/0067
inventory-method decision), labor GL supersession contra TODO, deliberate Z-report-vs-GL
divergence, ArchUnit layering debt (register dto→projection, BillAttachmentController→entity),
two stocktake tests that flake on local Postgres connection slots (green in isolation).

## 2026-08-31 — Open-bill lockdown: once a bill has items, its flow must end in payment

Owner rule: the POS open-bill flow was too loose — ANY operator (cashier included) could cancel an
open bill or trim its lines, with no server-side role check, and cancel even succeeded on a
partially-paid split-check (stranding the recorded sales) or under an in-flight gateway
reservation. Now (server = the real boundary, `restaurant-service`):
**Cancel** — a bill WITH lines requires owner/manager (403 `bill-mutation-forbidden`); an EMPTY
bill (wrong table opened) stays cancellable by anyone; a bill with PAID lines is uncancellable for
EVERY role (409 `bill-has-paid-lines`), as is one with payment-reserved lines (409
`bill-line-reserved`). **Remove/decrement lines** — owner/manager only; removing a PAID line is
refused server-side (409 `bill-line-paid`). Guards live in `BillWriter` (role, via
`ActorRolesProvider`) + `Bill.cancel()/removeLine()` (money invariants); pinned by
`BillLockdownTest` (10 cases incl. the reserved-line path, `X-Roles` MockHttpServletRequest
idiom). Frontend mirrors the policy as pure functions (`pos/lib/billPermissions.ts`, vitest):
cancel link becomes a visible "needs owner/manager" explainer for cashiers (touch has no
tooltips), disappears entirely on partially-paid bills, and −/trash affordances hide for cashiers
(+ stays — taking orders is still cashier work); `canVoid` uses
`effectiveRoles(auth.roles, auth.elevatedRoles)` so elevated device terminals light up (same as
return-sale). Lockdown problem slugs map to i18n copy instead of raw server English.
**Explicit ack (review W2):** the role guard inherits `ManualDiscountGuard`'s empty-roles-pass —
a request with NO `X-Roles` header is trusted (gateway-less dev recipe / direct service tests);
the gateway always stamps the header on authenticated routes, so a real cashier token is denied.
Making these guards JWT-authoritative is a deliberate follow-up, not an accident. Also noted:
full local suite surfaces PRE-EXISTING failures unrelated to this change — LayeredArchitectureTest
(register dto→projection since cbca8f42; BillAttachmentController→entity since d7cc142d) and two
stocktake tests that flake on local Postgres connection exhaustion (green in isolation).

> **For an AI agent:** this is the durable record of *what was built, why, and where we are* — the
> decisions especially (the code shows the *what*; this shows the *why*, which you can't re-derive).
> Keep it current: when you finish a milestone or make a design decision, add a dated line. The live
> task list is ephemeral; this file is the memory. Update the **Current status** section as you go.

## 2026-08-30 — UI-bug audit fixed fleet-wide (web + both Android shells)

A four-dimension audit (i18n/formatting, layout/stacking/theme, Android-WebView quirks, plus a
45-screen runtime walk) found and fixed, in one sweep:
**Shell-dead flows (the big ones):** every CSV/bank-file export and every `window.print()` was a
silent no-op in the Android apps (WebView ignores `<a download>`/blob and print). NativeShell
plugin v2 (both shells) adds `saveFile` (base64 → MediaStore Downloads, API 29+ gate, orphan-row
cleanup) and `printPage` (PrintManager over the same @media print CSS); web routes through
`lib/nativeShell.ts` — `deliverDownload` (lib/csv.ts, also used by `apiDownload`) saves via the
shell with a `FileSaveToast` confirmation and falls back to the browser anchor path, and 12
`window.print()` sites became `printCurrentPage()` (incl. "Cetak slip", the Employee app's only
print action). Old APKs degrade gracefully (bridge absent → browser path). Also shell-gated the
POS "Customer display" `window.open` (single-WebView shell would navigate the till away) and PDF
bill attachments now render in the in-app lightbox (blob URLs can't leave the WebView).
**Crash safety:** a global `ErrorBoundary` (console + employee, outermost above providers,
module-level `i18n.t`) replaces the white-screen-of-death with a recover+reload card.
**Scroll & stacking:** new `useScrollLock` (counter-based body lock + scrollbar compensation)
wired into every overlay (~50 — shared containers + bespoke) so touch flicks stop scroll-chaining
behind modals; OfflineBanner/AppUpdatePrompt now stack in one fixed banner rail instead of
covering each other; the Employee app gained the ADR-0062 update prompt.
**Safe-area, single source of truth:** `--safe-area-inset-*` gets stylesheet `env()` defaults
(Capacitor's injected values override), `viewport-fit=cover` added to console+employee, and every
fixed bottom surface (POS SummaryBar dock, BillDetail bar, staff tab bar/FAB/footer, self-order
CTA, BackGuard hint) pads with the var — bare `env()` read 0 inside the shells. Employee's
double-counted inset removed (body already contributes it).
**Layout/theme:** six finance tables (Customers/Vendors/Channels/BankAccounts/Expenses/Categories)
were phone-clipped with unreachable columns → `overflow-x-auto`; `min-h-screen` → `min-h-[100dvh]`
sweep (~13 files + body); TableFloor's Tailwind-default greens → brand tokens; global
`touch-action: manipulation` kills POS double-tap zoom without sacrificing pinch.
**i18n/formatting:** raw ISO dates → `formatDate` (staff Beranda/Profile, /me, groups, loyalty —
loyalty also labels the `9999-12-31` sentinel "tanpa tanggal akhir"); payslip periods share one
`periodLabel` helper (console /me showed raw "2026-07"); commission % via `formatPercent`;
printer test receipt uses Intl + company currency (was hardcoded `Rp`); self-order stepper
aria + plural keys. Verified: 594 vitest, lint/build green ×3 apps, both shells compile,
back-guard smoke 20/20, runtime walk clean. The saveFile/printPage upgrades ride the SAME APK
rebuild the back-guard's `minimize()` is already waiting on; everything else ships with the web.

## 2026-08-30 — Hardware-Back guard: confirm-before-leave + every overlay back-dismissable

Owner complaint: in the Android apps a stray Back press silently left a menu (or backgrounded the
app) — e.g. falling out of the POS mid-shift. Decision (owner-approved): EVERY page-changing Back
shows a confirm dialog ("Keluar dari halaman ini?" / at home "Keluar aplikasi?"), only open
overlays auto-close, and the guard runs ONLY in the native shells (browsers unchanged). Mechanism:
the shells translate Back into `webView.goBack()` → `popstate`, so the guard generalizes
`useBackDismiss`'s History-API trick to routes — `useBackGuard` parks a sentinel entry
(`{...routerState, backGuard:true}`, same URL so react-router never navigates) above every route
entry; a Back press pops the sentinel, the guard eagerly re-parks and asks. Confirm = `go(-2)`;
at root (pathname==home or `idx<=0` — never step back into Keycloak) the exit dialog backgrounds
the app via a new 2-method Capacitor plugin (`NativeShell.minimize()` → `moveTaskToBack`, both
shells; web falls back to a "tekan back sekali lagi" hint + 2.5s guard suspension on old APKs).
Deliberate in-app back arrows use `guardedNavigateBack` (`navigate(-2)` over the sentinel). All
protocol pieces (markers, LIFO overlay registry, per-event self-pop consumption) live in
`components/mobile/backGuardProtocol.ts` (unit-tested); ~50 overlays gained `useBackDismiss`
(now with an `enabled` param — payment surfaces pass `useIsMutating()===0` so Back can't dismiss
a mid-post charge and invite a double-sale re-attempt). Two latent `useBackDismiss` bugs fixed en
route: (1) stacked sheets all closed on one Back (every instance heard every popstate — now only
the registry's topmost reacts); (2) StrictMode's unmount→remount raced the async unwind
`history.back()` and stranded the overlay entry in forward history (verified in Chrome) — the
unwind is now deferred one tick and ADOPTABLE by a same-tick remount. Verified by
`scripts/backguard-smoke.mjs` (playwright walk of the full state machine against the dev server
with the guard forced via `?backGuardDev=1` — 20/20). Web ships first (thin clients pick it up
immediately); the APK rebuild only upgrades the exit button from hint-fallback to instant
minimize.

## 2026-08-23 — FIELD BUG: ONLINE-tender checkout born broken — channelCode nested vs top-level

Owner (merchant `c1e01e6e`) created their first sales channel (`SHOPEE`) and tried to ring an
ONLINE-tender sale from the POS → 400 "channelCode is required when tenderType is ONLINE", twice.
Edge-log + prod-DB trace: the channel row is valid+active, but **prod has never recorded a single
ONLINE sale** (62 CASH, 1 QRIS) — the flow was born broken in `61ee6939` (2026-08-03, ADR 0036 B3).
Root cause: a straight CONTRACT MISMATCH — the server reads `channelCode` from the request's TOP
LEVEL (`CheckoutRequest`/`PayParkedRequest`/`PayBillRequest`; `PaymentRequest` has no such field),
while the console always nested it inside the `payment` block, where Jackson silently ignores it.
Both sides shipped in the same commit, each with its own tests (backend tests post top-level,
frontend tests exercise the picker in isolation) — no test ever crossed the seam, the same blind
spot as the Midtrans-webhook outage. **Fix (frontend-only, v0.1.30):** the three tender-carrying
bodies are now built by pure, unit-tested builders (`pos/lib/tenderRequestBodies.ts`) that mirror
`payment.channelCode` to the top level; contract tests pin the mirror for all three (ONLINE →
code, non-ONLINE/no-payment → null). Sending the top-level field for non-ONLINE tenders is safe
(`OrderWriter`/`BillWriter` null it for any non-ONLINE tender). Old cached shells are equally
broken either way (the flow never worked), so no server-side back-compat shim is needed.

## 2026-08-22 — Reversed-sale visibility: history badge, receipt banner, net day total

Owner bug report: a successful "Kembalikan penjualan" (ADR 0061 return) left NO trace — the
today's-sales list showed the sale as if nothing happened, and a reprinted receipt gave no clue which
transaction was reversed. Root cause: a reversal is a status flip on the `payment` aggregate
(`CAPTURED → VOIDED | REFUNDED | PARTIALLY_REFUNDED` + cumulative `refunded_minor`), but
`SaleRepository.findHistory` never joined `payment`, and the receipt rendered the status only as one
12px label/value row buried under the tender lines. **Fix (read-model surfacing only — no `sale`
schema change, no new events):** `findHistory` LEFT JOINs `payment` (V45 adds the missing
`idx_payment_sale_id`; 1:0..1 today by writer discipline — per-payment sale idempotency keys — with
the split-tender revisit documented in the javadoc and non-duplication pinned by the acceptance
test) surfacing additive `paymentStatus` + `refundedMinor` (COALESCEd to 0); `PaymentResponse` /
`PaymentReceiptView` now carry `refundedMinor` too. Console: red `Badge tone="loss"`
("Dibatalkan"/"Dikembalikan"/"Dikembalikan sebagian") on rows in both history sheets, full
reversals struck through; a solid reversal banner on the receipt (mirrors the ADR 0028 provisional
banner) on screen AND in the ESC/POS output, the partial variant interpolating the refunded amount.
The "Penjualan hari ini" header total is now NET via pure `netSaleAmountMinor` (`VOIDED → 0`, else
`amount − refunded`; safe: `ck_payment_refunded` + `Payment.refund()` cap refunded ≤ amount, and a
VOIDED payment is terminal with refunded 0), and a partially-refunded row shows its −delta so the
visible rows still foot to the header. Documented divergence (deliberate): the register Z-report
attributes refund DELTAS to the day they happened (V22) and does not subtract voids — the two agree
on every shape the POS UI can produce (live-day full returns only; no UI issues voids). Beranda P&L
untouched — finance's `SaleVoided`/`SaleRefunded` reversal listeners already net the GL. Carwash
deliberately out of scope (no void/refund flow ported).

## 2026-08-22 — INCIDENT: Midtrans webhooks (and self-order submits) 401 — chunked-refusing body filters

Midtrans emailed the merchant: every notification to `POST /api/v1/psp-webhooks/midtrans/{companyId}`
got HTTP 401 (12 retries over 3h for one expire notification — an unpaid Rp 12.000 QR, no money
moved). Tracing hop by hop (edge access log → gateway metrics → payment-service metrics → a
throwaway copy of the prod gateway image wired to the real payment-service with security DEBUG):
the gateway's security chain and `pspWebhookRoute` are CORRECT — the request dies inside
payment-service, before its servlet pipeline (invisible to http_server_requests; the filter runs at
HIGHEST_PRECEDENCE, ahead of the observation filter). Root cause: **`PspWebhookBodySizeFilter`
refused any `Transfer-Encoding: chunked` request with 411** ("Midtrans always sends
Content-Length") — true at the edge, false one hop later: **the gateway's proxy re-streams EVERY
forwarded body as chunked** (captured on the wire). The 411's `sendError` then ERROR-dispatched to
`/error`, which is not in `native.security.public-paths`, so the security chain morphed it into the
bodyless 401 Midtrans saw. Net: **the settlement webhook never worked in ANY deployed environment**
— QRIS still settled because the POS `/sync` polling fallback masked it. Same defect in
`SelfOrderBodySizeFilter` (O-2 hardening): **anonymous self-order ORDER SUBMISSION through the
gateway was equally dead** (browse GETs fine, POST → empty 401), verified against prod. **Fix:**
both filters now enforce the cap WHILE reading — bounded O(cap) buffering per request (the bound IS
the heap-DoS protection), replay the buffered body to the chain (`BufferedBodyRequest`); a genuine
oversize is written DIRECTLY as a 413 problem+json (never `sendError`, so it can't morph into a
401). Self-order filter ordering flipped (header-only token check first, so bad-token junk never
pays the buffering). Regression tests cover the chunked-passes / streamed-oversize-413 /
lying-Content-Length / direct-write shapes.
Diagnosis rule of thumb this bought: an anonymous route answering an EMPTY 401 despite permitAll =
suspect a `sendError`/error-dispatch morph or a pre-security filter, and check the service's
http_server_requests for the request's ABSENCE. Also flagged: the affected company's QRIS gateway
runs with `provider_environment=SANDBOX` active in prod — a real customer can never pay a sandbox
QR; the owner must flip to Production credentials for real sales.

## 2026-08-21 — FIELD BUG: app "redirects to the web" after restart → allowNavigation fix

Field report: on some devices, opening the app after a restart lands the user in Chrome (the web
console) instead of the app. Root cause: Capacitor keeps a top-frame navigation inside the WebView
only when its **host** equals `server.url`'s host or is listed in `server.allowNavigation`
(`Bridge.launchIntent` — HOST-only, ports ignored); anything else fires `ACTION_VIEW` → system
browser. Prod Keycloak lives on the Business origin (`env.js` → `https://app.native-app.my.id/auth`,
= `PUBLIC_URL`), and the interactive login is a top-frame `signinRedirect`. On a normal open the
stored offline token renews **silently** (XHR — never navigates), so nothing breaks; after a restart
where that renew fails (offline session idled out / OEM cleared WebView storage) the user taps
sign-in → cross-host redirect → Chrome, and after login Keycloak sends them back to the origin *in
Chrome* — they stay on the web. Affected: (1) the **Employee app in prod** — its origin
`emp.native-app.my.id` differs from the auth host **by design** (ADR 0049 P5 shared issuer) and its
config had **no allowNavigation at all**; UAT never reproduced it because the employee origin and
Keycloak share `a8.tailbf9662.ts.net` (only ports differ, and ports are ignored). (2) **Old Business
APKs** baked with the funnel origin `native-prod.tailbf9662.ts.net` — cross-host ever since
`PUBLIC_URL` migrated to Cloudflare; those installs need the current APK reinstalled (origin is baked
in), and the funnel origin was found **down** during diagnosis anyway. **Fix:** both apps'
`capacitor.config.ts` now bake the auth host into `server.allowNavigation`, wired through
`build-app.mjs` (`--auth-url` / `NATIVE_*_AUTH_ORIGIN`; employee prod default
`https://app.native-app.my.id`, till default = its own origin — a no-op guard today, but required for
the ADR 0051 bundled shell where the WebView origin is `https://localhost`). External links stay
un-whitelisted on purpose (they belong in the browser). The Employee prod APK/AAB must be rebuilt
with this fix **before** the Play upload (still gated on its own upload keystore).

## 2026-08-16 — INCIDENT: fleet-wide Debezium CDC outage on deploy + hardening

Investigating a "corrected closing but P&L still wrong" report surfaced a bigger problem: **every
Debezium outbox connector task was FAILED** (`Couldn't obtain encoding for database … connect timed
out`). Root cause: a prod deploy earlier today **recreated the `native-prod-postgres` container** (new
IP), but the `native-prod-connect` container was NOT recreated/restarted, so Debezium held stale JDBC
connections → all tasks FAILED → **the outbox stopped flowing to Kafka fleet-wide** (sales, closings,
corrections stopped reaching finance). It went undetected because both `prod-deploy.sh` `wait_healthy`
and `ops-watch` only assert the connect **container** is "healthy" (the worker), never that the
connector **tasks** are RUNNING — so the deploy reported SUCCESS while CDC was dead. **Remediation
(live):** `docker restart native-prod-connect` → re-established connectivity → then restarted the
FAILED tasks via `POST /connectors/<c>/restart?includeTasks=true` → all 8 RUNNING. **Long-term fixes:**
(1) `prod-deploy.sh` now runs `recover_cdc` after the health gate — verifies every connector task is
RUNNING and, if not, restarts connect + the failed tasks (best-effort; never fails the app-tier
deploy, logs loudly). (2) `ops-watch.yml` gains a "Debezium CDC health" step that alerts when any
connector task is FAILED (the detection gap that hid this). **Follow-up:** the deploy ideally should
avoid recreating postgres at all, or restart connect whenever it does — the recover step covers it
either way. Also surfaced: register sessions closed **before** ADR 0064 shipped (v0.1.21) have a NULL
`close_event_id` so the correct-close feature refuses them (`RegisterCloseCorrectionNotAllowedException`)
— the affected Bara Kebab session was unblocked by backfilling `close_event_id` from its outbox row id
(which matches the finance journal's `source_event_id`); a broader backfill migration for pre-0064
sessions is a deferred follow-up.

## 2026-08-16 — Dashboard P&L is GL-derived — one "Laba bersih" (ADR 0065)

Owner report (Bara Kebab, rfkih23@gmail.com): the beranda net profit ≠ the Laba-Rugi (income
statement) net profit for the same month. Root cause: `GET /api/v1/pnl` (dashboard) read the
`consolidated_pnl` **accumulator**, fed only by hand-picked POS writers (SaleRecorded net revenue,
reversals, ExpenseRecorded, expense claims, labor, stocktake shrinkage), while the income statement is
GL-derived. Everything that hits the GL but no accumulator writer — register-close cash variance
(5700/4300), QRIS/platform fees, depreciation, disposal, service charge, AR/AP — showed on the
statement but not the dashboard. Classic "accumulator fed by selected writers" anti-pattern (each new
writer must remember to feed it). **Fix (Option A, consistency-first):** `PnlReader.pnlForPeriod` now
delegates to the same `IncomeStatementReader` the statement uses, returning a new immutable
`pnl.domain.PnlFigures`; beranda == Laba-Rugi **by construction**. `PnlResponse` HTTP contract
byte-identical (incl. the presentation-currency lens) → **zero frontend change**. `consolidated_pnl`
+ its writers stay, now serving only the two write-path currency guards (read via the new
`PnlReader.accumulatedPnlForPeriod`); retiring the accumulator is deferred. Behaviour deltas (all
improvements): multi-currency period 500→**422**; a GL-nonempty zero-P&L period 204→**200 zeros**.
New `PnlMatchesIncomeStatementTest` seeds a sale + a register-close cash short and proves
`pnl.net == income.net` while the old accumulator missed the short; the 9 writer-acceptance suites
repoint to `accumulatedPnlForPeriod`. Full finance-service `test` (unit + ArchUnit + Testcontainers)
green. **Known follow-ups (pre-existing GL bugs the unified number inherits, NOT introduced here):**
labor supersession posts no GL contra (`TODO(GL-labor-reversal)` → GL overstates labor on payroll
re-runs); labor `Dr 6000 / Cr 6900` (both EXPENSE) nets to zero until `PayrollLiabilitiesPosted`
clears 6900 (accrual-vs-liability timing). Both already affected the income statement; deferred.

## 2026-08-16 — Auto stock-tracking (1:1 auto-link) + per-day usage aggregate (ADR 0050 follow-up)

Owner ask: "aku mau setiap menu sudah otomatis mengurangi stock … jadi ketika stock opname sudah di
prefil sama system jadi cuma verifikasi dan adjust" + "di riwayat stock opname harian tampilkan juga
stock yang terpakai di hari itu". Per-sale ingredient depletion (ADR 0050) and the opname prefill
already existed; the gap was menu items with **no recipe** — those never moved any stock, so their
opname figures never pre-filled. **Part A — "Lacak stok otomatis" (1:1 auto-link):** for a recipe-less
menu item, mint a same-named ingredient (unit `pcs`, stock 0, uncosted) + a 1-per-portion base recipe
line, so the EXISTING depletion starts moving it. Bulk sweep ("Lacak stok semua menu"), per-item
1-klik (RecipeDrawer), and a create-menu checkbox (default ON, best-effort — a link failure never
fails the create). Idempotent; existing recipes untouched; an ACTIVE same-named `pcs` ingredient is
REUSED case-insensitively (the V31 unique is on `lower(name)`); a same-named **non-`pcs`** ingredient
(e.g. raw "Ayam" in grams) is **BLOCKED**, never silently depleted (bulk tallies `blockedCount`;
1-klik → 422 `recipe-auto-link-blocked`). **Part B — per-day usage aggregate:** new table
`ingredient_usage_day` (V42, `Auditable` + FORCE RLS), UPSERTed in the SAME transaction as each
depletion (`ON CONFLICT (ingredient_id, usage_date) DO UPDATE qty_used = existing + EXCLUDED`),
surfaced as "terpakai hari ini" in the opname sheet and "terpakai hari itu" in a new opname-history
sheet. Usage accrues from deploy forward (prior days unreconstructable). Business-date zone is a fixed
`Asia/Jakarta` v1 (a per-outlet zone is the additive follow-up); the console read key pins the same
zone so read/write agree. Sale-path safety: the usage UPSERT runs per-ingredient right after each
deplete, holding the same ascending-UUID lock order (no batch — would risk the cross-sale deadlock);
concurrency test proves additive accumulation, atomicity test proves a rolled-back sale leaves no
usage row. Dual code-reviewed (PASS; C1 case-sensitivity, W1 create-fail, W2 raw-material-collision
all fixed). restaurant-service tests + console tsc/lint/vitest green.

## 2026-08-14 — Persistent login: staff ~30 days, owner capped 1 day

Native/Android sessions were logging out after the phone sat inactive, even though the offline token
+ localStorage persistence + `offline_access` were already in place (Keycloak's default offline idle is
30 days). Root cause was **client-side**, two bugs in `auth.tsx`: (1) `recoverOrLogout` called
`manager.removeUser()` — which **deletes the offline token** — on ANY `signinSilent()` failure, so one
transient blip (no signal on reopen, IdP momentarily down) permanently ended a session with weeks left;
(2) nothing refreshed on resume, so a backgrounded WebView came back with a dead 5-min access token and
401'd before renewing. Fixes: only a TERMINAL error (`invalid_grant`/`invalid_token`/`login_required`/
`interaction_required`) logs out — a transient failure KEEPS the session and retries; a
`visibilitychange`/`focus` listener proactively renews via the offline token on foreground (no
`@capacitor/app` dep). **Owner 1-day cap** (owner request "owner auth only 1 day max"): an `owner`-role
login re-authenticates daily — `auth_time` (or a stored fresh-login stamp) > 24h → `removeUser()` +
`signinRedirect({prompt:'login'})`; managers, cashiers, staff, and kiosk `device` logins ride the
30-day session. Decision logic lives in a new pure `authSession.ts` (14 unit tests); no Keycloak
timeout change (30d == the default). Client-enforced (the shared `native-console` KC client can't do
per-role limits — a dedicated client is the follow-up if a hard server cap is needed). Dual-reviewed
(code + security PASS); console tsc + lint + vitest (498) green. Ships to devices via the console-web /
employee-web deploy (the native apps are thin clients).

## 2026-08-14 — POS daily transaction summary / Z-report at closing (ADR 0060)

Owner ("Bara Kebab") asked for a menu to **print today's transaction summary when closing the POS**.
Built as a printable **Z-report**: transaction count, revenue breakdown (Bruto − Diskon − Loyalti +
Servis + Pajak PB1 = TOTAL), per-tender net sales, refunds, net, and the cash-drawer reconciliation.
**Key decision (ADR 0060):** the price breakdown was already computed at ring time and put on the
`SaleRecorded` event but **thrown away on the row** — so instead of re-deriving it per order at report
time (rounding drift, promo/loyalty reconstruction, per-sale rule-version loss), we **snapshot it onto
`sale`** and `SUM` exactly. restaurant-service **V39** adds 6 nullable columns (`subtotal/discount/
service_charge/tax/loyalty_redeemed_minor`, `uses_illustrative_rules`) + covering index
`idx_sale_business_window`; `SaleWriter.stampBreakdown` mirrors the event's promo-only decomposition.
New **session-scoped** endpoint `GET /api/v1/register-sessions/{id}/summary` (works OPEN=live X-report /
CLOSED=final Z-report; window `[openedAt, closedAt ?? now)`) reuses the existing per-tender/cash sums.
Reporting-only (finance GL stays authoritative); tax is **PB1 "Pajak Restoran" (not PPN)**, badged
**"estimasi"** when the rate is illustrative, footer "bukan faktur pajak" (domain-specialist review).
Snapshot is **forward-only** (legacy rows fall back to `subtotal == amount`). Console: `DailySummary`
overlay prints through the existing `ThermalReceipt`/`usePrinter` pipeline (device + `window.print`
fallback + retry), reached from **both** the closing sheet ("Cetak ringkasan" on the close form +
verdict) and a till-menu item ("Ringkasan hari ini"); i18n en+id. **Code + security review PASS.**
Review W1/W2: the settlement block was reworked from per-tender NET to per-tender **GROSS + an explicit
gift-card settlement line** so `Σ tenders == total` foots even with refunds or a gift-card split (the
whole point of a drawer-reconciliation document). Backend `:check` green (checkstyle + ArchUnit + all
tests incl. the gift-card-footing case); console tsc+lint+vitest green (193). Branch
`feat/business-employee-apps`, NOT committed/deployed. `servicepos`/carwash entry points = fast-follow.

## 2026-08-14 — Dynamic QRIS gateway for BILLS/tabs, full-bill (ADR 0045 amendment, closes ADR 0036 residual)

Owner ("Bara Kebab") kept getting the manual "Demo · penyedia tertunda" panel when paying a **bill
("tagihan")** with QRIS — even after the per-env fix (v0.1.14) and confirming the effective endpoint
returns `GATEWAY, connected:true`. Root cause: dynamic QRIS was never built for bills — `BillWriter.
payBill` records a `Sale` directly (one-step, **no `Payment` row**), so a gateway charge had nothing
to attach to (the ADR 0036 residual). Orders work only because checkout mints a PENDING
`Payment(paymentId)` the charge keys on. This extends the two-step gateway flow to bills, **full-bill
only** (split checks = follow-up), with **zero payment-service change** (a bill's gateway payment is
a restaurant `Payment` row keyed by `paymentId`; the charge/`PaymentChargeSucceeded`/consumer plumbing
is reused, vertical stays `restaurant`). Migration **V38** (restaurant): `payment.order_id` nullable
+ `bill_id`/`discount_minor`/`check_idempotency_key` + `CHECK` exactly-one, and `bill_line.
pending_payment_id` (line reservation). `POST /bills/{id}/pay-pending` reserves the unpaid lines +
mints the PENDING bill payment; `BillPaymentCaptureWriter.capture` records the check Sale + closes the
bill on settlement (reusing `recordCheck` extracted from `payBill` — cash path byte-for-byte
unchanged); `PaymentChargeSucceededWriter` dispatches order-vs-bill by `isForBill()`; `POST
/payments/{id}/abandon` releases the reservation on cancel; `payBill` now excludes reserved lines
(real double-settle fix caught in review). Capture recompute-and-asserts at the mint instant and
parks on drift; idempotent by already-CAPTURED + the sale unique key. Console: `BillPaymentModal`
gained a gateway branch mirroring the order `RestaurantDigitalAttempt`. Built by two parallel agents,
dual-reviewed; restaurant-service `:check` green (693 tests, ArchUnit 14/14), console tsc+vitest+lint
green. Branch `feat/bill-gateway-qris`, NOT yet deployed (deploy gated on the prod Tailscale-Funnel
edge being committed to the release branch first — else a deploy would delete the tailscale container).

## 2026-08-13 — Per-environment QRIS gateway credentials + verify + honest degrade (ADR 0045 amendment)

First real go-live (company "Bara Kebab", GATEWAY + PRODUCTION) exposed a silent human-error trap in
the Midtrans settings: ONE credential slot + a write-only key (blank on save keeps the stored value),
so flipping `environment` without re-typing the key left the OTHER environment's key in place →
Midtrans auth failed at the till, surfaced only as the confusing "Demo · pending provider" MANUAL
fallback (which also masked the day's disk-full outage). Owner chose the full fix: **two credential
slots** (Sandbox + Production, migration **V6** — nullable ADD + RLS-wrapped backfill; legacy columns
kept dead for rollback safety), `provider_environment` demoted to the **active** selector, and a
domain guard (`activateEnvironment` → 422) so an environment can never be activated against another
environment's (or no) key. Switching active env now needs no key re-entry. Domain
`getServerKey()/hasServerKey()/getServerKeyLast4()` resolve the active slot, leaving ChargeWriter /
WebhookService untouched. Added an owner-only **"Test connection"** (`POST …/gateway/verify` →
`MidtransClient.verify`, a side-effect-free status probe: 404→VALID, 401/403→INVALID, else
UNREACHABLE — catches a wrong/mis-environment key at the settings page, not the till). Console: a
two-section gateway card (each env: keys + Connected·last4 + Test koneksi) + an "Environment aktif"
switch with a Save guard (`canActivateEnvironment`) + a PRODUCTION "real money" warning; and an
honest **"gateway unavailable — confirm manually"** till badge (PaymentModal / ServicePaymentModal /
BillPaymentModal) replacing the misleading "Demo" copy when a configured GATEWAY degrades. All gates
green (payment-service suite incl. Testcontainers + V6; console tsc + 31 vitest + lint). Branch
`feat/qris-per-env-credentials`, NOT pushed. ADR 0045 amended.

## 2026-08-13 — English-first localization; Indonesian gated to Indonesia (ADR 0059)

Taking the product global. Native was Indonesia-first: the console auto-picked Bahasa for any
`navigator.language=id` browser and showed the EN/ID switcher to everyone, everywhere. New policy —
**English is the global default; Indonesian is offered only in Indonesia** (and where offered, users
may still choose English). "Indonesia" resolves by context: in-app it's the active company's country
via the exact `baseCurrency === 'IDR'` proxy (ADR 0025 — no backend read change); on public pages
(landing + signup pre-country) it's the browser IANA time zone (`Asia/Jakarta·Pontianak·Makassar·
Jayapura`), which tracks physical location, not phone locale.

One predicate, `offeredLangs(indonesia)` (`frontend/console/src/lib/geo.ts`), feeds every gate: the
`LanguageSwitcher` (renders nothing when English is the only option), the signup/add-company chooser
(shown only for an ID country; other countries locked to English, mirroring the locked-currency
note), and `SessionProvider`, which on company-resolve **adopts the company's `defaultLanguage`**
(finally wired to the UI — it was stored but never applied) and **clamps** anything not offered here
to English. A manual toggle persists and wins; auto-selection never writes storage. Because the
employee app reuses `SessionProvider` + `@/i18n` via the `@` alias, it follows for free.

Server invariant lives in `CountryDefaults` beside the currency derivation — English for any country,
Indonesian only for `ID`. `SignupService` applies it at the same **derive-before-create** point as
the country check (a bad language `400`s before any Keycloak user exists — no compensation spent);
the `Company` aggregate enforces it on every create path. No DB/schema/event change (`default_language`
stays `en|id`, now country-gated). Behavior change: a non-ID company can no longer be created in
Indonesian (`SignupAcceptanceTest.countryUsDerivesUsdBooks` updated to English + a new
reject-with-no-residue case; `CountryDefaultsTest` + `geo.test.ts` cover the policy). Not pushed.

## 2026-08-12 — Close the ADR 0040 org-service error-inbox gap (500s → error_log fleet-complete)

The last open item on the traceable-error-reference track (ADR 0040). The shared `ApiExceptionHandler`
persists every unexpected HTTP 500 to `error_log` so a user-quoted `reference` resolves to a row — but
`org-service` was the one DB-backed service where it didn't: it's a **pure producer** (emits
`CompanyCreated` via outbox + Debezium CDC, runs no Kafka consumer), so it has no `kafka-clients` on its
classpath, and the shared `ErrorInboxAutoConfiguration` gated the whole thing on
`@ConditionalOnClass({JdbcTemplate, ConsumerRecord})` — the `ConsumerRecord` half was never satisfied, so
its `ErrorInboxWriter` bean never activated. org-service 500s returned a reference that resolved only in
the server log, not `error_log`.

Closed with two changes: (1) `org-service` `V13__error_log.sql` — the same ops-table shape as the other
services (no RLS, no Auditable; header records that org-service's `error_log` is fed **only** by the
HTTP-500 path, no `ConsumeErrorRecorder` here). (2) Split `ErrorInboxAutoConfiguration` into two
activation tiers — the **write path** (`ErrorInboxWriter` + its redactor / clock / REQUIRES_NEW template)
now activates on `@ConditionalOnClass(JdbcTemplate)` alone, so any DB-backed service gets it; the
**consumer/alert path** (`AlertWebhookClient` + `ConsumeErrorRecorder`) stays gated per-bean on
`ConsumerRecord`, so it appears only on event-consuming services with a DLT recoverer to wrap. **No new
dependency** — org-service already had the error-inbox classes transitively via `libs:security`
(`implementation(project(":libs:error-inbox"))`); only the condition changed. org-service keeps its own
`Clock` bean (`TimeConfig`), which the auto-config's `@ConditionalOnMissingBean errorInboxClock` yields to,
and has no by-type `TransactionTemplate` injection, so the added `errorInboxTransactionTemplate` is
unambiguous.

Tests: new `ErrorInboxAutoConfigurationConditionsTest` (ApplicationContextRunner + `FilteredClassLoader`)
proves the write path comes up JDBC-only with Kafka filtered out while the consumer/alert beans stay
absent, and that both tiers activate when Kafka is present. `org-service` `CreateCompanyAcceptanceTest`
(full context + real Postgres via Testcontainers) confirms V13 migrates cleanly and the context boots with
the newly-wired `ErrorInboxWriter`. Not pushed.

## 2026-08-09 — Preset role-based access: office vs floor roles, gateway-enforced (ADR 0052)

The owner wants everyone to log in as themselves and their **role** to decide the surface: office roles
(owner/manager/HR/accountant) see only their back-office slice, floor roles (cashier/chef/waitress) only
POS. Adds four roles — `hr`, `accountant`, `chef`, `waitress` — and splits the gateway's single broad
`owner/manager` back-office gate into named **capability arrays** (POS/OPS/REPORTS/FINANCE/HR/PAYROLL/
OWNER/ME); the gateway route→role table is the security boundary (menu-hiding + ADR-0013 page grants are
UI-only). Multi-role = the union of bundles; `manager` is deliberately narrowed to operations (OPS+REPORTS
+HR — **no** detailed finance, **no** payroll). `PATCH /users/{id}` became a multi-role set-replace.
Console mirrors it (`lib/rolePreset.ts` — `ROLE_HOME` + `can*` — byte-for-byte against `RoutingConfig`),
re-gates every nav group + route, and adds a **standalone HR People page** (over `useOrgUnits`, never the
OPS `/users`) so an `hr`-alone login has a working surface. `/groups` (mixed OPS-membership + FINANCE-
figures) is OPS-routed with the consolidated P&L gated to owner/accountant *inside* the page.

**Two CRITICALs were caught by the mandatory security review before deploy:** (1) the multi-role assign
had no anti-escalation guard — a `manager` could `PATCH` itself to `{manager,accountant,hr}` (self-granting
the finance/payroll surfaces this model withholds) or to `owner` (takeover); fixed with
`authorizeRoleAdministration` (owner administers office/privileged logins; a manager administers floor
logins only) on invite/patch/deactivate. (2) To give the HR area unit names we widened `GET /org-units` to
office roles, but a `/**` wildcard swept in `GET /org-units/{outletId}/device-credential` — the DECRYPTED
per-outlet till password — letting `hr`/`accountant` harvest POS credentials; fixed by narrowing to the
EXACT `/api/v1/org-units` list (sub-resources stay owner/manager). Both re-reviewed PASS. Commits
`289f9328` (P1) · `9d7a7ed9` (esc guard) · `be84d7da`→`d025c148` (org-units read-widen + narrow) ·
`9210533c` (P2 console) · `5cb68c74`/ADR 0052. Frontend code-reviewed PASS.

**Deployed to UAT 2026-08-09** (gateway + org-service + console rebuilt; the 4 realm roles applied to the
live realm via kcadm — realm-JSON does not auto-apply) and **verified end-to-end** with real browser OIDC
logins + per-login gateway token replay: hr→`/people` (HR+PAYROLL+org-read, all else 403); accountant→
`/statements` (REPORTS+FINANCE); chef→`/kitchen`, waitress→`/pos` (POS only); a multi-role hr+accountant
login = the union, landing on the higher-priority home. **Note:** office nav is `role ∧ grant ∧ tier`, so
a FREE-tier company hides the HR/finance nav even for a matching role (the role works; only the links are
tier-suppressed) — assign office roles on a paid tier. Per-company roles remain deferred (ADR 0021).
Branch `feat/business-employee-apps`, not pushed.

## 2026-08-09 — Operator-PIN follow-ups: manager-present first-PIN enrollment + employee self-service (ADR 0049 addendum 2)

Three refinements after the owner tried the operator-PIN feature. (1) A require-PIN outlet no longer
strands a PIN-less employee: the roster gained `hasPin` and now lists all assigned+linked employees, and
their FIRST PIN is set with a **manager present** — the till gates the Set-PIN step on an elevated
owner/manager (`auth.elevatedRoles`) and writes via the EXISTING owner/manager `PUT /employees/{id}/
operator-pin`, not a cashier-surface endpoint. (An earlier design added a `POST /operators/pin` enroll on
the POS surface; a code review flagged that a lone cashier could set a PIN-less colleague's first PIN and
ring as them — contradicting the owner/manager-only PIN-write invariant + the spoof-proof attribution
guarantee — so it was removed. Requiring an elevated manager keeps enrollment at the till AND closes the
impersonation surface; the gate decision was extracted to a pure, unit-tested `operatorSheetStep`.) (2)
Self-service: `PUT /api/v1/me/operator-pin` (resolved from the JWT sub; also forgot-PIN) + an Employee-app
Account screen with **Change PIN** and **Change password** — the latter delegated to Keycloak's own secure
`kc_action=UPDATE_PASSWORD` action (no backend, KC keeps sole ownership of the password). (3) The till's
policy read is now fresh (staleTime:0 + focus/interval refetch) so a kiosk picks up a manager's toggle.
PIN stays Argon2id inside employee-service; no event/contract change. Commits `5276b9c8` (backend),
`309cc4b7` (till), `33452597` (Employee-app account). Each phase security + code reviewed. Not pushed.

## 2026-08-09 — Per-outlet operator-PIN policy + terminal management + session-scoped operator (ADR 0049 addendum)

Three owner asks on the outlet terminal, shipped in three reviewed phases (security + code PASS each).
(1) **A manager can see the outlet's device login** (username + on-demand, audited password reveal) and
**set/reset employee PINs** from the console — PINs stay one-way Argon2id (set/reset only, never
viewable); only the outlet password is revealable. (2) **Per-outlet "require a PIN to operate" toggle**:
off ⇒ the cashier just taps their name (trust-based, no PIN) and rings; on ⇒ the pick→PIN flow is
unchanged. (3) **No operator session** — it now clears every time the app is closed. Kept the policy
**inside employee-service** (`outlet_operator_policy` V17) rather than an `org_unit` column + an
`OrgUnitChanged` field, so there is NO event-contract change and the verticals are untouched: a no-PIN
pick still mints an `operatorUserId`-bearing token, so the P4 device-guard and commission attribution
both still hold — the only thing the toggle changes is whether `OperatorSessionWriter#verifyAndMint`
checks a PIN. Absent policy row ⇒ require_pin=true (fail-safe). The operator session moved from
localStorage to sessionStorage (clears on cold app launch; the outlet/device token stays in localStorage,
kiosk-persistent). Trust-model trade-off is owner-accepted + bounded to attribution (the token's role is
never used for authz). Commits `3c4bba60` (backend), `ece1c6d2` (terminal UI), `14f8f873` (till + session).
Review fixes: `@Size(max=64)` on the PIN input (still admits null/blank → uniform 401), and `.reset()` the
reveal mutation so the plaintext can't linger in the MutationCache. Not pushed.

## 2026-08-09 — Two-app split + outlet-terminal auth (ADR 0049): P0–P4 shipped, P5 Employee app built

The owner split Native into **two apps**: a **Business/terminal app** (the console — logged in with a
per-outlet credential, kiosk-persistent, cashiers PIN-in to ring for commission and see name+role
only, owner/manager reach the back office via a personal login layered on top) and a dedicated
**Employee app** (personal login → full `/me` self-service). The load-bearing problem: today the
seller is the JWT `sub` (implicit → `sale.created_by` + `MetricPublished.subject`), so an outlet-login
terminal would credit every sale to the *device*. Fix = a per-employee **PIN → HMAC operator token,
verified OFFLINE inside each vertical** (hard-rule 2 forbids a sale-time restaurant→employee sync
call — mirrors the self-order token), stamped as the seller. The elegant core: **`MetricPublished.avsc`
is UNCHANGED** — only *which id the producer writes* to the existing `subject_id` changes, so the
whole commission/payroll pipeline is untouched.

Shipped inert-first so commission never silently breaks: **P0** seller field + `SaleRecorded` additive
`sold_by_user_id` (V33); **P1** employee-service `operator_pin` (Argon2id) + `POST /operators/session`
(HMAC mint); **P2** verticals verify the token offline + restaurant substitutes the operator as the
metric subject (carwash/barbershop stay washer/barber-attributed — substituting there would
misattribute); **P3** org-service per-outlet device (kiosk) Keycloak credential + the terminal UX
(PIN sign-in, name/role chip, personal owner/manager elevation via a 2nd dormant `UserManager`). P0–P3
are deployed + e2e-verified on UAT. **P4** flipped the enforce guard (an `X-Actor-Type=device` sale
must carry an operator, else 409) and threaded the ring-time operator through the **async** QRIS/card
capture — stamped on `payment.sold_by_user_id` (V35) at the PENDING-mint choke point, read back at
`PaymentCaptureWriter.capture` (a Kafka consumer thread; a new `ActorTypeProvider` defaults to `"user"`
off-request so the async path never trips the device guard). A **security-review HIGH** was fixed before
sign-off: the digital-tender stamp now applies the same tenant/outlet assertion as the cash path
(`OperatorMismatchException.requireMatch`, shared by `PaymentWriter`+`SaleWriter`) — the HMAC key is
fleet-wide, so without it a validly-signed but foreign-outlet token could have become the stored seller
and misattributed commission cross-tenant; commission credit follows the ring-time operator, never a
shift-change capturer. **P5** the Employee app is built + deploy-ready: a new sibling web package
`frontend/employee/` reusing the console's `/me` screens UNCHANGED via a `@`→`../console/src` alias
(personal login, elevation dormant, no POS/Shell), a working deploy image, and a `native-employee`
Capacitor shell (appId `id.co.nativeapp.employee`). Its live UAT origin (a 2nd Tailscale funnel origin
on :10000 — runbook in `frontend/employee/README.md`) is the one remaining step, deferred while a
concurrent session was actively deploying to the shared UAT stack. Branch `feat/business-employee-apps`,
not pushed.

## 2026-08-09 — Recipes/BOM costing phase A: per-sale ingredient depletion + HPP (ADR 0050)

The #2 competitive gap closed at its first stage (recipe → depletion → HPP; Pawoon/Olsera/majoo/
ESB all have it — none lands it in a real ledger, which is where phases B/C go). New restaurant
`recipe/` feature (V34 `recipe_line`): base lines + per-modifier-option signed deltas (keyed on the
option ids sale lines already snapshot), integer qty in the ingredient's own unit. Full-replace
`PUT /api/v1/menu/{itemId}/recipe` (whole-old-or-whole-new under concurrency) + reporting-only HPP
(`hpp-summary`, Σ base qty × ingredient cost, currency-match-only with completeness flags — never
guessed). `IngredientDepletionWriter` runs BESIDE `StockDeductionWriter` at all four sale sites
(checkout / payParked / per-CHECK bill payment behind the derived-key replay short-circuit /
digital capture): floors at 0 in ascending-UUID lock order and NEVER blocks a sale — the 86 gate
stays menu-item stock (ADR 0046 decision 2). Same-tx consistency: modifier deletes cascade recipe
deltas (`ModifierOptionCascade` hook), ingredient deactivation vetoed 409 while referenced
(`IngredientDeactivationGuard` hook) — both dependency-inverted, no feature cycles. Finance
untouched this phase; ADR 0050 pins the B/C contracts (`IngredientsReceived` → Dr 1100/Cr 2050
GRNI; `SaleCogsRecorded` → Dr 5100/Cr 1100) and the load-bearing **B-before-C** invariant
(perpetual COGS before purchase capitalization would drive 1100 negative + double-count food
cost). Console: RecipeDrawer editor + HPP/margin chips on /menu, opname copy now reads variance
as waste for recipe-covered menus.

## 2026-08-08 — MinIO object storage for binary media (ADR 0048)

All binary media moved out of Postgres into a MinIO object store — **infrastructure, not a
media-service** (rule 2 intact; talking to the store = talking to your own DB). One bucket
(`native-media`), key `{service}/{companyId}/{domain}/{sha256}.{ext}`: the per-service prefix is
the storage twin of database-per-service (prefix-scoped credentials from `docker/minio/init.sh`),
`companyId` is tenant isolation, the content hash makes objects immutable/cacheable. New
`libs/media-storage` (generic S3 client — AWS SDK v2, vendor-neutral exit — plus the fleet's now
ONE magic-byte image validator, replacing the identical employee/payment copies).

Three surfaces converted, each dual-read (legacy rows keep serving until converted, all conversion
runs inside tenant-bound transactions — never a Flyway backfill, the V6/V7 FORCE-RLS lesson):
**menu images** (restaurant V32 `image_key`; convert-on-write intercepts the console's base64 data
URLs; responses now carry anonymous public `/api/media/restaurant/**` URLs served by a GET-only
gateway proxy with immutable cache headers — payloads shrink from megabytes of inline base64;
owner-triggered idempotent backfill `POST /api/v1/menu/images/migrate`; console SW gained a
CacheFirst media route so the offline till keeps product photos), **expense receipts** (employee
V15 `object_key`, bytea nullable + payload-home CHECK; read-through migration on first serve;
receipt GETs gained sha256 ETag + private cache), and **static QRIS** (payment V5, same shape;
availability flag recognises either payload home). Replaced objects delete best-effort strictly
afterCommit. Ops: bucket versioning on; `mc mirror` backup is REQUIRED before prod trusts receipts
(RUNBOOK "Object store"); community MinIO is console-less — all admin via `mc`. Also defused a
date time-bomb the build tripped over: payment's Midtrans stub hardcoded expiry_time 2026-08-07,
so every stubbed charge was born expired from 08-08 — now dynamic.

## 2026-08-08 — Three-tier pricing (ADR 0047): Gratis/Basic/Premium + usage add-ons

The ADR 0044 ladder grows its designed third rung: `FREE < BASIC < FULL` (FULL *displayed*
"Premium" — the stored string is kept for grandfathered rows and the fail-open mapping). Feature
split is POS-vs-books: Basic adds the remaining operational surface (promotions, channels,
customer display, org structure); Premium adds everything financial (statements, accounting, HR).
New companies now start FREE on every create path (delivers ADR 0044 P2); existing rows
grandfather FULL. Prices live in ONE adjustable sheet (`frontend/console/src/lib/pricing.ts`,
integer IDR minor units): Basic Rp 149.000/mo, Premium Rp 299.000/mo, +Rp 49.000/mo per outlet
after 2, +Rp 50.000/mo per *started* 20-employee pack after 10 — with a pure, unit-tested
`computeMonthlyPrice`. Display + soft-enforce phase (owner decision): `/settings/features`
became three derived plan cards with an owner-only tier picker and a LIVE monthly quote from
actual outlet + distinct-active-employee counts, plus amber over-limit callouts — nothing
hard-blocks and nothing is charged in-app; collection stays manual until real billing (the
entitlement-service `billing_line` engine + `PlanTierChanged` gate stay future). The public
landing gained a `#harga` section rendered from the same sheet. i18n en+id throughout.
Ops note: while the stack is UAT-only, all QRIS payment settings are forced to MANUAL mode
(data-level, `updated_by='uat-disable-payments'`) so no static QR or gateway charge can occur.

## 2026-08-08 — Ingredient inventory phase 1 (ADR 0046): stock opname re-aimed at bahan

Owner feedback: "stock opname" must count INGREDIENTS (roti, patty, saus), not finished menu
items — the ADR 0038 phase-3 stocktake had the wrong subject for made-to-order food. Phase 1
(counts only, owner-chosen): new restaurant `inventory` feature — `ingredient` catalog per
outlet (integer qty in the ingredient's own unit; g/ml/pcs/pack picker because the ArchUnit
decimal-field ban makes fractional quantities impossible; nullable cost pair
unit_cost_minor+cost_currency), receive/set endpoints, and `/api/v1/ingredient-stocktakes`
cloning the stocktake idioms (Idempotency-Key replay, optimistic-lock retryable 409,
adjust-to-count). KEY CONTRACT CALL: the ingredient opname REUSES `StocktakeCompleted`
verbatim (the schema is subject-agnostic — one signed net shrinkage scalar), so
finance-service needed ZERO changes; a zero-costed-lines count emits no event (currency
required, nothing to post). Menu-item stock + its endpoints survive untouched as the 86
gate; the legacy `/api/v1/stocktakes` keeps serving history. Console: /ingredients
management screen (menu-grant gating), StocktakeSheet subject switch (units shown,
no-valued-lines summary), empty state links to /ingredients. Recipes/BOM + per-sale
depletion remain the deferred phase 2 — variance on costed bahan meanwhile includes normal
kitchen usage, which the entry hint says out loud.

## 2026-08-07 — Register cadence rework (ADR 0038 amendment): multi-session/day + POS open gate

UAT drill findings drove three same-day register changes. (1) The "expected 0" mystery = sales
rung OUTSIDE the session window (open+close in one motion at day end) — not a math bug. (2) The
open-drawer float now PREFILLS from the last close's counted cash (console-only, history endpoint,
derived-value pattern). (3) Owner-specified cadence: restaurant V30 drops the ADR-0038 day-final
unique — several sessions per outlet per day are legal again (one OPEN at a time unchanged;
cross-midnight legal; finance already safe per-session), and the till now auto-prompts the open
sheet when no session is open and gates the PAY action on an open session while online (offline
exempt — ADR 0028). Fail-open on session-read errors: a flaky read never blocks a sale.

## 2026-08-07 — QRIS scope amendment: DIVISION-level QR (ADR 0045 amendment) + outlet-first UI

Two same-day owner-driven iterations after the UAT deploy. (1) **Outlet-first settings UI**:
/settings/payments leads with the outlet list (accordion editor per outlet; Inherit = override
DELETE with a destructive confirm since the row drop removes the outlet's image); company default
+ credentials below as the fallback. (2) **Division scope**: `payment_settings.outlet_id` →
`org_unit_id` (payment V4), resolution now **outlet → division → company** per facet (credentials
stay company-only), unit endpoints at `/api/v1/payment-settings/units/{unitId}`, effective/image
reads + charge creation take a client-supplied advisory `divisionId`, org-service `OutletResponse`
gains the additive `divisionId` so the POS till knows its parent BU, and the settings page groups
outlets under their division with a division-level editor. UAT deploy notes: the service-template
clone needed the fleet's `!dev` JWT resource-server yml document (jwkSetUri-cannot-be-empty on
prod-profile boot), and `payment_service` role/DB had to be created manually in the existing UAT
postgres volume.

## 2026-08-07 — QRIS payment modes SHIPPED end-to-end (ADR 0045): 12 commits, all gates green

The whole program landed the same day it was planned. Per-company **MANUAL / STATIC / GATEWAY**
QRIS with per-outlet overrides: the owner's `/settings/payments` page (mode cards, static-image
upload with magic-byte verification, write-only AES-256-GCM Midtrans credentials showing only
last4), the till's STATIC panel (merchant's own QR + Tandai lunas) and GATEWAY panel (per-sale
dynamic QR from the **merchant's own Midtrans account**, countdown, auto-receipt on capture,
manual override always available, customer-display mirror). The money loop: console orchestrates
vertical-PENDING → payment-service charge (two-tx create, one-live-charge-per-payment,
replay-by-key) → Midtrans → **signed anonymous webhook** (`/api/v1/psp-webhooks/midtrans/
{companyId}`, provisional-bind → RLS-scoped loads → constant-time sha512, uniform 401, park-don't-
drop for unknown-order/amount-mismatch/late-settlement) or the `/sync` fallback →
`PaymentChargeSucceeded` (outbox, exactly-once via optimistic version) → each vertical's EXISTING
idempotent capture writer → `SaleRecorded` → finance debits 1901 with ZERO GL change. Bank payout
sweep: finance V52 `QRIS_CLEARING` reconcile category with the MDR fee leg (`Dr BANK net + Dr 5720
fee / Cr 1901 gross`). Gateway edge: owner-only settings route + POS carve-outs + POS charges
route + the fleet's third anonymous route (own `anon:psp-webhook:` bucket). Residuals (in the
ADR): bills GATEWAY (no Payment row on bills), programmatic QRIS refunds (out-of-band via the
merchant's dashboard), service-vertical/bills customer-display QR, tier-gating GATEWAY,
`X-Override-Notification` to re-verify against current Midtrans docs at the sandbox drill.
Full plan: `~/.claude/plans/nested-wibbling-rabin.md`.

## 2026-08-07 — QRIS payment modes program started: payment-service scaffolded (ADR 0045)

QRIS goes real (ADR 0045, supersedes 0007): per-company modes **MANUAL** (today's mark-as-paid,
still the default) / **STATIC** (merchant uploads their own QRIS image; till + customer display
show it) / **GATEWAY** (dynamic QRIS via the **merchant's own Midtrans account**; auto-capture on
the signed webhook → `PaymentChargeSucceeded` → the verticals' existing capture writers — zero GL
change, QRIS already routes to 1901). New **payment-service** scaffolded from service-template
(package `id.co.nativeapp.payment`, DB/role `payment_service` added to the Postgres init — existing
dev stacks must create them manually or `down -v`; producer-only, so no processed_event and no
Kafka dep). V1 baseline = outbox + error_log folded in (the loyalty lesson). ArchUnit suite kept
verbatim except the template's own `allowEmptyShould`/`optionalLayer` guards extended to every rule
so the clone is green before its first feature lands. Branch `feat/qris-payments`; full plan at
`~/.claude/plans/nested-wibbling-rabin.md`.

## 2026-08-07 — Android till app P1: the app is 1:1 with the web (native transport + branding)

P0's spike became the real thing. The thin-client already renders the web console verbatim, so
"1:1" had exactly two gaps and both are closed. **(1) Printing parity**: `'native'` is now the 5th
`TransportKind` in `frontend/console/src/lib/escpos/` — feature-detected off the Capacitor plugin
proxy (`window.NativePrint ?? Capacitor.Plugins.NativePrint`, absent in every browser → console
byte-for-byte inert outside the app), with a device **picker** in `/settings/printer` (the app has
no OS chooser — `listDevices()` returns bonded Classic + BLE + attached USB), `PrinterConfig.deviceId`
persistence and **deterministic silent re-attach** (the platform bond owns pairing), and
`classifyConnectError` trusting the bridge's D4 reject codes verbatim. The Kotlin side grew from
the SPP spike to all three links: SPP (bounded connect), **BLE GATT** (same print-service
preference order as the web BLE transport, per-chunk ack, MTU negotiation) and **USB host**
(bulk-OUT, consent dialog → `cancelled` on decline), one live connection, write-failure = close +
reject so the console's window.print() fallback semantics hold. Auto-print is silent by
construction in-app — no RawBT, no transient-activation caveat. En+id copy; vitest covers the
bridge with a faked `window.NativePrint` (feature-detect, base64 byte round-trip 0–255, code
mapping, browser-inert assertion) — console 280 tests green. **(2) Shell design parity**: adaptive
icon + splash are the console's own brand glyph (Wordmark trend line) on the brand-500→800
gradient, status bar = console paper, keep-screen-on on (D6 P1), versionCode 2 (D7: native change).
ADR 0043 amended with the as-shipped bridge contract. NOTE: the app shows the native tile only
after the console deploy reaches UAT (thin client renders the deployed origin).

## 2026-08-07 — Android till app P0: Capacitor shell + NativePrint SPP spike (ADR 0043 Proposed)

The Bluetooth-Classic printing gap (ADR 0041: Web Bluetooth is BLE-only, the cheap 58 mm printers
are SPP-only, RawBT = popup-or-companion-app) gets its real fix: a **native Android till app**.
P0 spike landed at `frontend/native-till/` — a **Capacitor 8 thin-client** (`server.url` → the live
UAT console origin, so web deploys reach the app with zero app updates; PWA offline queue works
unchanged in the WebView) plus the first slice of the **`NativePrint` bridge**: `NativePrintPlugin.kt`
(list bonded Classic devices + one-shot SPP test print, reject codes already on the D4
`ConnectFailureReason` mapping) over `SppTestPrinter.kt` (RFCOMM socket, dumb byte pipe — ESC/POS
stays 100% web-side). Debug builds get a native **🖨 TEST** overlay button (the console has no
native wiring until P1) that prints fixed ESC/POS bytes to a chosen bonded printer — the hardware
drill that proves the ADR 0041 gap closed. `BLUETOOTH_CONNECT` runtime permission only (no SCAN —
bonded devices only, pairing stays in Android Settings). The Android build is fully isolated from
the root Gradle build (own AGP 8.13/JDK 21/Gradle 8.14 toolchain; CI path filters don't match it —
Android CI job is P2). Next: owner drill on real hardware, then P1 = `'native'` as the 5th
`TransportKind` in the console + silent auto-print + sideload distribution. ADR 0043 (Proposed),
full plan at `~/.claude/plans/android-till-app.md`.

## 2026-08-07 — Simple mode for UMKM: per-company plan tier, P1 (ADR 0044)

The console now has a **per-company plan tier** (`company.plan_tier`, org V10, `FREE | FULL`) so a
small UMKM sees only the essentials — POS + register close, products, kitchen, printer, a simplified
dashboard, expenses, team — while the whole back-office (accounting suite, HR, promotions, channels,
org tree, customer display) stays hidden until the **owner** flips "Tampilkan fitur lengkap" at
`/settings/features` (owner-only `PUT /api/v1/companies/current/plan-tier`; 422 whitelist / 403
non-owner, RFC-7807). Design properties (plan: `~/.claude/plans/umkm-tier-mode.md`): a tier not a
boolean (PRO/ENTERPRISE rank in later; billing later replaces the *setter*, the reader never moves);
tier rides the existing `/companies/mine` session read (zero new round-trips, no event — mirrors ADR
0013); gating is one console map (`lib/featureTier.ts`) composed with page grants as
`visible = role ∧ grant ∧ tier` (owner bypasses grants but NOT tier — the always-visible owner-only
Features toggle is the escape hatch); Dashboard is never tier-hidden (home-redirect-loop guard), it
just simplifies in FREE; missing tier **fails open to FULL**. Existing companies grandfather to FULL
via the column default (single ALTER, no UPDATE — the FORCE-RLS zero-row trap); new-signups-default-
FREE + per-page friendly locked screens are P2. UI-only enforcement is deliberate and documented in
the ADR (roles at the gateway remain the only API authz). This deliberately lands BEFORE the Android
till app (ADR 0043 reserved) so the app's first impression is the simple surface.

## 2026-08-06 (later still) — Silent RawBT printing via the "Server for RawBT" WebSocket

Live-use follow-up: the intent hand-off flashes the RawBT app on every print. The rawbt transport
now tries the companion "Server for RawBT" app's local WebSocket first (`ws://127.0.0.1:40213/`,
raw ESC/POS binary then close(1000); localhost is mixed-content-exempt so the HTTPS page may use
it, and no user activation is needed — which also erases the auto-print ~5 s activation caveat
when the server app is present). Connection refused falls back to the intent URL in milliseconds —
prior behavior exactly. ADR 0041 amended; unit tests fake the WebSocket (send/refuse/hang/absent)
and pin the fallback decision.

## 2026-08-06 (later) — Auto-print the receipt on payment (per-device toggle)

Follow-up to the RawBT bridge, from live UAT use: the receipt now prints automatically the moment
a sale is paid — no Print tap — behind a per-device toggle on `/settings/printer` (stored in the
same localStorage `PrinterConfig`; older configs default off). `ThermalReceipt` gained an
`autoPrint` prop set by exactly the three surfaces that mount right after a successful payment
(`ReceiptView`, `ServiceReceipt`, `BillReceiptView` — gift-card print stays manual, it's already
behind an explicit tap); the effect fires ONCE per receipt appearance (ref-guarded; surfaces fully
unmount between sales, verified in review), goes to the device only — never auto-pops the
`window.print()` dialog — and a manual Print tap cancels a pending auto-print. The settings card
also finally exposes the drawer-kick toggle (`setDrawerKick` existed with no UI). Caveat, in-code:
RawBT's `intent:` navigation needs transient user activation (~5 s from the confirm tap), so a
slow online capture can silently swallow an auto-print — the Print button is the recovery. Review
PASS; known gap: no DOM test env in the console (vitest `node`, no testing-library), so the
effect's once-semantics are review-verified, not test-locked.

## 2026-08-06 — RawBT bridge transport: Bluetooth-Classic printers print from the POS (ADR 0041)

Field report from UAT: a Bluetooth thermal printer that prints fine through the RawBT Android app
got "printer is busy" from our connect flow. Root cause is a Chromium platform limit ADR 0039
documented but couldn't cross: **Web Bluetooth speaks BLE GATT only**, and many cheap 58 mm clones
are Bluetooth **Classic (SPP) only** — Chrome lists them in the chooser, then `gatt.connect()`
fails with `NetworkError`, which `classifyConnectError` mislabeled as "busy" (a retry loop that can
never succeed). Two changes (ADR 0041): (1) a fourth `rawbt` transport — each print navigates to
RawBT's `intent:base64,<escpos>` URL (package-pinned, Play-Store fallback), so the app drives the
Classic printer with our unchanged ESC/POS bytes; Android-only, nothing to pair, fire-and-forget by
design. (2) BLE `NetworkError` is now its own `bleUnreachable` reason whose copy names both real
causes (another app holding the printer's single Bluetooth socket — e.g. RawBT's background
service — or a Classic-only printer) and points at USB or the RawBT tile. `inUse` unchanged for
USB/serial. New byte-level tests for the intent-URL round-trip + classifier
(`lib/escpos/__tests__/transport.test.ts`).

## 2026-08-05 (later) — QA sweep: 9 verified findings found and fixed the same day

A 4-hunter adversarial QA sweep (money / tenancy / concurrency / frontend lenses, every finding
re-verified at the code site before reporting) found 9 bugs; all fixed, money-reviewed (PASS), and
CI-green the same day (commits 682d3bce..8b06fbc0). The headline root cause — **cross-topic
consume-order**: SaleVoided/SaleRefunded and SaleRecorded ride separate topics, so a reversal can
be consumed before its sale exists locally. Three consumers mishandled that; the fix is one
pattern, **"park, don't drop"**: a reversal that finds no local trace of its sale parks ONE durable
`pending_sale_reversal` row (loyalty V3, finance V48 — RLS FORCE, parked in the same tx as the
processOnce claim) and the sale's own ingest/posting applies it in the SAME transaction the sale
lands in. Parking beats throwing (a throw stalls the whole partition behind one missing sale) and
beats DLT (manual replay). Loyalty was CRITICAL (a voided sale permanently earned full
points/gift-card credit, zero trace); finance's gross-template fallback misbooked per-leg sales
(and used the payment-residual amount for gift-card voids); expense-claim voids now self-heal an
`unrecognizedVoid` ledger row the late approval reconciles onto. saleId-less legacy events keep
the old fallback — the Phase-1 tests now pin that contract explicitly.

Also fixed: **register-close cash race** (a concurrent uncommitted sale escaped the close's SUM,
posting a phantom OVER to real GL and falling into no session's window forever) via
`CashWindowLock` — a per-business shared/exclusive advisory pair (shared in every cash-committing
writer BEFORE occurred_at is stamped; exclusive in open/close before closeInstant; a plain mutex
was tried and rejected — it broke a genuine-concurrency test); **brought-forward assets** now
refuse tax-sealed periods (the one money writer missing the guard — the console posts assets
before the once-only opening entry, so a sealed as-of stranded a half-migration);
**opening-balance lines on the VAT control accounts** are rejected 422 (the GL-derived PPN return
would count them as period activity and the filing's netting entry would strand them); console:
commission % now locale-formatted (rule 9), the companies[0] fallback persists its pointer
correction, and SessionProvider got its first tests. Residuals tracked in the session memory:
carwash/barbershop entitlement-mirror ordering guard, no toast/banner infra for the fallback
notice, opening-balance currency vs base currency (needs a base-currency read model — ADR
candidate).

## 2026-08-05 — CI activated & verified on real runners; enforcement moved to commit time

The AI-driven-development gap-closing round: the pipeline (authored 2026-08-02, never green) is now
**live and verified** — full-fleet backend + console + new doc-drift job all green on GitHub
runners, master `gate` green, gate-only **branch protection ON** (admins exempt for solo direct
pushes), master Gradle cache seeded. Four failures only real runners could expose, all fixed:
(1) google-java-format 1.25.2 → **1.36.1** — setup-java hosts the Gradle daemon ON Temurin 25 where
1.25.2 crashes on removed javac internals; local daemons run on JDK 21 so it never reproduced
locally (mechanical rewrap of 8 files came with the bump); (2) **11 console ESLint errors** — local
gates never ran `npm run lint`; fixed properly (session.tsx split into session.ts +
SessionProvider.tsx with the two sync-setState effects converted to render-phase adjustments —
also closes a 1-frame window where company B could render with company A's outlet; photos/csv
splits; fresh-context review PASS); (3) `libs:observability` failed the jacoco branch ratchet —
libs' own check tasks never run in scoped builds, new post-processor test pins the ADR 0010
defaults; (4) restaurant's reorder-guard test silently depended on the dev machine's **ambient
Redis** — moved to `PostgresRedisTestBase` (lesson: a locally-green `@SpringBootTest` may be riding
the dev docker stack).

Enforcement now happens at commit time, not review time: a **PreToolUse hook**
(`scripts/hooks/pre-commit-quality.sh`, wired in `.claude/settings.json`) blocks any `git commit`
whose staged files fail spotlessCheck (scoped to their modules) or ESLint — drilled live. A new
always-on **doc-drift CI job** (`scripts/check-doc-drift.sh`) fails when an Avro schema is missing
from EVENT-CATALOG.md or a service from PROJECT-MAP.md; its first run caught PROJECT-MAP missing
barbershop-service and loyalty-service entirely (both added, table now "the 10 deployables").
Remaining manual step: GHCR package visibility (packages are born private; needs a
`read:packages`-scoped token to inspect/flip — only matters for anonymous image pulls).

## 2026-08-03 — Opening balances & business migration (ADR 0037)

Onboard an EXISTING business (or record a new one's initial capital) — the gap that blocked real
adoption: before this, adding an owned asset drove cash negative (acquire always `Cr CASH_CLEARING`)
and there was nowhere to record capital (no equity account/role, no manual journal path). Now a
one-time **opening balance sheet** posts as ONE balanced `JournalEntry` (Dr assets / Cr liabilities +
equity) — the ad-hoc-entry idiom (disposal/tax/settlement pattern, no Kafka) — auto-plugged to a new
**Opening Balance Equity (3900)** clearing account so it always balances (the QuickBooks/Xero/Odoo
"Undistributed Profits" pattern; plug = Σuserdebit − Σusercredit). **Balance-sheet accounts only**
(REVENUE/EXPENSE → 422; prior profit is a Retained-Earnings credit, never a re-posted P&L).
Once-only per company (`company_opening_balance`, `UNIQUE(company_id)`, FORCE RLS); Idempotency-Key
required (same-key+same-payload replay 200; different-payload 409 by **SHA-256 fingerprint of the
sorted lines**, not just the total; different-key-when-recorded 409). Posts into
`periodOf(asOfDate)` — a REAL `YYYY-MM`, NEVER a sentinel: statements filter `period <= asOf`
lexicographically, so `"OPENING"` would sort past every month and vanish from the balance sheet.

**Brought-forward (pre-owned) fixed assets** register cash-free (`POST /api/v1/assets/opening`):
`Dr FIXED_ASSET_COST gross / Cr ACCUMULATED_DEPRECIATION opening / Cr OPENING_BALANCE_EQUITY net` —
no cash. New `fixed_asset.opening_accumulated_minor` + `origin` (V47; `ADD COLUMN … DEFAULT`
back-fills every row without an UPDATE, so the FORCE-RLS backfill gotcha doesn't apply).
`depreciableBase()` subtracts opening accumulated so the monthly run depreciates only the REMAINING
base over the REMAINING life; the register roll-up and disposal derecognition add it back. ACQUIRED
assets are byte-for-byte unchanged (opening = 0).

Global illustrative seed (V46): equity accounts 3000 capital / 3100 retained-earnings / 3900 OBE +
1100 inventory / 2700 loans, role maps OWNER_CAPITAL/RETAINED_EARNINGS/OPENING_BALANCE_EQUITY.
**No reader changed**: `BalanceSheetReader` already classifies EQUITY credit-normal, and
`CashFlowReader` already buckets EQUITY as financing — so opening entries + brought-forward assets
reconcile EXACTLY (Dr 1500 investing-out + Cr 3900 financing-in net to zero cash; verified). Gateway
`/api/v1/opening-balances/**` DASHBOARD_ROLES. Console: standalone `/opening-balances` Finance page
(owner/manager, en/id, Intl money, live plug readout) + a non-blocking onboarding-wizard CTA.

**Money code-review: FAIL→fixed.** Backend money/tenancy/idempotency/migrations passed clean; two
HIGH console data-loss bugs in the once-only submit were fixed: (H1) per-asset Idempotency-Key was
loop-index-keyed → after a partial-failure row edit a retry could reuse another asset's key and
silently drop one → now keyed on the row's stable UUID; (H2) the main POST's success swapped the
page while the per-asset loop still ran → assets now register FIRST and the once-only main entry
posts LAST, so a mid-batch failure keeps the form and is retryable. Also M1 (fingerprint total→
SHA-256 lines + test), L1 (`asOfDate` ≥ 2000 → clean 400), L3 (`@NotBlank` currency), L2 (cash-flow
go-live-month presentation nuance documented). Gates: finance test+spotless+checkstyle green (incl.
`OpeningBalanceWriterTest` 9, `BroughtForwardAssetTest` 2); gateway 97/97 (+2 route-gating);
console tsc + 177 vitest. Residuals (ADR 0037): opening AR/AP/inventory are GL CONTROL balances only
(no per-invoice/per-SKU open items — aging won't itemize); OBE reclassification, FX-denominated
opening, and amend-after-record deferred; a fully-migrated company can't reopen the once-only form
to add a further brought-forward asset (backend-enforced).

## 2026-08-03 — Online channels + platform settlements, Phases B+C (ADR 0036) — settlement program COMPLETE

**B1 (finance deployed FIRST)**: additive `channel` on SaleRecorded/SaleVoided/SaleRefunded (LAST,
default null — rule 7; every producer puts explicit values; contract tests across all 5 consumers
prove old-payload→null both directions). BOTH `resolveClearingRole` switches (Revenue + Reversal
posting writers) learn `ONLINE → PLATFORM_RECEIVABLE` + WARN on unknown tenders. V44: 1250 Platform
Receivable / 5710 Platform Fee Expense + the negative-tolerant per-channel `platform_receivable`
accumulator (`PlatformReceivableWriter` atomic upsert inside the posting tx; null channel →
UNKNOWN + warn, money never dropped). **B2**: restaurant `sales_channel` CRUD (code immutable +
uppercase, soft-deactivate, owner/manager mutations service-side, cashier-readable) + V24 +
`TenderType.ONLINE` = synchronous capture (`Payment.capturedOnline`, tendered = amount, change 0);
checkout/payParked/payBill reject ONLINE without an active channel or with gift-card/loyalty
redemption; the channel snapshot rides Sale → SaleRecorded and void/refund from
`payment.channel_code`. **B3/C2 console**: /channels CRUD, POS ONLINE tender with channel-tap
panel (greyed while a redemption is active — stripping it would charge more than the screen
shows), /platform-settlements (outstanding incl. negative clawbacks, settle form w/ derived fee,
history). **C1**: V45 `platform_settlement` + `PlatformSettlementWriter` — required
Idempotency-Key replay-by-key-first (payload conflict 409), per-(company,channel) advisory lock,
GUARDED decrement (`outstanding >= gross` or 422, nothing touched), posts `Dr 1900 net + Dr 5710
fee / Cr 1250 gross` (zero legs omitted) so the payout's bank line reconciles via the ADR-0016
CLEARING sweep.

Live-proven end-to-end: 2 ONLINE sales → Dr 1250 JEs + accumulator 90k; full ONLINE refund →
clawback to 52k; settle 40k/34k → exact 3-leg JE + outstanding 12k; replay 200 / conflict 409 /
over-settle 422 / net>gross 422 / keyless 400; channel CRUD 201-403-409. Full gates green
(restaurant+finance+gateway+carwash+barbershop+loyalty + console tsc/vitest 144). Two gate
findings fixed: ArchUnit tx-naming (accumulator → `PlatformReceivableWriter`) and a latent phase-A
gateway test asserting a forwarded path without its query string. Ops: `native-postgres`
max_connections 100→300 (fleet outgrew it); NEVER rebuild a bootJar in C:\native-pos-build while a
service runs from it (lazy class loads explode — restart after building).

## 2026-08-03 — Closing kasir (register sessions) Phase A + money-review fix round (ADR 0036)

First slice of the settlement program (plan: functional-popping-gizmo): per-outlet-per-day cash
register sessions (open w/ float → close w/ drawer recount), the `RegisterSessionClosed` event, and
finance posting ONLY the signed variance (selisih kas: short → Dr 5700/Cr 1900, over → Dr 1900/Cr
4300, zero → processed-no-entry, sealed period → error-inbox quarantine carrying amount+currency).
Restaurant V21 (`cash_register_session`, one-OPEN-per-outlet partial unique, `sale.tender_type`) +
finance V43 (two new AccountRoles so an SME can remap both onto one "Selisih Kas" account). POS till
menu → RegisterSheet (open/close/verdict; disabled offline or with a non-empty sync queue, ADR 0028).

**Money review round** (all fixed, live-drilled): C1 a gift-card-split CASH sale overstated expected
cash → `sale.cash_collected_minor` (V22; COALESCE fallback for legacy rows); C3 cumulative
`payment.refunded_minor` double-counts partials spanning sessions → append-only `payment_refund`
delta ledger (V22) summed per window; **found live in the re-drill**: a cash GIFT-CARD SALE is
drawer money living outside `sale` (liability, not revenue) → third window term + V23 index; W1
OutletAccessGuard on close/current/history AND replay branches; W2/W3 replayed Idempotency-Key with
a different payload → 409 (`register-session-idempotency-key-conflict`), never a silent 200; W4
console close key is the STABLE `close:<sessionId>` (a fresh-per-attempt key made the server replay
path unreachable) + 409 → refetch; W5 `@NotNull Long countedCashMinor` (missing field used to
deserialize to a silent 0 count); W6 consumer identity asserts use `Math.*Exact`. Unit pins in
`RegisterSessionWriterTest`. The close's SELECT-FOR-UPDATE is the authorized pessimistic-lock
exception — rationale + quantified v1 approximations (window attribution, MVCC boundary, legacy
null-tender, Asia/Jakarta default, commission PPN) in ADR 0036. Next: Phase B (sales channels +
ONLINE tender → PLATFORM_RECEIVABLE, finance deploys FIRST), Phase C (platform settlements).
NOTE: restaurant V22+V23 are consumed by this fix round — Phase B's migration is V24+. loyalty-service
is NOT in scripts/start-dev-services.ps1; the gift-card mirror needs it running (manual launch, port
8093 — add it to the script when it grows a dev port).

## 2026-08-02 — POS redesign (P0–P5): shared shell, one-tap flows, 2 latent bugs fixed

Full-program UI/UX redesign of the POS across all three verticals (plan: functional-popping-gizmo;
ADR 0034 "verticals are adapters, not clones"). Phased extract-in-place, each phase gated by a
30-shot screenshot matrix (`frontend/console/scripts/pos-matrix.mjs` — vertical × viewport × theme
incl. print-emulated receipt/KOT shots; OIDC mode against the live stack) + vitest + tsc:

- **P0 harness caught two REAL production bugs**: (1) BillDetail called useMediaQuery below its
  early returns → every freshly opened bill crashed the POS blank (since 6ef2137); (2) the print
  isolation's `body:has(#id) *` outranked the `#id` un-hide rules → every thermal receipt / KOT /
  QR sheet printed a BLANK page (since the audit-16 :has() scoping). Fixed (`:where()` zeroes the
  specificity) + print-emulated shots are now a permanent tripwire.
- **P1/P2**: pure logic (categories/lineKey/discount/quick-chips/error-keys/display payloads)
  extracted into tested modules (38 new vitest cases; display builders validated against
  `isDisplayMessage`); the 3 POS monoliths (2013/1153/1146 lines) split into `components/` files.
  BillDetail's category fork (case-SENSITIVE, missed f050be1) deduped onto the shared source.
- **P3**: the ~2,500-line triplicated payment modals collapsed into `pos-shell/payment/` views +
  3 thin behavioral adapters. Money review: **PASS** (wire payloads, per-attempt idempotency,
  enqueue-before-success-UI all verified field-for-field; live drills: retry reuses the key,
  cancel/reopen mints fresh, offline QUEUED→SYNCED). Fixed a stale dev gateway masking the
  `/api/v1/pricing/**` route (offline provisional pricing needs it).
- **P4/P5**: the redesign — 56px ink-band terminal chrome (PosStatusBar: connection pill, ≤3
  pinned actions, till-menu overflow), pinned **Walk-in tab** (the hidden cart-vs-bill mode is
  now navigation), ticket dock with destination pill → order switcher, honest verbs (**Send
  fires the KOT directly; Pay opens payment directly; exact-tendered pre-filled** — walk-in cash
  n+4→n+3 taps, bill send n+5→n+3, bill pay 7→5), shadow-layered tiles, 3px-indicator rail,
  search moved into the catalog. Carwash/barbershop share the same chrome; their ticket panel
  caps at 45dvh on portrait (no more Charge-below-the-fold). `posShell.*` i18n (en+id).

**Residuals** (next passes): service requirement-chip dock + pinned Charge (the
`VerticalPosConfig.shell` block, ADR 0034); restaurant ≥lg right-rail ticket panel; BillDetail
dead modifier/props pruning (review S2); jsdom key-stability test (S3); FRONTEND-STRUCTURE.md
still describes the retired emerald system; `emerald*` alias naming debt in index.css (the
aliases are theme-aware — do NOT bulk-rename to brand-*, dark maps to different ramp stops).

## Current status (update me)
**Backend: complete, hardened, and proven end-to-end.** All of Phase 0–3 backend is built, every
milestone team-built → adversarially reviewed (code + security + domain-correctness) → fix-rounds →
full build green → committed. The validation slice runs **live** (sale → outbox → Debezium → Kafka →
finance → consolidated revenue = verified against real infra, not just Testcontainers). CI + Kustomize
deploy authored (unverified vs a real cluster). The whole codebase is **package-by-feature with
controller/service/repository/domain/dto/messaging layer sub-packages**, ArchUnit-enforced.

**Not done (hard gates — need a human/SME/infra, do NOT invent):**
- Frontends — **console slice live** (`frontend/console`: onboarding wizard, consolidated revenue/P&L
  dashboard, and a **cashier POS**, en/id, Intl money). Now behind **real Keycloak OIDC login** with
  **role-gated surfaces** (cashier → POS only; owner/manager → dashboard + POS), proven on the running
  authenticated stack. **The org-tree, group-consolidation, and period-close console pages are now
  built** (`frontend/console`: `/org`, `/groups`, `/close` — owner/manager-gated, en/id, Intl money,
  illustrative badges) over new RLS-scoped read-endpoints (org-service GET /api/v1/org-units, GET
  /api/v1/consolidation-groups[/{id}/members]; finance GET /api/v1/closes; gateway DASHBOARD_ROLES
  routes; real-DB two-tenant RLS isolation tests guard the RLS-only reads). Remaining: the employee
  PWA — design decisions, never autonomous.
- **Official DJP/BPJS statutory figures** — payroll ships `ILLUSTRATIVE_PLACEHOLDER` data (provenance
  column + loud seed + runtime flag); a tax SME must seed real effective-dated figures.
- **Full IAS-21 multi-currency consolidation** (CTA/OCI, historical-rate equity, opening-balance
  roll-forward) — ships a FLAGGED-SIMPLIFIED translation; needs an accounting SME.
- Live infra (a real registry/cluster/secrets for the deploy; a real notification transport).

**Open follow-ups (tracked):** notification real provider (needs a transport choice); payroll
expected-source registry (needs a rule); **POS indirect-tax + accounting** —
the PB1-vs-PPN identity, rates, service-charge-revenue-vs-tip treatment, and GL account mappings ship
`ILLUSTRATIVE_PLACEHOLDER` and need a tax/accounting SME (ADR 0006); **real QRIS/card PSP adapter +
settlement webhook** (ADR 0007, needs a provider choice); **own-sales commission is single-currency**
— `MetricPublished` carries a bare minor-units `value` with no currency, so commission is denominated
in the payroll base currency; correct only while sales are in the base currency. When multi-currency
lands, add an optional `currency` to the metric schema and reject a metric whose currency ≠ the
run/package currency (code-review W1; guarded today by the single-currency slice, commented at the
emit + resolve sites). (DONE: the P3d deferred operational items —
`member_group_index` backfill, the within-company concurrent-close lock, the within-close MVC tests;
the finance-expansion posting-currency robustness guard; and the org-tree move/deactivate semantics —
cascade-deactivate + reactivation.)

## Key design decisions (the why)
- **Package root `id.co.nativeapp`** — `id.co` reverse-domain; `nativeapp` because `native` is a Java
  reserved word (illegal package segment).
- **Layered + package-by-feature, ArchUnit-enforced** (not hexagonal) — controller→service→repository→
  domain, grouped by capability; later refactored so each feature has explicit
  `controller/service/repository/domain/dto/messaging` sub-packages (user preference for readability).
  The gateway is the documented carve-out (reactive edge, not aggregate-bearing).
- **The `*Writer` pattern** — every `@Transactional` write is its own `@Component` (`*Writer`), so it is
  invoked through the Spring proxy: a self-invocation would bypass the tx advice AND the
  `RlsAutoApplyAspect` that sets the tenant GUC. Load-bearing for RLS. Services orchestrate, Writers
  transact, Readers query.
- **RLS is enforced, not assumed** — every service connects as its own non-superuser role; tables use
  `ENABLE`+`FORCE ROW LEVEL SECURITY`; the tenant is a Postgres GUC (`app.current_tenant`) set per
  transaction via a scoped value (not ThreadLocal). Tested as the non-superuser `app_user`.
- **Group consolidation cross-tenant model (P3d)** — a group is a SECOND RLS scope: a new
  `app.current_group` GUC + a CONJUNCTION policy `group_id = app.current_group AND company_id =
  app.current_tenant` on the group tables. Members PUBLISH their trial balances (TrialBalancePublished),
  finance never cross-tenant-reads. Adversarially verified bypass-free. **Decision:** single-reporting-
  currency consolidation is fully correct; multi-currency is FLAGGED-SIMPLIFIED (balance-check gate +
  residual to a flagged CTA reserve + `uses_simplified_translation_policy`); full IAS-21 deferred to an
  SME. (Memory: `p3d-consolidation-scope`.)
- **CDC wire = base64'd Avro bytes** — the outbox payload is `bytea` (raw Avro); Debezium decodes it as a
  `ByteBuffer` that `ByteArrayConverter` rejects, so the connector base64's it (`binary.handling.mode`)
  and the consumer's `libs/events Base64ByteArrayDeserializer` decodes it back. AvroSerde + the "no
  Confluent serde" design are unchanged. (See RUNBOOK gotcha #3.)
- **Flagged-illustrative domain data** — anywhere real domain law is needed but absent (statutory
  payroll figures, FX rates, consolidation policy), the MACHINERY is real but the DATA is loudly flagged
  (provenance enum + loud seed comment + a runtime flag on the run/event) so it can never be mistaken
  for verified production values. Never invent tax/accounting law as production values.

## Milestone history (newest first; commit refs are illustrative anchors)
- **Self-order QR + customer display — Phase 6, the POS-parity program's FINAL phase (2026-08-01,
  ADR 0029)** — the fleet's second anonymous route and first anonymous TENANT-BINDING. A per-outlet
  HMAC-SHA256 token (base64url payload `{v,kid,companyId,businessId,outletId,tableLabel}` +
  signature; NO expiry — revocation = kid rotation killing every printed QR at once; secret
  AES-256-GCM at rest under `NATIVE_SELFORDER_KEY`, V19 `self_order_access` one-ACTIVE-per-outlet)
  admits a diner to EXACTLY two operations: read the menu, create a **PARKED order
  `source=SELF_ORDER`** (no sale/payment/outbox/stock — blast radius is junk parked rows; 50-cap →
  429 + 30-min sweep → EXPIRED). `SelfOrderTokenFilter` parses the claim, binds a provisional
  tenant for ONE RLS-scoped access-row lookup, constant-time-verifies, then REBINDS from the
  verified row's own company_id (actor `self-order:{table|kiosk}`). Gateway: `/api/v1/self-order/**`
  no-JWT (signup-precedent permit), namespace-parameterized `AnonymousRateLimitFilter`
  (60/min/IP), new `AnonymousTenantHeaderStripFilter` on BOTH anonymous routes (closed signup's
  pre-existing unstripped-spoof-header gap). Restaurant gained the fleet entitlement-projection
  pattern (first wiring; gates order-create on new `self_order` module, entitlement V5, defaults
  6→7). Console: `/pos/customer-display` (typed BroadcastChannel `pos-display:{outlet}`, lazy
  zero-overhead publisher, id-default locale), per-table + kiosk QR management with print-ready
  tents + rotate-with-confirm, ParkedTray SELF_ORDER badges. NEW `frontend/self-order` mini app
  (anonymous, token in memory only, menu grouped client-side, per-attempt idempotency key). — cash-only offline
  selling with NO new endpoint, event, or finance change: sales queue in IndexedDB (idempotency key
  minted+persisted at enqueue) and replay serially through the unchanged idempotent checkouts (409 on
  replay = already landed = synced). **Server** (all 3 verticals): `offlineReplay` +
  `clientOccurredAt` (accepted only on replay; ≤48h past / ≤5min future → else 422
  `offline-replay-invalid` / `ticket-offline-replay-invalid`) — the sale-day instant drives BOTH the
  pricing effective date and `SaleRecorded.occurred_at`, so the GL period is the sale day, not the
  sync day; replay is CASH-only (coupon/points/gift-card → 422; loyalty **earn** memberId rides);
  restaurant replayed oversells are accepted with the shortfall written to the newly activated
  `error_log` (V18) — money first, inventory repaired by count; per-vertical
  `GET …/pricing/effective-rules` snapshots today's rules for the client. **Pricing parity**: the
  server formula extracted into `PricingFormula`; a committed 12-case JSON fixture is asserted by a
  restaurant JUnit test AND the console's BigInt HALF_EVEN `provisionalPricing` vitest suite — drift
  breaks a build, not a drawer count. **Console**: vite-plugin-pwa (shell precache, `/api` NEVER
  cached), OfflineBanner + SyncCenter (end-of-day SYNCED_WITH_MISMATCH report; REJECTED kept
  visible), Web-Locks single replayer, `navigator.storage.persist()`, POS gates offline to cash
  quick-sales (bills/parking/void disabled), provisional-marked receipts, en/id. RUNBOOK gained the
  airplane-mode manual script.
  program's hardest design, shipped: a NEW **loyalty-service** (the sole ledger of record: members
  with AES-256-GCM-encrypted phone/name + a separate-key HMAC lookup hash — the ONLY PII home;
  append-only points + gift-card ledgers with UNIQUE(company, source_event_id, type) idempotency
  backstops; company-configured `earn_rule`, zero-earn until configured, **UTC-date effective
  dating** — the gate caught a live system-zone bug creating a daily 00:00–07:00 WIB zero-earn
  window). **The rule-2 answer:** console→gateway→loyalty is a CLIENT call (lookup/enroll fresh
  and authoritative at the till); the VERTICALS never call loyalty — they clamp + atomically
  decrement LOCAL `member_balance_ref`/`gift_card_ref` caches (absolute-value events +
  `balance_seq` set-if-newer = self-healing), and cross-outlet races within replication lag are
  applied anyway by the authoritative ledger, going NEGATIVE + emitting `LoyaltyRedemptionFlagged`
  — never silent, never blocking (the reservation saga is the documented escalation). **Events:**
  `SaleRecorded` +5 nullable fields (identity extends to subtotal − discount − loyalty_redeemed +
  sc + tax == amount; gift-card settlement excluded — a TENDER); four new schemas
  (GiftCardSold/LoyaltyBalanceChanged/GiftCardStateChanged/LoyaltyRedemptionFlagged), evolution
  proven both directions in all four consumers. **Money:** points redeem at 1 pt = Rp1 (documented
  v1; schema carries points+minor separately so a rate needs no schema change); gift-card
  redemption splits the clearing debit (Dr GIFT_CARD_LIABILITY + residual tender; fully-covered →
  zero-amount CAPTURED payment, wire tender NULL); earn is memo-only; finance V37 + its Java
  companion shipped as ONE build (the migration carries a deployment-order hazard banner — SALE v3
  basis tokens NET_TENDER/GIFT_CARD_TENDER/LOYALTY_REDEEMED), legacy sales byte-identical under v3
  (named regression x2); reversal replay contras the new legs with zero code change. Two capture
  bugs found en route: restaurant capture would have used the gift-card RESIDUAL as amount_minor
  (under-recording revenue) — fixed to the order total. **Ticket identity CHECKs extended**
  (carwash V10/barbershop V5); `discount_minor` stays promo-only everywhere. **Console:**
  MemberField/GiftCardField/GiftCardSellModal on both POS surfaces (keypad math targets
  residualDueMinor), /loyalty dashboard (earn rules create-only by design + back-office lookups),
  receipts render the card as a PAYMENT row (a tender, never a discount). Gateway:
  /api/v1/loyalty/** (POS) with /earn-rules carved to DASHBOARD; /api/v1/gift-card-sales
  (restaurant, POS). 27 new vertical suites + 19 loyalty suites; ALL gates green (loyalty,
  finance, gateway, three verticals, console).
- **Promotions engine — Phase 3 of the POS-parity program (2026-07-31, ADR 0026)** — automatic
  rules, coupons, and happy hour across ALL THREE vertical POSes, with **zero Avro and zero finance
  changes**: every deduction (line-scope rules → priority/exclusive automatics → at most ONE coupon
  → the manual discount, clamped ≤ subtotal) COLLAPSES into the existing
  `SaleRecorded.discount_minor`; per-rule detail lives in the new vertical-local
  `applied_promotion` audit rows (rule SNAPSHOTS, same tx as the sale). Schema x3 (restaurant V16 /
  carwash V8 / barbershop V3, identical + FORCE RLS): `promo_rule` (typed; happy-hour
  dow_mask/window/tz on ANY rule; `requires_coupon` = the coupon vehicle; BUY_X_GET_Y
  schema-reserved, admin-rejected), `coupon` (UNIQUE(company, code); **atomic redemption UPDATE**
  in the checkout tx — 0 rows → 409 rollback, race-proven x3), `applied_promotion`. Engine =
  `PromotionEngineService`, byte-identical x3 (CATEGORY scope documented never-matches off
  restaurant). **Promotions evaluate when money moves** — parked orders re-evaluate at pay (which
  exposed and fixed a pre-existing payParked stale-total bug); quotes report a NON-throwing
  `couponStatus` + itemized `appliedPromotions`. **Coupon no-new-benefit semantics** (found by the
  gate, fixed on principle): a coupon whose linked rule already fired automatically / grants zero /
  is clamped out stays APPLIED on the wire but burns NO redemption. Manual discount is now
  owner/manager-only (403 cashier) on every path incl. bills. Admin: restaurant
  `/api/v1/promotions` (gateway DASHBOARD_ROLES) + vertical-prefixed clones (POS-routed,
  service-side owner/manager write guard — reads deliberately open). Console: `/promotions`
  dashboard page (rules + coupons CRUD, happy-hour editor), CouponField + applied chips on BOTH POS
  surfaces, receipt itemization (drift-suppressed), cashier-hidden discount input, per-attempt
  idempotency keys on restaurant checkout (the W2 double-charge window closed). **Reviews: code
  PASS + security PASS (zero C/W security findings)**; W1 fixed — restaurant digital-tender capture
  now RECONSTRUCTS the breakdown (line sums + applied rows + manual → TaxChargeService at the
  order's persisted occurredAt; mismatch → WARN + null-breakdown fallback, money never distorted),
  reaching parity with the ticket verticals; two-tenant promotion isolation tests x3 (security
  S-1). Residuals (documented ADR 0026): abandoned digital checkout burns a redemption slot
  (over-counts only); per-vertical rule duplication for multi-vertical companies; coupon
  brute-force bounded by per-tenant rate limiting. All gates green x3 services + console.
- **Barbershop vertical + module rollout — Phase 2 of the POS-parity program (2026-07-30, ADR
  0024)** — the THIRD vertical sells, proving the carwash shape is a clone-able template:
  `services/barbershop-service` (145 files, 28 test suites) is a copy-with-rename of carwash with
  ONLY the domain differences: catalog = `service_item` (+ `duration_minutes` NULL — RESERVED for a
  future appointments app, persisted but inert) + `service_addon` + `staff_profile`; ticket carries
  `chair` (optional) not `bay`, no vehicle plate, and **barber attribution MANDATORY**
  (`staff_profile_id NOT NULL`, 400 without — every cut has a barber; the employee LINK stays
  optional and `sales_amount`@employee is skipped unlinked); declared outlet metric =
  `service_count` only; tax rule `VAT_BARBERSHOP` 1100 bp ILLUSTRATIVE (personal-care regime
  SME-gated). One V1 baseline folds in every Phase-1 lesson (outbox traceparent, `price_minor`
  `@AttributeOverride`, FORCE RLS everywhere). **Module rollout recipe** (the reusable part):
  entitlement V4 inserts `barbershop` into `module_catalog` BEFORE the `default-modules` config
  lands (`validateModulesExist` throws at the first `CompanyCreated` otherwise); NEW companies get
  the default grant; EXISTING companies are deliberately NOT backfilled (a Flyway write into
  FORCE-RLS `tenant_entitlement` is impossible by design and would bypass the outbox) — they
  self-serve via the newly gateway-routed `POST /api/v1/entitlements` (DASHBOARD_ROLES) + a console
  **ModulesPanel** on the org page (confirm-before-revoke). Gateway: `/api/v1/barbershop/**`
  (POS_ROLES). Console: `servicepos` generalized by config with zero carwash copy changes —
  `packagesPath` (packages|services), `primaryItemType`, per-vertical labels, `location.fieldName`
  (**the wire seam: barbershop serializes `chair`, carwash `bay`; vehiclePlate omitted for
  verticals without it**), required-attribution gate blocking Charge. **Review (money+tenancy):
  PASS — "unusually faithful clone", zero money/tenancy findings; W1/W2 fixed** (carwash "washer"/
  "package" copy leaking onto barbershop surfaces → staffLabels + summaryEmptyKey config groups).
  The whole clone produced ONE functional defect (a wrong MockMvc import). Full gates green:
  barbershop (28 suites), entitlement (6-module default grant), gateway (route matrix), console
  build. Zero Avro changes — the third `SaleRecorded` producer. NOTE: parallel sessions share this
  branch (signup/country work landed mid-phase as ADR 0025) — ADR numbers must be re-checked
  against `docs/adr/` before each phase (promotions is now 0026).
- **Odoo-style signup — country-derived currency, owner identity, funnel fields (2026-07-30,
  ADR 0025)** — the public signup now asks WHERE (country), not WHICH currency: `SignupRequest`
  dropped `baseCurrency` and gained `country` (ISO 3166-1 alpha-2) + `ownerFirstName`/optional
  `ownerLastName` (mononym-friendly, stored on Keycloak's NATIVE name fields) + optional `phone`
  (format-checked, no SMS) + Odoo's funnel bands `companySize`/`primaryInterest`. org-service
  derives the currency in `company.domain.CountryDefaults` (ID→IDR else USD) BEFORE any Keycloak
  call (invalid country = 400 with zero residue), and `company` grew write-once `country` +
  nullable funnel columns (V9, `NOT NULL DEFAULT 'ID'` — no RLS backfill trap). `CompanyCreated`
  deliberately NOT widened (sole live consumer reads `company_id` only); the in-app
  `POST /companies` keeps explicit currency + optional country (wizard alignment = follow-up).
  Console signup rebuilt as 5 steps (Company → Region → About you → Security → Review): native
  `<select>` country picker labeled via `Intl.DisplayNames` (no hardcoded country names), locked
  derived-currency callout, review rows with the currency pencil jumping to Region. Full
  org-service gate green (spotless/checkstyle/jacoco + Testcontainers acceptance incl. new
  ID→IDR / US→USD derivation, no-residue, mononym and legacy-baseCurrency-ignored cases);
  console build + all-5-steps screenshot walkthrough verified.
- **Carwash POS — Phase 1 of the POS-parity program (2026-07-30, ADR 0023)** — the second vertical
  sells: carwash-service grew from a bare `POST /washes` to a full POS backend on branch
  `feat/pos-parity`. **Backend** (V4–V7): outlet-scoped catalog (`wash_package`/`wash_addon` +
  `staff_profile` — a PII-free washer directory whose OPTIONAL `employee_id` link is snapshotted
  onto each ticket for commission), `tax_charge_rule` mirroring restaurant V5 (⚠ `VAT_CARWASH`
  1100 bp `ILLUSTRATIVE_PLACEHOLDER` — the carwash indirect-tax regime is SME-gated; NO
  service-charge rule seeded → zero fall-through), and the `ticket` money path: stateless quote →
  one-shot checkout (`/api/v1/carwash/tickets`) with the `WashService` idempotency contract
  verbatim, server-side price re-resolution (client amounts never trusted), `OutletAccessGuard`
  after the idempotency fast path, CASH capturing ticket+lines+payment+`SaleRecorded`(FULL
  breakdown + tender)+metrics in ONE tx, QRIS/CARD flagged-pending (PENDING, `sale_id` NULL, zero
  events) until `POST /{id}/capture` — revenue at capture (ADR 0006) preserved in one-shot form.
  Metrics: `wash_count`/`upsell_amount`@outlet (as washes) + **`sales_amount`@employee, subject =
  the WASHER** (unlike restaurant's cashier-subject), skipped when unlinked. **Zero Avro changes**
  (the nullable breakdown fields existed; finance consumed the richer producer with zero changes);
  legacy `POST /washes` untouched (OpenAPI-deprecated; its tests double as the back-compat proof).
  **Gateway**: `/api/v1/carwash/**` → carwash-service, POS_ROLES — vertical path prefixing (the
  `/api/v1/ap` precedent; restaurant's unprefixed routes are grandfathered). **Console**: reusable
  `features/servicepos/` surface (config-driven per vertical: package grid, addon chips, bay +
  vehicle + washer attribution, live quote with estimated badges, cash keypad / digital two-step
  pending→capture modal, `ThermalReceipt` reuse) + `CatalogManagement` + `PosSwitch` at `/pos`
  picking the surface by the effective outlet's vertical (fail-open-to-restaurant preserved); i18n
  en/id. **Dev stack**: carwash + entitlement Debezium connectors added (the entitlement connector
  is load-bearing — without it the module gate 403s live). **Review (money+tenancy): PASS; 3
  warnings fixed** — W1 console minted a fresh idempotency key per click (ambiguous-failure retry
  = double charge) → key now minted once per payment attempt and reused; W2 concurrent capture's
  optimistic-lock loser 500'd → recovered to an idempotent 200 + a two-thread race test proving
  exactly one `SaleRecorded`; W3 staff-profile writes were cashier-reachable (the employee link
  routes commission) → owner/manager-only 403 (packages/addons stay at restaurant-menu parity).
  Also: zero-grand-total checkout rejected (S1); found+fixed a Hibernate mismatch (`MoneyEmbeddable`
  defaults `amount_minor` vs the tables' `price_minor` → `@AttributeOverride` on 3 entities).
  Deviations kept for house-pattern parity: idempotency key in the body (not the header) with no
  payload-mismatch 409, matching `WashService`/restaurant. Full gate green; next = Phase 2
  (barbershop service + POS via /new-service, entitlement module rollout).
- **Fixed-asset disposal — gain/loss on disposal (2026-07-30, ADR 0022)** — selling/scrapping an
  asset now books correctly: `POST /api/v1/assets/{id}/dispose` (Idempotency-Key required; 201/200
  replay; one-shot 409 `asset-already-disposed`) posts the derecognition ad-hoc — Dr 1900 proceeds
  + Dr 1590 accumulated / Cr 1500 cost, plug Cr **4200 Gain** / Dr **5600 Loss** (new
  GAIN/LOSS_ON_DISPOSAL roles, V36) — into the CURRENT period (disposalDate = metadata; backdating
  would restate closed/filed periods). Guards: depreciation-in-step PER ASSET (posted-line COUNT ==
  months due before the posting period + no line ≥ it — runs may skip months, so MAX(period) proves
  nothing; disposal month gets no depreciation) → 409 `asset-depreciation-behind`. Disposal facts
  FROZEN onto fixed_asset (V36 all-or-nothing CHECK); both asset writers take the shared
  `company:ASSET_POSTING` advisory lock first, so a dispose can never interleave with a run posting
  the same asset's depreciation. **CashFlowReader reclassifies disposal periods from the frozen
  columns**: pure capex on 1500, ONE `DISPOSAL_PROCEEDS` investing inflow, pure depreciation
  add-back on 1590, gain/loss backed out of operating GROSS — the identity nets to zero so the
  exact-reconciliation assert still closes (and now cross-checks frozen vs posted). Register shows
  DISPOSED + zero book value + proceeds; console: status column + Dispose dialog (date + sale
  price, 0 = write-off) + localized proceeds line on the cash-flow statement. **512 finance tests
  green** (17 new: posting-legs units incl. write-off, controller slice, 2 lifecycle E2Es with
  cash-flow assertions + guard 409s vs real RLS Postgres, dispose×2 race + dispose-vs-run race
  proving the shared lock). SME-gated: disposal-month convention, 4200/5600 codes, PPN 16D not
  computed.
- **Multi-company ownership — one login, 1..N businesses (2026-07-30, ADR 0021)** — each business is
  its own legal entity = its own company with isolated books; a login can now hold MANY. The
  `company_id` KC claim became **multivalued** (the allowed set, first = default; mapper change in
  all 4 realm-JSON copies; readers accept `string|string[]` so old tokens work; RUNBOOK: a LIVE KC
  needs the mapper updated via admin API). The client picks the ACTIVE company per request via
  `X-Company-Id`, **validated against the token's set at the gateway AND at every service edge**
  (`TenantBindingFilter`) — in-set → bind, absent → first, outside → 403 `invalid-company-selection`
  (a spoofed header is now rejected outright, strictly stronger than the old silent overwrite; two
  spoof tests updated to the new contract). Exactly one tenant binds per request; RLS/books
  untouched; no re-auth per switch; no new sync edges. `RateLimitFilter` keys on the first company.
  **org-service**: `KeycloakUser.companyIds` (list) + `belongsTo` guards (team/page-grant/outlet —
  a multi-company login is manageable from EACH company's team page; KC `q=company_id:` search
  matches multi-valued attributes, proven); `addCompanyToUser`/`removeCompanyFromUser`
  (GET-merge-PUT preserving other attributes, idempotent); **`POST /companies` now BINDS its
  creator** (membership-first + compensating removal, mirroring signup — this FIXES the oidc
  onboarding loop where a created company was unreachable); new **`GET /api/v1/companies/mine`**
  (tenant-optional; callAs per verified-claim id; dangling memberships skipped); the gateway
  companies route uses a tenant-OPTIONAL tenant-filter variant so a 0-company token reaches the
  bootstrap endpoints. **Console**: `auth.companyIds` + `auth.refresh()` (silent renew);
  session = company LIST + per-login persisted active pointer; every call sends the active company
  as the validated `X-Company-Id`; the header pill is a **switcher dropdown** + "Add business" →
  the onboarding wizard (oidc: create → silent token renew → activate; dev: localStorage list).
  QueryKeys already carry companyId → switching re-fetches everything. **Review (auth/tenancy
  critical): the tenant-binding chain PASSED; one blocking regression found & fixed** — the session
  bootstrap moved to `/companies/mine`, which fell through to the dashboard-only `/companies/**`
  route and locked CASHIERS out of the POS → dedicated highest-precedence `ME_ROLES` route + a
  cashier-bootstrap pinning test; the KC GET-merge-PUT two-tab race + the renew-failure 403 window
  are documented ADR residuals (wizard already guards in-flight submits; renew now retried once).
  **Verified: 75 gateway + 13 libs/security + 186 org tests green** (incl.
  GatewayCompanySelectionTest — default-first / in-set honoured / out-of-set 403 / scalar
  back-compat / cashier `/mine` bootstrap; the libs defense-in-depth proof now
  includes the multi-company token defaulting to A and a validated selection re-binding RLS to B,
  end to end vs real KC + RLS Postgres; MultiCompanyMembershipAcceptanceTest — create-binds-creator
  → fresh-token claim → /mine → per-request selection → foreign-selection 403 → multi-valued team
  search) + console build. **Limitation (documented):** realm roles are global per login (owner
  everywhere) — per-company roles deferred; invitees stay single-company; "add existing login to my
  company" deferred (the primitive exists).
- **Fixed assets & deferrals — Phase 6, the FINAL pillar of the Odoo accounting-parity program
  (2026-07-30, ADR 0020)** — the system's first TIME-BASED SCHEDULED postings. New `finance/assets/`:
  `fixed_asset` + `deferral` + `amortization_run`(+`_line`) (V34, FORCE RLS) and GL config V35 (COA
  1400 Prepaid/1500 FA-Cost/1590 Accum-Dep/2400 Deferred-Rev/5500 Dep-Expense + 5 new AccountRoles;
  no posting_template — the ad-hoc Bank/Tax path). **Acquire** posts Dr 1500 / Cr 1900 (capex through
  clearing; source=asset id); **deferrals** post their opening pair (prepaid Dr 1400/Cr 1900; deferred
  revenue Dr 1900/Cr 2400). **The amortization run** (POST /api/v1/assets/runs {period}) posts EVERY
  due item's month-k share in ONE transaction — asset: Dr 5500/Cr 1590; prepaid: Dr 5000/Cr 1400;
  deferred revenue: Dr 2400/Cr 4000 — sealed once per (company, period) by the tax-filing pattern
  (advisory lock → findByPeriod no-op → uq_amortization_run; per-line UNIQUE source_event_id backstop;
  re-run → 200 no-op). **Straight-line exact-sum by cumulative rounding**: month k posts
  round(B·k/N)−round(B·(k−1)/N) (HALF_EVEN via Money.mulDiv) → Σ = cost−salvage EXACTLY (remainder
  spread evenly; a zero month records its line with no entry; periods independent → missed months
  catch up by just running them). Start convention: assets begin the month AFTER acquisition
  (ILLUSTRATIVE/SME-gated); deferrals at their chosen month. Book values = run-line sub-ledger SUMs
  (no per-item GL query). **CashFlowReader gains the INVESTING section**: an investing account set
  role-resolved from FIXED_ASSET_COST (mirrors the cash-role pattern) — capex → INVESTING while 1590
  deliberately stays OPERATING (the non-cash add-back); the exact reconciliation invariant holds.
  Endpoints `/api/v1/assets/**` + `/api/v1/deferrals/**` (DASHBOARD_ROLES, 2 new gateway routes).
  Console `features/assets/` (register + acquire dialog + Run-depreciation control + run history;
  Deferrals list + create dialog), en/id. **Code-review FAIL→fixed** (money/tenancy, fresh context):
  C-1 — acquire/create-deferral posted money with no idempotency guard (a retry double-posted capex)
  → both now REQUIRE an `Idempotency-Key` (keyless → 400) with a per-(company, key) replay probe +
  `UNIQUE(company_id, idempotency_key)` backstop (V34), replay → 200 with the original, nothing
  re-posted (the AR/AP payment pattern); W-1 — a backdated start into an already-run (sealed) month
  would silently under-amortize forever → writers now reject a `start_period ≤ MAX(run period)`
  (400); W-2 — unbounded `acquisitionDate` → `@PastOrPresent` + a year ≥ 1900 bound (clear 400, not
  a misleading 409/500); + S-1: `RunReader.detail` now a direct projection lookup. The schedule
  math, tenancy/RLS, cash-flow classifier and route ordering were all confirmed sound.
  **Verified: 495 finance + 69 gateway tests green** —
  DepreciationScheduleTest (exact-sum for awkward bases incl. 1-minor-unit/12 + index edges),
  AmortizationPostingTest (all 6 entry shapes balanced), AssetControllerTest (201/200/400/404/409/422
  + idempotent re-run 200), AssetTenancyIsolationTest E2E (acquire+2 deferrals → run month 1 →
  re-run no-op → run month 2 adds the asset's first depreciation → registers/book values correct →
  cash-flow shows capex under INVESTING + reconciles → RLS-isolated), AmortizationRunConcurrencyTest
  (two-thread race → exactly one run + share posted once). + console `npm run build`. Built in
  `C:\native-ar-build`. **THE ACCOUNTING PROGRAM IS COMPLETE: (1) AR ✓ (2) AP ✓ (3) Bank ✓ (4) Tax ✓
  (5) Cash-flow & budgets ✓ (6) Fixed assets & deferrals ✓ → ~90% of Odoo accounting**, SME-gated
  where real law/COA is required. Deferred: disposal/gain-loss; capitalize-from-AP-bill;
  declining-balance; partial-month proration; auto-scheduler.
- **Cash-flow statement + Budgets — Phase 5 of the Odoo accounting-parity program (2026-07-29, ADR
  0019)** — the two remaining reporting/planning pieces. **Neither posts to the GL** (cash flow is
  GL-derived; budgets compare against GL actuals), so no money-critical journal posting. **(A) Cash Flow
  Statement** (indirect, in `statements/`, NO migration / NO new gateway route — extends the
  already-routed `/api/v1/statements/**`): `glTrialBalance(period)` IS the per-account net movement, so
  `m = debit − credit`; cash & equivalents resolved from the `BANK`/`CASH_CLEARING`/`QRIS_CLEARING`/
  `CARD_CLEARING` roles; net income + working-capital adjustments (`credit − debit` per non-cash BS
  account — asset ↑ uses cash, liability ↑ provides it) classified operating/investing/financing;
  `netChangeInCash` **reconciles exactly** to the cash-account movement by double-entry (the reader
  asserts it, like BalanceSheetReader's balance check). `CashFlowReader` + `CashFlowResponse` +
  `/api/v1/statements/cash-flow`. **(B) Budgets** (`finance/budget/`, V33 `budget`+`budget_line`
  parent/child, FORCE RLS, per-month): a named monthly set of `account_code → planned amount_minor`
  (FK to chart_of_account, `amount ≥ 0`); the **budget-vs-actual** report joins each line's plan against
  the account's type-normal GL actual for the period → variance = actual − planned (no new "actual"
  store). `BudgetWriter` (create/delete, validates the account exists → 400), `BudgetReader`,
  `BudgetActualReader`, `AccountCatalogReader` (the COA picker), `/api/v1/budgets/**` (new gateway
  `budgetsRoute`, DASHBOARD_ROLES). Console: `statements/CashFlow.tsx` (reports grant) + `features/budget/`
  (list + create dialog with account picker + the variance report, plain canDashboard), en/id.
  **Verified: 462 finance + 66 gateway tests green** (CashFlowReaderTest — net income + working-capital
  adjustments + the exact reconciliation + an unbalanced set rejected; StatementsControllerTest cash-flow
  200/204/400; BudgetControllerTest 201/200/404/400/409/422; BudgetTenancyIsolationTest E2E — variance vs
  seeded AR/AP actuals, RLS-isolated, unknown-account 400, delete) + console `npm run build`. Built in
  the no-space worktree `C:\native-ar-build`. **Code-review PASS** (tenancy/RLS, fresh context) — budget
  RLS clean, cash-flow reconciliation a provable identity; one warning fixed (budget-vs-actual now
  guards the budget currency against the GL currency → 422, no cross-currency variance) + the account
  picker restricted to P&L accounts. **SME gate:** the cash-flow activity classification
  (current-vs-non-current, operating-vs-financing) is illustrative — everything is operating today (no
  fixed assets / financing). Program → **~85% Odoo accounting: (1) AR ✓ (2) AP ✓ (3) Bank ✓ (4) Tax ✓
  (5) Cash-flow & budgets ✓**; remaining (6) Fixed assets & deferrals. Deferred: multi-month/annual
  budgets; budget line editing (create+delete only); direct-method cash flow; comparative columns.
- **Tax / PPN — Phase 4 of the Odoo accounting-parity program (2026-07-29, ADR 0017)** — AR accrues
  output VAT to `2200` and AP input VAT to `1300` (illustrative 11%), but nothing turned those into a
  **tax return**. This adds the PPN (Indonesian VAT) pillar: a GL-derived **VAT report** (output =
  credit-net of `2200`, input = debit-net of `1300`, **net = output − input** → PAYABLE/CREDITABLE),
  an idempotent **File return** that posts the period-end netting entry and seals the period, and a
  **Settle** posting when the net is paid. New `finance/tax/` feature: V31 (`tax_filing` seal,
  UNIQUE(company,period,tax_type), FORCE RLS) + V32 (COA `2300 VAT Payable` + `1310 VAT Credit
  Carryforward` + role maps; no posting_template). `VatReturnReader` wraps `GlTrialBalanceReader`
  (inheriting its balance + single-currency asserts) and resolves VAT_OUTPUT/VAT_INPUT via
  `RoleAccountResolver`. Filing posts an **ad-hoc balanced netting entry into the RETURN period**
  (built directly in `TaxFilingWriter`, no posting_template/EventKind — the Bank approach, because the
  net leg flips side): `Dr 2200 (output) / Cr 1300 (input) / Cr 2300 (net payable)` or `Dr 1310 (net
  creditable)`, zero legs omitted; once filed the report reads the sealed `tax_filing` snapshot (the
  netting cleared the period's 2200/1300). Idempotent via an advisory lock + `findByPeriodAndTaxType`
  probe + UNIQUE `source_event_id`=filing id (re-file → no-op). **Settle** posts `Dr 2300 / Cr
  CASH_CLEARING (1900)` (routes the payment through the same clearing every cash movement uses), a
  one-shot FILED→SETTLED transition (status guard + UNIQUE source_event_id, no Idempotency-Key —
  bank rationale); CREDITABLE/zero-net is terminal at FILED. Single-base-currency guard on every post
  (→422). **e-Faktur** = a JSON endpoint of the period's output tax invoices → the console renders a
  CSV download (real DJP API deferred). New `AccountRole.VAT_PAYABLE`/`VAT_CREDIT_CARRYFORWARD`.
  Endpoints `/api/v1/tax/**` (DASHBOARD_ROLES). Console `features/tax/` (VAT report KPIs + File/Settle
  + e-Faktur export + filing history, en/id). Balance sheet gains `2300`/`1310`; the period's
  `2200`/`1300` draw to zero on filing. **Verified: 442 finance + 64 gateway tests green** (incl.
  TaxFilingPostingTest — the netting legs for payable/creditable/nil + settlement + the state machine;
  TaxControllerTest 200/201/400/404/409/422; TaxFilingTenancyIsolationTest E2E: file → 2200/1300
  cleared + 2300=660,000, settle → 2300=0 + 1900 credited, re-file idempotent 200, RLS-isolated;
  **TaxFilingConcurrencyTest** — two-thread races prove file()/settle() are each exactly-once under
  contention). **Code-review PASS** (money/tenancy, fresh context); two warnings fixed — idempotent
  re-file returns 200 not 201 (ENGINEERING-STANDARDS §1.1 + the close sibling), and the mandated §3.2
  concurrency proof added. Built in the no-space worktree `C:\native-ar-build`. **Most SME-gated
  phase** — rate, carryforward policy, PKP
  status, e-Faktur schema, filing deadlines all flagged illustrative (ADR 0017). Program → **~80% Odoo
  accounting: (1) AR ✓ (2) AP ✓ (3) Bank ✓ (4) Tax ✓**, (5) Cash-flow & budgets, (6) Fixed assets &
  deferrals. Deferred: amended returns / late postings to a sealed period; net-void periods; PPh +
  other tax types; the real DJP e-Faktur integration.
- **Bank & Reconciliation — Phase 3 of the Odoo accounting-parity program (2026-07-29, ADR 0016)** —
  AR receipts, AP payments, AND POS sales all post to CASH_CLEARING (`1900`) = cash in transit; this
  adds real **bank accounts** and a **reconciliation** flow that settles that clearing balance against
  bank statement lines (non-invasive — AR/AP/POS untouched). New `finance/bank/` feature: `bank_account`
  + `bank_statement_line` (signed `amount_minor`), V29 (tables) + V30 (COA `1000 Bank`/`4100 Interest
  Income`/`5400 Bank Charges` + role maps). **Reconcile-by-category** (the auto/line-item matching
  engine is deferred): reconciling a line posts an **ad-hoc balanced 2-line JournalEntry** built
  directly in `ReconciliationWriter` via `RoleAccountResolver` (no posting_template, no new EventKind):
  deposit/CLEARING → Dr BANK(1000) / Cr CASH_CLEARING(1900) [the sweep]; withdrawal/CLEARING → the
  reverse; withdrawal/BANK_FEE → Dr 5400 / Cr 1000; deposit/INTEREST → Dr 1000 / Cr 4100. Category is
  gated by direction (INTEREST only on a deposit, BANK_FEE only on a withdrawal → 400). One shared
  `BANK` control account (1000) — per-account balances in the sub-ledger, mirroring AR 1200 / AP 2000.
  Idempotent via the UNRECONCILED→RECONCILED status guard + UNIQUE `source_event_id`=lineId (re-reconcile
  → 409; no Idempotency-Key needed — it's a state transition, not a payment). Single-base-currency guard
  on the post (→422); DTO-only controllers; Location/​@Pattern/​LIMIT; all tables FORCE RLS. The
  reconciliation report = per-account bank balance (Σ reconciled lines) + the CASH_CLEARING GL balance
  (`SUM(debit−credit)` on 1900 = cash-in-transit awaiting sweep) + unreconciled lines. Endpoints
  `/api/v1/bank-accounts/**` + `/api/v1/bank/**` (DASHBOARD_ROLES). Balance sheet gains `1000 Bank`;
  `1900 CASH_CLEARING` DRAWS DOWN as lines reconcile (residual = true cash-in-transit); income statement
  gains 4100/5400. Console `features/bank/` (BankAccounts + a reconcile workspace with import + per-line
  reconcile + the report KPIs). **Verified: 417 finance + 62 gateway tests green** (incl. ReconcilePostingTest
  the 4 direction×category legs + BankTenancyIsolationTest E2E: +5,000,000 deposit swept + −25,000 fee →
  bank 4,975,000, clearing −5,000,000, RLS-isolated). Built in the no-space worktree `C:\native-ar-build`.
  Program → ~70% Odoo accounting: **(1) AR ✓ (2) AP ✓ (3) Bank & reconciliation ✓**, (4) Tax/e-invoicing,
  (5) Cash-flow & budgets, (6) Fixed assets & deferrals. Deferred: the line-item matching engine; CSV/
  bank-feed import; per-account GL accounts; multi-ccy bank accounts.
- **Accounts Payable — Phase 2 of the Odoo accounting-parity program (2026-07-29, ADR 0015)** — the
  vendor-facing MIRROR of AR: vendors, bills (draft → **posted** → (partially) paid | void),
  bill-payments, and an AP aging report, in **finance-service** (`ap/` feature), posting to the
  double-entry GL in-transaction via the existing `buildEntryFromBreakdown` (no new posting code; V28
  adds a `NET` amount_basis). **The GL sides are the CONTRA of AR** (a bill is a liability + expense,
  not an asset + revenue): **post** Dr `EXPENSE`(net=5000) / Dr `VAT_INPUT`(tax=1300, a recoverable
  ASSET) / Cr `AP`(total=2000); **payment** Dr AP / Cr cash-clearing; **void** the contra. New COA
  `2000 AP`(LIABILITY) + `1300 VAT Input`(ASSET); new `AccountRole.AP`/`VAT_INPUT` +
  `EventKind.BILL_POSTED`/`BILL_PAYMENT_MADE`/`BILL_VOID`. Single base currency; input PPN 11% is
  ILLUSTRATIVE (SME-gated); bill net posts to a single expense account (per-line cost centres
  deferred). Migrations **V27** (AP tables) + **V28** (GL config). **Every Phase-1 review fix baked in
  from line 1** (AR needed two rounds; AP got them in one): payment `Idempotency-Key` **required** +
  UNIQUE scoped to `(company, bill, key)` in V27; single-base-currency guard on **post + payment +
  void** (→422); aging mixed-ccy guard; DTO-only controllers, `Location` on 201s, status `@Pattern`,
  `LIMIT 500` on lists. AP flows into the balance sheet (2000 AP liability + 1300 VAT-input asset) +
  income statement (5000 expense) automatically. **Path collision resolved:** `/api/v1/bills` was
  already the restaurant "open bills" POS route, so AP bills are namespaced **`/api/v1/ap/bills`**
  (gateway `/api/v1/ap/**`; vendors at `/api/v1/vendors`); the AP `AgingController` was renamed
  `ApAgingController` to avoid a Spring bean-name clash with AR's. Console `features/ap/`
  (Vendors/BillsList/BillDetail/NewBill/ApAging) mirrors `features/ar/` (idempotency-key sent
  per-submit, overpay validation, aging invalidation carried forward). **Verified: 396 finance +
  62 gateway tests green** (incl. `ApTenancyIsolationTest` end-to-end RLS + `ApWriterIntegrationTest`
  idempotency+currency, mirroring the AR suite) + console `npm run build` green. Built in the no-space
  worktree `C:\native-ar-build` (space in `C:\Project 2` breaks Gradle), synced back. Program →
  ~100% Odoo accounting: **(1) AR ✓ (2) AP ✓**, (3) Bank & reconciliation, (4) Tax/e-invoicing,
  (5) Cash-flow & budgets, (6) Fixed assets & deferrals. Residual (both AR+AP): the sale/expense
  currency guard reads `consolidated_pnl` while AR/AP read `journal_entry` — a unified guard across
  all producers stays a tracked follow-up (unreachable while base ccy immutable+single).
- **Accounts Receivable — Phase 1 of the Odoo accounting-parity program (2026-07-28, ADR 0014)** —
  the first transactional AR layer + the first customer/party dimension in Native, all in
  **finance-service** (`ar/` feature). Customers, invoices (draft → issue → (partially) paid | void),
  payments/receipts, and an AR aging report. Invoices post to the existing double-entry GL **in the
  same transaction** as the sub-ledger write (no cross-service sync) via a new generic
  `JournalPostingService.buildEntryFromBreakdown` (an `amount_basis → Money` map reusing the
  `GROSS`/`GROSS_REVENUE`/`TAX` vocabulary — the SALE path untouched): **issue** Dr AR (1200) / Cr
  revenue (4000, net) / Cr output VAT (2200, tax, zero-omitted when non-taxable); **payment** Dr
  cash-clearing / Cr AR; **void** the contra. New `AccountRole.AR`/`VAT_OUTPUT` (already anticipated
  in the enum javadoc) + `EventKind.INVOICE_ISSUED`/`PAYMENT_RECEIVED`/`INVOICE_VOID`; illustrative
  COA/roles/templates seeded in **V25** (V24 = the four Auditable + FORCE-RLS AR tables). AR flows
  into the GL-derived **income statement + balance sheet** automatically (1200 AR = ASSET); it does
  NOT feed the dimensional POS `/pnl` dashboard (deliberate — the GL statements are authoritative).
  Reads are native-query projections (RLS-scoped, no `WHERE company_id`); aging buckets outstanding
  invoices by days-overdue in the reader. Gateway routes `/api/v1/customers|invoices|ar/**`
  (DASHBOARD_ROLES). Decisions (ADR 0014): AR is finance-local; customer is finance-local; sub-ledger
  drives aging (the GL journal has no counterparty dimension); **single-currency** (base ccy);
  **output tax flagged-illustrative** (PPN 11% placeholder, `uses_illustrative_rules` badged
  "Estimated", SME-gated like POS); **events deferred to Phase 1b** (no mail transport yet, so no new
  event added to the catalog). Invoice number = per-tenant `INV-NNNNN` via a `pg_advisory_xact_lock` +
  RLS-scoped COUNT (UNIQUE backstop). **Verified: the whole finance suite (367 tests) green**,
  including `ArTenancyIsolationTest` — an end-to-end Testcontainers test that drives create → issue
  (11% VAT: 1,000,000 → 1,110,000) → part-pay (300,000, outstanding 810,000) → aging against real
  PostgreSQL as the unprivileged `app_user` and proves cross-tenant RLS invisibility. ArchUnit
  layering + web-slice contract tests (201/400/404/409 RFC-7807) + gateway build green. **Deliberate
  Phase-1 exclusions** (later sub-steps): multi-currency invoices, credit notes, recurring invoices,
  PDF/email delivery, dunning, fractional line quantities. **Console AR feature built** (Customers /
  Invoices list+detail / New-invoice / Aging; additive to the console over the page-grants WIP, npm
  build green). **Two adversarial code-review rounds** (backend + frontend) landed fixes: payment
  idempotency (`Idempotency-Key` required + scoped per-invoice, V26 unique index; console sends a
  fresh key per submit); the single-base-currency guard on issue/void/payment (M1/W-1) → 422; aging
  mixed-currency guard; overpay client-validation + aging-cache invalidation. **Residual follow-ups
  (tracked):** the sale/expense currency guard reads `consolidated_pnl` while AR reads `journal_entry`
  — a unified single-currency-GL guard across ALL producers is deferred (defense-in-depth; unreachable
  in a correct single-currency tenant, base ccy immutable); AR list/aging pagination envelope (interim
  `LIMIT 500` on the two lists; aging still aggregates in-memory). Program roadmap → ~100% Odoo
  accounting: **(1) AR ✓**, (2) AP, (3) Bank & reconciliation, (4) Tax/e-invoicing, (5) Cash-flow &
  budgets, (6) Fixed assets & deferrals.
- **Employee logins + self-service /me + page grants + own-sales commission (2026-07-28)** — HR
  employees became loggable-in users with a dashboard of their own and a commission on the sales they
  ring. Six phases (A–F). **A/B — logins:** reused the org-service invite flow to create a Keycloak
  login for an employee (temp password shown once, forced change), optionally POS-capable via a
  checkbox (roles `[employee]` or `[employee, cashier]`; invite gained a `roles: string[]`, assigned
  sequentially). A new `employee` realm role threads through gateway `BUSINESS_ROLES`, org
  `ALLOWED_ROLES`, and the console. employee V7 = a nullable `user_id VARCHAR(64)` + partial-unique
  index; `POST/DELETE /api/v1/employees/{id}/login-link` sets/clears it. **The Keycloak `sub` is the
  universal join key** — gateway `X-Actor` = `jwt.getSubject()` = `TenantContext.actor()` =
  `sale.created_by` = `user_outlet_assignment.user_id` = `employee.user_id`; the console OIDC auth had
  to be taught to RETAIN `sub` (it defaulted to `preferred_username`). **C — /me:** gateway routes
  `/api/v1/me/**` (ME_ROLES incl. employee); employee-service `me` feature resolves the caller
  EXCLUSIVELY from actor→V7 link (never a request param, so a caller reads only their OWN rows). NIK/
  bank stay MASKED even to the person; only payslip AMOUNTS decrypt — this is the FIRST caller of the
  long-dormant `findPayslipAuthorized`. Console `/me` full-screen dashboard. **D — page grants (ADR
  0013):** org V8 `user_page_grant`, subtractive UI-level gating (`GET /users/me/pages` →
  `{mode, pageKeys}`; `GET|PUT /users/{id}/pages`). **Decision: grants NARROW the console; roles
  remain the API authz boundary** (no event — no consumer; staleness is a fetch away, not baked into a
  JWT). **E — commission = X% of the employee's OWN sales.** Three sub-decisions locked with the user:
  own-sales % (not team/pool), REAL payslip amounts on /me, POS via optional checkbox. **The
  load-bearing correctness fix:** the `MetricPublished` consumer projection was last-write-wins
  (`applyValue` REPLACED the natural-key row) — wrong for any per-unit-of-activity producer; carwash
  already undercounted same-day washes and a per-sale feed would collapse a day to its last ticket.
  Changed to delta-ACCUMULATE (`applyDelta`, `value += delta`), safe under the event-UUID idempotency
  guard. restaurant became the **second `MetricPublished` producer** (no schema change — the shared
  avsc already lists the `employee` grain): every sale emits `sales_amount`@`employee`, subject = the
  cashier's sub, in the SaleRecorded transaction, at both `SaleWriter` choke points. The engine gained
  `PERCENT_OF_METRIC` (reusing `earning_rule`'s existing `percent_basis_points` + `metric_key` columns
  — **no migration**): the run sums the employee's own-sub metric rows for the month and applies the
  rate via `Money.applyBasisPoints`, reusing the single-period-grain guard (mixed grain → throw, never
  double-count). Config API `GET/POST/DELETE .../compensation/{pkgId}/commission` (non-PII bp echoed;
  open-duplicate → 409). `GET /api/v1/me/sales` previews rate×sales (labelled a preview — the payslip
  is authoritative; currency from the open package, amount never read). Console: a commission control
  in the salary dialog + a sales card on /me. **Dev caveat:** the header-trust recipe's fixed actor is
  not a UUID, so `SaleWriter` skips the metric (subject_id is a UUID column); commission accrues only
  over OIDC (real logins carry a UUID sub). **F — cleanup:** discovered restaurant-service carried a
  backlog of pre-existing google-java-format violations (committed via worktree builds that skipped
  `spotlessCheck`); isolated the reformat into its own `style` commit so the feature diff stayed clean.
  Shipped as five commits (1 style + E1 delta + E2 producer + E3–E5 backend + console); each build
  green.
- **Employee management + payroll in the console (2026-07-28)** — the org-unit hub gains an
  **Employees** tab (Odoo-style HR records: create employee→contract→assignment chain with role
  presets [free-text `assignment.role`, no new aggregate], assign-to-outlet, end-assignment,
  masked salary packages, terminate) and a real **Payroll** tab (one-click illustrative setup,
  per-unit run scope, run history with KPIs, masked payslips, labor-cost-by-outlet bars, loud
  ILLUSTRATIVE banner whenever provenance ≠ OFFICIAL). employee-service — which already had the
  engine — gained the console-facing APIs: employee LIST (`?orgUnitIds=` — **the BU rollup is
  CLIENT-computed** from the org tree the console already has; `org_unit_projection` deliberately
  gains no parent_id), assignment END (re-emits `AssignmentChanged` with the new `effective_to`;
  consumers upsert by id — **zero event/schema changes in the whole increment**), the org-unit
  legal-employer lookup, compensation CRUD (create validates contract ownership + **overlap→409**
  because the run SUMS covering packages [double-pay guard]; every read masked, the list
  projection never selects `base_pay_enc`), payroll-setup status/seed (delegating to the existing
  idempotent illustrative seeder), run list per period, the **aggregated** allocation summary
  (SUM per outlet+GL — per-employee rows would leak salary; all-zeros sentinel = UNALLOCATED),
  and the payslip index. Gateway routes all three prefixes DASHBOARD_ROLES (HR is never a POS
  surface). Decisions: runs stay COMPANY-scoped (period+run_seq) — a unit tab runs for its
  employees' ids and lists all company runs; re-run = an ADDITIONAL posting (no reversal event
  yet — UI warns, follow-up); salary reads masked-only (authorized-HR read deferred); HR
  employees separate from Keycloak login users (People tab relabeled "App access"). V6 =
  read-path indexes only. Adversarial fresh-context review: **FAIL → fixed same day.** The
  CRITICAL: the console ran payroll per-unit, but finance treats (period, run_seq) as
  **company-wide supersession** — a higher run REVERSES every earlier ACTIVE run's labor postings,
  so a second unit's run would erase the first unit's labor cost off the ledger. Fix: **a console
  payroll run is always COMPANY-WIDE** (every payable employee regardless of which unit tab; the
  gate checks every active BU seal), and the re-run copy now states the supersession truthfully
  (the MAJOR was that it claimed the exact opposite). Also fixed: same-day salary-replace guard
  (was a dead-end 400), FAILED-run rows labeled in history, ILIKE wildcard escaping on the name
  search. Follow-ups noted: a binding test for the frontend ISO-exponent mirror of Money;
  the k=1 single-employee outlet allocation residual stays as documented/signed-off. Separately
  hardened all user input (server whitelists: NIK exactly 16 digits, bank 6–32 digits, PTKP
  TK0–3/K0–3, employment-type enum, role ≤128, name ≤255 — malformed PII is rejected BEFORE
  encryption; console mirrors them inline and every disabled Save now says WHY, incl. the
  unit-not-synced-to-HR state). Dev gotcha: employee-service's `org_unit_projection` only
  hydrates from events — a reset employee DB misses org units whose events left Kafka; dev
  backfill = COPY org_unit rows across (superuser bypasses RLS).
- **Business-unit verticals — restaurant | carwash | barbershop (2026-07-28)** — `org_unit.vertical`
  (org V6: nullable VARCHAR(32); backfill existing BUs → `restaurant`. **The V6 backfill was
  silently swallowed by FORCE RLS** — Flyway runs as the table owner with no tenant GUC, the
  policy filtered the UPDATE to zero rows, and Flyway reported success; caught on the live dev DB,
  invisible to acceptance tests (they only create rows post-migration). V7 redoes it inside the
  `NO FORCE ROW LEVEL SECURITY` escape hatch — restaurant-service V6 is the fleet precedent —
  pinned by `VerticalBackfillMigrationTest`, which migrates→V5, plants a pre-existing BU over the
  BYPASSRLS admin connection, migrates→latest as the owner role, and asserts the backfill landed.
  **Lesson: any migration UPDATE on an RLS-forced table needs the NO FORCE hatch or a
  self-checking follow-up like V2's `SET NOT NULL`.**): REQUIRED on every
  BUSINESS_UNIT creation path (signup, create-company, add-business, org-units), rejected for
  outlet/team, IMMUTABLE after create (like base currency: `updatable = false`, no PATCH path).
  **Casing decision:** stored/emitted/requested as LOWERCASE module-key strings via a JPA
  `AttributeConverter` (never `@Enumerated`, which would silently store the UPPERCASE enum name —
  the exact casing-trap class the hub increment hit with `org_unit.type`), deliberately aligned with
  entitlement-service's `module_catalog.module_key` vocabulary — though entitlements remain a
  SEPARATE company-level concept, untouched this increment. `OrgUnitCreated/Changed` gained an
  optional `vertical` (`["null","string"] default null`, appended LAST — positional-decode safety,
  pinned by an old-reader contract test); consumers stay opaque. The POS learns the vertical via
  `GET /api/v1/outlets` (`{id, name, vertical}` — parent-BU LEFT JOIN; cashiers can't read the
  dashboard-only org-units endpoint). Console: vertical ChoiceCards on signup/onboarding, a
  BU-only select in the org-tree add dialog, tree/hub badges, and a `requiredVertical` gate on
  POS/Kitchen/Menu that renders a branded coming-soon panel (embedded outlet picker — never traps
  a multi-vertical user) for carwash/barbershop outlets. **Client fails OPEN to restaurant on a
  null vertical** (backfill guarantees it server-side; never brick a POS terminal on cache
  staleness — do not "fix" this). No new ADR: the whitelist + ADR-0012-style semantics are
  recorded here; a real carwash/barbershop POS is a later increment. Adversarial fresh-context
  review: **PASS**, no critical/major (RLS self-join verified leak-free — the policy scopes both
  aliases); follow-ups landed: an OrgUnitChanged old-reader pin for contract-test parity and a
  whitelist-copies cross-link on the Vertical enum. Live-proven E2E: V7 backfill on the dev DB,
  carwash signup → coming-soon POS (EN+ID), outlet switch → full POS, and finance's
  pre-increment jar consuming the new-field events.
- **Org-unit hub — Odoo-style record detail (2026-07-28)** — clicking a BUSINESS_UNIT or OUTLET
  in `/org` now opens `/org/:unitId` (the app's first param route): breadcrumb, sheet header
  (type badge + status + rename/(de|re)activate via dialogs lifted into `features/org/parts.tsx`),
  Odoo-style smart buttons (Outlets / People / This-period Net; an outlet shows a parent-unit
  related-record link), and Segmented notebook tabs. **Overview** = per-unit P&L from the new
  finance `unitpnl` feature (`GET /api/v1/pnl/org-units/{id}` — ONE native query rolls up the unit
  + child outlets via `org_unit_ref.parent_id` [captured in V22, queried for the first time] LEFT
  JOINed to `ledger_posting` with signed FILTER sums; V23 adds the two access-path indexes; 204 /
  zeros-with-currency-hint mirror PnlController; the labor UNALLOCATED sentinel is excluded
  structurally). **People** = new org `GET /api/v1/org-units/{id}/users` (assignments under the
  unit's outlet set in-SQL, identity joined client-side from the cached team list; 404
  unknown/foreign anti-enumeration, 400 TEAM). **Outlets** tab manages children inline (Add preset
  to the BU). **Expenses/Payroll** ship as coming-soon panels — no expense producer exists and
  employee-service is not gateway-routed. Zero gateway work (both endpoints under already-routed
  prefixes). Live-E2E-caught fix: `org_unit_ref.type` stores the EVENT value — the enum NAME,
  UPPERCASE (`OrgUnitCreatedSchema` emits `.name()`), while V22 comments + one contract fixture
  misleadingly suggested lowercase; the rollup predicate is now case-insensitive. Also deflaked
  `OrgUnitRefConsumeAcceptanceTest` (its cross-topic drain marker guaranteed no ordering; the
  tests now await the expected state). Review (fresh context) FAILED once on a REAL money bug the
  E2E's untaxed sale could not show: the rollup summed `ledger_posting.amount_minor` — the GRAND
  TOTAL (incl. service charge + tax) — while every other surface reports NET revenue; fixed by
  sourcing the revenue leg from the `outlet_revenue` NET accumulator (the same source
  `/pnl/outlets` serves; reversal netting lives in the accumulator) with expenses still signed-
  summed from the ledger, pinned by a regression test posting a fully-taxed Phase-2 sale
  (106k gross / 90k net). Re-review deltas also added the closed-assignment exclusion test and a
  smart-button error state. Follow-ups: expense-entry slice (producer + UI) and the HR/payroll
  console (needs gateway routing + list endpoints + tax-SME statutory figures) unlock the two
  disabled tabs; W2 (positively re-prove duplicate-delivery consumption in the deflaked consume
  test, e.g. via processed_event) noted as nice-to-have.
- **Org-tree flattening — outlet IS the branch (ADR 0012, 2026-07-28)** — nine atomic commits
  remove the BRANCH level (`business_unit > outlet > team`; `OrgUnitType` is the single encoding),
  seed **one default OUTLET under every new business unit** (company bootstrap AND add-business,
  named after the BU, same tx + own OrgUnitCreated via the outbox), and delete the console's silent
  business-unit fallback: POS/Menu/Kitchen render inside a new **OutletGate** fed by a shared
  `useResolvedOutlets` hook (company outlets ∩ own assignments + outlets[0] self-heal, hoisted out
  of OutletPicker so gate and picker cannot disagree) — `businessId` on a POS surface is now ALWAYS
  a real outlet id. The ignored `firstBusinessType`/`type` came off the signup/create-company wire
  (old bodies still accepted — unknown JSON fields ignored, proven by test); the signup/onboarding
  business-type picker (dead UI) was removed. Wire compat: the events' `type` is a free Avro string
  — doc-string/catalog-only changes; consumers store type opaquely (proven by their contract
  tests). Migration-comment edits changed Flyway checksums → dev stack reset documented in RUNBOOK
  ("2026-07 org-tree flattening — dev data"), which also gives the keep-data per-BU outlet recipe.
  Follow-up (unchanged): restaurant-service still trusts client `businessId` (no org ref table to
  enforce type=OUTLET server-side). Review (fresh context) = PASS; deferred suggestions: treat a
  `/users/me/outlets` load error as a gate error instead of fail-open to the full outlet list
  (reads only — sale writes are backstopped by OutletAccessGuard); memoize `useResolvedOutlets`'
  outlets array; drop the now-unused `idx_org_unit_ref_company_type` in a future migration; add a
  create-company old-body back-compat test mirroring the signup one.
- **Outlet-scoping increment — the org tree means something (phases 1–5, 2026-07-27)** — five
  independently-shipped phases make `business_id` a real, named, enforced outlet dimension.
  **P1** finance `outlet_revenue` read model (keyed company/outlet/period/currency, fed by the same
  SaleRecorded consumer + void/refund reversal path) + `GET /api/v1/pnl/outlets`. **P2** finance
  consumes OrgUnitCreated/Changed into `org_unit_ref` → `/pnl/outlets` gains `outletName`; dashboard
  per-outlet panel shows real named rows. **P3** POS outlet picker (per-tab sessionStorage,
  `CompanySession.activeOutletId`, "ringing for «Outlet»" indicator) + org `GET /api/v1/outlets`.
  **P4** org `user_outlet_assignment` (user_id = KC sub, effective-dated, V5) +
  `GET/PUT /api/v1/users/{id}/outlets` + `/users/me/outlets` + Team outlets editor; picker intersects
  with the caller's assignments. **P5 (NEW EVENT + enforcement)** `UserOutletAssignmentChanged`
  (org outbox, emitted inside the replace-set tx; Avro in libs/contracts; partition key =
  `assignment_id` → per-(user,outlet) ordering, NOT per-user) consumed by restaurant into
  `user_outlet_assignment_ref` (V14, Auditable+FORCE RLS; `processed_event` V15 for event-UUID
  idempotency; DLT fail-closed on missing/non-UUID id header or undecodable payload). Enforcement
  policy (signed off): owner/manager bypass; cashier default-closed with an ACTIVE
  (actor, businessId) row required; grandfather = company with ZERO ref rows (scoping never
  adopted) → allow. The guard (`OutletAccessGuard`, outletref.service) covers EVERY sale-recording
  path — OrderWriter checkout/park/payParked AND BillWriter open/payBill (the bill gap was found
  post-resume and closed: bills record sales via SaleWriter and would otherwise sidestep the
  order-path guard). 403 = RFC-7807 `…/outlet-not-assigned`, mapped to i18n copy (en/id) on all four
  POS surfaces; Team page renders cashier+0-assignments as an amber warning (owner/manager keep
  "All outlets" — they bypass). Proven: full suites green (enforcement 9/9 branches, contract triad,
  consumer idempotency + cross-tenant isolation, producer outbox 4/4, listener fail-closed 4/4) AND
  live E2E on the real stack: org PUT → outbox → Debezium (`org-outbox-connector`, registered
  2026-07-27) → Kafka → restaurant ref row → curl checkout as an unassigned cashier = 403
  problem+json / assigned outlet = sale recorded. Code-review PASS (fix-round applied: partition-key
  contract corrected in catalog+avsc, readiness comment de-overstated, dead code removed).
  **Known limitations (deliberate):** (1) the `kafka` readiness indicator checks broker
  reachability only — on a first deploy with earliest-offset replay, enforcement can briefly run
  against a partially-hydrated ref table (a caught-up/lag gate is a tracked follow-up); (2) the
  guard runs before the idempotency fast path, so a retry of an already-completed order after
  mid-shift revocation returns 403 rather than replaying the original response (security-first
  ordering, accepted); (3) restaurant-service is the only enforcing vertical so far.
- **Outlet-enforcement hardening — close the SaleWriter choke-point bypass (2026-07-27, follow-up
  to phase 5)** — a post-commit adversarial bug hunt (two independent agents) found the phase-5
  guard was on `OrderWriter`/`BillWriter` but NOT on `SaleWriter`, the choke point every
  revenue-recognizing path funnels through. Two real, cashier-reachable bypasses: **(F1, critical)**
  the legacy `POST /api/v1/sales` (`SaleController → SaleWriter.create`, client-supplied
  `businessId`, gateway-routed to `POS_ROLES`) minted `SaleRecorded` at ANY outlet with no check;
  **(F2, high)** `PaymentCaptureWriter.capture → SaleWriter.recordInCurrentTx` recognized digital
  revenue with no outlet re-check. Fix: `OutletAccessGuard.enforce(businessId)` at both `SaleWriter`
  entry points — in `create` placed AFTER the idempotency fast path (an idempotent replay of an
  already-recorded sale still returns 200; only a NEW sale at an unassigned outlet is rejected), in
  `recordInCurrentTx` at the top (the sole guard for capture). The `OrderWriter`/`BillWriter` guards
  stay as fail-fast + coverage for the no-sale paths (park, bill-open). Tests: direct-sale 403 +
  grandfather-allow, capture 403 + assigned-capture success.
  **Documented-not-fixed findings (both hunts, tracked for a later increment):** (a) the
  grandfather clause fails OPEN if the local ref cache diverges from org-service (consumer down /
  DLT / lag → `countAllForCompany()==0` → allow) — adoption is inferred from cache cardinality, not
  an explicit company flag; (b) the **grandfather cliff**: the company's FIRST-ever assignment
  flips every never-assigned cashier to default-closed mid-shift with no "you are enabling
  enforcement for the whole company" signal; (c) roles come from the `X-Roles` header, not the
  validated JWT, so the owner/manager bypass is only as strong as network isolation while tenant
  isolation is token-bound (matters only off-gateway; mTLS deferred); (d) the guard-before-
  idempotency ordering on the order/bill call sites (F2/#4) can 403 a legitimate retry after
  mid-shift revocation; (e) the `/users/me/outlets` gateway route isn't method-constrained (latent).
  Verified CORRECT by the hunts (no change needed): consumer ordering/reopen (stable `assignment_id`
  per tuple), `processOnce`+upsert atomicity, the multi-outlet replace-set diff, epoch-day/sentinel
  range, and RLS fail-closed on an unset GUC.
- **Signup flow hardening — enterprise-gap fixes (2026-07-25)** — closed the register-flow gaps found
  in the Odoo/enterprise gap analysis. Backend (org-service): server-side whitelists for
  currency/language/business-type (`@Pattern` — a direct API call can no longer create an EUR or
  `"xx"`-language tenant); ToS consent required (`termsAccepted` `@AssertTrue`, consent instant
  recorded as the Keycloak `terms_accepted_at` user attribute); email verification support
  (`emailVerified=false` + config-gated `VERIFY_EMAIL` required action via
  `native.keycloak-admin.require-email-verification` — default false because dev has no SMTP,
  **production must enable it**; the signup response carries `emailVerificationRequired` so the UI
  shows the right success state); **signup flow inverted** (Keycloak user FIRST under a
  pre-generated company id, company second) so the failure residual flips from an
  uncompensatable orphaned tenant row (bootstrap events already outboxed) to a single idempotent
  compensating `deleteUser` — only a double failure leaves an orphaned KC user (ERROR-logged);
  Keycloak admin token cache got a lock-free fast path (was: every admin call synchronized).
  Gateway: the public `/api/v1/signup` route is now throttled by a dedicated
  `AnonymousRateLimitFilter` — per-client-IP Redis token bucket (`rate-limit.signup.*`, default
  10/hour burst 10), spoof-safe by default (X-Forwarded-For honored ONLY when
  `trust-forwarded-for` is explicitly enabled, and then only its last entry). Console: signup
  rework — 4 consolidated steps (was 5), real `<form>` per step (Enter advances), confirm-password +
  dependency-free strength meter, ToS checkbox, review rows link back to their step, all API errors
  mapped to i18n keys (raw English RFC-7807 details no longer shown to id-locale users), and
  post-signup sign-in passes `login_hint` so Keycloak pre-fills the just-registered email.
  Proven: SignupAcceptanceTest (8, real KC26+PG), GatewaySignupRateLimitTest (2, real Redis),
  AnonymousRateLimitFilterTest (4 — incl. the multi-line XFF flattening regression from the
  security review's LOW finding); both service suites + console build green; security-engineer
  review PASS. Remaining
  follow-ups (deliberate): CAPTCHA/Turnstile on top of the IP throttle, realm SMTP config +
  enabling verification in prod, idempotency-key on the signup POST, funnel analytics.
- **Fix: `management.tracing.export.enabled=false` broke W3C propagation (ADR 0010 #13, 2026-06-22)** —
  Root-cause found and fixed. `TraceContinuityConsumeAcceptanceTest` was consistently failing: every
  Kafka listener started a new root span instead of continuing the producer trace. Investigation via
  Spring Boot 4.1 source: `@ConditionalOnEnabledTracingExport` gates the W3C `TextMapPropagator` bean
  in `OpenTelemetryPropagationConfigurations`; when `management.tracing.export.enabled=false`, that
  condition evaluates false, so only `TextMapPropagator.noop()` is registered via `NoPropagation`,
  making `OtelPropagator.extract()` always return an invalid span context (→ new root span). The
  property was set in `ObservabilityEnvironmentPostProcessor` to suppress OTLP connection-failure
  noise, but was unnecessary: the OTLP span exporter is never created without an endpoint, because
  `OtlpTracingConfigurations.Exporters` requires `@ConditionalOnBean(OtlpTracingConnectionDetails.class)`
  and that bean only materialises when `management.opentelemetry.tracing.export.otlp.endpoint` is
  set. Fix: removed `management.tracing.export.enabled=false` from the post-processor defaults.
  Only `management.otlp.metrics.export.enabled=false` remains (disabling the OTLP metrics push
  registry). `TraceContinuityConsumeAcceptanceTest` now passes; ADR 0010 and `build.gradle.kts`
  comments updated to explain the constraint.
- **Trace continuity through the CDC pipeline + outbox-lag metric (ADR 0010 #13, 2026-06-22)** —
  Closed the outbox→Debezium→Kafka trace gap: producer services now stamp the W3C `traceparent`
  (current Micrometer span) into a new nullable `traceparent VARCHAR(64)` column on every `outbox`
  table (one Flyway `ALTER TABLE` migration per the 7 producer services). `libs/events` gained a
  `TraceparentSupplier` functional interface, a `MicrometerTraceparentSupplier` implementation
  (reads from `io.micrometer.tracing.Tracer`; both are `compileOnly` so DB-only modules are
  unaffected), and an updated `OutboxWriter` that accepts the supplier. `OutboxRecord` was extended
  with a nullable `traceparent` field (not in `requireNonNull`). All 7 `EventsConfig` classes were
  wired with `ObjectProvider<Tracer>` (degrades to NOOP if no Tracer present). The Debezium connector
  template (`docker/debezium/outbox-connector.json`) appends `traceparent:header:traceparent` to
  `table.fields.additional.placement` so Debezium maps the column to a Kafka header. All 5 consumer
  `KafkaConfig` classes set `factory.getContainerProperties().setObservationEnabled(true)` on the
  custom container factory, and `spring.kafka.listener.observation-enabled: true` was added to each
  consumer's `application.yml` — so Spring Kafka extracts the header and makes the listener span a
  child of the producer span via the Micrometer OTel bridge. The outbox-lag gauge
  `native.outbox.unpublished` (tag: `service`, COUNT WHERE published_at IS NULL via the existing
  partial index) was added as `OutboxLagMetrics` in `libs/events` and registered in every producer's
  `EventsConfig`. **Dependency decision:** `micrometer-tracing` and `micrometer-core` added as
  `compileOnly` in `libs/events` — the gateway depends on `libs/observability` (not `libs/events`),
  so the DB-free gateway stayed clean; services already have both at runtime via
  `libs/observability`'s `api("spring-boot-starter-opentelemetry")`. **Tests:** (a)
  `OutboxWriterTraceparentTest` — four unit tests on H2 proving supplier-present → stored, supplier-null
  → NULL, NOOP constructor → NULL, direct-record write → round-trip; (b)
  `TraceContinuityConsumeAcceptanceTest` — consumer-side Testcontainers Kafka + PostgreSQL 16 test
  that publishes a record with a known `traceparent` header and asserts the listener span's traceId
  equals the header's traceId (proves framework propagation end-to-end). `docs/EVENT-CATALOG.md`
  updated with the `traceparent` header row. **Remaining of #13:** OTLP collector + Grafana (handled
  by the parallel observability backend work stream).
- **Observability backend + dashboards — Prometheus / Grafana / Tempo overlay (scorecard #13, 2026-06-22)** —
  Added the metrics/trace BACKEND infra as a composable Docker Compose overlay (`docker/compose.observability.yml`)
  that sits alongside the dev stack but does not bloat `compose.dev.yml`. Three containers: **Prometheus**
  (`prom/prometheus:v2.53.3`) scraping `/actuator/prometheus` on all 8 services at 15 s intervals (one job
  per service, `service` label), **Grafana Tempo** (`grafana/tempo:2.6.1`) receiving OTLP spans over HTTP
  port 4318 / gRPC port 4317, and **Grafana** (`grafana/grafana:11.3.2`) with fully provisioned datasources
  (Prometheus + Tempo, trace-to-metrics correlation wired) and three dashboards: `native-red.json` (RED
  method per service from `http_server_requests_seconds_*`, templated `$service` variable), `native-events.json`
  (Kafka consumer lag via `kafka_consumer_fetch_manager_records_lag`, listener throughput/latency via
  `spring_kafka_listener_seconds_*`, outbox lag via `native_outbox_unpublished` gauge — the gauge itself is
  authored by a parallel work stream), and `native-jvm.json` (heap/non-heap, GC pause, CPU, threads, buffer
  pools from standard Micrometer JVM metrics). OTLP export remains **OFF by default** (ADR 0010 decision
  preserved — the SDK is real and trace IDs populate MDC, nothing is shipped until an operator sets
  `MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true` + `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces`).
  All YAML validated with `python3 -m yaml.safe_load`; all dashboard JSON validated with `python3 -m json.tool`.
  **Config-only / author-only** — same status as the rest of `docker/` (not exercised against a live
  multi-service run; a real cluster would need port assignments confirmed and Tempo storage adjusted).
  Closes scorecard **#13** for the local dev observability backend half (Vault/Linkerd at the service-split
  point remain deferred per CLAUDE.md). See `docker/README.md §Observability stack` for bring-up instructions.
- **Distributed tracing — Micrometer Tracing + OpenTelemetry fleet-wide (ADR 0010, scorecard #10)** —
  §5 called for OTel trace context across every hop + `traceId`/`spanId` in the JSON logs, but **no
  tracing was wired**: every log line carried an empty `[,]`, and the RFC-7807 advice + error-inbox read
  a `trace_id` MDC key nothing ever populated. Wired **Micrometer Tracing + the OpenTelemetry bridge**
  from `libs/observability` (the one dependency every service + the gateway already has) via
  `spring-boot-starter-opentelemetry` — Spring Boot 4.0 split tracing into per-concern modules, and the
  bare bridge alone falls back to a **no-op tracer**. Aligned the shared logback MDC keys to Micrometer's
  `traceId`/`spanId`, and every `MDC.get("trace_id")` reader (the advice in each service, libs/security's
  `ApiExceptionHandler`, the error-inbox `ConsumeErrorRecorder`) to `traceId` — so the real trace id now
  flows into error responses' `traceId` and `error_log.trace_id` (the DB column keeps its name). Full
  sampling (1.0) via a lowest-precedence `ObservabilityEnvironmentPostProcessor` default; OTLP span +
  metric export **disabled by default** (no collector yet → nothing shipped, no connection-failure
  noise). W3C `traceparent` propagation across the gateway→service sync edge is Spring Boot's
  auto-instrumentation. **Deferred (infra-gated):** the outbox→Debezium→Kafka trace continuity (producer
  stamps `traceparent` into the outbox; connector maps it; consumer extracts it — needs a schema
  migration on every producer + connector changes + the live CDC loop) and a real OTLP collector (#13).
  Closes scorecard **#10** for the OTel + MDC + HTTP-propagation half (ahead of blackheart's custom IDs).
  Verified: a `TracingWiringTest` (real Tracer + 1.0 sampling + a valid 32-hex W3C trace id) + the WHOLE
  fleet's Testcontainers suites green across all 8 services + the libs — the fleet-wide classpath +
  MDC-key change re-verified end to end (the employee PII-log drift guard updated to the new allow-list);
  checkstyle + spotless green. With #10 done, the four "then code" scorecard gaps — **9, 12, 11, 10** —
  are all closed; their infra-gated remainders fold into #13.
- **Error-inbox fleet rollout — `libs/error-inbox` (ADR 0009, scorecard #11)** — the finance error-inbox
  pilot (ADR 0005) became a shared library and was rolled out to **every** event-consuming service. The
  five service-agnostic pieces (`ErrorMessageRedactor`, `ErrorInboxWriter`, `AlertPayload`,
  `AlertWebhookClient`, `ConsumeErrorRecorder`) now live once in **`libs/error-inbox`** with an
  `ErrorInboxAutoConfiguration` that registers them (REQUIRES_NEW tx template + a
  `@ConditionalOnMissingBean` Clock; the alert's `service` label comes from `spring.application.name`,
  not a hardcoded constant). A **dedicated lib, not `libs/observability`** — the stateless gateway
  depends on observability and must stay DB-free; error-inbox carries JDBC/Kafka/RestClient, so it is
  consumed only by the event-consuming services. **finance** was migrated onto the lib (its in-service
  copies + `ObservabilityConfig` deleted), and **carwash, employee, entitlement, notification** each
  gained it: a `libs:error-inbox` dependency, an `error_log` Flyway migration (per-service DB — NOT
  Auditable, NOT RLS; `company_id` nullable diagnostic context; PII redacted at write time as the
  RLS-substitute mitigation, HR-6 — the ADR 0005 deviations carried forward verbatim), and a one-line
  wrap of the existing DLT `DeadLetterPublishingRecoverer` in a `ConsumerRecordRecoverer` that records
  the failure before publishing. **Deliberate exclusions:** org + restaurant (pure producers, no DLT to
  guard) and the gateway (no consumers, no DB). So a poison money/business event is now recorded
  (fingerprint-deduped) + milestone-alerted (PII-redacted egress) on the WHOLE fleet, from one
  definition. Closes scorecard **#11** for the DB-inbox+alerting half (the RED-metrics/outbox-lag/Grafana
  half stays a follow-up tied to #13). Verified: the lib's pure-unit tests (redaction, milestone
  predicate, fail-safe swallow + PII-safe egress) + finance's `ErrorInboxWriterTest` (the lib bean vs
  finance's real `error_log`) + the full Testcontainers suites of carwash/employee/entitlement/
  notification (each boots with its new migration + the wired recorder) all green; ArchUnit + spotless
  + checkstyle green.
- **Client resilience — explicit outbound timeouts + startup self-check (scorecard #12)** — closed the
  "every outbound client sets explicit connect/read timeouts" gap (ENGINEERING-STANDARDS §4). Business
  services talk only via events (HR-2), so the outbound HTTP surface is just the **Keycloak JWKS fetch**
  and the **finance alert webhook**. *(Security review caught a wrong premise mid-implementation and it
  was corrected before commit: on the pinned Spring Security 7.1.0, `NimbusJwtDecoder.withJwkSetUri(...)`
  is NOT infinite — its default `RestTemplateWithNimbusDefaultTimeouts` already bounds the fetch to
  500ms/500ms via Nimbus' `RemoteJWKSet.DEFAULT_HTTP_*`. So this is not an infinite-hang fix; an earlier
  draft defaulting to 2s/3s would have **loosened** the framework's 500ms — a regression — and was
  reverted.)* What the change delivers: the shared `libs/security JwtSecurityConfig` (every business
  service) and the gateway's own `JwtDecoderConfig` feed EXPLICIT timeouts into the decoder's
  `restOperations`, sourced from `@Validated @ConfigurationProperties` (`native.security.jwks.*`,
  `@NotNull`, defaults **500ms/500ms** to MATCH the framework — never a back-door loosening). The value
  is now owned config: externalized (a slow-Keycloak env can widen it with no code change, §7), asserted
  positive at boot, and immune to a silent shift if a future library bump changes the framework default.
  A new `OutboundClientTimeoutCheck` (registered in `NativeSecurityAutoConfiguration`, runs in every
  profile) **fails fast at boot** on a null/zero/negative timeout (`0s` = infinite in
  `SimpleClientHttpRequestFactory`); the gateway carve-out (no libs/security dep) makes the same
  assertion in its decoder constructor. The finance `AlertWebhookClient` already carried explicit
  timeouts (ADR 0005). Verified: fail-fast unit tests (libs/security + gateway) + the existing
  real-Keycloak JWKS proofs (libs/security defense-in-depth + gateway JWT routing + org secured
  bootstrap) all green — the `restOperations`-wired decoder still validates tokens end-to-end. Scorecard
  **#12 → ✅**.
- **OpenAPI docs — springdoc fleet rollout + `@Operation` ArchUnit enforcer (ADR 0008)** — the finance
  springdoc pilot (ADR 0004) became the fleet standard. Every service exposing a business REST API —
  **org, restaurant, carwash, employee, entitlement** + the **finance** pilot (6 services) — now serves
  `/v3/api-docs` (OpenAPI 3.1) + `/swagger-ui`, with an `@Operation` on **every** handler (**56 handlers
  across 20 controllers**) and a class-level `@Tag` on each `@RestController`. A new
  `apiHandlersAreDocumentedWithAnOperation` ArchUnit rule in each `LayeredArchitectureTest` — and in
  `service-template` (with `allowEmptyShould` so a fresh clone inherits it) — fails the build on any
  `@RequestMapping`/`@GetMapping`/… handler missing an `@Operation` (the `config`
  `HealthController`/`/healthz` is exempt via `resideOutsideOfPackage("..config..")`). A per-service
  `OpenApiDocsSmokeTest` boots the real service and asserts `/v3/api-docs` is genuine OpenAPI JSON (not the
  Base64 blob the Boot-3 springdoc 2.8.x line returns on Framework 7) documenting the live endpoints — six
  smoke tests now guard the shared catalog-pinned springdoc version across the fleet. **Deliberate
  exclusions:** notification-service (no business REST API — a pure event consumer) and the reactive
  gateway (would need the webflux starter; routes no endpoints of its own). OpenAPI annotations are
  **developer-facing docs, so HR-9 i18n does not apply**. Closes competitive-scorecard **#9** (Native ≥
  blackheart: springdoc + `@Operation`/`@Tag` everywhere, PLUS an ArchUnit enforcer + smoke tests, where
  blackheart relies on discipline). Verified green across the 7 touched modules: compile + every
  `LayeredArchitectureTest` + every `OpenApiDocsSmokeTest` (Testcontainers) + checkstyle + spotless.
  Follow-up (ENGINEERING-STANDARDS §1.3): `@ApiResponse`/`ProblemDetail` error-response modelling + a
  "generated spec ⊇ published spec" contract test.
- **Robust restaurant POS — 4 phases (ADR 0006/0007)** — the validation-slice POS (menu → atomic
  order → `SaleRecorded`) became a real point-of-sale, built + adversarially-reviewed phase by phase
  (every phase's mandatory money/tenancy review FAILED first and caught a real bug; all fixed +
  tested). **P1 Payments:** a provider-agnostic tender port — **cash live** (tendered + change), **QRIS/
  card flagged-pending** (a `DigitalProvider` that never moves money; real adapter deferred to ADR
  0007), the load-bearing **revenue-recognised-at-capture** invariant (a digital tender is PENDING
  with no sale until capture), and **void/refund** driving a balanced finance reversal. *(Review caught:
  the void/refund events had no finance listener — reversals were dead in prod.)* **P2 Pricing:** an
  order price breakdown (PB1 restaurant tax + service charge + order discount), round-once `Money`
  math, posted to the GL as a balanced 5-leg entry (tax→liability, discount→contra-revenue), with a
  read-only `/orders/quote` for the live cart total. *(Review caught: a void under-stated revenue
  (unwound by gross not net) and a refund left tax over-collected.)* **P3 Catalog:** categories,
  per-item modifiers/variants with price deltas, 86'ing/availability. *(Review caught: a quote↔checkout
  price drift; a back-fill migration dead under FORCE-RLS.)* **P4 Order ops:** dine-in/takeaway/delivery,
  tables + occupancy, **hold/park → resume → pay-parked** (no revenue until pay), printable receipt.
  *(Review caught: the gateway lacked a `/tables/**` route; pay-parked dropped the tax split.)* All
  indirect-tax law is **flagged-illustrative** (PB1≈10% vs PPN 11%, service-charge-as-revenue-vs-tip,
  COA mappings — `ILLUSTRATIVE_PLACEHOLDER` + `uses_illustrative_rules` propagated to the books and
  badged "Estimated" in the UI; an SME must confirm). Verified live end-to-end (Keycloak → gateway →
  restaurant → finance): a cash sale with a Size modifier on a dine-in table, tax breakdown, receipt.
  restaurant 155 · finance 323 · gateway 22 tests green.
- **Error-inbox + webhook alerting (finance pilot, ADR 0005)** — a DLT'd money event is no longer a
  silent failure. The Kafka DLT recoverer records each consume failure into a per-service
  `error_log` ops table (V14) via a fingerprint-deduped `INSERT … ON CONFLICT` upsert
  (`ErrorInboxWriter`, plain JdbcTemplate in a REQUIRES_NEW tx so it survives the rolled-back
  business tx), then fires an async webhook alert on occurrence-count milestones (1/10/100/every-1000;
  no-op when the URL is unset, so dev/CI never call out). Deliberate, ADR-recorded deviations:
  `error_log` is NOT Auditable and NOT RLS-scoped — it is cross-tenant **operator** data, `company_id`
  nullable context never an access key; the HR-6 mitigation in place of RLS is **PII redaction at
  write time** (email + ≥10-digit runs incl. space/hyphen-separated). Code-reviewed (money/tenancy
  gate) → fixed a **blocker**: the alert webhook had shipped the RAW exception message off-box; it now
  carries only the redacted message + the real dedup fingerprint, the upsert runs under a bounded tx
  timeout (no partition stall), and a payload test guards the egress. Closes scorecard **gap #11** for
  finance; fleet rollout + Grafana dashboards (#13) deferred to a follow-up ADR. 279 finance tests
  green. (commits `cd4d744` + `e51325c`)
- **OpenAPI docs — springdoc pilot (finance-service)** — finance now serves `/v3/api-docs`
  (OpenAPI 3.1) + `/swagger-ui`, generated from the live controllers. Pinned **springdoc-openapi
  3.0.x** — the Boot 4 / Framework 7 line; an earlier probe's 2.8.x (Boot 3) returns a Base64-mangled
  `/v3/api-docs` on Framework 7. An `OpenApiDocsSmokeTest` boots the service and asserts the endpoint
  is real OpenAPI JSON (not Base64) documenting the statements paths, so a Boot/springdoc bump that
  breaks doc generation fails the build. Docs sit behind the JWT chain and are not gateway-routed
  (dev/in-cluster only). Decision recorded in **ADR 0004**; fleet-wide rollout is a later ADR.
- **Financial Statements (Income Statement + Balance Sheet)** — finance gains a read-only statements
  API derived ENTIRELY from the double-entry GL (no new tables, no migrations). `GET
  /api/v1/statements/income?period=YYYY-MM` is period-scoped (REVENUE−EXPENSE=net, via
  `GlTrialBalanceReader`); `GET /api/v1/statements/balance-sheet?asOf=YYYY-MM` is CUMULATIVE — a new
  `JournalEntryRepository#glTrialBalanceAsOf` native query + `GlCumulativeTrialBalanceLineView`
  projection sums all journal activity where `period <= :asOf`. **Retained earnings is computed on
  read** (Σrevenue − Σexpense cumulative; never posted to the GL) so the sheet balances, with a
  defence-in-depth `assets == liabilities + equity` gate that throws on imbalance (an internal
  posting bug → 500). Sign conventions per `AccountType` (ASSET/EXPENSE debit-normal,
  LIABILITY/EQUITY/REVENUE credit-normal); all amounts `long` minor units + `Math.*Exact` (HR-8).
  Tenant-scoped via RLS only — readers are `@Transactional`, no manual `WHERE company_id`; a tenancy
  isolation test proves A's books are invisible to B. Code-reviewed → fixed: a multi-currency trial
  balance is now a TYPED `GlMultiCurrencyException` → **422** (was a bare `IllegalStateException` →
  opaque 500; mirrors the within-close `MultiCurrencyTrialBalanceException`), and an unmapped account
  a typed `GlUnmappedAccountException` (→ non-leaking internal 500, like `UnmappedLedgerAccountException`);
  added the missing `StatementsControllerTest` web-slice (200/204/400/422 + RFC-7807 shape) for parity
  with the sibling controllers. 248 finance tests green. The gateway routes + **owner/manager
  role-gates** `/api/v1/statements/**` → finance-service (a new `statementsRoute`, mirroring the
  pnl/revenue dashboard routes; a cashier is denied 403 at the edge), with a role-routing test. The
  **console** adds two owner/manager pages — Income statement (`/statements/income`) and Balance
  sheet (`/statements/balance-sheet`) — dashboard-style (KPI tiles + a bar chart + an expandable
  per-account breakdown), en/id, Intl money via the shared `money.ts`, reusing the illustrative
  badge; `tsc` + `vite build` green (also fixed a latent type error in the console's dev-proxy
  config). SME-deferred: SAK-EMKM presentation grouping/labels, comparative columns, Cash Flow
  statement.
- **Double-entry General Ledger** — finance gains a real double-entry GL (`journal_entry` + balanced
  `journal_line`, the invariant enforced in the aggregate so an unbalanced entry can't exist)
  ALONGSIDE the existing dimensional ledger (untouched). Every money event auto-posts a balanced
  journal in the SAME consume transaction via a data-driven posting-template framework — SME-gated:
  the Indonesian COA / account mappings / tax are higher-version effective-dated rows, with a loud
  flagged-illustrative seed today; the GL trial-balance read proves Σdebit==Σcredit. Code-reviewed →
  fixed (authoritative-period vs `periodOf(occurredAt)`, fail-loud on an unmapped account,
  illustrative-flag OR). V13. (commits `04fb971` + `ee3f400`)
- **Console production auth + POS** — real Keycloak OIDC (authorization-code + PKCE) login in the
  console; the SPA sends a bearer to the gateway, which now **role-gates** routes (cashier → the
  restaurant POS routes; owner/manager → the finance/org dashboard routes via a `RoleAuthorizationFilter`)
  and the SPA gates its surfaces by role (cashier → POS only). New restaurant **POS** (menu + atomic
  order checkout reusing `SaleRecorded`). Fixed a 100× IDR bug (`money.ts` fraction digits ≠ libs/money).
  Added org `GET /api/v1/companies/current`, a `native-console` PKCE realm client, a console Docker
  image + Kustomize overlay + CI job, and the **≥-blackheart standards scorecard** (a maintained
  competitive bar) to ENGINEERING-STANDARDS. Verified live end-to-end (owner sees dashboard+POS;
  cashier POS-only with the dashboard 403'd at the gateway; forged tenant headers stripped).
- **Org-tree move/deactivate semantics (#25)** — the undecided lifecycle semantics, resolved by user
  decision: deactivation **cascades** to the active subtree (one `DEACTIVATED` event per node), and a
  node can be **reactivated** (`REACTIVATED`, requires an active parent, no cascade down). Enforces a
  single invariant — *no active node may have an inactive ancestor* — across all four structural ops
  (cascade-deactivate; reactivate-needs-active-parent; can't move an active node under an inactive
  parent; can't create under an inactive parent). New `REACTIVATED` `change_kind` is a backward-compat
  Avro `string`; employee-service consumes via the `active` flag (no consumer change). Producer-only
  change, no migration. Adversarially reviewed (PASS; closed the create-guard gap it found); org +
  employee builds green.
- **Finance-expansion robustness — posting-currency guard (#26)** — a `SaleRecorded`/`ExpenseRecorded`
  in a currency other than the company's immutable base currency used to silently create a SECOND
  `consolidated_pnl`/`consolidated_revenue` row (keyed on `(company, period, currency)`), detonating
  later as a raw read-time `500` in `PnlReader`. Added a write-time guard (`PnlReadModelWriter
  .requireConsistentCurrency`, RLS-scoped) on the revenue+expense writers: a divergent posting throws
  a typed, non-retryable `MismatchedPostingCurrencyException` → the consume rolls back (no divergent
  row) and the record is DLT'd (fail-closed, money held, read stays clean). Mirrors the labor path's
  `CURRENCY_MISMATCH` guard + the within-close `MultiCurrencyTrialBalanceException`; the `PnlReader`
  multi-currency branch stays as a defense-in-depth backstop. Adversarially reviewed (PASS); full
  finance build green.
- **Console read-endpoints: org tree, group consolidation, period close (2026-06-22)** — added three
  GET endpoints required by the console dashboard pages: `GET /api/v1/org-units` (flat org-unit list,
  RLS-scoped, native+projection), `GET /api/v1/consolidation-groups` (groups the current company leads)
  and `GET /api/v1/consolidation-groups/{groupId}/members` (group members, 404 if not led by caller)
  in org-service; `GET /api/v1/closes` (close history, most-recent first) in finance-service. Gateway
  routes added for all four with `DASHBOARD_ROLES` (`owner`, `manager`). Projection-to-DTO mapping
  kept strictly in the service layer (`OrgUnitReader`, `GroupReader`, `CloseHistoryReader`) per
  CODE-STRUCTURE §3.3 (ArchUnit `featureLayersRespectTheLayeredArchitecture` enforces no Dto→Projection
  access). `WithinCompanyCloseControllerTest` updated to declare `@MockitoBean CloseHistoryReader` for
  the new controller constructor arg. org-service 93/93 tests green; finance-service 298/299 green
  (1 pre-existing `TraceContinuityConsumeAcceptanceTest` flakiness from ADR 0010 tracing, unrelated).
- **P3d deferred operational items (#46)** — (a) V12 backfills the non-RLS `member_group_index` from
  the FORCE-RLS `group_member` for pre-V10 memberships (whose `GroupMembershipChanged` was already
  deduped and will never re-fire, so a within-company close would silently emit no
  `TrialBalancePublished` for them); the migration uses a `NO FORCE`/`FORCE` bracket so the
  table-owning role can read across tenants at migrate time (a plain FORCE-RLS read sees zero rows
  with no GUC bound). (b) An advisory lock (`pg_advisory_xact_lock`, mirroring the labor primitive) on
  the within-company close serializes concurrent closes so the loser returns the idempotent no-op,
  not a UNIQUE-violation 500. (c) Web-slice MVC tests for `WithinCompanyCloseController` (200/422/400)
  + a concurrency regression test + a backfill projection test. Adversarially reviewed (PASS); full
  finance build green.
- **Live end-to-end validation + 3 CDC fixes** — ran the real outbox→Debezium→Kafka→finance loop;
  found+fixed the publication-mode, occurred_at-timestamp, and bytea/ByteBuffer (base64) bugs the
  stubbed-relay tests couldn't catch. Proven: `GET /api/v1/revenue` = the recorded sales.
- **Follow-up hardening sweeps** — consolidation money-math regression-locks; FX/mapping resolution
  determinism + a group-RLS migration-lint guard; P3d tenancy/predicate/typed-fault hardening; platform
  defense (show-details pinned, encoded-JSON PII guard, readiness composition, RLS-bean presence);
  payroll/labor guards (mixed-grain, top-bracket-cap, control-total currency, looped race).
- **CI + deploy (#24)** — GitHub Actions (build+test+image matrix) + Kustomize base+overlays + fixed the
  broken service Dockerfiles + the missing entitlement DB. Author-only / unverified vs a real cluster.
- **Layer-subpackage refactor** — every feature split into controller/service/repository/domain/dto/
  messaging; ArchUnit retargeted; service-template + docs updated. Pure move, 624 tests green.
- **Phase 3 (P3a–P3d)** — payroll engine (flagged statutory) · finance consumes labor cost (supersession,
  concurrency-safe) · FX/multi-currency (non-float FxRate, flagged stub) · group consolidation
  (two-GUC RLS, intercompany elimination, FX translation, the ConsolidationClosed producer — closing the
  loop notification consumes). 5 seams, each gated + fixed + committed.
- **#14 cross-cutting hardening** — JSON structured logs, readiness probes, RLS-ordering + anti-redeclare
  + float-ban ArchUnit guards.
- **Phase 2** — entitlement-service + the gate lib · full org tree + legal_employer · employee records
  (PII field-encryption) · carwash (2nd vertical) · finance expansion (mapping rules + dimensional
  ledger + expenses) · notification-service.
- **Phase 1 (validation slice)** — gateway + Keycloak (M1.1) · org-service create-company (M1.2) ·
  restaurant record-sale (M1.4) · finance consume → consolidated revenue (M1.5) · event transport (M0.4).
- **Phase 0** — Gradle monorepo + Java 25 toolchain · shared libs (money/tenant/events) · service-template
  · the quality gates · the engineering-standards + code-structure docs.
