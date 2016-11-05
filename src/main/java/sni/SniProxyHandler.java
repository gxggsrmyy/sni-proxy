package sni;

import java.net.IDN;
import java.net.SocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import util.ChannelUtils;
import util.Console;

/**
 * <p>Enables <a href="https://tools.ietf.org/html/rfc3546#section-3.1">SNI
 * (Server Name Indication)</a> extension for server side SSL. For clients
 * support SNI, the server could have multiple host name bound on a single IP.
 * The client will send host name in the handshake data so server could decide
 * which certificate to choose for the host name.</p>
 */
public class SniProxyHandler extends ByteToMessageDecoder implements ChannelOutboundHandler {

    // Maximal number of ssl records to inspect before fallback to the default SslContext.
    private static final int MAX_SSL_RECORDS = 4;

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SniProxyHandler.class);

    private boolean handshakeFailed;
    private boolean suppressRead;
    private boolean readPending;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!suppressRead && !handshakeFailed) {
            final int writerIndex = in.writerIndex();
            try {
                loop:
                for (int i = 0; i < MAX_SSL_RECORDS; i++) {
                    final int readerIndex = in.readerIndex();
                    final int readableBytes = writerIndex - readerIndex;
                    if (readableBytes < SslUtils.SSL_RECORD_HEADER_LENGTH) {
                        // Not enough data to determine the record type and length.
                        return;
                    }

                    final int command = in.getUnsignedByte(readerIndex);

                    // tls, but not handshake command
                    switch (command) {
                        case SslUtils.SSL_CONTENT_TYPE_CHANGE_CIPHER_SPEC:
                        case SslUtils.SSL_CONTENT_TYPE_ALERT:
                            final int len = SslUtils.getEncryptedPacketLength(in, readerIndex);

                            // Not an SSL/TLS packet
                            if (len == -1) {
                                handshakeFailed = true;
                                NotSslRecordException e = new NotSslRecordException("not an SSL/TLS record: " + ByteBufUtil.hexDump(in));
                                in.skipBytes(in.readableBytes());
                                ctx.fireExceptionCaught(e);

                                SslUtils.notifyHandshakeFailure(ctx, e);
                                return;
                            }
                            if (writerIndex - readerIndex - SslUtils.SSL_RECORD_HEADER_LENGTH < len) {
                                // Not enough data
                                return;
                            }
                            // increase readerIndex and try again.
                            in.skipBytes(len);
                            continue;
                        case SslUtils.SSL_CONTENT_TYPE_HANDSHAKE:
                            final int majorVersion = in.getUnsignedByte(readerIndex + 1);

                            // SSLv3 or TLS
                            if (majorVersion == 3) {
                                final int packetLength = in.getUnsignedShort(readerIndex + 3) + SslUtils.SSL_RECORD_HEADER_LENGTH;

                                if (readableBytes < packetLength) {
                                    // client hello incomplete; try again to decode once more data is ready.
                                    return;
                                }

                                final int endOffset = readerIndex + packetLength;
                                int offset = readerIndex + 43;

                                if (endOffset - offset < 6) {
                                    break loop;
                                }

                                final int sessionIdLength = in.getUnsignedByte(offset);
                                offset += sessionIdLength + 1;

                                final int cipherSuitesLength = in.getUnsignedShort(offset);
                                offset += cipherSuitesLength + 2;

                                final int compressionMethodLength = in.getUnsignedByte(offset);
                                offset += compressionMethodLength + 1;

                                final int extensionsLength = in.getUnsignedShort(offset);
                                offset += 2;
                                final int extensionsLimit = offset + extensionsLength;

                                if (extensionsLimit > endOffset) {
                                    // Extensions should never exceed the record boundary.
                                    break loop;
                                }

                                for (;;) {
                                    if (extensionsLimit - offset < 4) {
                                        break loop;
                                    }

                                    final int extensionType = in.getUnsignedShort(offset);
                                    offset += 2;

                                    final int extensionLength = in.getUnsignedShort(offset);
                                    offset += 2;

                                    if (extensionsLimit - offset < extensionLength) {
                                        break loop;
                                    }

                                    // SNI
                                    // See https://tools.ietf.org/html/rfc6066#page-6
                                    if (extensionType == 0) {
                                        offset += 2;
                                        if (extensionsLimit - offset < 3) {
                                            break loop;
                                        }

                                        final int serverNameType = in.getUnsignedByte(offset);
                                        offset++;

                                        if (serverNameType == 0) {
                                            final int serverNameLength = in.getUnsignedShort(offset);
                                            offset += 2;

                                            if (extensionsLimit - offset < serverNameLength) {
                                                break loop;
                                            }

                                            final String hostname = in.toString(offset, serverNameLength, CharsetUtil.UTF_8);
                                            final String remoteHost = IDN.toASCII(hostname, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.US);
                                            final Bootstrap b = new Bootstrap();
                                            b.group(ctx.channel().eventLoop())
                                                    .channel(NioSocketChannel.class)
                                                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 6000)
                                                    .option(ChannelOption.SO_KEEPALIVE, true)
                                                    .handler(new ChannelInitializer<SocketChannel>() {
                                                        @Override
                                                        public void initChannel(SocketChannel ch) {
                                                            ch.pipeline().addLast(new IdleStateHandler(0, 0, 60, TimeUnit.SECONDS));
                                                            ch.pipeline().addLast(new RelayHandler(ctx.channel()));
                                                        }
                                                    });
                                            b.connect(remoteHost, 443).addListener((ChannelFuture connectFuture) -> {
                                                if (connectFuture.isSuccess()) {
                                                    final Channel directChannel = connectFuture.channel();
                                                    if(ctx.channel().isActive() && directChannel.isActive()){
                                                        ctx.pipeline().addFirst(new IdleStateHandler(0, 0, 60, TimeUnit.SECONDS));
                                                        ctx.pipeline().replace(SniProxyHandler.this, "relay-handler", new RelayHandler(directChannel));
                                                        suppressRead = false;
                                                        if (readPending) {
                                                            readPending = false;
                                                            ctx.read();
                                                        }
                                                    } else {
                                                        ChannelUtils.closeOnFlush(directChannel);
                                                        ChannelUtils.closeOnFlush(ctx.channel());
                                                    }
                                                } else {
                                                    //可能发生了超时(io.netty.channel.ConnectTimeoutException)
                                                    ChannelUtils.closeOnFlush(ctx.channel());
                                                }
                                            });
                                            return;
                                        } else {
                                            // invalid enum value
                                            break loop;
                                        }
                                    }

                                    offset += extensionLength;
                                }
                            }
                            // Fall-through
                        default:
                            //not tls, ssl or application data, do not try sni
                            break loop;
                    }
                }
            } catch (Throwable e) {
                Console.error("sni.SniProxyHandler", "Error: " + e.getMessage());
            }
            // Close
            ChannelUtils.closeOnFlush(ctx.channel());
        }
    }

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        ctx.bind(localAddress, promise);
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
                        ChannelPromise promise) throws Exception {
        ctx.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        ctx.disconnect(promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        ctx.close(promise);
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        ctx.deregister(promise);
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        if (suppressRead) {
            readPending = true;
        } else {
            ctx.read();
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ctx.write(msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }
}
