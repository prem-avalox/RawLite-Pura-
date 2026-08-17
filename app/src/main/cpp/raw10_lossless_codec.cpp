#include "raw10_lossless_codec.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <zlib.h>

namespace rawlite {
namespace {

bool valid_geometry(int width, int height, int row_bytes, size_t* frame_bytes) {
    if (width <= 0 || height <= 0 || row_bytes <= 0 || width % 4 != 0) {
        return false;
    }
    const uint64_t bytes = static_cast<uint64_t>(row_bytes) * static_cast<uint64_t>(height);
    if (bytes == 0 || bytes > std::numeric_limits<size_t>::max()) {
        return false;
    }
    *frame_bytes = static_cast<size_t>(bytes);
    return true;
}

inline uint16_t raw10_pixel(const uint8_t* raw, int row_bytes, int x, int y) {
    const size_t group = static_cast<size_t>(y) * row_bytes + static_cast<size_t>(x / 4) * 5;
    const uint8_t high = raw[group + static_cast<size_t>(x & 3)];
    const uint8_t low = raw[group + 4];
    return static_cast<uint16_t>((static_cast<uint16_t>(high) << 2) |
        ((low >> ((x & 3) * 2)) & 0x03));
}

inline void set_raw10_pixel(uint8_t* raw, int row_bytes, int x, int y, uint16_t value) {
    const size_t group = static_cast<size_t>(y) * row_bytes + static_cast<size_t>(x / 4) * 5;
    raw[group + static_cast<size_t>(x & 3)] = static_cast<uint8_t>(value >> 2);
    const int shift = (x & 3) * 2;
    raw[group + 4] = static_cast<uint8_t>(
        (raw[group + 4] & ~(0x03U << shift)) | ((value & 0x03U) << shift)
    );
}

bool transform_cfa_delta(
    const uint8_t* raw,
    int width,
    int height,
    int row_bytes,
    std::vector<uint8_t>& transformed
) {
    const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t tight_bytes = pixel_count + (pixel_count + 3) / 4;
    const size_t frame_bytes = static_cast<size_t>(row_bytes) * static_cast<size_t>(height);
    if (tight_bytes != frame_bytes) {
        return false;
    }
    transformed.assign(frame_bytes, 0);
    uint8_t* low = transformed.data();
    uint8_t* high = low + pixel_count;
    size_t pixel_index = 0;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x, ++pixel_index) {
            const uint16_t value = raw10_pixel(raw, row_bytes, x, y);
            const uint16_t predictor = x >= 2
                ? raw10_pixel(raw, row_bytes, x - 2, y)
                : (y >= 2 ? raw10_pixel(raw, row_bytes, x, y - 2) : 0);
            const uint16_t delta = static_cast<uint16_t>((value - predictor) & 0x03ff);
            low[pixel_index] = static_cast<uint8_t>(delta & 0xff);
            high[pixel_index / 4] |= static_cast<uint8_t>(
                ((delta >> 8) & 0x03) << ((pixel_index & 3) * 2)
            );
        }
    }
    return true;
}

bool inverse_cfa_delta(
    const uint8_t* transformed,
    size_t transformed_size,
    int width,
    int height,
    int row_bytes,
    uint8_t* output,
    size_t output_size
) {
    const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t tight_bytes = pixel_count + (pixel_count + 3) / 4;
    if (transformed_size != tight_bytes || output_size < tight_bytes) {
        return false;
    }
    std::memset(output, 0, tight_bytes);
    const uint8_t* low = transformed;
    const uint8_t* high = transformed + pixel_count;
    size_t pixel_index = 0;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x, ++pixel_index) {
            const uint16_t delta = static_cast<uint16_t>(
                low[pixel_index] |
                (((high[pixel_index / 4] >> ((pixel_index & 3) * 2)) & 0x03) << 8)
            );
            const uint16_t predictor = x >= 2
                ? raw10_pixel(output, row_bytes, x - 2, y)
                : (y >= 2 ? raw10_pixel(output, row_bytes, x, y - 2) : 0);
            set_raw10_pixel(
                output,
                row_bytes,
                x,
                y,
                static_cast<uint16_t>((predictor + delta) & 0x03ff)
            );
        }
    }
    return true;
}

uint32_t crc(const uint8_t* data, size_t size) {
    return static_cast<uint32_t>(::crc32(0L, data, static_cast<uInt>(size)));
}

}  // namespace

bool encode_raw10_lossless(
    const uint8_t* raw,
    int width,
    int height,
    int row_bytes,
    std::vector<uint8_t>& transform_scratch,
    EncodedRaw10Frame& output
) {
    size_t frame_bytes = 0;
    if (raw == nullptr || !valid_geometry(width, height, row_bytes, &frame_bytes) ||
        frame_bytes > std::numeric_limits<uInt>::max()) {
        return false;
    }

    output.flags = 0;
    output.raw_crc32 = crc(raw, frame_bytes);
    const uint8_t* compression_input = raw;
    if (transform_cfa_delta(raw, width, height, row_bytes, transform_scratch)) {
        compression_input = transform_scratch.data();
        output.flags |= kRaw10FrameFlagCfaDelta;
    }

    uLongf compressed_capacity = ::compressBound(static_cast<uLong>(frame_bytes));
    output.payload.resize(static_cast<size_t>(compressed_capacity));
    z_stream stream{};
    int compressed = ::deflateInit2(
        &stream,
        Z_BEST_SPEED,
        Z_DEFLATED,
        MAX_WBITS,
        8,
        Z_HUFFMAN_ONLY
    );
    if (compressed == Z_OK) {
        stream.next_in = const_cast<Bytef*>(compression_input);
        stream.avail_in = static_cast<uInt>(frame_bytes);
        stream.next_out = output.payload.data();
        stream.avail_out = static_cast<uInt>(compressed_capacity);
        compressed = ::deflate(&stream, Z_FINISH);
        compressed_capacity = stream.total_out;
        ::deflateEnd(&stream);
    }
    if (compressed == Z_STREAM_END && compressed_capacity < frame_bytes) {
        output.flags |= kRaw10FrameFlagDeflate;
        output.payload.resize(static_cast<size_t>(compressed_capacity));
    } else {
        output.flags = 0;
        output.payload.assign(raw, raw + frame_bytes);
    }
    output.payload_crc32 = crc(output.payload.data(), output.payload.size());
    return true;
}

bool decode_raw10_lossless(
    const uint8_t* payload,
    size_t payload_size,
    uint32_t flags,
    uint32_t expected_raw_crc32,
    int width,
    int height,
    int row_bytes,
    uint8_t* output,
    size_t output_size,
    std::vector<uint8_t>& transform_scratch
) {
    size_t frame_bytes = 0;
    if (payload == nullptr || output == nullptr ||
        !valid_geometry(width, height, row_bytes, &frame_bytes) || output_size < frame_bytes ||
        frame_bytes > std::numeric_limits<uInt>::max()) {
        return false;
    }

    const uint8_t* decoded = payload;
    if ((flags & kRaw10FrameFlagDeflate) != 0) {
        transform_scratch.resize(frame_bytes);
        uLongf decoded_size = static_cast<uLongf>(frame_bytes);
        if (::uncompress(
                transform_scratch.data(),
                &decoded_size,
                payload,
                static_cast<uLong>(payload_size)
            ) != Z_OK || decoded_size != frame_bytes) {
            return false;
        }
        decoded = transform_scratch.data();
    } else if (payload_size != frame_bytes) {
        return false;
    }

    if ((flags & kRaw10FrameFlagCfaDelta) != 0) {
        if (!inverse_cfa_delta(decoded, frame_bytes, width, height, row_bytes, output, output_size)) {
            return false;
        }
    } else {
        std::memcpy(output, decoded, frame_bytes);
    }
    return crc(output, frame_bytes) == expected_raw_crc32;
}

}  // namespace rawlite
