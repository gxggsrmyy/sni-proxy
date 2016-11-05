package sni;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import util.Console;

public class SniProxyServer {
    public static void main(String[] args) {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new SniProxyServerInitializer());
        b.bind("0.0.0.0", 443).addListener((ChannelFuture f) -> {
            if(f.isSuccess()){
                Console.info("sni.SniProxyServer", "Sni Proxy start on 443 ...");
            } else {
                Console.error("sni.SniProxyServer", "Error: " + f.cause().getMessage());
                System.exit(0);
            }
        });

        //关闭时清理资源
        Runtime.getRuntime().addShutdownHook(new Thread(){
            @Override
            public void run() {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }
        });

    }
}
