#!/bin/sh
set -eu

cypher-shell \
    -a bolt://neo4j:7687 \
    -u neo4j \
    -p "$NEO4J_PASSWORD" \
    -d neo4j \
    -f /init/neo4j-schema.cypher

