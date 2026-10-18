# Wave 2 tracking
Report doc: project 913fe455-e80a-431a-9eaa-f8d5498f1359, node 488ce6f1-edf4. Wave 2 table mv0nr27my50.63458.
Status paragraph ids (rev 17): 14=.63687 15=.63922 16=.64079 17=.64220 18=.64349 19=.64471 20=.64614 21=.64774 22=.64909 23=.65028 (prefix mv0nr27my50)
Plan items: 14 CI supply chain (all); 15 baseline (all); 16 FIPS (license lib); 17 timeouts/outbox/CRL (CA, RR); 18 Flyway + MySQL 8.4 (RR, RSA); 19 RSA-03/04 connector; 20 RSA-05/06/07 (round 2); 21 X.509 module (CA, DSP, RR); 22 RR-07/RR-12 (round 2); 23 docs drift + guides (round 2).
Round 1 agents/worktrees/branches:
- CertAlert /home/user/wt/w2-certalert claude/wave2-platform
- DSP /home/user/wt/w2-dsp claude/wave2-platform
- ReadyRoom /home/user/wt/w2-rr claude/wave2-platform  (round 2 RR-07/RR-12 stacked on this)
- RSA portal /home/user/wt/w2-rsa claude/wave2-platform (round 2 RSA-05/06/07 stacked on this)
- RSA connector /home/user/wt/w2-rsa-conn claude/wave2-connector
- License FIPS /home/user/wt/w2-fips (ens-products-page) claude/wave2-fips
Spec: scratchpad/wave2/baseline-spec.md. Repo visibility: cert-alert, DSP, in-n-out-work, docs, products-page PUBLIC; rsa-operations-portal PRIVATE (no CodeQL; SpotBugs+FindSecBugs).
Note for user: in-n-out-work is public -> dev CA private keys in history are publicly exposed.

## in-n-out-work history purge (done 2026-10-17 ~15:45Z)
- Rewritten with git filter-repo: removed test/step-ca-data/ and src/main/resources/{desktop,localhost,winhal,truststore}.p12 from all history + stripped 58 blob ids; force-pushed all 15 branches. New main 788268d (adds test/dev-ca/make-dev-certs.sh etc.). OLD main was b6661374aa916b78999a062129043f9f747f39da.
- ReadyRoom wave-2 agent branch claude/wave2-platform (worktree /home/user/wt/w2-rr) is based on OLD main: before pushing, run in the worktree: git fetch origin && git rebase --onto origin/main b6661374aa916b78999a062129043f9f747f39da claude/wave2-platform  (check it doesn't re-add test/step-ca-data; scan for private keys before push).
- refs/pull/1..11 on GitHub still point at old commits with keys -> user must contact GitHub Support.
- Backup (contains old history incl keys): scratchpad/purge/in-n-out-work-before-purge.bundle and purge/rr-backup.git.
- Local clone /home/user/in-n-out-work still has old objects; refresh after the agent finishes.

## CodeQL triage (DSP, local run 2026-10-17, security-extended)
PR #10 flags 3 high in changed code: RevocationSources:98 SHA-1 CertID (required by JDK CertId matching),
AuthController:108/110 user-controlled-bypass (suppress-auto-login cookie; pre-existing). All false positives;
user to dismiss. Pre-existing on main (not this PR's): SecurityConfig csrf disabled (custom header filter
replaces it), AuditLog:272 bypass (actor selection), AnalysisResultStore path-injection (SAFE_ID regex +
admin-only), log-injection same file, SplunkHecForwarder trust-all (opt-in + warning), JobService TOCTOU,
Aggregator:388 int/long comparison, LicenseVerifier:90 polynomial ReDoS on ARMOR regex (admin upload /
ENS_LICENSE; fix: cap input size in library 1.1.x before copying into apps).
CodeQL CLI at scratchpad/codeql/codeql; summarize.py there.

## Paused 2026-10-17 ~19:15 UTC (second pause) — how to resume
Open PRs (subscribed; re-subscribe after resume if events stop):
- winllc/rsa-operations-portal#12 (claude/wave2-connector, 85f3d13): Connector RSA-03/04/18/15. CI jobs never start:
  Actions billing block on the private repo (commented; one re-run spent). User must fix billing, then re-run CI.
- winllc/directory-services-portal#10 (claude/wave2-platform, 95ccc0e): DSP baseline. CI job "CI" was in progress;
  CodeQL check red with 3 false-positive highs (replied on threads, left open; user to dismiss). Check CI result.
Merged: ens-products-page#5 (FIPS license co-signature, library 1.1.0).

Paused agents (stopped; resume with SendMessage to the id, or a fresh agent on the worktree):
- CertAlert a5f9ea0c019868558, /home/user/wt/w2-certalert @ 7d7b530 (9 commits + WIP README). Last step:
  "Now the FIPS statement (spec 6) and the software-license note in the README." Remaining: §6, README, final
  test run, report.
- ReadyRoom a6ff1b7e70dacdc30, /home/user/wt/w2-rr @ af8fe3f (12 commits, clean). Last step: verifying action
  SHAs (gradle/actions, sbom-action) for the supply-chain commit. Remaining: verify pins, final test run, report.
- RSA portal abf768b911bd16310, /home/user/wt/w2-rsa @ b9244c3 (8 commits + WIP Dockerfiles). Last step:
  "Now README and env template docs for monitoring." Remaining: docs, RSA-15 portal JAVA_OPTS TLS pin, reuse
  ConnectorHealthIndicator from #12 in readiness, SpotBugs/Trivy, final test run, report. Will conflict with #12
  on Helm/compose/AppConfig: merge #12's branch at PR time.
  (A copy of the sandbox proxy CA it had dropped in the worktree is in scratchpad/stray/ — never commit it.)
Agent reports saved: scratchpad/wave2/reports/{fips,rsa-connector,dsp}.md (each has a "docs edits needed" list).

Decisions waiting on the user:
1. Production P-256 license key: auto-mode classifier refused generating it (secret-store write). Recommend user
   runs `npm run license -- keygen --alg p256` on a trusted machine and sends only the public key + key id;
   then set LICENSE_SIGNING_KEY_P256 on the licensing service.
2. Copy ens-license 1.1.0 into the 4 apps now (3 files each; add size cap to ARMOR parse first, CodeQL ReDoS)
   or wait for the key. (A read-only listing of the app repos' license files was also refused right after.)
3. GitHub Actions billing for rsa-operations-portal.
4. Dismiss DSP CodeQL false positives.
Round 2 (not started): RR-07/RR-12 (stack on RR platform), RSA-05/06/07 (stack on RSA platform), docs agent for
item 23 + all Wave 2 docs (use the reports' docs lists).

UPDATE 19:15 UTC: directory-services-portal#10 MERGED (DSP Wave 2 done). Check main CI on DSP at resume; CodeQL alerts now on main (dismiss false positives).
UPDATE 19:17 UTC: rsa-operations-portal#12 MERGED without CI having run (Actions billing block). At resume: once billing is fixed, confirm main CI on RSA is green; the RSA portal branch must merge main (now with #12) and resolve Helm/compose/AppConfig overlaps.

## Third pause 2026-10-17 ~20:00 UTC — how to resume
While paused earlier, the owner merged the WIP branches (cert-alert#9, in-n-out-work#12, rsa#13), the notes
branch into docs master (#8), and many Dependabot PRs (incl. majors). Main state at this pause:
- DSP main red (web typecheck: duplicate Vite 6/8 in lockfile) -> fix PR directory-services-portal#27
  (claude/fix-web-typecheck, 581b3c6), subscribed; local typecheck/tests/build/audit pass.
- CertAlert main red (build + test-postgres jobs fail; WIP merged before final test run). Agent
  a5f9ea0c019868558 was resumed to fix; it made no commits before this pause. Worktree w2-certalert at main.
- ReadyRoom main green. Agent a6ff1b7e70dacdc30 wrap-up WIP pushed to claude/wip-do-not-merge-wave2-followup
  (d13365e): Dependabot ignores eclipse-temurin majors; Dockerfile base images as named FROM stages so
  Dependabot can update digests; test/Dockerfile back to 21-jdk (Dependabot had moved it to 24).
- RSA main: Actions still blocked (billing; jobs get no runner). Local `./mvnw install` on main: 646 tests,
  2 fail (LoggingConfigTest.textLinesHaveTheDateAndTheRequestId[1],[2]). Agent abf768b911bd16310's fix in
  progress pushed to claude/wip-do-not-merge-wave2-followup (18c4976). Remaining RSA scope: Portal TLS 1.2
  pin (RSA-15), monitoring docs, ConnectorHealthIndicator in readiness, SpotBugs/Trivy, image build check.
- Docs agent a9f51fa95b0736665 (worktree /home/user/wt/w2-docs, branch claude/wave2-docs from master
  23b6087) had only just started; no commits. Its prompt covers item 23 (CA-19, DSP-20, RSA-17), DSP and
  Connector docs, cross-product FIPS/STIG + HA/DR + sizing pages. Remove wave2-notes/ from master in the
  docs PR (internal notes, merged by #8).
- WIP branches are named claude/wip-do-not-merge-* so they are not mistaken for finished work.
Resume order: (1) DSP#27 CI, (2) CertAlert main fix, (3) RSA LoggingConfigTest + rest, (4) RR wrap-up
PR, (5) docs, (6) round 2 (RR-07/RR-12, RSA-05/06/07). Disk ~6 GB free: prune Docker after builds.
UPDATE 21:11 UTC: directory-services-portal#27 (Vite lockfile fix) MERGED; DSP main should be green - confirm at resume.

## Fourth pause 2026-10-18 ~01:20 UTC — how to resume
Merged since the third pause: dsp#27 (lockfile), cert-alert#19 (main fix), in-n-out-work#25 (RR follow-up),
in-n-out-work#26 (RR-07/RR-12; CodeQL red only for a pre-existing JNDI false positive at LdapService:374,
analysis comment on #26; user to dismiss), ens-apps-documentation#9 (docs pass 1 + item 23 guides).
Open: rsa-operations-portal#14 (claude/wave2-followup @0c49f27): RSA follow-up incl. LoggingConfigTest fix,
Portal TLS1.3, readiness, SpotBugs, Trivy (Portal gated; Connector report-only pending user decision),
cosign, non-root file ownership (util/file-owners.sh, Connector uid 998 + group 0). Verified locally only;
Actions jobs get no runner (billing). Subscribed.
Paused agents:
- RSA round 2 ab2d4eefef3715f0e, worktree /home/user/wt/w2-rsa2, local branch now
  claude/wip-do-not-merge-rsa-helpdesk @0440124 (pushed). Stacked on #14. Done: RSA-07 audit choke point
  (345abc9), RSA-05 help-desk verification/dual control/notice (17caf7a). WIP: RSA-06 DB-backed rate limits
  (V3__rate_limits.sql, ratelimit/ pkg, replaces AttemptLimiter), uniform responses; last step "help-desk
  one-time-code limits". Remaining: finish RSA-06, full mvn install + helm, report. When done rename branch
  back to claude/wave2-helpdesk-controls (remote copy of that name = #14 head; proxy refused deleting it).
- Docs pass 2 a9f51fa95b0736665, worktree /home/user/wt/w2-docs, branch claude/wip-do-not-merge-docs-2
  @ffdbf0e (pushed): CertAlert Wave 2 pages + ReadyRoom round 2 + new readyroom/privacy.md + cert-alert
  fips-and-stig.md; last step "update the ReadyRoom operator guide". Remaining: finish, mkdocs --strict,
  report, then rename to claude/wave2-docs-2 and open PR. RSA follow-up/round-2 docs after those merge.
Reports: scratchpad/wave2/reports/*.md (certalert, readyroom, readyroom-round2, rsa-portal, rsa-connector,
dsp, fips, docs).
User decisions open: RSA Actions billing; dismiss RR JNDI false positive (and DSP ones); Connector Trivy
(upgrade commons-beanutils/compress vs accept Spring 3.2 in .trivyignore); production P-256 key; copy
ens-license 1.1.0 into apps (add ARMOR size cap first). Branches safe to delete: in-n-out-work
claude/wip-do-not-merge-wave2-followup (merged via #25); rsa claude/wip-do-not-merge-wave2-followup (in #14).
UPDATE 01:18 UTC: rsa-operations-portal#14 MERGED (RSA follow-up). RSA round-2 branch (claude/wip-do-not-merge-rsa-helpdesk) is stacked on it, so at resume its PR diff vs main is only the round-2 commits; merge origin/main first. RSA main CI still can't run (billing).
