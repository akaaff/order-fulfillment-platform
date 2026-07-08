#!/bin/sh
# One-shot Couchbase cluster bootstrap for local dev. Every step is tolerant
# of "already done" failures (cluster re-init, bucket already exists, index
# already exists) so this script is safe to run against an already-initialized
# cluster - that's what lets couchbase-init run on every `docker compose up`
# without needing to track init state anywhere.
set -eu

HOST="http://couchbase:8091"
USER="${COUCHBASE_ADMIN_USER:-Administrator}"
PASS="${COUCHBASE_ADMIN_PASSWORD:-devpassword}"
BUCKET="orders"

echo "Waiting for Couchbase to accept connections..."
until curl -sf "$HOST/pools" > /dev/null; do
  sleep 2
done

echo "Configuring node services (kv,n1ql,index)..."
curl -s -o /dev/null -X POST "$HOST/node/controller/setupServices" \
  -d 'services=kv,n1ql,index' || true

echo "Setting memory quotas..."
curl -s -o /dev/null -X POST "$HOST/pools/default" \
  -d 'memoryQuota=512' -d 'indexMemoryQuota=256' || true

echo "Creating admin user (activates the cluster)..."
curl -s -o /dev/null -X POST "$HOST/settings/web" \
  -d "username=$USER" -d "password=$PASS" -d 'port=8091' || true

echo "Setting index storage mode (forestdb - required on Community Edition)..."
curl -s -o /dev/null -u "$USER:$PASS" -X POST "$HOST/settings/indexes" \
  -d 'storageMode=forestdb' || true

echo "Creating bucket '$BUCKET' if it doesn't exist..."
# replicaNumber=0: this is a single-node dev cluster, so majority-durability
# writes (which Couchbase's ACID transactions use by default) are physically
# impossible with any replicaNumber > 0 - there's no second node to hold a
# replica. A real multi-node deployment would want replicas back.
curl -s -o /dev/null -u "$USER:$PASS" -X POST "$HOST/pools/default/buckets" \
  -d "name=$BUCKET" -d 'bucketType=couchbase' -d 'ramQuotaMB=256' -d 'flushEnabled=1' -d 'replicaNumber=0' || true

echo "Waiting for the bucket to be queryable..."
until curl -s -u "$USER:$PASS" "$HOST/pools/default/buckets/$BUCKET" | grep -q '"name"'; do
  sleep 2
done
sleep 5

echo "Creating indexes..."
curl -s -o /dev/null -u "$USER:$PASS" "http://couchbase:8093/query/service" \
  --data-urlencode "statement=CREATE PRIMARY INDEX IF NOT EXISTS ON \`$BUCKET\`" || true
curl -s -o /dev/null -u "$USER:$PASS" "http://couchbase:8093/query/service" \
  --data-urlencode "statement=CREATE INDEX idx_type IF NOT EXISTS ON \`$BUCKET\`(type)" || true

echo "Couchbase bootstrap complete."
