#!/usr/bin/env bash
# OPTIONAL, run it yourself: gives the laptop backend a public HTTPS address so judges' phones can reach it over
# any network (no Cloudflare account needed). This exposes the backend to the internet while it runs; it holds no
# user data beyond anonymous events, is rate-limited per IP and has a daily spend cap, but stop it after the demo.
#   1. download cloudflared:  https://github.com/cloudflare/cloudflared/releases (cloudflared-windows-amd64.exe)
#   2. bash backend/tunnel.sh   -> prints https://<random>.trycloudflare.com
#   3. put that URL in the app: Settings > Advanced > Backend URL (it changes on every run)
exec "${CLOUDFLARED:-$LOCALAPPDATA/amrit-tools/cloudflared.exe}" tunnel --no-autoupdate --url http://localhost:8000
