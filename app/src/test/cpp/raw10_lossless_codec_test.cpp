#include "../../main/cpp/raw10_lossless_codec.h"

#include <algorithm>
#include <cassert>
#include <array>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <random>
#include <vector>

namespace {

void put_le32(uint8_t* destination, size_t offset, uint32_t value) {
    destination[offset] = static_cast<uint8_t>(value & 0xff);
    destination[offset + 1] = static_cast<uint8_t>((value >> 8) & 0xff);
    destination[offset + 2] = static_cast<uint8_t>((value >> 16) & 0xff);
    destination[offset + 3] = static_cast<uint8_t>((value >> 24) & 0xff);
}

void set_pixel(std::vector<uint8_t>& raw, int width, int x, int y, uint16_t value) {
    const int row_bytes = width * 5 / 4;
    const size_t group = static_cast<size_t>(y) * row_bytes + static_cast<size_t>(x / 4) * 5;
    raw[group + static_cast<size_t>(x & 3)] = static_cast<uint8_t>(value >> 2);
    const int shift = (x & 3) * 2;
    raw[group + 4] = static_cast<uint8_t>(
        (raw[group + 4] & ~(0x03U << shift)) | ((value & 0x03U) << shift)
    );
}

void verify(int width, int height, bool noisy) {
    const int row_bytes = width * 5 / 4;
    std::vector<uint8_t> raw(static_cast<size_t>(row_bytes) * height, 0);
    std::mt19937 random(0x52415710);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const uint16_t value = noisy
                ? static_cast<uint16_t>(random() & 0x03ff)
                : static_cast<uint16_t>((x * 3 + y * 2 + ((x & 1) * 97) + ((y & 1) * 41)) & 0x03ff);
            set_pixel(raw, width, x, y, value);
        }
    }

    std::vector<uint8_t> transform;
    rawlite::EncodedRaw10Frame encoded;
    assert(rawlite::encode_raw10_lossless(
        raw.data(), width, height, row_bytes, transform, encoded
    ));
    std::vector<uint8_t> decoded(raw.size(), 0);
    std::vector<uint8_t> decode_scratch;
    assert(rawlite::decode_raw10_lossless(
        encoded.payload.data(),
        encoded.payload.size(),
        encoded.flags,
        encoded.raw_crc32,
        width,
        height,
        row_bytes,
        decoded.data(),
        decoded.size(),
        decode_scratch
    ));
    assert(raw == decoded);
    std::printf(
        "%s raw=%zu encoded=%zu ratio=%.3f flags=%u\n",
        noisy ? "noise" : "gradient",
        raw.size(),
        encoded.payload.size(),
        static_cast<double>(encoded.payload.size()) / raw.size(),
        encoded.flags
    );
}

}  // namespace

int main(int argc, char** argv) {
    verify(64, 48, false);
    verify(64, 48, true);
    verify(2032, 1536, false);
    verify(2032, 1536, true);
    if (argc == 4 || argc == 5) {
        const int width = std::stoi(argv[2]);
        const int height = std::stoi(argv[3]);
        const int row_bytes = width * 5 / 4;
        std::ifstream input(argv[1], std::ios::binary);
        std::vector<uint8_t> raw(
            (std::istreambuf_iterator<char>(input)),
            std::istreambuf_iterator<char>()
        );
        assert(raw.size() == static_cast<size_t>(row_bytes) * height);
        std::vector<uint8_t> transform;
        rawlite::EncodedRaw10Frame encoded;
        assert(rawlite::encode_raw10_lossless(
            raw.data(), width, height, row_bytes, transform, encoded
        ));
        std::vector<uint8_t> decoded(raw.size());
        std::vector<uint8_t> scratch;
        assert(rawlite::decode_raw10_lossless(
            encoded.payload.data(), encoded.payload.size(), encoded.flags,
            encoded.raw_crc32, width, height, row_bytes,
            decoded.data(), decoded.size(), scratch
        ));
        assert(raw == decoded);
        std::printf(
            "sample raw=%zu encoded=%zu ratio=%.3f flags=%u\n",
            raw.size(), encoded.payload.size(),
            static_cast<double>(encoded.payload.size()) / raw.size(), encoded.flags
        );
        if (argc == 5) {
            std::array<uint8_t, 64> container_header{};
            std::copy_n("RLR10C01", 8, container_header.begin());
            put_le32(container_header.data(), 8, 64);
            put_le32(container_header.data(), 12, 1);
            put_le32(container_header.data(), 16, static_cast<uint32_t>(width));
            put_le32(container_header.data(), 20, static_cast<uint32_t>(height));
            put_le32(container_header.data(), 24, static_cast<uint32_t>(row_bytes));
            put_le32(container_header.data(), 28, static_cast<uint32_t>(raw.size()));
            put_le32(container_header.data(), 32, 1);
            put_le32(container_header.data(), 36, 1);
            std::array<uint8_t, 32> frame_header{};
            std::copy_n("RLF1", 4, frame_header.begin());
            put_le32(frame_header.data(), 4, 32);
            put_le32(frame_header.data(), 8, 0);
            put_le32(frame_header.data(), 12, encoded.flags);
            put_le32(frame_header.data(), 16, static_cast<uint32_t>(raw.size()));
            put_le32(frame_header.data(), 20, static_cast<uint32_t>(encoded.payload.size()));
            put_le32(frame_header.data(), 24, encoded.raw_crc32);
            put_le32(frame_header.data(), 28, encoded.payload_crc32);
            std::ofstream output(argv[4], std::ios::binary | std::ios::trunc);
            output.write(
                reinterpret_cast<const char*>(container_header.data()),
                static_cast<std::streamsize>(container_header.size())
            );
            output.write(
                reinterpret_cast<const char*>(frame_header.data()),
                static_cast<std::streamsize>(frame_header.size())
            );
            output.write(
                reinterpret_cast<const char*>(encoded.payload.data()),
                static_cast<std::streamsize>(encoded.payload.size())
            );
            assert(output.good());
        }
    }
    return 0;
}
