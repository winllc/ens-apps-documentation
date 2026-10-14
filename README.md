# ENS Applications Documentation

Source for the documentation website covering the ENS application suite:

| Application | Section | Source repository |
|---|---|---|
| CertAlert | `docs/cert-alert/` | [winllc/cert-alert](https://github.com/winllc/cert-alert) |
| Directory Services Portal | `docs/directory-services-portal/` | [winllc/directory-services-portal](https://github.com/winllc/directory-services-portal) |
| ReadyRoom | `docs/readyroom/` | [winllc/in-n-out-work](https://github.com/winllc/in-n-out-work) |
| RSA Operations Portal | `docs/rsa-operations-portal/` | [winllc/rsa-operations-portal](https://github.com/winllc/rsa-operations-portal) |

The site is built with [MkDocs](https://www.mkdocs.org/) and the
[Material](https://squidfunk.github.io/mkdocs-material/) theme.

## Working locally

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
mkdocs serve            # live preview at http://127.0.0.1:8000
mkdocs build --strict   # what CI runs; fails on broken links or nav entries
```

## Layout

Every application has the same core pages, so readers can move between them predictably:

| Page | Audience |
|---|---|
| `index.md` — Overview | Everyone |
| `install.md` — Install guide | Whoever deploys it |
| `configuration.md` — Configuration guide | Whoever deploys it |
| `operator-guide.md` — Operator guide | Whoever keeps it running |
| `admin-guide.md` — Admin guide | In-app administrators |
| `user-guide.md` — User guide | End users |

Applications add further pages (architecture, API reference, security, troubleshooting, …)
where they have something to say. New pages must be added to `nav` in `mkdocs.yml`.

## Publishing

`.github/workflows/docs.yml` builds the site with `--strict` on every push and pull request,
and deploys pushes to the default branch to GitHub Pages. Enable it once under
**Settings → Pages → Build and deployment → Source: GitHub Actions**.
