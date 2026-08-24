# Concursos Watch v4 CI and Publication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** publicar dados e dashboard sem endpoint público de escrita, com validação completa, QA visual obrigatório e rollback seguro.

**Architecture:** Python gera artefatos idempotentes; GitHub Actions valida e grava no D1 usando credencial Cloudflare restrita. O dashboard só é promovido depois de testes Python, Rust, segurança HTML/CSS e screenshot. Deploy Rust e publicação de dados são passos separados para permitir rollback independente.

**Tech Stack:** GitHub Actions, Python 3.12+, Rust stable, Wrangler, Cloudflare D1, Android emulator workflow existente.

**Spec:** `docs/superpowers/specs/2026-08-24-concursos-watch-v4-final-design.md`

## Global Constraints

- Nenhum endpoint público de ingestão.
- D1 recebe somente concursos `ACCEPTED`.
- Quarentena/rejeições não são expostas pela API pública.
- Dashboard não publica sem QA visual verde.
- Toda escrita deve ser idempotente.
- Secrets Cloudflare permanecem somente no GitHub Actions.
- Deploy do Rust e atualização do dashboard devem ser reversíveis separadamente.

---

### Task 1: Replace HTTP ingest publisher with D1 batch generator

**Files:**
- Create: `build_d1_snapshot.py`
- Test: `tests/test_build_d1_snapshot.py`
- Retire after cutover: `publish_api_snapshot.py`

**Interfaces:**
- Produces: `build_snapshot(contests_state, priority_state) -> Snapshot`
- Produces: `write_sql(snapshot, path: Path) -> None`
- Produces file: `state/d1_snapshot.sql`

- [ ] **Step 1: Write failing accepted-only snapshot test**

```python
from build_d1_snapshot import build_snapshot


def test_snapshot_excludes_non_accepted_contests():
    contests = {"items": [
        {"id": "ok", "title": "Concurso", "relevance_status": "ACCEPTED"},
        {"id": "bad", "title": "Pregão", "relevance_status": "REJECTED_PROCUREMENT"},
    ]}
    snapshot = build_snapshot(contests, {"events": [], "documents": []})
    assert [x["id"] for x in snapshot.contests] == ["ok"]
```

- [ ] **Step 2: Run and verify failure**

Run: `python -m pytest tests/test_build_d1_snapshot.py -q`

Expected: FAIL because module does not exist.

- [ ] **Step 3: Implement immutable snapshot model**

```python
from dataclasses import dataclass

@dataclass(frozen=True)
class Snapshot:
    contests: tuple[dict, ...]
    documents: tuple[dict, ...]
    events: tuple[dict, ...]
    alerts: tuple[dict, ...]
    source_health: tuple[dict, ...]
```

Filter contests with:

```python
if item.get("relevance_status", "ACCEPTED") == "ACCEPTED"
```

- [ ] **Step 4: Generate parameter-safe SQL literals through one helper**

```python
def sql_text(value: object) -> str:
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"
```

Prefer JSON1/temporary staging table if practical; never concatenate unescaped source text directly.

The output SQL must begin with `BEGIN IMMEDIATE;` and end with `COMMIT;`.

- [ ] **Step 5: Make full snapshot idempotent**

Use `INSERT ... ON CONFLICT(id) DO UPDATE` for contests/documents/events and mark missing contests inactive within the same transaction. Do not delete favorite/user-local state because that remains Android-side.

- [ ] **Step 6: Run tests and commit**

```bash
python -m pytest tests/test_build_d1_snapshot.py -q
git add build_d1_snapshot.py tests/test_build_d1_snapshot.py
git commit -m "feat(ci): generate idempotent D1 snapshot offline"
```

### Task 2: Publish snapshots directly with Wrangler

**Files:**
- Modify: `.github/workflows/monitor.yml`
- Modify: `.github/workflows/cloudflare-deploy.yml`

**Interfaces:**
- Consumes: `state/d1_snapshot.sql`.
- Produces: remote D1 write from CI only.

- [ ] **Step 1: Replace `publish_api_snapshot.py` invocation**

Workflow sequence:

```yaml
- name: Build D1 snapshot
  run: python build_d1_snapshot.py

- name: Apply D1 snapshot
  env:
    CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
    CLOUDFLARE_ACCOUNT_ID: ${{ secrets.CLOUDFLARE_ACCOUNT_ID }}
  run: npx wrangler d1 execute concursos-watch --remote --file=state/d1_snapshot.sql
```

- [ ] **Step 2: Remove WATCHDOG_TOKEN dependency from publication**

Delete `WATCHDOG_TOKEN` from the snapshot publishing path. If another legacy scanner still uses it, keep it scoped only to that scanner until migrated.

- [ ] **Step 3: Add post-write read-only smoke check**

```bash
curl -fsS "$CONCURSOS_API_URL/api/v1/contests?limit=1" | python -m json.tool >/dev/null
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/monitor.yml .github/workflows/cloudflare-deploy.yml
git commit -m "ci(data): write D1 snapshots without public ingest endpoint"
```

### Task 3: Dashboard config validator and publication SQL

**Files:**
- Create: `validate_dashboard_config.py`
- Create: `build_dashboard_sql.py`
- Test: `tests/test_dashboard_config.py`
- Modify: `config/dashboard.json`

**Interfaces:**
- Produces: `validate_dashboard_config(data: dict) -> None`
- Produces: `state/dashboard_publish.sql`

- [ ] **Step 1: Write failing schema tests**

```python
import pytest
from validate_dashboard_config import validate_dashboard_config


def test_unknown_section_type_is_rejected():
    with pytest.raises(ValueError):
        validate_dashboard_config({
            "schema_version": 1,
            "dashboard_version": 2,
            "style_version": 1,
            "min_app_version": "4.0.0",
            "sections": [{"type": "raw_html", "html": "<script>x</script>"}],
        })
```

- [ ] **Step 2: Implement strict allowlist**

Allowed section types:

```python
ALLOWED_SECTIONS = {
    "attention", "priority_watch", "open_contests",
    "region_contests", "closing_soon", "source_warning", "empty_state",
}
```

Reject unknown keys inside each section except the documented keys for that type.

- [ ] **Step 3: Generate publication transaction**

`build_dashboard_sql.py` inserts the validated config as `draft`; promotion to `published` happens in a later task only after visual QA.

Expected SQL shape:

```sql
BEGIN IMMEDIATE;
INSERT INTO dashboard_configs (...) VALUES (...) ON CONFLICT(version) DO UPDATE SET ...;
COMMIT;
```

- [ ] **Step 4: Run tests and commit**

```bash
python -m pytest tests/test_dashboard_config.py -q
git add validate_dashboard_config.py build_dashboard_sql.py tests/test_dashboard_config.py config/dashboard.json
git commit -m "feat(dashboard): validate declarative dashboard config"
```

### Task 4: Static HTML/CSS security gate

**Files:**
- Create: `validate_dashboard_bundle.py`
- Test: `tests/test_dashboard_bundle.py`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: `validate_html(text: str) -> None`
- Produces: `validate_css(text: str) -> None`

- [ ] **Step 1: Write malicious fixture tests**

```python
import pytest
from validate_dashboard_bundle import validate_html, validate_css


def test_rejects_script():
    with pytest.raises(ValueError):
        validate_html("<main><script>alert(1)</script></main>")


def test_rejects_remote_css_url():
    with pytest.raises(ValueError):
        validate_css(".x{background:url(https://evil.example/x.png)}")
```

- [ ] **Step 2: Implement conservative validators**

HTML denylist at minimum:

```text
script iframe object embed form input textarea select button meta[http-equiv=refresh]
```

CSS denylist at minimum:

```text
@import
javascript:
url(http
url(//
expression(
```

- [ ] **Step 3: Wire to CI before deploy**

```yaml
- name: Validate dashboard bundle
  run: python validate_dashboard_bundle.py edge/test-output/dashboard.html edge/assets/dashboard.css
```

- [ ] **Step 4: Commit**

```bash
git add validate_dashboard_bundle.py tests/test_dashboard_bundle.py .github/workflows/ci.yml
git commit -m "ci(dashboard): reject active content and remote resources"
```

### Task 5: Mandatory dashboard visual QA before promotion

**Files:**
- Create: `.github/workflows/dashboard-visual-qa.yml`
- Create: `scripts/capture-dashboard.mjs`
- Modify: `build_dashboard_sql.py`

**Interfaces:**
- Produces artifact: `ConcursosWatch-v4-dashboard-visual-qa`.
- Produces a promotion SQL only when QA job succeeds.

- [ ] **Step 1: Render dashboard locally from Rust**

Add a deterministic Rust test/preview command in the Rust plan that writes:

```text
edge/test-output/dashboard.html
edge/test-output/dashboard.css
```

using fixture D1/dashboard data.

- [ ] **Step 2: Capture screenshot with browser automation**

`scripts/capture-dashboard.mjs` should open the local generated HTML with JavaScript disabled at browser context level and save:

```text
visual-qa/dashboard-phone.png
visual-qa/dashboard-wide.png
```

Use viewport sizes `412x915` and `1080x1920` respectively.

- [ ] **Step 3: Workflow must upload screenshot even on failure**

```yaml
- uses: actions/upload-artifact@v4
  if: always()
  with:
    name: ConcursosWatch-v4-dashboard-visual-qa
    path: visual-qa/
```

- [ ] **Step 4: Gate publication on QA success**

The promotion job must use `needs: [validate, visual_qa]` and `if: github.ref == 'refs/heads/main' && needs.visual_qa.result == 'success'`.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/dashboard-visual-qa.yml scripts/capture-dashboard.mjs build_dashboard_sql.py
git commit -m "ci(dashboard): require visual QA before publication"
```

### Task 6: Promote dashboard atomically and preserve rollback version

**Files:**
- Modify: `build_dashboard_sql.py`
- Modify: `.github/workflows/dashboard-visual-qa.yml`
- Test: `tests/test_dashboard_config.py`

**Interfaces:**
- Produces: `state/dashboard_promote.sql`.

- [ ] **Step 1: Add promotion SQL test**

Expected SQL semantics:

```sql
BEGIN IMMEDIATE;
UPDATE dashboard_configs SET status='superseded' WHERE status='published';
UPDATE dashboard_configs SET status='published' WHERE version=<candidate> AND status='draft';
COMMIT;
```

Assert exactly one row can become published.

- [ ] **Step 2: Store previous published version in job summary**

Before promotion:

```bash
npx wrangler d1 execute concursos-watch --remote --command "SELECT version FROM dashboard_configs WHERE status='published' ORDER BY version DESC LIMIT 1"
```

Record it in `$GITHUB_STEP_SUMMARY` as rollback version.

- [ ] **Step 3: Apply promotion**

```bash
npx wrangler d1 execute concursos-watch --remote --file=state/dashboard_promote.sql
```

- [ ] **Step 4: Verify manifest version**

```bash
curl -fsS "$CONCURSOS_API_URL/api/v1/dashboard-manifest" > manifest.json
python - <<'PY'
import json
m=json.load(open('manifest.json'))
assert int(m['dashboard_version']) == int(__import__('os').environ['EXPECTED_VERSION'])
PY
```

- [ ] **Step 5: Commit**

```bash
git add build_dashboard_sql.py .github/workflows/dashboard-visual-qa.yml tests/test_dashboard_config.py
git commit -m "feat(dashboard): publish with atomic rollback-safe promotion"
```

### Task 7: Full CI matrix and release gates

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/android-apk.yml`
- Modify: `.github/workflows/android-visual-qa.yml`
- Modify: `.github/workflows/cloudflare-deploy.yml`

**Interfaces:**
- Produces mandatory green gates for v4.

- [ ] **Step 1: Add Rust checks**

```yaml
- name: Rust format
  run: cargo fmt --manifest-path edge/Cargo.toml --check
- name: Rust clippy
  run: cargo clippy --manifest-path edge/Cargo.toml --all-targets -- -D warnings
- name: Rust tests
  run: cargo test --manifest-path edge/Cargo.toml
```

- [ ] **Step 2: Add Python v4 gates**

```yaml
- name: Python v4 tests
  run: python -m pytest tests/test_relevance_filter.py tests/test_new_contests.py tests/test_build_d1_snapshot.py tests/test_dashboard_config.py tests/test_dashboard_bundle.py -q
```

- [ ] **Step 3: Keep Android gates**

Required:

```text
:testDebugUnitTest
:assembleDebug
connected security test for WebView
Android Visual QA screenshots
```

- [ ] **Step 4: Block release if any subsystem is red**

Do not use `continue-on-error` for relevance, Rust API, dashboard security, Android dashboard validation or visual QA jobs.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml .github/workflows/android-apk.yml \
        .github/workflows/android-visual-qa.yml .github/workflows/cloudflare-deploy.yml
git commit -m "ci(v4): enforce end-to-end release gates"
```

### Task 8: Delete legacy public ingest only after v4 verification

**Files:**
- Delete: `publish_api_snapshot.py`
- Delete after Rust production parity: `cloudflare/src/app.js`
- Modify: `README.md`
- Modify: `docs/SETUP.md`

**Interfaces:**
- Consumes: successful production Rust API + D1 direct publication.
- Produces: no public write path remaining.

- [ ] **Step 1: Verify no workflow references legacy publisher**

Run:

```bash
grep -R "publish_api_snapshot\|/api/v1/ingest" -n .github cloudflare *.py || true
```

Expected before deletion: only files intentionally being removed.

- [ ] **Step 2: Remove legacy files/references**

Delete `publish_api_snapshot.py` and public JS ingest route after production parity is proven.

- [ ] **Step 3: Document operational flow**

README/SETUP must state:

```text
Python -> validated SQL snapshot -> GitHub Actions -> D1
Rust -> read-only public API/dashboard
Android -> native API + validated local dashboard
```

- [ ] **Step 4: Run full suite**

```bash
python -m pytest -q
cargo test --manifest-path edge/Cargo.toml
gradle -p android :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore(v4): remove legacy public ingestion path"
```
