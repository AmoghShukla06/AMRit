// Supabase Edge Function entry point. Deploy: see backend/DEPLOY.md.
// SUPABASE_DB_URL is provided to every Edge Function by Supabase; OPENROUTER_API_KEY and EXPORT_TOKEN are secrets.
import { configFromEnv, handle } from "./handler.ts";
import { PgStore } from "./store.ts";

const store = new PgStore(Deno.env.get("SUPABASE_DB_URL")!);
const config = configFromEnv((k) => Deno.env.get(k));

Deno.serve((req) => handle(req, { store, config, fetch, log: (m) => console.log(m) }));
