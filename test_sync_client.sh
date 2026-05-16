#!/usr/bin/env bash
# Usage: ./test_sync_client.sh [DEVICE_IP]
# If DEVICE_IP is omitted, discovers the first _activitywatch-tv._tcp service via mDNS.
# Requires: curl, jq
# macOS: uses dns-sd (built-in)   Linux: uses avahi-browse (avahi-utils)
set -euo pipefail

PORT=5606
MDNS_TYPE="_activitywatch-tv._tcp"

pass()    { printf '\033[32m[PASS]\033[0m %s\n' "$1"; }
fail()    { printf '\033[31m[FAIL]\033[0m %s\n' "$1"; exit 1; }
section() { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
info()    { printf '  %s\n' "$1" >&2; }

# ── mDNS helpers ──────────────────────────────────────────────────────────────

# macOS: dns-sd -B → dns-sd -L → dns-sd -G v4
# Uses process substitution so dns-sd output is read line-by-line as produced
# (file redirection causes stdio full-buffering and lines never arrive in time).
discover_macos() {
    info "Browsing for $MDNS_TYPE via dns-sd ..."

    # Step 1: browse → first instance name
    local svc_name="" line
    while IFS= read -r -t 10 line; do
        if [[ "$line" == *" Add "* ]]; then
            # Columns: timestamp A/R flags if domain service-type [instance words...]
            svc_name=$(awk '{for(i=7;i<=NF;i++) printf "%s%s",$i,(i<NF?" ":""); print ""}' <<<"$line")
            break
        fi
    done < <(dns-sd -B "$MDNS_TYPE" local 2>/dev/null)
    [ -z "$svc_name" ] && return 1
    info "Found: \"$svc_name\""

    # Step 2: lookup → mdns-hostname:port
    local mdns_host="" mdns_port=""
    while IFS= read -r -t 5 line; do
        if [[ "$line" == *"can be reached at"* ]]; then
            mdns_host=$(sed 's/.*can be reached at \([^:]*\):.*/\1/' <<<"$line")
            mdns_port=$(sed 's/.*:\([0-9]*\) (.*/\1/'               <<<"$line")
            mdns_host="${mdns_host%.}"   # strip trailing dot from .local. hostname
            break
        fi
    done < <(dns-sd -L "$svc_name" "$MDNS_TYPE" local 2>/dev/null)
    [ -z "$mdns_host" ] && return 1
    info "Resolved: $mdns_host:$mdns_port"

    # Step 3: .local hostname → bare IPv4 (needed when mDNS doesn't integrate with DNS)
    local ip=""
    while IFS= read -r -t 5 line; do
        if [[ "$line" == *" Add "* ]]; then
            ip=$(awk '{print $6}' <<<"$line")
            break
        fi
    done < <(dns-sd -G v4 "${mdns_host}." 2>/dev/null)

    if [ -n "$ip" ]; then
        info "IP: $ip"
        printf '%s:%s\n' "$ip" "$mdns_port"
    else
        # macOS resolves .local natively — pass hostname directly to curl
        info "Using .local hostname"
        printf '%s:%s\n' "$mdns_host" "$mdns_port"
    fi
}

# Linux: avahi-browse with parseable output
# Format: =;iface;proto;name;type;domain;hostname;ip;port;txt
discover_linux() {
    info "Browsing for $MDNS_TYPE via avahi-browse ..."
    local result=""
    while IFS= read -r -t 10 line; do
        if [[ "$line" == "="* ]]; then
            result="$line"
            break
        fi
    done < <(avahi-browse "$MDNS_TYPE" --resolve -p 2>/dev/null)
    [ -z "$result" ] && return 1
    local ip port
    ip=$(cut -d';' -f8   <<<"$result")
    port=$(cut -d';' -f9 <<<"$result")
    info "Found: $ip:$port"
    printf '%s:%s\n' "$ip" "$port"
}

discover_mdns() {
    case "$(uname -s)" in
        Darwin) command -v dns-sd       &>/dev/null && discover_macos  && return 0 ;;
        Linux)  command -v avahi-browse &>/dev/null && discover_linux  && return 0 ;;
    esac
    return 1
}

# ── Resolve target ─────────────────────────────────────────────────────────────

HOST="${1:-}"

if [ -z "$HOST" ]; then
    section "mDNS Discovery"
    TARGET=$(discover_mdns) \
        || fail "mDNS discovery failed. Install dns-sd (macOS built-in) or avahi-utils (Linux), or pass IP directly: $0 <IP>"
    HOST=$(printf '%s' "$TARGET" | cut -d: -f1)
    DISC_PORT=$(printf '%s' "$TARGET" | cut -d: -f2)
    [ -n "$DISC_PORT" ] && PORT="$DISC_PORT"
    printf '  \033[1mConnecting to %s:%s\033[0m\n' "$HOST" "$PORT"
fi

BASE="http://$HOST:$PORT/api/v0"

# ── 1. Info ────────────────────────────────────────────────────────────────────
section "GET /info"
INFO=$(curl -sf --max-time 5 "$BASE/info") \
    || fail "/info unreachable — is the device on the network and the service running?"
echo "$INFO" | jq .
pass "info"

# ── 2. Buckets ─────────────────────────────────────────────────────────────────
section "GET /buckets"
BUCKETS=$(curl -sf --max-time 5 "$BASE/buckets") || fail "/buckets unreachable"
echo "$BUCKETS" | jq .
pass "buckets"

# ── 3. Bucket events ───────────────────────────────────────────────────────────
FIRST=$(echo "$BUCKETS" | jq -r '.[0].id // empty')
if [ -n "$FIRST" ]; then
    section "GET /buckets/$FIRST/events?limit=5"
    ENCODED=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$FIRST")
    curl -sf --max-time 5 "$BASE/buckets/$ENCODED/events?limit=5" | jq .
    pass "bucket events"
else
    info "[SKIP] No buckets in DB yet"
fi

# ── 4. SSE stream (10 s) ───────────────────────────────────────────────────────
section "SSE stream (10 s, ?since=0)"
info "(Trigger media playback on the TV to see live events)"
curl -sN --max-time 12 \
    -H "Accept: text/event-stream" \
    "$BASE/sync/stream?since=0" &
SPID=$!
sleep 10
kill "$SPID" 2>/dev/null || true
wait "$SPID" 2>/dev/null || true
pass "sse stream (no crash)"

# ── 5. SSE stream with bucket filter ──────────────────────────────────────────
if [ -n "$FIRST" ]; then
    section "SSE stream filtered to bucket '$FIRST' (5 s)"
    curl -sN --max-time 7 \
        -H "Accept: text/event-stream" \
        "$BASE/sync/stream?buckets=$FIRST&since=0" &
    SPID2=$!
    sleep 5
    kill "$SPID2" 2>/dev/null || true
    wait "$SPID2" 2>/dev/null || true
    pass "sse stream with bucket filter"
fi

# ── 6. ACK ────────────────────────────────────────────────────────────────────
section "POST /sync/ack  {\"seq\": 0}"
ACK=$(curl -sf --max-time 5 -X POST \
    -H "Content-Type: application/json" \
    -d '{"seq":0}' \
    "$BASE/sync/ack") || fail "/sync/ack unreachable"
echo "$ACK" | jq .
pass "ack"

# ── 7. Reconnect with Last-Event-ID ───────────────────────────────────────────
section "SSE reconnect with Last-Event-ID: 0 (5 s)"
curl -sN --max-time 7 \
    -H "Accept: text/event-stream" \
    -H "Last-Event-ID: 0" \
    "$BASE/sync/stream" &
SPID3=$!
sleep 5
kill "$SPID3" 2>/dev/null || true
wait "$SPID3" 2>/dev/null || true
pass "sse reconnect"

# ── 8. Error handling ─────────────────────────────────────────────────────────
section "404 for unknown route"
STATUS=$(curl -so /dev/null -w "%{http_code}" --max-time 5 "$BASE/nonexistent")
[ "$STATUS" = "404" ] && pass "404 on unknown route" || fail "expected 404, got $STATUS"

section "400 on bad ACK body"
STATUS=$(curl -so /dev/null -w "%{http_code}" --max-time 5 -X POST \
    -H "Content-Type: application/json" \
    -d '{"bad":"body"}' \
    "$BASE/sync/ack")
[ "$STATUS" = "400" ] && pass "400 on bad ACK body" || fail "expected 400, got $STATUS"

printf '\n\033[1;32mAll tests passed.\033[0m\n'
