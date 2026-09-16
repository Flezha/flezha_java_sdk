#!/usr/bin/env bash
# Fetch what this SDK needs from one core release, each asset checked against
# the release's SHA-256 manifest before it is used.
#
# This SDK is proven against a core it does not contain. A release of the core
# publishes the native libraries, the fake controller and the contract set as
# assets with their digests in one manifest, so obtaining them is a download
# and a checksum rather than a checkout and a Rust toolchain.
#
#   scripts/fetch-core.sh --from <dir|url> [--version 0.1.0]
#       the host's library, the fake controller and the contract set: what the
#       three tiers need
#   scripts/fetch-core.sh --from <dir|url> [--version 0.1.0] --all-platforms
#       the five libraries a portable jar bundles, leaving any already staged
#       alone so a set cross-built in the core's tree is never overwritten
#
# `--from` is a staged release directory (what `scripts/build-dist.sh
# --stage-release` writes in the core's tree) or the URL a GitHub release
# serves its assets from. Everything it installs is build output and is ignored
# by git: the libraries under `flezha-sdk-native/src/main/resources/native/`,
# the fake controller at `.core/`, and the contract set at `contract/`.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

FROM=""
VERSION="0.1.0"
ALL_PLATFORMS=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --from)          FROM="$2"; shift 2 ;;
    --version)       VERSION="$2"; shift 2 ;;
    --all-platforms) ALL_PLATFORMS=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

refuse() {
  echo "fetch-core: $*" >&2
  exit 1
}

[[ -n "${FROM}" ]] || refuse "--from <dir|url> is required"
command -v sha256sum >/dev/null 2>&1 || refuse "sha256sum is required"

NATIVE=flezha-sdk-native/src/main/resources/native

# The platform a core release names its per-platform assets by, the directory
# column of the core's `scripts/native_triples.sh`, with each one's extension.
declare -A EXT=([linux-x64]=so [linux-arm64]=so [macos-x64]=dylib [macos-arm64]=dylib [windows-x64]=dll)

scratch=.core/download
rm -rf "${scratch}"
mkdir -p "${scratch}"

# One asset's bytes, from a directory or a release URL.
asset() {
  local name="$1" into="$2"
  if [[ "${FROM}" =~ ^https?:// ]]; then
    curl -fsSL "${FROM%/}/${name}" -o "${into}"
  else
    [[ -f "${FROM}/${name}" ]] && cp "${FROM}/${name}" "${into}"
  fi
}

# One asset, checked against the manifest and moved to `into` only if it matches.
verified() {
  local name="$1" into="$2" expected actual
  expected="$(awk -v name="${name}" '$2 == name || $2 == "*" name { print $1 }' "${scratch}/SHA256SUMS.txt")"
  [[ -n "${expected}" ]] || refuse "${name} is not in SHA256SUMS.txt; it was not part of this release"
  asset "${name}" "${scratch}/${name}" || refuse "${name}: listed in SHA256SUMS.txt but not obtainable from ${FROM}"
  actual="$(sha256sum "${scratch}/${name}" | awk '{ print $1 }')"
  [[ "${actual}" == "${expected}" ]] || refuse "${name} does not match its manifest digest"
  mkdir -p "$(dirname "${into}")"
  mv "${scratch}/${name}" "${into}"
  echo "  verified ${name} -> ${into}"
}

# One platform's library, under the directory the runtime's loader reads.
library() {
  local platform="$1" ext="${EXT[$1]}"
  verified "libflz_core_ffi-${platform}.${ext}" "${NATIVE}/${platform}/libflz_core_ffi.${ext}"
}

if (( ALL_PLATFORMS )); then
  missing=()
  for platform in "${!EXT[@]}"; do
    if [[ -f "${NATIVE}/${platform}/libflz_core_ffi.${EXT[${platform}]}" ]]; then
      echo "  kept the staged ${platform} library"
    else
      missing+=("${platform}")
    fi
  done
  # A set cross-built in the core's tree is packaged before its release exists,
  # so nothing is asked of a release that has nothing to add.
  (( ${#missing[@]} > 0 )) || { rm -rf "${scratch}"; exit 0; }
fi

echo "fetch-core: taking the ${VERSION} core from ${FROM}"
# No manifest is no release. Cross-building the libraries instead is the core
# tree's job, because this repository has no workspace to build them from.
asset SHA256SUMS.txt "${scratch}/SHA256SUMS.txt" || refuse "no core release v${VERSION} at ${FROM}.
Cross-build the libraries in a checkout of flezha/flezha, which carries this
repository at sdk/java, with:
  scripts/build-native.sh --output sdk/java/${NATIVE} --targets all"

if (( ALL_PLATFORMS )); then
  for platform in "${missing[@]}"; do
    library "${platform}"
  done
  rm -rf "${scratch}"
  echo "fetch-core: done"
  exit 0
fi

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)               host=linux-x64 ;;
  Linux-aarch64|Linux-arm64)  host=linux-arm64 ;;
  Darwin-x86_64)              host=macos-x64 ;;
  Darwin-arm64)               host=macos-arm64 ;;
  *) refuse "no core release asset is published for $(uname -s) $(uname -m)" ;;
esac
library "${host}"

verified "flz-fake-controller-${host}" .core/flz-fake-controller
chmod +x .core/flz-fake-controller

verified "flezha-fixtures-${VERSION}.tar.gz" "${scratch}/fixtures.tar.gz"
rm -rf contract
mkdir -p contract
tar -xzf "${scratch}/fixtures.tar.gz" -C contract
echo "  unpacked the contract set -> contract/"

rm -rf "${scratch}"
echo "fetch-core: done"
