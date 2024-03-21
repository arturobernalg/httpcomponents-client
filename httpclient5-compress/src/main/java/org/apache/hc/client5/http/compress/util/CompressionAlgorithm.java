package org.apache.hc.client5.http.compress.util;

public enum CompressionAlgorithm {
    BROTLI("br"),
    BZIP2("bzip2"),
    GZIP("gz"),
    PACK200("pack200"),
    XZ("xz"),
    LZMA("lzma"),
    SNAPPY_FRAMED("snappy-framed"),
    SNAPPY_RAW("snappy-raw"),
    Z("z"),
    DEFLATE("deflate"),
    DEFLATE64("deflate64"),
    LZ4_BLOCK("lz4-block"),
    LZ4_FRAMED("lz4-framed"),
    ZSTANDARD("zstd"),
    IDENTITY("identity");

    private final String identifier;

    CompressionAlgorithm(final String identifier) {
        this.identifier = identifier;
    }

    public String getIdentifier() {
        return identifier;
    }

    public boolean isSame(final String value) {
        if (value == null) {
            return false;
        }
        return getIdentifier().equalsIgnoreCase(value);
    }
}