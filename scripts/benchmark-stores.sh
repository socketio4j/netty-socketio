#!/usr/bin/env bash
#
# Copyright (c) 2025 The Socketio4j Project
#
# Benchmark runner script for testing publish & subscribe throughput,
# latency, and reliability across Socketio4j pub/sub event stores.
#

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

MESSAGES=2000
WARMUP=200
THREADS=1
ITERATIONS=3
STORES="all"
MODE="both"
SCENARIO="cluster"

usage() {
    cat << 'EOF'
Usage: ./scripts/benchmark-stores.sh [OPTIONS]

Options:
  -c, --scenario <type>   Benchmark scenario: cluster, throughput, all (default: cluster)
                          cluster: Multi-server, multi-event selective subscription & storm test
                          throughput: 1-to-1 continuous throughput & latency benchmark
  -s, --stores <list>     Comma-separated store list or 'all' (default: all)
                          Available: all, memory, redis, redis_stream,
                                     nats, kafka
  -M, --mode <mode>       Store channel mode: both, single, multi (default: both)
  -m, --messages <count>  Number of messages per store benchmark (default: 2000)
  -t, --threads <count>   Number of concurrent publishing threads (default: 1)
  -w, --warmup <count>    Number of un-timed warmup messages (default: 200)
  -i, --iterations <n>    Number of iterations per store for averaging (default: 3)
  -h, --help              Show this help message

Examples:
  # Run multi-server cluster benchmark showing where multi-channel shines
  ./scripts/benchmark-stores.sh --scenario cluster --stores memory,redis,redis_stream

  # Run standard 1-to-1 throughput benchmark
  ./scripts/benchmark-stores.sh --scenario throughput --stores all -m 2000
EOF
    exit 0
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -c|--scenario)
            SCENARIO="$2"
            shift 2
            ;;
        -s|--stores)
            STORES="$2"
            shift 2
            ;;
        -M|--mode)
            MODE="$2"
            shift 2
            ;;
        -m|--messages)
            MESSAGES="$2"
            shift 2
            ;;
        -t|--threads)
            THREADS="$2"
            shift 2
            ;;
        -w|--warmup)
            WARMUP="$2"
            shift 2
            ;;
        -i|--iterations)
            ITERATIONS="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage
            ;;
    esac
done

# Docker check for container-backed stores
if [[ "${STORES}" != "memory" ]]; then
    if ! docker info >/dev/null 2>&1; then
        echo "WARNING: Docker is not running or accessible. Container-backed stores require Docker." >&2
        echo "If you only want to test the in-memory baseline, run: ./scripts/benchmark-stores.sh --stores memory" >&2
    fi
fi

echo "Compiling benchmark test suite..."
mvn -q test-compile -pl netty-socketio-core -DskipTests

if [[ "${SCENARIO}" == "cluster" || "${SCENARIO}" == "all" ]]; then
    CLUSTER_ARGS="--stores ${STORES} --messages ${MESSAGES}"
    echo "Starting Multi-Server Cluster Benchmark with: ${CLUSTER_ARGS}"
    mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:exec \
        -pl netty-socketio-core \
        -Dexec.classpathScope=test \
        -Dexec.executable="java" \
        -Dexec.args="-cp %classpath com.socketio4j.socketio.benchmark.ClusterTopologyPubSubBenchmark ${CLUSTER_ARGS}"
fi

if [[ "${SCENARIO}" == "throughput" || "${SCENARIO}" == "all" ]]; then
    EXEC_ARGS="--stores ${STORES} --mode ${MODE} --messages ${MESSAGES} --threads ${THREADS} --warmup ${WARMUP} --iterations ${ITERATIONS}"
    echo "Starting 1-to-1 Throughput Benchmark with: ${EXEC_ARGS}"
    mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:exec \
        -pl netty-socketio-core \
        -Dexec.classpathScope=test \
        -Dexec.executable="java" \
        -Dexec.args="-cp %classpath com.socketio4j.socketio.benchmark.PubSubStoreThroughputBenchmark ${EXEC_ARGS}"
fi
