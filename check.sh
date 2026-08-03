#!/usr/bin/env bash
#
# Verifiche automatiche da passare prima di ogni release.
#
# Copre solo ciò che una macchina può decidere da sola: test unitari, lint, compilazione.
# Tutto il resto (rendering, Android Auto, OAuth, aggiornamento) richiede un dispositivo ed
# è elencato in docs/verifica.md. Passare questo script non significa che l'app funzioni,
# significa che non è rotta in modo dimostrabile senza telefono.
#
# Uso: ./check.sh

set -euo pipefail
cd "$(dirname "$0")"

echo "== Test unitari, lint e compilazione"
./gradlew --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# I risultati sono in XML: si contano i casi eseguiti invece di fidarsi del solo exit code,
# perché una suite che non esegue nulla esce comunque con successo.
totale=0
falliti=0
for report in app/build/test-results/testDebugUnitTest/*.xml; do
    [ -e "$report" ] || continue
    eseguiti=$(sed -n 's/.*tests="\([0-9]*\)".*/\1/p' "$report" | head -1)
    errori=$(sed -n 's/.*failures="\([0-9]*\)".*/\1/p' "$report" | head -1)
    problemi=$(sed -n 's/.*errors="\([0-9]*\)".*/\1/p' "$report" | head -1)
    totale=$((totale + eseguiti))
    falliti=$((falliti + errori + problemi))
done

if [ "$totale" -eq 0 ]; then
    echo "ERRORE: nessun test eseguito. La suite non ha girato." >&2
    exit 1
fi
if [ "$falliti" -ne 0 ]; then
    echo "ERRORE: $falliti test falliti su $totale." >&2
    exit 1
fi

apk="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$apk" ] || { echo "ERRORE: APK non prodotta in $apk" >&2; exit 1; }

echo
echo "== Verifiche automatiche superate"
echo "   $totale test, lint senza errori, APK prodotta ($(du -h "$apk" | cut -f1))"
echo
echo "Restano le prove sul dispositivo, che nessuno script può fare al posto tuo."
echo "Elenco completo e ordinato: docs/verifica.md"
