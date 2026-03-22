#!/bin/bash
EVENT_JSON="{\"foo\":\"bar'baz\"}"
cat << EOF2 > payload.json
$EVENT_JSON
EOF2
cat payload.json
