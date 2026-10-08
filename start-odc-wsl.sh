#!/usr/bin/env bash
# WSL launcher for odc-server, based on script/nohup-start-odc.sh + script/start-odc.sh
#
# Usage:
#   wsl bash /mnt/e/Projects/Java/odc/start-odc-wsl.sh            # local datasource (OB in docker on Windows host)
#   wsl bash /mnt/e/Projects/Java/odc/start-odc-wsl.sh handheld   # handheld datasource (10.79.173.8)
#
# Mapping to the shell scripts:
#   - delegates to script/start-odc.sh (nohup-style background start, like nohup-start-odc.sh)
#   - datasource presets replace `export DATABASE_*`:
#       local    : mysql -h127.0.0.1   -P2881 -uodc@test     -p"$LOCAL_DB_PASSWORD"
#                  (from WSL2 NAT, the Windows-host docker OB is reached via the default gateway IP)
#       handheld : mysql -h10.79.173.8 -P2881 -uodcuser@test -p"$HANDHELD_DB_PASSWORD"
#   - DB credentials are NOT stored in this script (it is committed to a public repo):
#     export LOCAL_DB_PASSWORD / HANDHELD_DB_PASSWORD yourself, or define them in
#     start-odc-wsl.local.env next to this script (kept out of git), e.g.
#       LOCAL_DB_PASSWORD='...'
#       HANDHELD_DB_PASSWORD='...'
#   - JDK8 lives in the WSL home dir, exported as JAVA_HOME for start-odc.sh
#   - SPRING_DATASOURCE_URL re-adds the yml jdbc params plus connect/socket timeouts;
#     env var keeps the URL away from start-odc.sh's `eval` (the '&' would break it)
#   - module dir is passed via ODC_JVM_EXTRA_OPTIONS (start-odc.sh has no ODC_MODULE_DIR hook)

set -u

DATASOURCE="${1:-local}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk1.8.0_411}"

# credentials: env vars first, then the local untracked file (never committed)
LOCAL_ENV_FILE="$ROOT/start-odc-wsl.local.env"
if [ -f "$LOCAL_ENV_FILE" ]; then
  . "$LOCAL_ENV_FILE"
fi

require_env() {
  if [ -z "${!1:-}" ]; then
    echo "[ERROR] $1 is not set; export it or define it in $LOCAL_ENV_FILE" >&2
    exit 1
  fi
}

case "$DATASOURCE" in
  local)
    WINHOST="$(ip route show default | awk '{print $3}')"
    if [ -z "$WINHOST" ]; then
      echo "[ERROR] cannot resolve WSL default gateway (Windows host IP) for local datasource" >&2
      exit 1
    fi
    export DATABASE_HOST="$WINHOST"
    export DATABASE_PORT="2881"
    export DATABASE_NAME="odc_metadb"
    export DATABASE_USERNAME="${LOCAL_DB_USERNAME:-odc@test}"
    require_env LOCAL_DB_PASSWORD
    export DATABASE_PASSWORD="$LOCAL_DB_PASSWORD"
    ;;
  handheld)
    export DATABASE_HOST="10.79.173.8"
    export DATABASE_PORT="2881"
    export DATABASE_NAME="odc_metadb"
    export DATABASE_USERNAME="${HANDHELD_DB_USERNAME:-odcuser@test}"
    require_env HANDHELD_DB_PASSWORD
    export DATABASE_PASSWORD="$HANDHELD_DB_PASSWORD"
    ;;
  *)
    echo "usage: $0 [local|handheld]" >&2
    exit 1
    ;;
esac

# jar: prefer lib/odc-server-*-executable.jar (start-odc.sh default location), fall back to maven target
JAR="$(ls -t "$ROOT"/lib/odc-server-*-executable.jar 2>/dev/null | head -1)"
if [ -z "$JAR" ]; then
  JAR="$(ls -t "$ROOT"/server/odc-server/target/odc-server-*-executable.jar 2>/dev/null | head -1)"
fi
if [ -z "$JAR" ]; then
  echo "[ERROR] no executable jar found in lib/ or server/odc-server/target/" >&2
  echo "run first: script/build_jar.sh (or ./mvnw -pl server/odc-server -am package -Dmaven.test.skip=true)" >&2
  exit 1
fi

export ODC_SERVER_PORT="${ODC_SERVER_PORT:-8990}"
export ODC_LOG_DIR="$ROOT/log"
export OBCLIENT_WORK_DIR="$ROOT/data"
export ODC_PLUGIN_DIR="$ROOT/distribution/plugins"
export ODC_STARTER_DIR="$ROOT/distribution/starters"
export ODC_JAR_FILE="$JAR"
export ODC_WORK_DIR="$ROOT"
export ODC_JVM_EXTRA_OPTIONS="-Dmodule.dir=$ROOT/distribution/modules"
export SPRING_DATASOURCE_URL="jdbc:oceanbase://$DATABASE_HOST:$DATABASE_PORT/$DATABASE_NAME?allowMultiQueries=true&zeroDateTimeBehavior=convertToNull&useCompatibleMetadata=true&connectTimeout=10000&socketTimeout=120000"

mkdir -p "$ODC_LOG_DIR" "$OBCLIENT_WORK_DIR"

echo "=== start odc-server via WSL (datasource=$DATASOURCE) ==="
echo "JAVA_HOME : $JAVA_HOME"
echo "Jar       : $JAR"
echo "DB        : $DATABASE_HOST:$DATABASE_PORT/$DATABASE_NAME"
echo "Port      : $ODC_SERVER_PORT"

nohup bash "$ROOT/script/start-odc.sh" > "$ODC_LOG_DIR/odc-stdout-wsl-$DATASOURCE.log" 2>&1 &
ret=$?
pid=$!
echo "start odc-server done, ret=$ret, pid=$pid"

sleep 2
if kill -0 "$pid" 2>/dev/null; then
  echo "process start success!"
  echo "you may check log by 'tailf $ODC_LOG_DIR/odc.log'"
else
  echo "process start failed! check $ODC_LOG_DIR/odc-stdout-wsl-$DATASOURCE.log :" >&2
  tail -30 "$ODC_LOG_DIR/odc-stdout-wsl-$DATASOURCE.log" >&2
  exit 1
fi
