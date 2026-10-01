#!/bin/sh
#
# Creates the databases the compose file's POSTGRES_DB does not.
#
# POSTGRES_DB makes exactly one database — systmone, for the control plane, which the gateway
# also writes its own audit table into. The PDS match review application does not share it: its
# store is full of patient-identifiable data at a different sensitivity, with its own retention
# policy and its own grants (pds-review/docs/requirements.md §5.4, FR-AUD-10, NFR-CMP-01). Keeping
# it a separate database is what makes those separable rather than aspirational.
#
# IMPORTANT: the official Postgres image runs everything in /docker-entrypoint-initdb.d ONLY when
# the data directory is empty — that is, on a genuinely fresh volume. On an existing one this
# script never runs, and a missing database has to be created by hand:
#
#   docker exec systmone-postgres psql -U systmone -d systmone \
#     -c 'CREATE DATABASE systmone_pds_review OWNER systmone'
#
# That is the "database systmone_pds_review does not exist" error, and this file is why it should
# not happen twice.

set -e

create_database() {
    name="$1"
    echo "Creating database $name owned by $POSTGRES_USER"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
        -c "CREATE DATABASE \"$name\" OWNER \"$POSTGRES_USER\""
}

create_database "${PDS_REVIEW_DATABASE:-systmone_pds_review}"
