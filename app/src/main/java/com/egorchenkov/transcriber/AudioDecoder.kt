package com.egorchenkov.transcriber

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder


/** Декодирует любой аудио/видеофайл, который понимает Android, в моно 16 кГц float порциями. */
object AudioDecoder {

    /** Длительность в секундах (0, если неизвестна). */
    fun durationSec(ctx: Context, uri: Uri): Float {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ctx, uri, null)
            val i = audioTrack(ex)
            if (i < 0) 0f else {
                val f = ex.getTrackFormat(i)
                if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1e6f else 0f
            }
        } catch (_: Exception) {
            0f
        } finally {
            ex.release()
        }
    }

    private fun audioTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return -1
    }

    /**
     * @param onChunk порция 16 кГц моно и позиция в секундах; вернуть false — прервать.
     */
    fun decode(ctx: Context, uri: Uri, onChunk: (FloatArray, Float) -> Boolean) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = audioTrack(ex)
        require(track >= 0) { "в файле нет аудиодорожки" }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var floatPcm = false
        var resampler = Resampler(rate, SAMPLE_RATE)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(rate, SAMPLE_RATE)
                    }
                    out >= 0 -> {
                        val buf = codec.getOutputBuffer(out)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val mono: FloatArray
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            mono = FloatArray(frames) { i ->
                                var s = 0f
                                for (c in 0 until channels) s += fb.get(i * channels + c)
                                s / channels
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / channels
                            mono = FloatArray(frames) { i ->
                                var s = 0f
                                for (c in 0 until channels) s += sb.get(i * channels + c)
                                s / channels / 32768f
                            }
                        }
                        codec.releaseOutputBuffer(out, false)
                        val res = resampler.process(mono)
                        if (res.isNotEmpty() && !onChunk(res, info.presentationTimeUs / 1e6f)) return
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
    }
}
