#!/usr/bin/env bash
# Actual Android Firebase SDK + Robolectric, against local demo emulators only.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/myfam-android-integration.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
AUTH_PORT="${MYFAM_AUTH_PORT:-19099}"
DATABASE_PORT="${MYFAM_DATABASE_PORT:-19000}"
[[ "$AUTH_PORT" =~ ^[0-9]+$ && "$DATABASE_PORT" =~ ^[0-9]+$ ]] || { echo 'Emulator ports must be integers.' >&2; exit 2; }
FIREBASE="$ROOT/signaling/node_modules/.bin/firebase"
[[ -x "$FIREBASE" ]] || { echo 'First run: cd signaling && npm ci --ignore-scripts --no-audit --no-fund' >&2; exit 2; }
GRADLE="${MYFAM_GRADLE:-$ROOT/gradlew}"
node - "$ROOT" "$WORK" "$AUTH_PORT" "$DATABASE_PORT" <<'JS'
const fs=require('fs');const [root,work,auth,database]=process.argv.slice(2);
fs.writeFileSync(work+'/firebase.json',JSON.stringify({database:[{instance:'demo-my-fam-call-integration',rules:root+'/signaling/database.rules.json'}],emulators:{auth:{host:'127.0.0.1',port:Number(auth)},database:{host:'127.0.0.1',port:Number(database)},hub:{host:'127.0.0.1',port:19440},logging:{host:'127.0.0.1',port:19450},ui:{enabled:false},singleProjectMode:true}}));
JS
cat > "$WORK/integration.gradle" <<GRADLE
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'myfam.integration', 'true'
        systemProperty 'myfam.authPort', '$AUTH_PORT'
        systemProperty 'myfam.databasePort', '$DATABASE_PORT'
        systemProperty 'http.nonProxyHosts', 'localhost|127.*'
        systemProperty 'https.nonProxyHosts', 'localhost|127.*'
        outputs.upToDateWhen { false }
    }
}
GRADLE
{
    printf '#!/usr/bin/env bash\nset -euo pipefail\ncd %q\n' "$ROOT"
    printf 'exec %q --no-daemon ' "$GRADLE"
    if [[ -n "${MYFAM_GRADLE_INIT:-}" ]]; then printf -- '--init-script %q ' "$MYFAM_GRADLE_INIT"; fi
    printf -- '--init-script %q :app:testDebugUnitTest --tests com.myfamcall.app.SignalingIntegrationTest\n' "$WORK/integration.gradle"
} > "$WORK/run-gradle.sh"
chmod +x "$WORK/run-gradle.sh"
# Avoid routing local emulator management through a corporate HTTP proxy.
# Gradle can retain explicit proxy JVM options when dependency downloads need them.
cd "$WORK"
env -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u http_proxy -u https_proxy -u all_proxy \
    NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost \
    "$FIREBASE" emulators:exec --config "$WORK/firebase.json" --project demo-my-fam-call-integration \
    --only auth,database "bash '$WORK/run-gradle.sh'"
