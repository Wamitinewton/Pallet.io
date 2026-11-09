FROM docker.io/library/alpine:3.24.4@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf

RUN apk add --no-cache \
        ca-certificates=20261020-r0 \
        curl=8.23.0-r0 \
        tar=1.35-r5 \
        skopeo=1.20.1-r2 \
    && install -d -m 0755 /usr/local/lib/pallet /etc/pallet \
    && addgroup -S -g 10001 pallet \
    && adduser -S -D -H -u 10001 -G pallet -h /tmp -s /sbin/nologin pallet

COPY --chmod=0444 steps.sh /usr/local/lib/pallet/steps.sh
COPY --chmod=0444 policy.json /etc/pallet/policy.json
COPY --chmod=0555 fetch.sh /usr/local/bin/fetch
COPY --chmod=0555 push.sh /usr/local/bin/push
COPY --chmod=0555 report.sh /usr/local/bin/report

ENV HOME=/tmp TMPDIR=/tmp
USER 10001:10001
WORKDIR /tmp
