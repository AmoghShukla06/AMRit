import { assertEquals } from "jsr:@std/assert@1.0.8";
import { aggregate, DashEvent } from "../api/logic.ts";

const now = new Date("2026-11-20T12:00:00Z");
const ev = (o: Partial<DashEvent>): DashEvent => ({ ingredients: ["Paracetamol"], aware_group: null, is_antibiotic: false, banned: false,
  pincode: null, scanned_at: "2026-11-20T09:00:00Z", prescribed: null, ...o });
const abx = (group: string, pin: string, prescribed: boolean | null = null) =>
  ev({ ingredients: ["Azithromycin"], aware_group: group, is_antibiotic: true, pincode: pin, prescribed });

Deno.test("totals and the WHO group mix", () => {
  const d = aggregate([abx("Watch", "201309"), abx("Access", "201301"), ev({}), ev({ banned: true })], now) as any;
  assertEquals(d.total_scans, 4);
  assertEquals(d.antibiotic_scans, 2);
  assertEquals(d.by_group, { Access: 1, Watch: 1, Reserve: 0 });
  assertEquals(d.banned_scans, 1);
});

Deno.test("areas are 3-digit postal regions and need at least k scans to be shown", () => {
  const rows = [...Array(5)].map(() => abx("Watch", "201309")).concat([abx("Access", "560001")]);
  const d = aggregate(rows, now) as any;
  assertEquals(d.regions.map((r: any) => r.region), ["201"]);   // 560 has 1 scan: hidden
  assertEquals(d.regions[0].scans, 5);
  assertEquals(d.regions[0].watch_reserve_share, 1);
  assertEquals(d.hidden_regions, 1);
  // never a full pincode anywhere in the output
  assertEquals(JSON.stringify(d).includes("201309"), false);
});

Deno.test("prescription share is shown only once k answers exist", () => {
  const few = aggregate([abx("Watch", "201309", false), abx("Watch", "201309", true)], now) as any;
  assertEquals(few.without_prescription_share, null);
  const rows = [false, false, false, true, true].map((p) => abx("Watch", "201309", p));
  const d = aggregate(rows, now) as any;
  assertEquals(d.rx_answered, 5);
  assertEquals(d.without_prescription_share, 0.6);
});

Deno.test("last 30 days by day, oldest first", () => {
  const d = aggregate([ev({ scanned_at: "2026-11-19T10:00:00Z" }), abx("Watch", "201309"), ev({ scanned_at: "2026-09-01T10:00:00Z" })], now) as any;
  assertEquals(d.daily.length, 30);
  assertEquals(d.daily[29], { day: "2026-11-20", scans: 1, antibiotic: 1 });
  assertEquals(d.daily[28], { day: "2026-11-19", scans: 1, antibiotic: 0 });
});
