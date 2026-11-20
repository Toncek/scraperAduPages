package com.harbor.tv.feature.player

import android.media.MediaCodecList
import android.os.Build
import com.harbor.tv.core.model.DeviceCodecCapabilities

/**
 * Inspects device hardware video decoders via [MediaCodecList] at startup so [PlaybackResolver]
 * can prefer hardware-accelerated 4K codecs (H.264 / HEVC / AV1) on low-RAM Android TV SoCs.
 */
object HardwareCodecCapabilities {

    private const val MIME_AVC = "video/avc"
    private const val MIME_HEVC = "video/hevc"
    private const val MIME_AV1 = "video/av01"

    fun detect(): DeviceCodecCapabilities {
        return runCatching {
            val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            var avc4k = false
            var hevc4k = false
            var av14k = false

            for (info in codecInfos) {
                if (info.isEncoder) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !info.isHardwareAccelerated) {
                    continue
                }
                for (type in info.supportedTypes) {
                    val mime = type.lowercase()
                    if (mime != MIME_AVC && mime != MIME_HEVC && mime != MIME_AV1) continue
                    val supports4k = runCatching {
                        val caps = info.getCapabilitiesForType(type)
                        caps.videoCapabilities?.isSizeSupported(3840, 2160) == true
                    }.getOrDefault(false)
                    if (!supports4k) continue
                    when (mime) {
                        MIME_AVC -> avc4k = true
                        MIME_HEVC -> hevc4k = true
                        MIME_AV1 -> av14k = true
                    }
                }
            }

            val supportedLabels = buildList {
                if (avc4k) add("H.264")
                if (hevc4k) add("HEVC")
                if (av14k) add("AV1")
            }
            val summary = if (supportedLabels.isEmpty()) {
                "HW 4K Decoders: None detected (1080p preferred)"
            } else {
                "HW 4K Decoders: ${supportedLabels.joinToString(", ")}"
            }

            DeviceCodecCapabilities(
                hwAvc4k = avc4k,
                hwHevc4k = hevc4k,
                hwAv14k = av14k,
                summary = summary
            )
        }.getOrElse {
            DeviceCodecCapabilities()
        }
    }
}
