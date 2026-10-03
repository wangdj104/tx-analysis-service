#!/usr/bin/env bash
# CI-only synthetic MySQL 8.0 acceptance. Never sources application/.env settings.
set -euo pipefail
set +x
umask 077
refuse() { printf 'care-plan MySQL guard: %s\n' "$1" >&2; exit 64; }
[[ $# -le 1 ]] || refuse 'unsupported arguments'
[[ $# -eq 0 || "$1" == '--check-guards' ]] || refuse 'unsupported argument'
[[ ${CARE_PLAN_TEST_ONLY:-} == true ]] || refuse 'CARE_PLAN_TEST_ONLY=true is required'
case ${CARE_PLAN_MYSQL_HOST:-} in
  127.0.0.1|localhost|::1) ;;
  mysql) [[ ${CI:-} == true && ${GITHUB_ACTIONS:-} == true ]] || refuse 'service host requires isolated CI' ;;
  *) refuse 'host must be loopback or the isolated mysql CI service' ;;
esac
port=${CARE_PLAN_MYSQL_PORT:-}
[[ $port =~ ^[1-9][0-9]{0,4}$ ]] && (( port <= 65535 )) || refuse 'invalid or missing port'
for key in CARE_PLAN_MYSQL_DATABASE CARE_PLAN_MYSQL_UPGRADE_DATABASE CARE_PLAN_MYSQL_RESTORE_DATABASE; do
  value=${!key:-}
  [[ $value =~ ^care_plan_test_[a-z0-9_]+$ && ${#value} -le 64 ]] || refuse 'database must be an explicit care_plan_test_ identifier, at most 64 characters'
done
fresh=$CARE_PLAN_MYSQL_DATABASE
upgrade=$CARE_PLAN_MYSQL_UPGRADE_DATABASE
restore=$CARE_PLAN_MYSQL_RESTORE_DATABASE
[[ $fresh != "$upgrade" && $fresh != "$restore" && $upgrade != "$restore" ]] || refuse 'fresh, upgrade and restore databases must be distinct'
user=${CARE_PLAN_MYSQL_USER:-}
[[ $user =~ ^care_plan_test_[a-z0-9_]+$ && ${#user} -le 32 ]] || refuse 'user must be an ephemeral care_plan_test_ identifier, never root'
password=${CARE_PLAN_MYSQL_PASSWORD:-}
[[ $password =~ ^[a-zA-Z0-9]{24,128}$ ]] || refuse 'password must be a nonempty generated alphanumeric test value (24-128 characters)'
container=${CARE_PLAN_MYSQL_CONTAINER_ID:-}
[[ $container =~ ^[a-f0-9]{12,64}$ ]] || refuse 'container must be the exact CI service container ID'
project=${CARE_PLAN_MYSQL_PROJECT:-}
[[ $project == . || $project == cn ]] || refuse 'project must be . or cn'
if [[ ${1:-} == '--check-guards' ]]; then
  printf 'care-plan MySQL guards passed (no database/client operation performed)\n'
  exit 0
fi
[[ ${CI:-} == true && ${GITHUB_ACTIONS:-} == true ]] || refuse 'execution requires the disposable GitHub Actions MySQL service'
command -v docker >/dev/null || { echo 'Required Docker service client is unavailable' >&2; exit 1; }
command -v mvn >/dev/null || { echo 'Required Maven is unavailable' >&2; exit 1; }
# Validate the selected service before administering anything. Root is used only
# inside this throwaway container over its Unix socket, never over mapped TCP.
service_image=$(docker inspect --format '{{.Config.Image}}' "$container")
[[ $service_image == mysql:8.0 || $service_image == mysql:8.0@sha256:* ]] || refuse 'container image must be the official mysql:8.0 test service'
service_env=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container")
[[ $'\n'$service_env$'\n' == *$'\nMYSQL_ALLOW_EMPTY_PASSWORD=yes\n'* ]] || refuse 'container is not the declared disposable test service'
root_host_lines=0
while IFS= read -r line; do
  if [[ $line == MYSQL_ROOT_HOST=* ]]; then
    ((root_host_lines+=1))
    [[ $line == MYSQL_ROOT_HOST=localhost ]] || refuse 'MYSQL_ROOT_HOST=localhost must be set explicitly'
  fi
done <<< "$service_env"
[[ $root_host_lines -eq 1 ]] || refuse 'MYSQL_ROOT_HOST=localhost must be set exactly once, never defaulted'

unset service_env
admin() { docker exec -i "$container" mysql --user=root --protocol=socket --batch --skip-column-names; }
identity=$(printf 'SELECT CURRENT_USER(), VERSION();\n' | admin)
[[ $identity == root@localhost$'\t'8.0.* ]] || refuse 'socket identity/version is not root@localhost on MySQL 8.0'
nonlocal_roots=$(printf "SELECT COUNT(*) FROM mysql.user WHERE User='root' AND Host<>'localhost';\n" | admin)
[[ $nonlocal_roots == 0 ]] || refuse 'disposable service contains a non-local root account'
partial_revokes=$(printf 'SELECT @@GLOBAL.partial_revokes;\n' | admin)
[[ $partial_revokes == 0 ]] || refuse 'escaped exact-schema grants require partial_revokes=OFF in the disposable service'

printf 'Native MySQL service: %s; image identity: ' "${identity#*$'\t'}"
docker inspect --format '{{.Image}}' "$container"
created=()
user_created=false
cleanup() {
  rc=$?
  trap - EXIT
  # Drop only identifiers whose CREATE succeeded in this invocation. Never use
  # wildcard schema cleanup or IF NOT EXISTS to adopt another run's database.
  for database in "${created[@]}"; do
    if ! printf 'DROP DATABASE `%s`;\n' "$database" | admin >/dev/null; then
      echo "Synthetic schema cleanup failed: $database" >&2
      rc=1
    fi
  done
  if [[ $user_created == true ]]; then
    if ! printf "DROP USER '%s'@'%%';\n" "$user" | admin >/dev/null; then
      echo 'Ephemeral synthetic user cleanup failed' >&2
      rc=1
    fi
  fi
  exit "$rc"
}
trap cleanup EXIT
for database in "$fresh" "$upgrade" "$restore"; do
  printf 'CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n' "$database" | admin >/dev/null
  created+=("$database")
done
# Password travels only on stdin within the disposable service, never argv/logs.
printf "CREATE USER '%s'@'%%' IDENTIFIED BY '%s';\n" "$user" "$password" | admin >/dev/null
user_created=true
unset password
for database in "${created[@]}"; do
  # Database-level GRANT treats unescaped underscores as SQL wildcards even
  # inside backticks. Match the official entrypoint's literal-name escaping.
  grant_database=${database//_/\\_}
  printf "GRANT ALL PRIVILEGES ON \`%s\`.* TO '%s'@'%%';\n" "$grant_database" "$user" | admin >/dev/null
done
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$root/$project"
mvn -B -Dtest=CarePlanMysqlIntegrationTest -DcarePlanMysqlRequired=true -DskipTests=false test
printf 'Native MySQL schema, upgrade, constraint, UTC, row-lock and full restore assertions passed\n'
