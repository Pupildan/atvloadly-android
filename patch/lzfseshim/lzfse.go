//go:build !android

// Package lzfseshim wraps the lzfse decompressor used by the BOM/asset
// decoder. On every platform except Android it delegates to the purego-based
// bitxeno/lzfse-go, matching upstream atvloadly behaviour exactly.
package lzfseshim

import lzfse "github.com/bitxeno/lzfse-go"

// DecodeBuffer decompresses a plain lzfse-family stream.
func DecodeBuffer(src []byte) ([]byte, error) { return lzfse.DecodeBuffer(src) }

// DecodeZBMBuffer decompresses into a caller-provided fixed-size buffer and
// returns the number of bytes written.
func DecodeZBMBuffer(src, dst []byte) (int, error) { return lzfse.DecodeZBMBuffer(src, dst) }