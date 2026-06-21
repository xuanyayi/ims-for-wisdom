//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

object SipAudioTrackFactory {
    fun createVoiceCallTrack(
        audioCodec: NegotiatedAudioCodec = SipAudioCodecs.AMR_NB,
    ): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            audioCodec.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val frameBytes = (audioCodec.sampleRate / 50) * 2
        val fallbackBufferSize = (frameBytes * 8).coerceAtLeast(2048)
        val bufferSize =
            (if (minBufferSize > 0) minBufferSize else fallbackBufferSize)
                .coerceAtLeast(fallbackBufferSize)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(audioCodec.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
    }
}
