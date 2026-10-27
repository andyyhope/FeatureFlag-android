#!/usr/bin/env bash
# Regenerates fixtures/ from FeatureFlag 0.12.1 through fixture-generator/.
# fixtures/ is only ever written by this script; never edit it by hand.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT

swift run --package-path "$root/fixture-generator" generate-fixtures "$staging/fixtures"

rm -rf "$root/fixtures"
mv "$staging/fixtures" "$root/fixtures"
echo "fixtures/ now holds $(find "$root/fixtures" -type f | wc -l | tr -d ' ') files."
