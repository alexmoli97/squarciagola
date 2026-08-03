#!/usr/bin/env bash
#
# Pubblica una release su GitHub a partire dal versionCode dichiarato nel build.
#
# Il tag non si passa a mano di proposito: viene ricavato da app/build.gradle.kts, perché
# l'unico errore capace di rompere l'aggiornamento in modo silenzioso è un tag che non
# corrisponde al versionCode dell'APK allegata. Il telefono scaricherebbe, Android
# rifiuterebbe l'installazione, e nulla lo spiegherebbe.
#
# Uso:
#   ./release.sh                 note generate da GitHub dai commit
#   ./release.sh "Cosa cambia"   note scritte da te

set -euo pipefail
cd "$(dirname "$0")"

note="${1:-}"
build="app/build.gradle.kts"
apk="app/build/outputs/apk/debug/app-debug.apk"

# --- controlli preliminari ----------------------------------------------------------------

ramo=$(git branch --show-current)
if [ "$ramo" != "main" ]; then
    echo "ERRORE: sei su '$ramo'. Le release si fanno da main." >&2
    exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
    echo "ERRORE: ci sono modifiche non committate. Una release deve corrispondere a un commit." >&2
    git status --short >&2
    exit 1
fi

versionCode=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$build" | head -1)
versionName=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$build" | head -1)
if [ -z "$versionCode" ] || [ -z "$versionName" ]; then
    echo "ERRORE: versionCode o versionName non leggibili da $build" >&2
    exit 1
fi
tag="v$versionCode"

if git rev-parse "$tag" >/dev/null 2>&1; then
    echo "ERRORE: il tag $tag esiste già. Alza versionCode in $build." >&2
    exit 1
fi

# L'app confronta numeri: una release con un numero non superiore all'ultima non comparirebbe
# mai sul telefono, e sembrerebbe un aggiornamento che non arriva.
ultimo=$(gh release view --json tagName --jq .tagName 2>/dev/null || true)
if [ -n "$ultimo" ]; then
    ultimoCodice="${ultimo#v}"
    if [ "$versionCode" -le "$ultimoCodice" ]; then
        echo "ERRORE: versionCode $versionCode non è superiore all'ultima release ($ultimo)." >&2
        echo "        Nessun telefono vedrebbe questo aggiornamento." >&2
        exit 1
    fi
fi

echo "== Release $versionName, tag $tag"
echo

# --- verifiche e build --------------------------------------------------------------------

./check.sh

# --- pubblicazione ------------------------------------------------------------------------

echo
echo "== Push del ramo"
git push origin main

echo
echo "== Creazione della release"
if [ -n "$note" ]; then
    gh release create "$tag" "$apk" --title "$versionName" --notes "$note"
else
    gh release create "$tag" "$apk" --title "$versionName" --generate-notes
fi

# --- verifica finale ----------------------------------------------------------------------

# Si controlla la risposta reale dell'API, la stessa che legge UpdateChecker: pubblicare senza
# errori non garantisce che l'app trovi quello che si aspetta.
echo
echo "== Verifica come la vede l'app"
repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
risposta=$(curl -sf "https://api.github.com/repos/$repo/releases/latest")

tagRemoto=$(printf '%s' "$risposta" | grep -oP '"tag_name":\s*"\K[^"]+' | head -1)
apkRemota=$(printf '%s' "$risposta" | grep -oP '"browser_download_url":\s*"\K[^"]+\.apk' | head -1)

if [ "$tagRemoto" != "$tag" ]; then
    echo "ERRORE: l'API restituisce '$tagRemoto' invece di '$tag'." >&2
    exit 1
fi
if [ -z "$apkRemota" ]; then
    echo "ERRORE: nessun allegato .apk nella release. L'app non saprebbe cosa scaricare." >&2
    exit 1
fi

echo "   tag: $tagRemoto"
echo "   apk: $apkRemota"
echo
echo "Fatto. Sui telefoni con un versionCode inferiore a $versionCode la scheda di"
echo "aggiornamento compare alla prossima apertura della schermata."
