#!/bin/bash
# Copyright 2018 The gRPC Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Update VERSION then in this directory run ./import.sh

set -e
BRANCH=master
# import VERSION from one of the google internal CLs
VERSION=f709434b37e9ff74666d5b854aa11fb2f1ec37f3
GIT_REPO="https://github.com/envoyproxy/envoy.git"
GIT_BASE_DIR=envoy
SOURCE_PROTO_BASE_DIR=envoy/api
TARGET_PROTO_BASE_DIR=src/main/proto
FILES=(
envoy/api/v2/auth/cert.proto
envoy/api/v2/cds.proto
envoy/api/v2/cluster/circuit_breaker.proto
envoy/api/v2/cluster/filter.proto
envoy/api/v2/cluster/outlier_detection.proto
envoy/api/v2/core/address.proto
envoy/api/v2/core/base.proto
envoy/api/v2/core/config_source.proto
envoy/api/v2/core/grpc_service.proto
envoy/api/v2/core/health_check.proto
envoy/api/v2/core/http_uri.proto
envoy/api/v2/core/protocol.proto
envoy/api/v2/discovery.proto
envoy/api/v2/eds.proto
envoy/api/v2/endpoint/endpoint.proto
envoy/api/v2/endpoint/load_report.proto
envoy/api/v2/lds.proto
envoy/api/v2/listener/listener.proto
envoy/api/v2/listener/udp_listener_config.proto
envoy/api/v2/rds.proto
envoy/api/v2/route/route.proto
envoy/api/v2/srds.proto
envoy/config/filter/accesslog/v2/accesslog.proto
envoy/config/filter/network/http_connection_manager/v2/http_connection_manager.proto
envoy/config/listener/v2/api_listener.proto
envoy/service/discovery/v2/ads.proto
envoy/service/discovery/v2/sds.proto
envoy/service/load_stats/v2/lrs.proto
envoy/type/matcher/regex.proto
envoy/type/matcher/string.proto
envoy/type/percent.proto
envoy/type/range.proto
)

# clone the envoy github repo in a tmp directory
tmpdir="$(mktemp -d)"
pushd "${tmpdir}"
rm -rf $GIT_BASE_DIR
git clone -b $BRANCH $GIT_REPO
cd "$GIT_BASE_DIR"
git checkout $VERSION
popd

cp -p "${tmpdir}/${GIT_BASE_DIR}/LICENSE" LICENSE
cp -p "${tmpdir}/${GIT_BASE_DIR}/NOTICE" NOTICE

rm -rf "${TARGET_PROTO_BASE_DIR}"
mkdir -p "${TARGET_PROTO_BASE_DIR}"
pushd "${TARGET_PROTO_BASE_DIR}"

# copy proto files to project directory
for file in "${FILES[@]}"
do
  mkdir -p "$(dirname "${file}")"
  cp -p "${tmpdir}/${SOURCE_PROTO_BASE_DIR}/${file}" "${file}"
done
popd

rm -rf "$tmpdir"
