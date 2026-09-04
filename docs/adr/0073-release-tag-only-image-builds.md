# ADR 0073 — Images are built on release tags only; `master` is the single release line

- **Status:** Accepted (2026-09-04)
- **Deciders:** owner ("penghematan maksimal", 2026-09-04) + tech lead
- **Amends:** [0053](0053-environments-branching-release-pipeline.md) §1 (image lineage) and §2
  (UAT auto-deploy). §3 (GitHub Flow + release tags on `master`) is unchanged — this ADR *restores*
  it.

## Context

ADR 0053 §1 made "an image is built **once**, on merge to `master`" the core rule, on the
assumption that **UAT would consume those GHCR images** (§2: "UAT … pulling GHCR `:sha`,
auto-deployed on every merge"). That consumer never materialised: `scripts/uat-up.ps1` builds UAT
images **locally** from the working tree (`native-uat/<svc>:latest`), and `docker/compose.uat.yml`
references only third-party images. Verified across the repo, the **only** consumer of GHCR app
images is the production deploy, which resolves `…/<svc>:<commit-sha>` **digests** into a pinned
release manifest — and those images come from the **tag** build (`FORCE_ALL`, the complete 13-image
set at one SHA, which is exactly what the manifest requires).

So every merge to `master` was building and pushing images that nothing pulled. ADR 0053's own
Context section had already recorded the symptom — *"Nothing consumes those images."* — and the
decision meant to fix it by adding a consumer; in practice the consumer went another way and the
build stayed.

Two further facts settled the shape of this change:

- **`master` had drifted out of §3.** Releases were being cut from a long-lived
  `feat/business-employee-apps`, which let `master` fall 112 commits behind and caused two
  incidents in one week (an ungated `workflow_dispatch` deploy reading stale default-branch
  workflows; a migration-safety false positive from using a stale `origin/master` as the
  "already deployed" proxy). `master` was brought current and that branch retired on 2026-09-04.
- **`ci.yml` on `master` is load-bearing** and stays: `deploy-prod`'s `verify-ci` gate refuses to
  deploy unless a green `ci` run exists for the tag's exact commit, and after a squash-merge only
  the master-push run carries that SHA.

## Decision

1. **`images.yml` runs on release tags (`v*`) and `workflow_dispatch` only** — not on `master`
   pushes. The prod lineage is unchanged: tag → all 13 images at that SHA → digest-pinned manifest
   → health-gated deploy.
2. **`:latest` now tracks the newest RELEASE**, not the newest `master` push. It is the fallback
   `docker/compose.prod.yml` resolves when no release manifest is layered over it; pointing it at a
   release is strictly safer than letting it rot at whatever last landed on master.
3. **UAT stays locally built** (`scripts/uat-up.ps1`), which is how it has actually worked all
   along. An untagged commit that genuinely needs a GHCR image is served by running `images.yml`
   on demand.
4. **`master` is the single release line** (restating §3, now enforced structurally by
   `verify-ci`): PR → `master` → annotated tag on `master` → approve `deploy-prod`.

## Consequences

- Removes the largest remaining recurring Actions cost after the ops-watch reduction: a merge to
  master no longer builds or pushes any container image.
- A release tag must point at a commit that landed on `master` (its `ci` run is what `verify-ci`
  requires, and its images are what the deploy pins). Tagging a side branch now fails closed — the
  deploy is refused rather than shipping unverified code.
- `:latest` changes meaning. Anything that assumed "latest = tip of master" would now get the last
  release; nothing in the repo did (only `compose.prod.yml`'s fallback references it).
- ADR 0053 §2's "UAT auto-deployed on every merge" is formally retired; UAT is refreshed on demand.
  If a future UAT does want GHCR images, re-add the trigger deliberately rather than assuming it.
