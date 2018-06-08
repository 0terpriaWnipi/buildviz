#!/bin/sh
set -e

goal_server() {
    java -jar ${BUILDVIZ_SERVER_DIR}/${BUILDVIZ_JAR}
}

goal_do() {
    java -cp ${BUILDVIZ_SERVER_DIR}/${BUILDVIZ_JAR} "$@"
}

goal_schedule() {
    while true; do
        goal_do "$@"
        sleep $((10 * 60))
    done
}

main() {
    CMD="$1"
    shift
    "goal_${CMD}" "$@"
}

main "$@"
