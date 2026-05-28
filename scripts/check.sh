#!/usr/bin/env bash
set -euo pipefail

mvn -B clean test
mvn -B -DskipTests package
