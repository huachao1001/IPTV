package com.iptv.live;

/**
 * 从 TS 分片里识别音轨和视频编码。
 *
 * <p>本机硬解只有 H.264/HEVC，也没有任何 MP2 解码器。选源时不看编码会踩两个坑：
 * MP2 频道只能出画面没声音（强行给 MP3 解码器还会崩），
 * mpeg2video 频道要软解而且常是机房的纯色占位画面。
 */
final class TsAudio {

    /** 音轨编码。 */
    enum Kind {
        /** 尚未识别。 */
        UNKNOWN,
        /** AAC（ADTS 或 LATM），本机能解。 */
        AAC,
        /** MPEG-1/2 音频，本机解不了。 */
        MP2,
        /** 识别出是别的编码（AC3 等）。 */
        OTHER
    }

    /** 视频编码。 */
    enum Video {
        UNKNOWN,
        H264,
        HEVC,
        /** 老式 MPEG-2，本机没有硬解。 */
        MPEG2,
        OTHER
    }

    private static final int TS_PACKET = 188;
    private static final int PID_PAT = 0;

    private TsAudio() {
    }

    /** 扫描一段 TS 数据里的音轨编码，识别不出返回 UNKNOWN。 */
    static Kind detect(byte[] data, int length) {
        int pmt = findPmt(data, length);
        if (pmt < 0) {
            return Kind.UNKNOWN;
        }
        Kind best = Kind.UNKNOWN;
        for (int type : streamTypes(data, length, pmt)) {
            if (type == 0x0F || type == 0x11) {
                // AAC 优先，它比其他音轨更值得
                return Kind.AAC;
            }
            if (best == Kind.UNKNOWN) {
                if (type == 0x03 || type == 0x04) {
                    best = Kind.MP2;
                } else if (type == 0x81 || type == 0x87) {
                    best = Kind.OTHER;
                }
            }
        }
        return best;
    }

    /** 扫描一段 TS 数据里的视频编码，识别不出返回 UNKNOWN。 */
    static Video detectVideo(byte[] data, int length) {
        int pmt = findPmt(data, length);
        if (pmt < 0) {
            return Video.UNKNOWN;
        }
        for (int type : streamTypes(data, length, pmt)) {
            if (type == 0x1B) {
                return Video.H264;
            }
            if (type == 0x24) {
                return Video.HEVC;
            }
            if (type == 0x02) {
                return Video.MPEG2;
            }
        }
        return Video.UNKNOWN;
    }

    /** 读 PMT 里所有 elementary stream 的 stream_type。 */
    private static int[] streamTypes(byte[] data, int length, int pmtOffset) {
        int sectionLength = ((data[pmtOffset + 1] & 0x0F) << 8) | (data[pmtOffset + 2] & 0xFF);
        // 减去 CRC，循环里不越读到校验位
        int end = Math.min(pmtOffset + 3 + sectionLength - 4, length);
        // 3 字节表头 + 9 字节固定字段之后才是流循环
        int p = pmtOffset + 12;
        int[] out = new int[8];
        int n = 0;
        while (p + 5 <= end && n < out.length) {
            out[n++] = data[p] & 0xFF;
            int esInfoLength = ((data[p + 3] & 0x0F) << 8) | (data[p + 4] & 0xFF);
            p += 5 + esInfoLength;
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** 从 PAT 定位 PMT 的 section 起始位置。 */
    private static int findPmt(byte[] data, int length) {
        int pmtPid = -1;
        int patOffset = findSection(data, length, PID_PAT, 0x00);
        if (patOffset < 0) {
            return -1;
        }
        int sectionLength = ((data[patOffset + 1] & 0x0F) << 8) | (data[patOffset + 2] & 0xFF);
        int end = Math.min(patOffset + 3 + sectionLength - 4, length);
        // 表头之后是 4 字节一组的 program，program_number 为 0 的是 NIT
        for (int p = patOffset + 8; p + 4 <= end; p += 4) {
            int program = ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
            if (program != 0) {
                pmtPid = ((data[p + 2] & 0x1F) << 8) | (data[p + 3] & 0xFF);
                break;
            }
        }
        if (pmtPid < 0) {
            return -1;
        }
        return findSection(data, length, pmtPid, 0x02);
    }

    /** 找到指定 PID 上某个 table 的 section 起始位置。 */
    private static int findSection(byte[] data, int length, int pid, int tableId) {
        for (int off = 0; off + TS_PACKET <= length; off += TS_PACKET) {
            if ((data[off] & 0xFF) != 0x47) {
                continue;
            }
            int cur = ((data[off + 1] & 0x1F) << 8) | (data[off + 2] & 0xFF);
            if (cur != pid || (data[off + 1] & 0x40) == 0) {
                continue;
            }
            int p = off + 4;
            int adaptation = (data[off + 3] & 0x30) >> 4;
            if (adaptation == 2) {
                // 只有适配域、没有载荷
                continue;
            }
            if (adaptation == 3) {
                p += 1 + (data[p] & 0xFF);
            }
            if (p + 1 >= length) {
                continue;
            }
            // 跳过 pointer_field
            p += 1 + (data[p] & 0xFF);
            if (p + 3 > length) {
                continue;
            }
            if ((data[p] & 0xFF) == tableId) {
                return p;
            }
        }
        return -1;
    }
}
