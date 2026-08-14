#!/usr/bin/env bash
#
# Creates a dbId index for each node label in the graph database.
#
# WHY THIS IS NEEDED
#
# curator-tool-ws writes an instance by looking it up under its *concrete* schema class, e.g.
#
#     MATCH (n:GO_MolecularFunction {dbId: $dbId}) ...
#
# (CurationRepository.resetNode, CurationRepository.storeNodeProperties, CurationRepository.store,
# CypherQueryUtilities line ~254). The only dbId index it creates is
#
#     CREATE INDEX db_id_index IF NOT EXISTS FOR (n:DatabaseObject) ON (n.dbId)
#
# (CypherQueryUtilities.createDbIdIndex). Neo4j cannot use an index on :DatabaseObject to resolve a
# match on :GO_MolecularFunction, so every one of those lookups is a NodeByLabelScan plus a property
# filter -- and a single commit performs roughly twenty of them. Indexing dbId per label lets each
# lookup become a NodeIndexSeek.
#
# This is a schema-only change: it adds indexes and touches no data. It speeds up every
# curator-tool-ws write, not just the GO update.
#
# USAGE
#
#     scripts/create-dbid-indexes.sh                # dry run: print the DDL that would be applied
#     scripts/create-dbid-indexes.sh --apply        # create the indexes and wait for them to come online
#     scripts/create-dbid-indexes.sh --profile      # show the query plan for a dbId lookup
#     scripts/create-dbid-indexes.sh --list         # show the dbId indexes that already exist
#     scripts/create-dbid-indexes.sh --drop         # drop the indexes this script created, and nothing else
#
# Connection settings are read from cypher-shell's own environment variables, so nothing is passed on
# the command line where it would show up in "ps":
#
#     NEO4J_ADDRESS   (default bolt://localhost:7687)
#     NEO4J_USERNAME  (default neo4j)
#     NEO4J_PASSWORD  (default root)
#     NEO4J_DATABASE  (default graph.db)
#
# The cypher-shell binary itself is configurable, for hosts where it is not on PATH or where a
# particular Neo4j installation's copy has to be used:
#
#     CYPHER_SHELL      (default cypher-shell, resolved on PATH)
#     --cypher-shell PATH   overrides CYPHER_SHELL for this run
#
# e.g. scripts/create-dbid-indexes.sh --cypher-shell /var/lib/neo4j/bin/cypher-shell --apply
#
# Labels with fewer than --min-nodes nodes are skipped: scanning a few hundred nodes costs less than
# maintaining an index on them. Every label that is skipped, and why, is printed.

set -euo pipefail

export NEO4J_ADDRESS="${NEO4J_ADDRESS:-bolt://localhost:7687}"
export NEO4J_USERNAME="${NEO4J_USERNAME:-neo4j}"
export NEO4J_PASSWORD="${NEO4J_PASSWORD:-root}"
export NEO4J_DATABASE="${NEO4J_DATABASE:-graph.db}"

CYPHER_SHELL="${CYPHER_SHELL:-cypher-shell}"

MIN_NODES=1000
AWAIT_SECONDS=1800
MODE=dry-run

usage() {
    sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//; $d'
    exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply)   MODE=apply;   shift ;;
        --profile) MODE=profile; shift ;;
        --list)    MODE=list;    shift ;;
        --drop)    MODE=drop;    shift ;;
        --cypher-shell)
            CYPHER_SHELL="${2:-}"
            if [[ -z "$CYPHER_SHELL" ]]; then
                echo "error: --cypher-shell needs a path to the cypher-shell binary" >&2
                exit 2
            fi
            shift 2
            ;;
        --min-nodes)
            MIN_NODES="${2:-}"
            if ! [[ "$MIN_NODES" =~ ^[0-9]+$ ]]; then
                echo "error: --min-nodes needs a non-negative integer, got '${2:-}'" >&2
                exit 2
            fi
            shift 2
            ;;
        -h|--help) usage 0 ;;
        *) echo "error: unknown argument '$1'" >&2; usage 2 ;;
    esac
done

# command -v covers both forms: a bare name is looked up on PATH, a path is accepted only if it is
# executable.
if ! command -v "$CYPHER_SHELL" >/dev/null 2>&1; then
    if [[ "$CYPHER_SHELL" == */* ]]; then
        echo "error: '$CYPHER_SHELL' is not an executable file" >&2
    else
        echo "error: '$CYPHER_SHELL' not found on PATH; set CYPHER_SHELL or pass --cypher-shell PATH" >&2
    fi
    exit 1
fi

cypher() {
    "$CYPHER_SHELL" --format plain "$@"
}

cypher_verbose() {
    "$CYPHER_SHELL" --format verbose "$@"
}

# cypher-shell's plain format quotes string values; strip the quotes and drop the header row.
values() {
    tail -n +2 | sed 's/^"//; s/"$//' | sed '/^$/d'
}

require_connection() {
    if ! cypher "RETURN 1;" >/dev/null 2>&1; then
        echo "error: cannot reach $NEO4J_DATABASE at $NEO4J_ADDRESS as $NEO4J_USERNAME." >&2
        echo "       Is Neo4j running? Override with NEO4J_ADDRESS / NEO4J_USERNAME / NEO4J_PASSWORD / NEO4J_DATABASE." >&2
        exit 1
    fi
}

list_indexes() {
    echo "== dbId indexes currently in $NEO4J_DATABASE"
    cypher_verbose \
        "SHOW INDEXES YIELD name, labelsOrTypes, properties, state, populationPercent
         WHERE 'dbId' IN properties
         RETURN name, labelsOrTypes, state, populationPercent ORDER BY name;"
}

# The labels that already have a dbId index, as a Cypher list literal. Some already come from the
# uniqueness constraints the graph importer creates (:DatabaseObject, :Complex, :Event, ...), and
# creating a second index over the same schema would be rejected. Neo4j 4.4 cannot chain clauses onto
# SHOW INDEXES, so this is a query of its own whose result is fed back in as a literal.
indexed_labels_literal() {
    local labels
    labels=$(cypher "SHOW INDEXES YIELD labelsOrTypes, properties, entityType
                     WHERE properties = ['dbId'] AND entityType = 'NODE'
                     RETURN labelsOrTypes[0] AS label ORDER BY label;" \
             | values | grep -E '^[A-Za-z0-9_]+$' || true)

    if [[ -z "$labels" ]]; then
        echo "[]"
    else
        echo "['$(echo "$labels" | paste -sd, - | sed "s/,/','/g")']"
    fi
}

# Emits the DDL from Cypher itself rather than parsing a label/count table, so no output parsing can
# corrupt a statement. The label counts come from a single pass over the nodes.
generate_ddl() {
    cypher "MATCH (n) UNWIND labels(n) AS label
            WITH label, count(*) AS nodes
            WHERE nodes >= $MIN_NODES AND label =~ '[A-Za-z0-9_]+' AND NOT label IN $INDEXED_LABELS
            RETURN 'CREATE INDEX dbid_' + label + ' IF NOT EXISTS FOR (n:\`' + label + '\`) ON (n.dbId);' AS ddl
            ORDER BY ddl;" | values
}

report_untouched() {
    local already skipped

    already=$(cypher "MATCH (n) UNWIND labels(n) AS label
                      WITH label, count(*) AS nodes
                      WHERE label IN $INDEXED_LABELS
                      RETURN label + ' (' + toString(nodes) + ' nodes)' AS label
                      ORDER BY label;" | values)

    skipped=$(cypher "MATCH (n) UNWIND labels(n) AS label
                      WITH label, count(*) AS nodes
                      WHERE NOT label IN $INDEXED_LABELS
                        AND (nodes < $MIN_NODES OR NOT label =~ '[A-Za-z0-9_]+')
                      RETURN label + ' (' + toString(nodes) + ' nodes)' AS label
                      ORDER BY label;" | values)

    if [[ -n "$already" ]]; then
        echo
        echo "== $(echo "$already" | wc -l) label(s) already have a dbId index, left alone"
        echo "$already" | sed 's/^/   /'
    fi

    if [[ -n "$skipped" ]]; then
        echo
        echo "== $(echo "$skipped" | wc -l) label(s) skipped: fewer than $MIN_NODES nodes, or a name that is not a plain"
        echo "   identifier (scanning that few nodes costs less than maintaining an index on them)"
        echo "$skipped" | sed 's/^/   /'
    fi
}

# Blocks until the indexes this script creates are ONLINE. This deliberately does not use
# CALL db.awaitIndexes(), which fails outright when *any* index in the database is in a FAILED state --
# gk_central copies carry FAILED taxId uniqueness constraints on :Species and :Taxon from duplicate
# values in the data, so awaitIndexes would report someone else's broken index as this script's failure.
await_dbid_indexes() {
    local deadline=$((SECONDS + AWAIT_SECONDS)) pending

    while true; do
        pending=$(cypher "SHOW INDEXES YIELD name, state
                          WHERE name STARTS WITH 'dbid_' AND state <> 'ONLINE'
                          RETURN name + ' (' + state + ')' AS pending ORDER BY pending;" | values)

        if [[ -z "$pending" ]]; then
            echo "All dbId indexes are ONLINE."
            return 0
        fi

        if (( SECONDS >= deadline )); then
            echo "warning: $(echo "$pending" | wc -l) index(es) not ONLINE after ${AWAIT_SECONDS}s:" >&2
            echo "$pending" | sed 's/^/   /' >&2
            echo "         Check the Neo4j logs; a run started now may not get the benefit of them." >&2
            return 1
        fi

        sleep 5
    done
}

# Failed indexes that have nothing to do with dbId are worth surfacing -- they are pre-existing, they
# are not this script's doing, and they are the reason for the hand-rolled wait above.
warn_foreign_failed_indexes() {
    local failed
    failed=$(cypher "SHOW INDEXES YIELD name, state, labelsOrTypes, properties
                     WHERE state = 'FAILED' AND NOT name STARTS WITH 'dbid_'
                     RETURN name + ' on ' + labelsOrTypes[0] + '(' + properties[0] + ')' AS failed
                     ORDER BY failed;" | values)

    if [[ -n "$failed" ]]; then
        echo
        echo "note: $(echo "$failed" | wc -l) index(es) unrelated to dbId are FAILED in this database. They are"
        echo "      pre-existing and not created by this script, but they are why the wait below polls only the"
        echo "      dbId indexes rather than calling db.awaitIndexes()."
        echo "$failed" | sed 's/^/      /'
    fi
}

profile_lookup() {
    # Profile a lookup by concrete label -- the shape curator-tool-ws actually issues on every commit.
    local label dbid
    label=$(cypher "MATCH (n) UNWIND labels(n) AS label
                    WITH label, count(*) AS nodes
                    WHERE label =~ '[A-Za-z0-9_]+' AND label <> 'DatabaseObject'
                    RETURN label ORDER BY nodes DESC LIMIT 1;" | values)
    dbid=$(cypher "MATCH (n:\`$label\`) RETURN n.dbId LIMIT 1;" | values)

    echo "== query plan for MATCH (n:$label {dbId: $dbid})"
    echo "   NodeByLabelScan  => this label has no usable dbId index (run with --apply)"
    echo "   NodeIndexSeek    => the index is in place and being used"
    echo
    cypher_verbose "PROFILE MATCH (n:\`$label\` {dbId: $dbid}) RETURN n.displayName;"
}

require_connection

case "$MODE" in
    list)
        list_indexes
        exit 0
        ;;
    profile)
        profile_lookup
        exit 0
        ;;
    drop)
        # Matched on the dbid_ name prefix, so only the indexes this script created are dropped. The
        # uniqueness constraints the graph importer created are named constraint_* and are left in place.
        drop_ddl=$(cypher "SHOW INDEXES YIELD name WHERE name STARTS WITH 'dbid_'
                           RETURN 'DROP INDEX ' + name + ' IF EXISTS;' AS ddl ORDER BY ddl;" | values)

        if [[ -z "$drop_ddl" ]]; then
            echo "No dbid_* indexes to drop in $NEO4J_DATABASE."
            exit 0
        fi

        echo "== dropping $(echo "$drop_ddl" | wc -l) index(es) created by this script from $NEO4J_DATABASE"
        echo "$drop_ddl"
        echo
        echo "$drop_ddl" | cypher
        echo
        list_indexes
        exit 0
        ;;
esac

INDEXED_LABELS=$(indexed_labels_literal)
ddl=$(generate_ddl)

if [[ -z "$ddl" ]]; then
    echo "Every label with $MIN_NODES or more nodes already has a dbId index; nothing to do."
    report_untouched
    exit 0
fi

if [[ "$MODE" == dry-run ]]; then
    echo "== would create $(echo "$ddl" | wc -l) index(es) in $NEO4J_DATABASE at $NEO4J_ADDRESS"
    echo "$ddl"
    report_untouched
    echo
    echo "Re-run with --apply to create them."
    exit 0
fi

echo "== creating $(echo "$ddl" | wc -l) index(es) in $NEO4J_DATABASE at $NEO4J_ADDRESS"
echo "$ddl"
report_untouched
warn_foreign_failed_indexes
echo

# One cypher-shell invocation for every statement, then block until they finish populating, so that a
# run started straight afterwards does not race an index that is still coming online.
echo "$ddl" | cypher

await_status=0
await_dbid_indexes || await_status=$?

echo
list_indexes
exit "$await_status"
