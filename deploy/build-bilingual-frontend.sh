#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/.." && pwd)"
english_frontend="$repository_root/frontend"
chinese_frontend="$repository_root/cn/frontend"
english_output="$english_frontend/dist"

npm --prefix "$english_frontend" ci
npm --prefix "$english_frontend" run build
npm --prefix "$chinese_frontend" ci
npm --prefix "$chinese_frontend" run build

mkdir -p "$english_output/cn" "$english_output/demo" "$english_output/cn/demo"
cp -a "$chinese_frontend/dist/." "$english_output/cn/"
cp -a "$repository_root/demo/." "$english_output/demo/"
cp -a "$repository_root/cn/demo/." "$english_output/cn/demo/"

# Nginx normally runs as an unprivileged user. Keep generated files readable
# even when the deployment account uses a restrictive umask.
find "$english_output" -type d -exec chmod 755 {} +
find "$english_output" -type f -exec chmod 644 {} +

test -f "$english_output/index.html"
test -f "$english_output/cn/index.html"
test -f "$english_output/demo/index.html"
test -f "$english_output/cn/demo/index.html"

printf 'Bilingual frontend bundle is ready at %s\n' "$english_output"
