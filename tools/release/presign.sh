#!/usr/bin/env bash
# Prints time-limited download links for one release of one product, to send to a customer.
#
#   tools/release/presign.sh <product> [version|latest] [expiry]
#
#   product   cert-alert | directory-services-portal | readyroom | rsa-operations-portal
#   version   e.g. 1.4.0; default "latest" (read from <product>/latest.txt in the bucket)
#   expiry    link lifetime in seconds; default 604800 (7 days, the most S3 allows)
#
# Needs the aws CLI and credentials that can read the release bucket:
#   RELEASE_S3_BUCKET    bucket name (required)
#   RELEASE_S3_ENDPOINT  endpoint URL for Cloudflare R2 / MinIO (optional; omit for AWS S3)
#   AWS_REGION           default us-east-1 (R2 uses "auto")
#   plus the usual AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY or an AWS profile.
set -euo pipefail

products="cert-alert directory-services-portal readyroom rsa-operations-portal"
product=${1:-}
version=${2:-latest}
expiry=${3:-604800}

if [[ -z "$product" || " $products " != *" $product "* ]]; then
    echo "usage: $0 <product> [version|latest] [expiry-seconds]" >&2
    echo "products: $products" >&2
    exit 2
fi
: "${RELEASE_S3_BUCKET:?set RELEASE_S3_BUCKET to the release bucket name}"
export AWS_REGION=${AWS_REGION:-us-east-1}

aws_s3() {
    if [[ -n "${RELEASE_S3_ENDPOINT:-}" ]]; then
        aws --endpoint-url "$RELEASE_S3_ENDPOINT" s3 "$@"
    else
        aws s3 "$@"
    fi
}

if [[ "$version" == latest ]]; then
    version=$(aws_s3 cp "s3://$RELEASE_S3_BUCKET/$product/latest.txt" - | tr -d '[:space:]')
    [[ -n "$version" ]] || { echo "no latest.txt for $product" >&2; exit 1; }
fi

prefix="s3://$RELEASE_S3_BUCKET/$product/$version/"
files=$(aws_s3 ls "$prefix" 2>/dev/null | awk '{print $4}' || true)
[[ -n "$files" ]] || { echo "nothing at $prefix" >&2; exit 1; }

echo "$product $version - links valid for $((expiry / 3600)) hours"
echo
for f in $files; do
    echo "$f"
    aws_s3 presign "$prefix$f" --expires-in "$expiry"
    echo
done
