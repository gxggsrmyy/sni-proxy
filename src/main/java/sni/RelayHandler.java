package sni;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import util.ChannelUtils;
import util.Console;

public final class RelayHandler extends ChannelInboundHandlerAdapter {

    private final Channel relayChannel;

    public RelayHandler(Channel relayChannel) {
        this.relayChannel = relayChannel;
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        relayChannel.flush();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (relayChannel.isActive()) {
            relayChannel.write(msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        //如果浏览器的请求连接空闲，则关闭两个连接
        if(evt instanceof IdleStateEvent) {
            ChannelUtils.closeOnFlush(ctx.channel());
            ChannelUtils.closeOnFlush(relayChannel);
            Console.info("sni.RelayHandler", "Close idle connection.");
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ChannelUtils.closeOnFlush(relayChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Console.error("ReplayHandler", cause.getMessage());
    }
}
