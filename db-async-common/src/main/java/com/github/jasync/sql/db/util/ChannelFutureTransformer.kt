package com.github.jasync.sql.db.util

import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelFuture
//import scala.concurrent.Promise
//import scala.concurrent.Future
import com.github.jasync.sql.db.exceptions.CanceledChannelFutureException
//import scala.language.implicitConversions
import java.util.concurrent.CompletableFuture

fun ChannelFuture.toCompletableFuture(): CompletableFuture<ChannelFuture> {
  val promise = CompletableFuture<ChannelFuture>()

  val listener = ChannelFutureListener { future ->
    if (future.isSuccess) {
      promise.complete(future)
    } else {
      val exception = if (future.cause() == null) {
        CanceledChannelFutureException(future)
            .fillInStackTrace()
      } else {
        future.cause()
      }
      promise.completeExceptionally(exception)
    }
  }
  this.addListener(listener)

  return promise
}


fun ChannelFuture.onFailure(handler: (Throwable) -> Unit) {
  this.toCompletableFuture().onFailure(onFailureFun = handler)
}
