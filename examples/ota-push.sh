#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────
# OTA Push Script (reference template)
#
# Builds your web bundle, zips the `dist/` directory, uploads to S3,
# and publishes a manifest.json that the plugin will consume on the
# next cold boot.
#
# You do not need S3 specifically — any public HTTPS endpoint that can
# serve the zip + manifest works. The two `aws s3` commands and the
# CloudFront invalidation at the bottom are the only AWS-specific parts
# and are trivially swappable.
#
# Usage:
#   bash ota-push.sh 1.2.0         (silent update)
#   bash ota-push.sh 1.2.0 true    (force update — app calls applyNow())
#
# ─── Threat model ────────────────────────────
# The SHA-256 below is INTEGRITY ONLY. It protects against accidental
# corruption (truncated download, byte-flips, CDN cache poisoning that
# preserves the URL). It is NOT authenticity — an attacker who can
# write to your manifest URL can publish a malicious bundle and a
# matching checksum, and devices will install it.
#
# Authenticity today is delegated to your bucket/origin access
# controls. For end-to-end authenticity, compute an ed25519 signature
# over the bundle bytes, put the hex in the manifest's optional
# `signature` field, and pin the public key in the plugin. The plugin
# carries the field today and ignores it; verification will land in
# a future minor.
# ─────────────────────────────────────────────

VERSION="${1:?Usage: ota-push.sh VERSION [FORCE]}"
FORCE="${2:-false}"

# ─── Configure for your stack ────────────────
BUCKET="${OTA_BUCKET:-YOUR_BUCKET}"
REGION="${OTA_REGION:-YOUR_REGION}"
CDN="${OTA_CDN:-https://cdn.example.com}"
DISTRIBUTION_ID="${OTA_DISTRIBUTION_ID:-YOUR_CLOUDFRONT_DISTRIBUTION_ID}"

# Bump this when you ship a native APK with breaking plugin changes.
# The plugin passes this through from the manifest; your JS decides
# whether to gate on it.
MIN_NATIVE_VERSION="${MIN_NATIVE_VERSION:-1.0}"
# ─────────────────────────────────────────────

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

cd "$(dirname "$0")"

# ── Build your web bundle ────────────────────
# Replace this with whatever produces a Capacitor-compatible webroot
# in ./dist (index.html at the root + referenced assets).
#
# Example:
#   npm run build
#   # or: pnpm run build, yarn build, bun run build, etc.
echo -e "${YELLOW}Build your web bundle now (e.g. 'npm run build').${NC}"
echo -e "${YELLOW}This template assumes the output is at ./dist${NC}"
if [ ! -d dist ]; then
    echo -e "${RED}./dist not found — aborting.${NC}" >&2
    exit 1
fi

# ── Zip ──────────────────────────────────────
echo -e "${YELLOW}Zipping dist...${NC}"
ZIPFILE="ota-${VERSION}.zip"
(cd dist && zip -qr "../${ZIPFILE}" .)

# ── Checksum ─────────────────────────────────
CHECKSUM=$(shasum -a 256 "${ZIPFILE}" | cut -d' ' -f1)
echo -e "${GREEN}SHA256: ${CHECKSUM}${NC}"

# ── Upload bundle ────────────────────────────
echo -e "${YELLOW}Uploading bundle to S3...${NC}"
aws s3 cp "${ZIPFILE}" "s3://${BUCKET}/ota/bundles/${VERSION}.zip" \
    --region "${REGION}" \
    --cache-control "public, max-age=31536000, immutable"

# ── Upload manifest ──────────────────────────
echo -e "${YELLOW}Uploading manifest...${NC}"
cat > /tmp/ota-manifest.json <<MANIFEST
{
  "version": "${VERSION}",
  "url": "${CDN}/ota/bundles/${VERSION}.zip",
  "checksum": "${CHECKSUM}",
  "min_native_version": "${MIN_NATIVE_VERSION}",
  "force": ${FORCE},
  "released_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
MANIFEST

# Atomic two-step swap. A direct `s3 cp` to manifest.json has a
# sub-second window where a client GET can read a partial body while
# the upload is mid-flight (S3 multipart upload visibility is
# per-object, not per-write).
#
#   Step 1: upload to manifest.json.new (different key — readers never see this)
#   Step 2: server-side COPY to manifest.json (atomic at the object level —
#           clients see either the old or the new manifest, never a torn one)
#   Step 3: best-effort cleanup of .new (failure here is harmless — next push overwrites)
aws s3 cp /tmp/ota-manifest.json "s3://${BUCKET}/ota/manifest.json.new" \
    --region "${REGION}" \
    --cache-control "public, max-age=300"

aws s3 cp "s3://${BUCKET}/ota/manifest.json.new" "s3://${BUCKET}/ota/manifest.json" \
    --region "${REGION}" \
    --cache-control "public, max-age=300" \
    --metadata-directive REPLACE

aws s3 rm "s3://${BUCKET}/ota/manifest.json.new" --region "${REGION}" 2>/dev/null || true

# ── Invalidate manifest in CDN ───────────────
# Bundles are cache-forever (versioned URL). Manifest must be invalidated
# so clients see the new version within minutes rather than after the
# `max-age=300` TTL expires everywhere.
if [ "${DISTRIBUTION_ID}" != "YOUR_CLOUDFRONT_DISTRIBUTION_ID" ]; then
    echo -e "${YELLOW}Invalidating CDN cache for manifest...${NC}"
    aws cloudfront create-invalidation \
        --distribution-id "${DISTRIBUTION_ID}" \
        --paths "/ota/manifest.json"
fi

# ── Cleanup ──────────────────────────────────
rm -f "${ZIPFILE}"
rm -f /tmp/ota-manifest.json

echo ""
echo -e "${GREEN}OTA v${VERSION} pushed.${NC}"
echo -e "  Checksum: ${CHECKSUM}"
echo -e "  Force:    ${FORCE}"
echo -e "  URL:      ${CDN}/ota/bundles/${VERSION}.zip"
