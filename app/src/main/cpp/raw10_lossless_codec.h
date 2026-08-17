#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace rawlite {

constexpr uint32_t kRaw10FrameFlagDeflate = 1U << 0;
constexpr uint32_t kRaw10FrameFlagCfaDelta = 1U << 1;

struct EncodedRaw10Frame {
    uint32_t flags = 0;
    uint32_t raw_crc32 = 0;
    uint32_t payload_crc32 = 0;
    std::vector<uint8_t> payload;
};

bool encode_raw10_lossless(
    const uint8_t* raw,
    int width,
    int height,
    int row_bytes,
    std::vector<uint8_t>& transform_scratch,
    EncodedRaw10Frame& output
);

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
);

}  // namespace rawlite
