"""Smoke tests: python -m pytest -q  (or just python test_backend.py)"""
import base64, os, tempfile, uuid

os.environ["AMRIT_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
os.environ["OPENROUTER_API_KEY"] = ""
os.environ["TRUSTED_PROXY_HOPS"] = "1"  # the tests play the role of one proxy that appends the caller's IP
os.environ["EXPORT_TOKEN"] = "test-token"
AUTH = {"authorization": "Bearer test-token"}
from fastapi.testclient import TestClient  # noqa: E402
import app as backend  # noqa: E402

c = TestClient(backend.app)
JPEG = base64.b64encode(b"\xff\xd8" + b"0" * 100).decode()


def test_events_validated_and_idempotent():
    eid = str(uuid.uuid4())
    e = {"event_id": eid, "ingredients": ["Azithromycin"], "aware_group": "Watch", "is_antibiotic": True, "banned": False,
         "pincode": "201309", "scanned_at": "2026-11-19T10:37:12Z"}
    assert c.post("/v1/events", json=[e, e]).status_code == 200
    rows = c.get("/v1/events/export", headers=AUTH).json()
    assert len(rows) == 1 and rows[0]["scanned_at"] == "2026-11-19T10:00:00Z"  # hour granularity enforced
    assert c.post("/v1/events", json=[{**e, "event_id": str(uuid.uuid4()), "pincode": "12"}]).status_code == 422
    assert c.post("/v1/events", json=[{**e, "event_id": str(uuid.uuid4()), "aware_group": "Bogus"}]).status_code == 422


def test_resolve_without_key_says_so():
    r = c.post("/v1/resolve", json={"image_jpeg_b64": JPEG, "ocr_tokens": ["x"]}).json()
    assert r["source"] == "error" and "not configured" in r["error"]
    assert c.post("/v1/resolve", json={"image_jpeg_b64": base64.b64encode(b"GIF89a").decode()}).status_code == 400


def test_shared_cache_hit_needs_no_llm():
    with backend.db() as db:
        db.execute("INSERT INTO llm_cache(ocr_sig, reading, created_at, hits) VALUES(?,?,0,0)",
                   ("azithral alembic azithromycin tablets", '{"brand":"Azithral 500","legible":true}'))
    r = c.post("/v1/resolve", json={"image_jpeg_b64": JPEG,
                                    "ocr_tokens": ["Azithral", "500", "Azithromycin", "Tablets", "Alembic"]}).json()
    assert r["source"] == "server_cache" and r["reading"]["brand"] == "Azithral 500" and r["cost_usd"] == 0


def test_alias_promoted_after_two_distinct_reporters():
    a = {"phrase": "zif1 cv", "base_key": "zifi cv", "db_version": "v1"}
    assert c.post("/v1/aliases", json=a, headers={"x-forwarded-for": "1.1.1.1"}).json()["promoted"] is False
    assert c.post("/v1/aliases", json=a, headers={"x-forwarded-for": "1.1.1.1"}).json()["promoted"] is False  # same reporter
    assert c.post("/v1/aliases", json=a, headers={"x-forwarded-for": "2.2.2.2"}).json()["promoted"] is True
    assert c.get("/v1/aliases?since=0&db_version=v1").json()["aliases"] == [{"phrase": "zif1 cv", "base_key": "zifi cv"}]


def test_learned_product_promoted_after_two_reporters():
    r = {"brand_key": "evion 200", "display_name": "Evion 200", "form": "capsule", "items": "2817:200mg", "db_version": "v1"}
    assert c.post("/v1/learned", json=r, headers={"x-forwarded-for": "3.3.3.3"}).json()["promoted"] is False
    assert c.post("/v1/learned", json={**r, "items": "2817:400mg"}, headers={"x-forwarded-for": "4.4.4.4"}).json()["promoted"] is False
    assert c.post("/v1/learned", json=r, headers={"x-forwarded-for": "5.5.5.5"}).json()["promoted"] is True  # 2 agree
    got = c.get("/v1/learned?since=0&db_version=v1").json()["products"]
    assert got == [{"brand_key": "evion 200", "display_name": "Evion 200", "form": "capsule", "items": "2817:200mg"}]
    assert c.post("/v1/learned", json={**r, "items": "DROP TABLE"}).status_code == 422


if __name__ == "__main__":
    for k, f in list(globals().items()):
        if k.startswith("test_"):
            f(); print("ok", k)


def test_export_needs_token():
    assert c.get("/v1/events/export").status_code == 401
    assert c.get("/v1/events/export", headers={"authorization": "Bearer wrong"}).status_code == 401
    assert c.get("/v1/events/export", headers=AUTH).status_code == 200


def test_spoofed_forwarded_for_is_ignored():
    from starlette.requests import Request
    def req(xff):
        return Request({"type": "http", "headers": [(b"x-forwarded-for", xff.encode())], "client": ("10.0.0.1", 1)})
    # a client prepending a fake address can't change the IP our proxy recorded (rightmost entry)
    assert backend.client_ip(req("6.6.6.6, 9.9.9.9")) == "9.9.9.9"
    assert backend.client_ip(req("7.7.7.7, 9.9.9.9")) == "9.9.9.9"


def test_misread_check_keeps_vitamin_numbers():
    r = lambda ns: backend.LlmReading(ingredients=[{"name": n, "strength": None} for n in ns])
    assert not backend.consistent(r(["Pyridoxine IP", "Pyridoxine I.P."]))  # same ingredient twice: a misread
    assert not backend.consistent(r(["Folic Acid B.P.", "Folic acid"]))
    assert backend.consistent(r(["Vitamin B6", "Vitamin B12", "Vitamin B1"]))  # different vitamins
    assert backend.consistent(r(["Pyridoxine Hydrochloride IP", "Thiamine IP"]))


def test_prescription_answer_updates_the_same_event():
    e = {"event_id": str(uuid.uuid4()), "ingredients": ["Azithromycin"], "aware_group": "Watch", "is_antibiotic": True,
         "banned": False, "pincode": "201309", "scanned_at": "2026-11-19T10:37:12Z"}
    assert c.post("/v1/events", json=[e]).status_code == 200
    assert c.post("/v1/events", json=[{**e, "prescribed": False}]).status_code == 200
    assert c.post("/v1/events", json=[e]).status_code == 200  # a retried original upload doesn't erase the answer
    row = [r for r in c.get("/v1/events/export", headers=AUTH).json() if r["event_id"] == e["event_id"]][0]
    assert row["prescribed"] is False
    assert c.get("/v1/stats").json()["antibiotic_without_prescription"] >= 1
