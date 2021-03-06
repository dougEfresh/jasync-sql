package com.github.mauricio.async.db.mysql.codec

import com.github.jasync.sql.db.Configuration
import com.github.jasync.sql.db.exceptions.DatabaseException
import com.github.jasync.sql.db.general.MutableResultSet
import com.github.jasync.sql.db.util.ExecutorServiceUtils
import com.github.jasync.sql.db.util.XXX
import com.github.jasync.sql.db.util.failure
import com.github.jasync.sql.db.util.flatMap
import com.github.jasync.sql.db.util.head
import com.github.jasync.sql.db.util.length
import com.github.jasync.sql.db.util.onFailure
import com.github.jasync.sql.db.util.tail
import com.github.jasync.sql.db.util.toCompletableFuture
import com.github.mauricio.async.db.mysql.binary.BinaryRowDecoder
import com.github.mauricio.async.db.mysql.message.client.AuthenticationSwitchResponse
import com.github.mauricio.async.db.mysql.message.client.HandshakeResponseMessage
import com.github.mauricio.async.db.mysql.message.client.PreparedStatementExecuteMessage
import com.github.mauricio.async.db.mysql.message.client.PreparedStatementPrepareMessage
import com.github.mauricio.async.db.mysql.message.client.QueryMessage
import com.github.mauricio.async.db.mysql.message.client.QuitMessage
import com.github.mauricio.async.db.mysql.message.client.SendLongDataMessage
import com.github.mauricio.async.db.mysql.message.server.AuthenticationSwitchRequest
import com.github.mauricio.async.db.mysql.message.server.BinaryRowMessage
import com.github.mauricio.async.db.mysql.message.server.ColumnDefinitionMessage
import com.github.mauricio.async.db.mysql.message.server.EOFMessage
import com.github.mauricio.async.db.mysql.message.server.ErrorMessage
import com.github.mauricio.async.db.mysql.message.server.HandshakeMessage
import com.github.mauricio.async.db.mysql.message.server.OkMessage
import com.github.mauricio.async.db.mysql.message.server.PreparedStatementPrepareResponse
import com.github.mauricio.async.db.mysql.message.server.ResultSetRowMessage
import com.github.mauricio.async.db.mysql.message.server.ServerMessage
import com.github.mauricio.async.db.mysql.util.CharsetMapper
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.CodecException
import mu.KotlinLogging
import sun.java2d.xr.XRUtils.None
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class MySQLConnectionHandler(
    val configuration: Configuration,
    val charsetMapper: CharsetMapper,
    val handlerDelegate: MySQLHandlerDelegate,
    val group: EventLoopGroup,
    val executionContext: ExecutorService = ExecutorServiceUtils.CommonPool,
    val connectionId: String
) : SimpleChannelInboundHandler<Any>() {

  private val internalPool = executionContext
  private val log = KotlinLogging.logger("<connection-handler>$connectionId")
  private val bootstrap = Bootstrap().group(this.group)
  private val connectionPromise = CompletableFuture<MySQLConnectionHandler>()
  private val decoder = MySQLFrameDecoder(configuration.charset, connectionId)
  private val encoder = MySQLOneToOneEncoder(configuration.charset, charsetMapper)
  private val sendLongDataEncoder = SendLongDataEncoder()
  private val currentParameters = mutableListOf<ColumnDefinitionMessage>()
  private val currentColumns = mutableListOf<ColumnDefinitionMessage>()
  private val parsedStatements = HashMap<String, PreparedStatementHolder>()
  private val binaryRowDecoder = BinaryRowDecoder()

  private var currentPreparedStatementHolder: PreparedStatementHolder? = null
  private var currentPreparedStatement: PreparedStatement? = null
  private var currentQuery: MutableResultSet<ColumnDefinitionMessage>? = null
  private var currentContext: ChannelHandlerContext? = null

  fun connect(): CompletableFuture<MySQLConnectionHandler> {
    this.bootstrap.channel(NioSocketChannel::class.java)
    this.bootstrap.handler(object : ChannelInitializer<io.netty.channel.Channel>() {

      override fun initChannel(channel: io.netty.channel.Channel) {
        channel.pipeline().addLast(
            decoder,
            encoder,
            sendLongDataEncoder,
            this)
      }

    })

    this.bootstrap.option(ChannelOption.SO_KEEPALIVE, true)
    this.bootstrap.option<ByteBufAllocator>(ChannelOption.ALLOCATOR, LittleEndianByteBufAllocator.INSTANCE)

    val channelFuture: ChannelFuture = this.bootstrap.connect(InetSocketAddress(configuration.host, configuration.port))
    channelFuture.onFailure { exception ->
      this.connectionPromise.completeExceptionally(exception)
    }

    return this.connectionPromise
  }

  override fun channelRead0(ctx: ChannelHandlerContext, message: Any) {
    when (message) {
      is ServerMessage -> {
        when (message.kind) {
          ServerMessage.ServerProtocolVersion -> {
            handlerDelegate.onHandshake(message as HandshakeMessage)
          }
          ServerMessage.Ok -> {
            this.clearQueryState()
            handlerDelegate.onOk(message as OkMessage)
          }
          ServerMessage.Error -> {
            this.clearQueryState()
            handlerDelegate.onError(message as ErrorMessage)
          }
          ServerMessage.EOF -> {
            this.handleEOF(message)
          }
          ServerMessage.ColumnDefinition -> {
            val m = message as ColumnDefinitionMessage

            this.currentPreparedStatementHolder?.let {
              if (it.needsAny()) {
                it.add(m)
              }
            }

            this.currentColumns += message
          }
          ServerMessage.ColumnDefinitionFinished -> {
            this.onColumnDefinitionFinished()
          }
          ServerMessage.PreparedStatementPrepareResponse -> {
            this.onPreparedStatementPrepareResponse(message as PreparedStatementPrepareResponse)
          }
          ServerMessage.Row -> {
            val message = message as ResultSetRowMessage
            val items = Array<Any?>(message.length()) {
              if (message[it] == null) {
                null
              } else {
                val columnDescription = this.currentQuery!!.columnTypes[it]
                columnDescription.textDecoder.decode(columnDescription, message[it]!!, configuration.charset)
              }
            }

            this.currentQuery?.addRow(items)
          }
          ServerMessage.BinaryRow -> {
            val m = message as BinaryRowMessage
            this.currentQuery?.addRow(this.binaryRowDecoder.decode(m.buffer, this.currentColumns))
          }
          ServerMessage.ParamProcessingFinished -> {
          }
          ServerMessage.ParamAndColumnProcessingFinished -> {
            this.onColumnDefinitionFinished()
          }
        }
      }
    }

  }

  override fun channelActive(ctx: ChannelHandlerContext): Unit {
    log.debug("Channel became active")
    handlerDelegate.connected(ctx)
  }


  override fun channelInactive(ctx: ChannelHandlerContext) {
    log.debug("Channel became inactive")
  }

  override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    // unwrap CodecException if needed
    when (cause) {
      is CodecException -> handleException(cause.cause ?: cause)
      else -> handleException(cause)
    }

  }

  private fun handleException(cause: Throwable) {
    if (!this.connectionPromise.isDone) {
      this.connectionPromise.failure(cause)
    }
    handlerDelegate.exceptionCaught(cause)
  }

  override fun handlerAdded(ctx: ChannelHandlerContext) {
    this.currentContext = ctx
  }

  fun write(message: QueryMessage): ChannelFuture {
    this.decoder.queryProcessStarted()
    return writeAndHandleError(message)
  }

  fun sendPreparedStatement(query: String, values: List<Any>): CompletableFuture<ChannelFuture> {
    val preparedStatement = PreparedStatement(query, values)

    this.currentColumns.clear()
    this.currentParameters.clear()

    this.currentPreparedStatement = preparedStatement

    val item = this.parsedStatements[preparedStatement.statement]
    return when {
      item != null -> {
        this.executePreparedStatement(item.statementId(), item.columns.size, preparedStatement.values, item.parameters)
      }
      else -> {
        decoder.preparedStatementPrepareStarted()
        writeAndHandleError(PreparedStatementPrepareMessage(preparedStatement.statement)).toCompletableFuture()
      }
    }
  }

  fun write(message: HandshakeResponseMessage): ChannelFuture {
    decoder.hasDoneHandshake = true
    return writeAndHandleError(message)
  }

  fun write(message: AuthenticationSwitchResponse): ChannelFuture = writeAndHandleError(message)

  fun write(message: QuitMessage): ChannelFuture {
    return writeAndHandleError(message)
  }

  fun disconnect(): ChannelFuture = this.currentContext!!.close()

  fun clearQueryState() {
    this.currentColumns.clear()
    this.currentParameters.clear()
    this.currentQuery = null
  }

  fun isConnected(): Boolean {
    return this.currentContext?.channel()?.isActive ?: false
  }

  private fun executePreparedStatement(statementId: ByteArray, columnsCount: Int, values: List<Any?>, parameters: List<ColumnDefinitionMessage>): CompletableFuture<ChannelFuture> {
    decoder.preparedStatementExecuteStarted(columnsCount, parameters.size)
    this.currentColumns.clear()
    this.currentParameters.clear()
    val (longValues1, nonLongIndicesOpt1) = values.mapIndexed{ index, any -> index to any}
        .partition{ (_, any) -> any != null && isLong(any) }
    val nonLongIndices: List<Int> = nonLongIndicesOpt1.map { it.first }
    val longValues: List<Pair<Int, Any>> = longValues1.mapNotNull { if (it.second == null) null else it.first to it.second!!  }

    return if (longValues.isNotEmpty()) {
      val (firstIndex, firstValue) = longValues.head
      var channelFuture: CompletableFuture<ChannelFuture> = sendLongParameter(statementId, firstIndex, firstValue)
      longValues.tail.forEach { (index, value) ->
        channelFuture = channelFuture.flatMap { _ ->
          sendLongParameter(statementId, index, value)
        }
      }
       channelFuture.toCompletableFuture().flatMap { _ ->
        writeAndHandleError(PreparedStatementExecuteMessage(statementId, values, nonLongIndices.toSet(), parameters)).toCompletableFuture()
      }
    } else {
      writeAndHandleError(PreparedStatementExecuteMessage(statementId, values, nonLongIndices.toSet(), parameters)).toCompletableFuture()
    }
  }

  private fun isLong(value: Any): Boolean {
    return when (value) {
      is ByteArray -> value.length > SendLongDataEncoder.LONG_THRESHOLD
      is ByteBuffer -> value.remaining() > SendLongDataEncoder.LONG_THRESHOLD
      is ByteBuf -> value.readableBytes() > SendLongDataEncoder.LONG_THRESHOLD
      else -> false
    }
  }

  private fun sendLongParameter(statementId: ByteArray, index: Int, longValue: Any): CompletableFuture<ChannelFuture> {
    return when (longValue) {
      is ByteArray ->
        sendBuffer(Unpooled.wrappedBuffer(longValue), statementId, index)

      is ByteBuffer ->
        sendBuffer(Unpooled.wrappedBuffer(longValue), statementId, index)

      is ByteBuf ->
        sendBuffer(longValue, statementId, index)
      else -> XXX("no handle for ${longValue::class.java}")
    }.toCompletableFuture()
  }

  private fun sendBuffer(buffer: ByteBuf, statementId: ByteArray, paramId: Int): ChannelFuture {
    return writeAndHandleError(SendLongDataMessage(statementId, buffer, paramId))
  }

  private fun onPreparedStatementPrepareResponse(message: PreparedStatementPrepareResponse) {
    this.currentPreparedStatementHolder = PreparedStatementHolder(this.currentPreparedStatement!!.statement, message)
  }

  fun onColumnDefinitionFinished() {

    val columns =
        this.currentPreparedStatementHolder?.columns ?: this.currentColumns

    this.currentQuery = MutableResultSet<ColumnDefinitionMessage>(columns)

    this.currentPreparedStatementHolder?.let {
      this.parsedStatements.put(it.statement, it)
      this.executePreparedStatement(
          it.statementId(),
          it.columns.size,
          this.currentPreparedStatement!!.values,
          it.parameters
      )
      this.currentPreparedStatementHolder = null
      this.currentPreparedStatement = null
    }
  }

  private fun writeAndHandleError(message: Any): ChannelFuture {
    val result = if (this.currentContext?.channel()?.isActive == true) {
      val res: ChannelFuture = this.currentContext!!.writeAndFlush(message)

      res.onFailure { e: Throwable ->
        handleException(e)
      }

      res
    } else {
      val error = DatabaseException("This channel is not active and can't take messages")
      handleException(error)
      this.currentContext!!.channel().newFailedFuture(error)
    }
    return result
  }

  private fun handleEOF(m: ServerMessage) {
    when (m) {
      is EOFMessage -> {
        val resultSet = this.currentQuery
        this.clearQueryState()

        if (resultSet != null) {
          handlerDelegate.onResultSet(resultSet, m)
        } else {
          handlerDelegate.onEOF(m)
        }
      }
      is AuthenticationSwitchRequest -> {
        handlerDelegate.switchAuthentication(m)
      }
    }
  }

  fun schedule(block: () -> Unit, duration: Duration): Unit {
    this.currentContext!!.channel().eventLoop().schedule(block, duration.toMillis(), TimeUnit.MILLISECONDS)
  }

}
