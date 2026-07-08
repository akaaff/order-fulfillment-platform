#!/bin/sh
# Generates the RSA key pair used to sign/verify demo JWTs. Re-runnable -
# regenerating invalidates every previously issued token, which is fine for
# local dev/demo but is exactly why this key pair must never be checked into
# git (see .gitignore) - in minikube this becomes a K8s Secret instead.
set -eu

DIR="$(dirname "$0")/keys"
mkdir -p "$DIR"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$DIR/jwt-private.pem"
openssl rsa -pubout -in "$DIR/jwt-private.pem" -out "$DIR/jwt-public.pem"

echo "Wrote $DIR/jwt-private.pem and $DIR/jwt-public.pem"
