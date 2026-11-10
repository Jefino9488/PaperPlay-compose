package io.max.paperplay.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Range-reads a [StreamSource] looked up by the DataSpec uri. */
internal class StreamDataSource(private val sources: Map<String, StreamSource>) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var source: StreamSource? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val s = sources[dataSpec.uri.toString()] ?: throw IOException("Unknown stream ${dataSpec.uri}")
        if (dataSpec.position > s.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        source = s
        uri = dataSpec.uri
        position = dataSpec.position
        val left = s.size - position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) left else minOf(dataSpec.length, left)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val chunk = source!!.read(position, minOf(length.toLong(), remaining).toInt())
        if (chunk.isEmpty()) return C.RESULT_END_OF_INPUT
        System.arraycopy(chunk, 0, buffer, offset, chunk.size)
        position += chunk.size
        remaining -= chunk.size
        bytesTransferred(chunk.size)
        return chunk.size
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (source != null) transferEnded()
        source = null
        uri = null
    }
}

/** Sends registered stream keys to [StreamDataSource], everything else to [upstream]. */
internal class RoutingDataSource(
    private val sources: Map<String, StreamSource>,
    private val stream: StreamDataSource,
    private val upstream: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        stream.addTransferListener(transferListener)
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val d = if (sources.containsKey(dataSpec.uri.toString())) stream else upstream
        active = d
        return d.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() {
        active?.close()
        active = null
    }
}
