#!/bin/sh
# Creates every Secret imperatively rather than as static YAML with embedded
# secret data - consistent with "nothing secret ever gets committed" even
# though these are the same dev-only placeholder credentials already used in
# infra/docker-compose.yml. Re-runnable: each command is create-or-update via
# --dry-run=client -o yaml | kubectl apply -f -, so this can be run again
# after e.g. regenerating the JWT keys.
set -eu

NAMESPACE=order-platform
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic jwt-keys -n "$NAMESPACE" \
  --from-file=jwt-private.pem="$REPO_ROOT/infra/keys/jwt-private.pem" \
  --from-file=jwt-public.pem="$REPO_ROOT/infra/keys/jwt-public.pem" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic postgres-credentials -n "$NAMESPACE" \
  --from-literal=POSTGRES_USER=inventory \
  --from-literal=POSTGRES_PASSWORD=devpassword \
  --from-literal=POSTGRES_DB=inventory \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic couchbase-credentials -n "$NAMESPACE" \
  --from-literal=COUCHBASE_ADMIN_USER=Administrator \
  --from-literal=COUCHBASE_ADMIN_PASSWORD=devpassword \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Secrets created/updated in namespace $NAMESPACE."
