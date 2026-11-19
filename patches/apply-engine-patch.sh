#!/usr/bin/env bash
# Patch PlumeImpactor (the plumesign engine) so TLS works on Android.
#
# Root cause: on non-Windows, plume_core builds its HTTP client with reqwest's
# `rustls` feature, which uses rustls-platform-verifier. On Android that crate
# needs a JVM/JNI context (JavaVM + applicationContext + class loader) to load
# the system CA store. plumesign runs as a standalone native binary spawned by
# the Go backend, so there is no JVM and platform-verifier fails with
# "could not load extra certs".
#
# Fix: on Android, build the TLS trust store directly from the bundled
# webpki-roots (Mozilla roots, pure Rust, no JVM) plus Apple's root cert, and
# hand reqwest a preconfigured rustls ClientConfig so platform-verifier is never
# invoked. Other platforms keep the upstream behavior.
#
# Usage: apply-engine-patch.sh /path/to/PlumeImpactor
set -euo pipefail

ENGINE_DIR="${1:?usage: apply-engine-patch.sh <PlumeImpactor-dir>}"
CORE_TOML="$ENGINE_DIR/crates/plume_core/Cargo.toml"
CORE_LIB="$ENGINE_DIR/crates/plume_core/src/lib.rs"

[ -f "$CORE_TOML" ] || { echo "error: $CORE_TOML not found" >&2; exit 1; }
[ -f "$CORE_LIB" ] || { echo "error: $CORE_LIB not found" >&2; exit 1; }

# 1) Add webpki-roots for the Android target only.
if ! grep -q 'webpki-roots' "$CORE_TOML"; then
  cat >> "$CORE_TOML" <<'EOF'

# On Android the default rustls-platform-verifier needs a JVM/JNI context that a
# standalone binary lacks, so bundle webpki-roots instead (see lib.rs client()).
[target.'cfg(target_os = "android")'.dependencies]
webpki-roots = "0.26"
EOF
  echo "patch applied: webpki-roots dep added for android"
else
  echo "webpki-roots dep already present"
fi

# 2) Rewrite client(): add an Android-specific version using a preconfigured
#    rustls ClientConfig built from webpki-roots + Apple root.
if grep -q 'tls_backend_preconfigured' "$CORE_LIB"; then
  echo "lib.rs client() already patched"
  exit 0
fi

python3 - "$CORE_LIB" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()

old = '''pub fn client() -> Result<reqwest::Client, Error> {
    const APPLE_ROOT: &[u8] = include_bytes!("./apple_root.der");
    let client = reqwest::ClientBuilder::new()
        .add_root_certificate(reqwest::Certificate::from_der(APPLE_ROOT)?)
        // uncomment when debugging w/ charles proxy
        // .danger_accept_invalid_certs(true)
        .http1_title_case_headers()
        .connection_verbose(true)
        .pool_max_idle_per_host(0)
        .build()?;

    Ok(client)
}'''

new = '''#[cfg(target_os = "android")]
pub fn client() -> Result<reqwest::Client, Error> {
    const APPLE_ROOT: &[u8] = include_bytes!("./apple_root.der");

    // rustls-platform-verifier needs a JVM/JNI context to read Android's CA
    // store, which a standalone binary doesn't have. Build the trust store
    // from bundled webpki-roots plus Apple's root instead, and hand reqwest a
    // preconfigured rustls config so platform-verifier is never used.
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    roots.add(rustls::pki_types::CertificateDer::from(APPLE_ROOT.to_vec()))
        .map_err(|e| Error::Certificate(e.to_string()))?;

    let provider = std::sync::Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| Error::Certificate(e.to_string()))?
        .with_root_certificates(roots)
        .with_no_client_auth();

    let client = reqwest::ClientBuilder::new()
        .tls_backend_preconfigured(config)
        .http1_title_case_headers()
        .connection_verbose(true)
        .pool_max_idle_per_host(0)
        .build()?;

    Ok(client)
}

#[cfg(not(target_os = "android"))]
pub fn client() -> Result<reqwest::Client, Error> {
    const APPLE_ROOT: &[u8] = include_bytes!("./apple_root.der");
    let client = reqwest::ClientBuilder::new()
        .add_root_certificate(reqwest::Certificate::from_der(APPLE_ROOT)?)
        // uncomment when debugging w/ charles proxy
        // .danger_accept_invalid_certs(true)
        .http1_title_case_headers()
        .connection_verbose(true)
        .pool_max_idle_per_host(0)
        .build()?;

    Ok(client)
}'''

if old not in src:
    print("error: client() function not found verbatim in lib.rs", file=sys.stderr)
    sys.exit(1)

src = src.replace(old, new, 1)
open(path, "w").write(src)
print("patch applied: lib.rs client() split for android (webpki-roots)")
PY