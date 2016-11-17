package sni;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.NetUtil;
import util.Console;

public class SniProxyServer {
    public static void main(String[] args) {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new SniProxyServerInitializer());
        ChannelFuture f;
        if(args.length > 0 && NetUtil.isValidIpV4Address(args[0])){
            f = b.bind(args[0], 443);
        } else {
            f = b.bind(443);
        }
        f.addListener((ChannelFuture bindFuture) -> {
            if(bindFuture.isSuccess()){
                Console.info("sni.SniProxyServer", "Sni Proxy start on 443 ...");
            } else {
                Console.error("sni.SniProxyServer", "Error: " + bindFuture.cause().getMessage());
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
