#!/usr/bin/env bash
# 合并后全量总账冒烟驱动：gl1~gl9 各用独立空库，依次起 8081 后端、跑脚本、杀进程、查错误
# 用法（在 backend/ 上一级工作树根执行）：bash development/06-testing/scripts/_gl-merge-smoke-driver.sh
set -u
ROOT=/e/work/erp-wms-tms-gl
BK=$ROOT/backend
JAR=$BK/target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar
JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVA" ] || JAVA=java
EXCLUDE=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration
LOGDIR=/tmp/gl-smoke-logs
mkdir -p "$LOGDIR"

kill8081() {
  local pid
  pid=$(netstat -ano | grep ':8081' | grep LISTENING | awk '{print $5}' | head -1)
  if [ -n "$pid" ]; then cmd //c "taskkill /F /PID $pid" >/dev/null 2>&1; fi
  sleep 2
}

wait_ready() {
  for i in $(seq 1 60); do
    out=$(curl -s -m 3 -X POST http://localhost:8081/api/auth/login \
      -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}' 2>/dev/null)
    if echo "$out" | grep -q '"code":"0"'; then return 0; fi
    sleep 2
  done
  return 1
}

PASS=0; FAIL=0; FAILED=""
for n in 1 2 3 4 5 6 7 8 9; do
  SCRIPT=$(ls "$ROOT"/development/06-testing/scripts/gl-m${n}-*.js | head -1)
  NAME=$(basename "$SCRIPT")
  DB="erp-smoke-gl${n}"
  echo "==================== M${n}: ${NAME} ===================="
  kill8081
  rm -f "$BK/data/${DB}.mv.db" "$BK/data/${DB}.trace.db"
  BLOG="$LOGDIR/backend-gl${n}.log"
  ( cd "$BK" && "$JAVA" -jar "$JAR" --server.port=8081 \
    "--spring.datasource.url=jdbc:h2:file:./data/${DB};DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
    "--spring.autoconfigure.exclude=${EXCLUDE}" >"$BLOG" 2>&1 ) &
  BK_PID=$!
  if wait_ready; then
    SLOG="$LOGDIR/smoke-gl${n}.log"
    ( cd "$ROOT" && node "$SCRIPT" ) >"$SLOG" 2>&1
    RC=$?
    if [ $RC -eq 0 ]; then
      echo "M${n} SMOKE: PASS"; PASS=$((PASS+1))
    else
      echo "M${n} SMOKE: FAIL (rc=$RC)"; tail -20 "$SLOG"; FAIL=$((FAIL+1)); FAILED="$FAILED M${n}"
    fi
  else
    echo "M${n} BACKEND: 启动失败"; tail -30 "$BLOG"; FAIL=$((FAIL+1)); FAILED="$FAILED M${n}(boot)"
  fi
  kill8081
  ERR=$(grep -c ' ERROR ' "$BLOG" 2>/dev/null || echo 0)
  echo "M${n} 后端日志 ERROR 行数: $ERR"
done

echo "==================================================="
echo "汇总: PASS=$PASS FAIL=$FAIL  失败项:$FAILED"
