#!/bin/bash
PAYLOAD='{"foo":"bar"}'
SECRET="mysecret"
echo -n "$PAYLOAD" > payload.json
SIGNATURE=$(openssl dgst -sha256 -hmac "$SECRET" payload.json | awk '{print $2}')
echo "sha256=$SIGNATURE"
