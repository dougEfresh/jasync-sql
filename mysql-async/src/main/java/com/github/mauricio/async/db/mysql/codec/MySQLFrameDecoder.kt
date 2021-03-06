
package com.github.mauricio.async.db.mysql.codec

import com.github.jasync.sql.db.exceptions.BufferNotFullyConsumedException
import com.github.jasync.sql.db.exceptions.NegativeMessageSizeException
import com.github.jasync.sql.db.exceptions.ParserNotAvailableException
import com.github.jasync.sql.db.util.BufferDumper
import com.github.jasync.sql.db.util.ByteBufferUtils.read3BytesInt
import com.github.jasync.sql.db.util.readBinaryLength
import com.github.mauricio.async.db.mysql.decoder.AuthenticationSwitchRequestDecoder
import com.github.mauricio.async.db.mysql.decoder.ColumnDefinitionDecoder
import com.github.mauricio.async.db.mysql.decoder.ColumnProcessingFinishedDecoder
import com.github.mauricio.async.db.mysql.decoder.EOFMessageDecoder
import com.github.mauricio.async.db.mysql.decoder.ErrorDecoder
import com.github.mauricio.async.db.mysql.decoder.HandshakeV10Decoder
import com.github.mauricio.async.db.mysql.decoder.MessageDecoder
import com.github.mauricio.async.db.mysql.decoder.OkDecoder
import com.github.mauricio.async.db.mysql.decoder.ParamAndColumnProcessingFinishedDecoder
import com.github.mauricio.async.db.mysql.decoder.ParamProcessingFinishedDecoder
import com.github.mauricio.async.db.mysql.decoder.PreparedStatementPrepareResponseDecoder
import com.github.mauricio.async.db.mysql.decoder.ResultSetRowDecoder
import com.github.mauricio.async.db.mysql.message.server.BinaryRowMessage
import com.github.mauricio.async.db.mysql.message.server.ColumnProcessingFinishedMessage
import com.github.mauricio.async.db.mysql.message.server.EOFMessage
import com.github.mauricio.async.db.mysql.message.server.ParamAndColumnProcessingFinishedMessage
import com.github.mauricio.async.db.mysql.message.server.PreparedStatementPrepareResponse
import com.github.mauricio.async.db.mysql.message.server.ServerMessage
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import mu.KotlinLogging
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger


class MySQLFrameDecoder(val charset: Charset, val connectionId: String) : ByteToMessageDecoder() {

  private val log = KotlinLogging.logger("<frame-decoder>$connectionId")
  private val messagesCount = AtomicInteger()
  private val handshakeDecoder = HandshakeV10Decoder(charset)
  private val errorDecoder = ErrorDecoder(charset)
  private val okDecoder = OkDecoder(charset)
  private val columnDecoder = ColumnDefinitionDecoder(charset, DecoderRegistry(charset))
  private val rowDecoder = ResultSetRowDecoder(charset)
  private val preparedStatementPrepareDecoder = PreparedStatementPrepareResponseDecoder()
  private val authenticationSwitchDecoder = AuthenticationSwitchRequestDecoder(charset)

  private var processingColumns = false
  private var processingParams = false
  private var isInQuery = false
  private var isPreparedStatementPrepare = false
  private var isPreparedStatementExecute = false
  private var isPreparedStatementExecuteRows = false
   var hasDoneHandshake = false

  private var totalParams = 0L
  private var processedParams = 0L
  private var totalColumns = 0L
  private var processedColumns = 0L

  private var hasReadColumnsCount = false

  override fun decode(ctx: ChannelHandlerContext, buffer: ByteBuf, out: MutableList<Any>) {
    if (buffer.readableBytes() > 4) {

      buffer.markReaderIndex()

      val size = read3BytesInt(buffer)

      val sequence = buffer.readUnsignedByte() // we have to read this

      if (buffer.readableBytes() >= size) {

        messagesCount.incrementAndGet()

        val messageType = buffer.getByte(buffer.readerIndex())

        if (size < 0) {
          throw NegativeMessageSizeException(messageType, size)
        }

        val slice = buffer.readSlice(size)

        if (log.isTraceEnabled) {
          log.trace("Reading message type $messageType - " +
            "(count=$messagesCount,hasDoneHandshake=$hasDoneHandshake,size=$size,isInQuery=$isInQuery,processingColumns=$processingColumns,processingParams=$processingParams,processedColumns=$processedColumns,processedParams=$processedParams)" +
            "\n${BufferDumper.dumpAsHex(slice)}}")
        }

        slice.readByte()

        if (this.hasDoneHandshake) {
          this.handleCommonFlow(messageType, slice, out)
        } else {
          val decoder = when(messageType.toInt()) {
            ServerMessage.Error -> {
              this.clear()
              this.errorDecoder
            }
            else -> this.handshakeDecoder
          }
          this.doDecoding(decoder, slice, out)
        }
      } else {
        buffer.resetReaderIndex()
      }

    }
  }

  private fun handleCommonFlow(messageType: Byte, slice: ByteBuf, out: MutableList<Any>) {
    val decoder = when(messageType.toInt()) {
      ServerMessage.Error -> {
        this.clear()
        this.errorDecoder
      }
      ServerMessage.EOF -> {

        if (this.processingParams && this.totalParams > 0) {
          this.processingParams = false
          if (this.totalColumns == 0L) {
            ParamAndColumnProcessingFinishedDecoder
          } else {
            ParamProcessingFinishedDecoder
          }
        } else {
          if (this.processingColumns) {
            this.processingColumns = false
            ColumnProcessingFinishedDecoder
          } else {
            this.clear()
            EOFMessageDecoder
          }
        }

      }
      ServerMessage.Ok -> {
        if (this.isPreparedStatementPrepare) {
          this.preparedStatementPrepareDecoder
        } else {
          if (this.isPreparedStatementExecuteRows) {
            null
          } else {
            this.clear()
            this.okDecoder
          }
        }
      }
      else -> {

        if (this.isInQuery) {
          null
        } else {
          throw ParserNotAvailableException(messageType)
        }

      }
    }

    doDecoding(decoder, slice, out)
  }

  private fun doDecoding(decoder: MessageDecoder?, slice: ByteBuf, out: MutableList<Any>) {
    if (decoder == null) {
      slice.readerIndex(slice.readerIndex() - 1)
      val result = decodeQueryResult(slice)

      if (slice.readableBytes() != 0) {
        throw BufferNotFullyConsumedException(slice)
      }
      if (result != null) {
        out.add(result)
      }
    } else {
      val result = decoder.decode(slice)

       when(result) {
        is PreparedStatementPrepareResponse -> {
          this.hasReadColumnsCount = true
          this.totalColumns = result.columnsCount.toLong()
          this.totalParams = result.paramsCount.toLong()
        }
        is ParamAndColumnProcessingFinishedMessage -> {
          this.clear()
        }
        is ColumnProcessingFinishedMessage  -> {
         when {
           this.isPreparedStatementPrepare -> this.clear()
           this.isPreparedStatementExecute -> this.isPreparedStatementExecuteRows = true
         }
        }
      }

      if (slice.readableBytes() != 0) {
        throw BufferNotFullyConsumedException(slice)
      }

      if (result != null) {
         when(result) {
          is PreparedStatementPrepareResponse -> {
            out.add(result)
            if (result.columnsCount == 0 && result.paramsCount == 0) {
              this.clear()
              out.add(ParamAndColumnProcessingFinishedMessage(EOFMessage(0, 0)))
            }
          }
          else -> out.add(result)
        }
      }
    }
  }

  private fun decodeQueryResult(slice: ByteBuf): Any? {
    if (!hasReadColumnsCount) {
      this.hasReadColumnsCount = true
      this.totalColumns = slice.readBinaryLength()
      return null
    }

    if (this.processingParams && this.totalParams != this.processedParams) {
      this.processedParams += 1
      return this.columnDecoder.decode(slice)
    }


    return if (this.totalColumns == this.processedColumns) {
      if (this.isPreparedStatementExecute) {
        val row = slice.readBytes(slice.readableBytes())
        row.readByte() // reads initial 00 at message
        BinaryRowMessage(row)
      } else {
        this.rowDecoder.decode(slice)
      }
    } else {
      this.processedColumns += 1
      this.columnDecoder.decode(slice)
    }

  }

  fun preparedStatementPrepareStarted() {
    this.queryProcessStarted()
    this.hasReadColumnsCount = true
    this.processingParams = true
    this.processingColumns = true
    this.isPreparedStatementPrepare = true
  }

  fun preparedStatementExecuteStarted(columnsCount: Int, paramsCount: Int) {
    this.queryProcessStarted()
    this.hasReadColumnsCount = false
    this.totalColumns = columnsCount.toLong()
    this.totalParams = paramsCount.toLong()
    this.isPreparedStatementExecute = true
    this.processingParams = false
  }

  fun queryProcessStarted() {
    this.isInQuery = true
    this.processingColumns = true
    this.hasReadColumnsCount = false
  }

  private fun clear() {
    this.isPreparedStatementPrepare = false
    this.isPreparedStatementExecute = false
    this.isPreparedStatementExecuteRows = false
    this.isInQuery = false
    this.processingColumns = false
    this.processingParams = false
    this.totalColumns = 0
    this.processedColumns = 0
    this.totalParams = 0
    this.processedParams = 0
    this.hasReadColumnsCount = false
  }

}
