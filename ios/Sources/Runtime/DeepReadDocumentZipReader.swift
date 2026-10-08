import Foundation
import Compression

// DOCX ZIP entry reader reused from DocumentAccessStore, without its grants or tools.
struct DeepReadDocumentZipReader {
    static func entry(named expectedName: String, in data: Data) throws -> Data? {
        guard let eocd = data.lastRange(of: Data([0x50, 0x4b, 0x05, 0x06]))?.lowerBound else {
            throw DocumentAccessError.unsupportedFileType("DOCX 不是有效的 ZIP 文档。")
        }
        let entryCount = Int(try data.doc_uint16LE(at: eocd + 10))
        let centralOffset = Int(try data.doc_uint32LE(at: eocd + 16))
        var cursor = centralOffset

        for _ in 0..<entryCount {
            guard try data.doc_uint32LE(at: cursor) == 0x02014b50 else {
                throw DocumentAccessError.unsupportedFileType("DOCX ZIP 中央目录损坏。")
            }
            let method = try data.doc_uint16LE(at: cursor + 10)
            let compressedSize = Int(try data.doc_uint32LE(at: cursor + 20))
            let uncompressedSize = Int(try data.doc_uint32LE(at: cursor + 24))
            let nameLength = Int(try data.doc_uint16LE(at: cursor + 28))
            let extraLength = Int(try data.doc_uint16LE(at: cursor + 30))
            let commentLength = Int(try data.doc_uint16LE(at: cursor + 32))
            let localOffset = Int(try data.doc_uint32LE(at: cursor + 42))
            let nameStart = cursor + 46
            let nameEnd = nameStart + nameLength
            guard nameEnd <= data.count,
                  let name = String(data: data[nameStart..<nameEnd], encoding: .utf8) else {
                throw DocumentAccessError.unsupportedFileType("DOCX ZIP 条目名损坏。")
            }

            defer {
                cursor = nameEnd + extraLength + commentLength
            }
            guard name == expectedName else { continue }
            guard uncompressedSize <= DeepReadFileImporter.maxReadableBytes else {
                throw DocumentAccessError.fileTooLarge
            }
            guard try data.doc_uint32LE(at: localOffset) == 0x04034b50 else {
                throw DocumentAccessError.unsupportedFileType("DOCX ZIP 本地条目损坏。")
            }
            let localNameLength = Int(try data.doc_uint16LE(at: localOffset + 26))
            let localExtraLength = Int(try data.doc_uint16LE(at: localOffset + 28))
            let dataStart = localOffset + 30 + localNameLength + localExtraLength
            let dataEnd = dataStart + compressedSize
            guard dataEnd <= data.count else {
                throw DocumentAccessError.unsupportedFileType("DOCX ZIP 条目大小损坏。")
            }
            let payload = Data(data[dataStart..<dataEnd])
            switch method {
            case 0:
                guard payload.count <= DeepReadFileImporter.maxReadableBytes else { throw DocumentAccessError.fileTooLarge }
                return payload
            case 8:
                let decompressed = try decompress(payload, maximumBytes: DeepReadFileImporter.maxReadableBytes)
                if uncompressedSize > 0, decompressed.count > max(uncompressedSize * 2, uncompressedSize + 1024) {
                    throw DocumentAccessError.unsupportedFileType("DOCX ZIP 解压结果异常。")
                }
                return decompressed
            default:
                throw DocumentAccessError.unsupportedFileType("DOCX 使用了暂不支持的 ZIP 压缩方式：\(method)。")
            }
        }
        return nil
    }

    private static func decompress(_ payload: Data, maximumBytes: Int) throws -> Data {
        let chunkBytes = 64 * 1_024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunkBytes)
        defer { buffer.deallocate() }
        return try payload.withUnsafeBytes { bytes in
            guard let input = bytes.bindMemory(to: UInt8.self).baseAddress else {
                throw DocumentAccessError.unsupportedFileType("DOCX ZIP 解压失败，暂不能提取此文档文本。")
            }
            var stream = compression_stream(dst_ptr: buffer, dst_size: chunkBytes, src_ptr: input, src_size: payload.count, state: nil)
            guard compression_stream_init(&stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) != COMPRESSION_STATUS_ERROR else {
                throw DocumentAccessError.unsupportedFileType("DOCX ZIP 解压失败，暂不能提取此文档文本。")
            }
            defer { compression_stream_destroy(&stream) }
            stream.src_ptr = input
            stream.src_size = payload.count
            var output = Data()
            while true {
                stream.dst_ptr = buffer
                stream.dst_size = chunkBytes
                let status = compression_stream_process(&stream, 0)
                let count = chunkBytes - stream.dst_size
                // Directory sizes are untrusted; enforce the bound while decoding.
                guard count <= maximumBytes - output.count else { throw DocumentAccessError.fileTooLarge }
                output.append(buffer, count: count)
                if status == COMPRESSION_STATUS_END { return output }
                guard status == COMPRESSION_STATUS_OK, count > 0 else {
                    throw DocumentAccessError.unsupportedFileType("DOCX ZIP 解压失败，暂不能提取此文档文本。")
                }
            }
        }
    }

}

private extension Data {
    func doc_uint16LE(at offset: Int) throws -> UInt16 {
        guard offset + 2 <= count else {
            throw DocumentAccessError.unsupportedFileType("DOCX ZIP 读取越界。")
        }
        return UInt16(self[offset]) | (UInt16(self[offset + 1]) << 8)
    }

    func doc_uint32LE(at offset: Int) throws -> UInt32 {
        guard offset + 4 <= count else {
            throw DocumentAccessError.unsupportedFileType("DOCX ZIP 读取越界。")
        }
        return UInt32(self[offset]) |
            (UInt32(self[offset + 1]) << 8) |
            (UInt32(self[offset + 2]) << 16) |
            (UInt32(self[offset + 3]) << 24)
    }
}
