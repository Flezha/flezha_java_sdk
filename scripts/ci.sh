#!/usr/bin/env bash
# The three tiers this repository runs, and no controller anywhere.
#
#   unit        the modules' own suites, including the fixture checks that read
#               the contract set, and the spec-coverage gate `verify` runs
#   local mode  the declared graph run in process, over the five capabilities
#               the language layer owns
#   smoke       the fake controller on a socket directory, this repository's own
#               test app launched against it, and the binary's exit code
#
# Everything the core contributes is fetched from one of its releases and
# checked against that release's manifest: the native library, the fake
# controller and the contract set. Nothing here builds the core, and nothing
# here needs a checkout of it.
#
#   scripts/ci.sh --core <dir|url> [--version 0.1.0]
#
# What this cannot see is the fifteen-capability vocabulary the controller
# owns: persistence, effectively-once, correction, partitioning, workflows,
# liveness, restarts. Those are end-to-end scenarios and they run in the core's
# own tree, against the commit it pins this repository at.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

CORE=""
VERSION="0.1.0"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --core)    CORE="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done
[[ -n "${CORE}" ]] || { echo "--core <dir|url> is required" >&2; exit 1; }

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

step "fetching the core ${VERSION}"
scripts/fetch-core.sh --from "${CORE}" --version "${VERSION}"

# `install` rather than `verify`: the test app resolves the SDK from the local
# Maven repository, so the modules this run tested are the ones it launches.
step "unit, local mode and spec coverage"
mvn -B -P native-verify install

step "building the test app"
mvn -B -q -DskipTests -f test_app/pom.xml package

step "smoke"
# The fake controller launches the runner against a socket directory of its own,
# holds its standard input open for the run and prints its output on failure.
# The tier's verdict is the fake controller's, never the runner's: a terminal
# disconnect ends the runner non-zero on purpose, so the orchestrator that owns
# it restarts it.
exec .core/flz-fake-controller \
  --factory do_nothing --operative echo-1 --channel input \
  --input alpha --input beta --timeout-ms 60000 \
  -- java -jar test_app/target/java_runner.jar smoke
