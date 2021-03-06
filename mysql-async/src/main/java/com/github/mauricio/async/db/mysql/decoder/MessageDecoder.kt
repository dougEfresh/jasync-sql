
package com.github.mauricio.async.db.mysql.decoder

import io.netty.buffer.ByteBuf
import com.github.mauricio.async.db.mysql.message.server.ServerMessage

interface MessageDecoder {

   fun decode( buffer : ByteBuf ) : ServerMessage

}
