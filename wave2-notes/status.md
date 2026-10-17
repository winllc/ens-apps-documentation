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
Spec: wave2-notes/baseline-spec.md (this folder). Repo visibility: cert-alert, DSP, in-n-out-work, docs, products-page PUBLIC; rsa-operations-portal PRIVATE (no CodeQL; SpotBugs+FindSecBugs).
Note for user: in-n-out-work is public -> dev CA private keys in history are publicly exposed.

## in-n-out-work history purge (done 2026-10-17 ~15:45Z)
- Rewritten with git filter-repo: removed test/step-ca-data/ and src/main/resources/{desktop,localhost,winhal,truststore}.p12 from all history + stripped 58 blob ids; force-pushed all 15 branches. New main 788268d (adds test/dev-ca/make-dev-certs.sh etc.). OLD main was b6661374aa916b78999a062129043f9f747f39da.
- ReadyRoom wave-2 agent branch claude/wave2-platform (worktree /home/user/wt/w2-rr) is based on OLD main: before pushing, run in the worktree: git fetch origin && git rebase --onto origin/main b6661374aa916b78999a062129043f9f747f39da claude/wave2-platform  (check it doesn't re-add test/step-ca-data; scan for private keys before push).
- refs/pull/1..11 on GitHub still point at old commits with keys -> user must contact GitHub Support.
- Backup (contains old history incl keys): scratchpad/purge/in-n-out-work-before-purge.bundle and purge/rr-backup.git.
- Local clone /home/user/in-n-out-work still has old objects; refresh after the agent finishes.
