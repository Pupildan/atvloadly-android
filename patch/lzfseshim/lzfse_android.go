//go:build android

// Package lzfseshim wraps the lzfse decompressor used by the BOM/asset
// decoder. On Android the upstream bitxeno/lzfse-go cannot be used: it calls
// into the C lzfse library through purego, whose dlopen path requires cgo and
// therefore cannot build with CGO_ENABLED=0 on this target.
//
// App icons embedded in a compiled Assets.car are a purely cosmetic part of
// the sideload flow — they never affect pairing, signing or installation.
// Regular PNG icons (the common case) are still extracted from the IPA by
// internal/ipa without touching this package. So on Android we return a
// "not supported" error here and let the asset decoder skip .car icons
// gracefully, instead of dragging in cgo or reimplementing the lzfse codec.
package lzfseshim

import "errors"

var errUnsupported = errors.New("lzfseshim: lzfse decoding is unavailable on android")

// DecodeBuffer decompresses a plain lzfse-family stream.
func DecodeBuffer(src []byte) ([]byte, error) { return nil, errUnsupported }

// DecodeZBMBuffer decompresses into a caller-provided fixed-size buffer.
func DecodeZBMBuffer(src, dst []byte) (int, error) { return 0, errUnsupported }