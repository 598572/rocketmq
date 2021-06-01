/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.namesrv;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Callable;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.apache.rocketmq.srvutil.ShutdownHookThread;
import org.slf4j.LoggerFactory;

public class NamesrvStartup {

    private static InternalLogger log;
    private static Properties properties = null;
    private static CommandLine commandLine = null;

    public static void main(String[] args) {
        main0(args);
    }

    /**
     * 1.加载配置 初始化  namesrvConfig 和 namesrvConfig  并用他俩 创建NamesrvController控制器
     *      1.1 处理命令行和日志配置
     *      1.2 创建NamesrvController实例
     * 2.启动控制器
     *      2.1.初始化 NamesrvController 用了很多线程池 包括： 创建netty相关远程服务与工作线程  开启定时任务：移除不活跃的broker 以及打印kV   TLS等
     *      2.2. 钩子函数 用于释放资源 在jvm退出之前应该是
     *      2.3.启动nameServerController 底层其实是启动netty服务
     *
     *       //netty的部分操作摘要
     *       handshakeHandler：处理握手操作，用来判断tls的开启状态
     *       encoder/NettyDecoder：处理报文的编解码操作
     *       IdleStateHandler：处理心跳
     *       connectionManageHandler：处理连接请求
     *       serverHandler：处理读写请求
     *
     *          //2.3 创建netty服务 部分重要代码摘要
     *          ServerBootstrap childHandler =
     *             this.serverBootstrap.group(this.eventLoopGroupBoss, this.eventLoopGroupSelector)
     *                 .channel(useEpoll() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
     *                 .option(ChannelOption.SO_BACKLOG, 1024)
     *                 .option(ChannelOption.SO_REUSEADDR, true)
     *                 .option(ChannelOption.SO_KEEPALIVE, false)
     *                 .childOption(ChannelOption.TCP_NODELAY, true)
     *                 .childOption(ChannelOption.SO_SNDBUF, nettyServerConfig.getServerSocketSndBufSize())
     *                 .childOption(ChannelOption.SO_RCVBUF, nettyServerConfig.getServerSocketRcvBufSize())
     *                     //监听端口
     *                 .localAddress(new InetSocketAddress(this.nettyServerConfig.getListenPort()))
     *                 .childHandler(new ChannelInitializer<SocketChannel>() {
     *                     @Override
     *                     public void initChannel(SocketChannel ch) throws Exception {
     *                         ch.pipeline()
     *                             .addLast(defaultEventExecutorGroup, HANDSHAKE_HANDLER_NAME, handshakeHandler)
     *                             .addLast(defaultEventExecutorGroup,
     *                                 encoder,
     *                                 new NettyDecoder(),
     *                                 new IdleStateHandler(0, 0, nettyServerConfig.getServerChannelMaxIdleTimeSeconds()),
     *                                 connectionManageHandler,
     *                                 //netty用来处理连接事件与读写事件的线程
     *                                     serverHandler
     *                             );
     *                     }
     *                 });
     *
     * @param args
     * @return
     */
    public static NamesrvController main0(String[] args) {

        try {
            //1.创建控NamesrvController
            NamesrvController controller = createNamesrvController(args);
            //2.启动控制器
            start(controller);
            //可以看到 默认序列化类型 = JSON
            String tip = "The Name Server boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();
            log.info(tip);
            System.out.printf("%s%n", tip);
            return controller;
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }

        return null;
    }

    /**
     * 两件事情
     *
     * 1.1 处理命令行和日志配置
     *
     * 1.2 创建NamesrvController实例
     *
     * @param args
     * @return
     * @throws IOException
     * @throws JoranException
     */
    public static NamesrvController createNamesrvController(String[] args) throws IOException, JoranException {
        System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));
        //PackageConflictDetect.detectFastjson();

        /**
         * <1> 构建命令行对象 并将其赋值给 namesrvConfig 和 namesrvConfig
         */
        Options options = ServerUtil.buildCommandlineOptions(new Options());
        commandLine = ServerUtil.parseCmdLine("mqnamesrv", args, buildCommandlineOptions(options), new PosixParser());
        if (null == commandLine) {
            System.exit(-1);
            return null;
        }

        final NamesrvConfig namesrvConfig = new NamesrvConfig();
        final NettyServerConfig nettyServerConfig = new NettyServerConfig();
        //可以看到 端口是写死的
        nettyServerConfig.setListenPort(9876);
        if (commandLine.hasOption('c')) {
            //读取命令行-c参数指定的配置文件
            String file = commandLine.getOptionValue('c');
            if (file != null) {
                // 将文件转成输入流
                InputStream in = new BufferedInputStream(new FileInputStream(file));
                properties = new Properties();
                //加载文件内容
                properties.load(in);
                //反射赋值 装载配置
                MixAll.properties2Object(properties, namesrvConfig);
                MixAll.properties2Object(properties, nettyServerConfig);
                //设定文件存储地址
                namesrvConfig.setConfigStorePath(file);

                System.out.printf("load config properties file OK, %s%n", file);
                in.close();
            }
        }

        if (commandLine.hasOption('p')) {
            InternalLogger console = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_CONSOLE_NAME);
            MixAll.printObjectProperties(console, namesrvConfig);
            MixAll.printObjectProperties(console, nettyServerConfig);
            System.exit(0);
        }
        // 将命令行set进namesrvConfig对象中去 本项目启动时没指定命令行 所以没有什么可以set的 关于命令行可见   https://developer.aliyun.com/article/664981
        MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), namesrvConfig);
        // 不设置ROCKETMQ_HOME ? 分分钟给你报错 没错 我就遇到了
        if (null == namesrvConfig.getRocketmqHome()) {
            System.out.printf("Please set the %s variable in your environment to match the location of the RocketMQ installation%n", MixAll.ROCKETMQ_HOME_ENV);
            System.exit(-2);
        }
        // 日志相关的 不明白为毛自己封装个 ？ 暂时不管他
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(lc);
        lc.reset();
        configurator.doConfigure(namesrvConfig.getRocketmqHome() + "/conf/logback_namesrv.xml");

        log = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

        MixAll.printObjectProperties(log, namesrvConfig);
        MixAll.printObjectProperties(log, nettyServerConfig);

        /**
         * <2> 这个应该比较重要了 使用namesrvConfig nettyServerConfig 初始化NamesrvController 呵呵其实就是赋值 其他的属性都是在构造中new的
         */
        final NamesrvController controller = new NamesrvController(namesrvConfig, nettyServerConfig);

        // remember all configs to prevent discard
        // 翻译过来就是 记住所有配置以防丢失 因为没设置命令行 -c  所以这里的properties is null
        controller.getConfiguration().registerConfig(properties);

        return controller;
    }

    /**
     *
     * 2.1.初始化 NamesrvController 包括： 处理netty相关远程配置 创建远程服务与工作线程  开启定时任务：移除不活跃的broker 以及打印kV
     * 2.2. 钩子函数 用于释放资源 在jvm退出之前应该是
     * 2.3.启动nameServerController 底层其实是启动netty服务
     *
     *     handshakeHandler：处理握手操作，用来判断tls的开启状态
     *     encoder/NettyDecoder：处理报文的编解码操作
     *     IdleStateHandler：处理心跳
     *     connectionManageHandler：处理连接请求
     *     serverHandler：处理读写请求
     *
     *         // 2.3.1 重要代码记录
     *         ServerBootstrap childHandler =
     *             this.serverBootstrap.group(this.eventLoopGroupBoss, this.eventLoopGroupSelector)
     *                 .channel(useEpoll() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
     *                 .option(ChannelOption.SO_BACKLOG, 1024)
     *                 .option(ChannelOption.SO_REUSEADDR, true)
     *                 .option(ChannelOption.SO_KEEPALIVE, false)
     *                 .childOption(ChannelOption.TCP_NODELAY, true)
     *                 .childOption(ChannelOption.SO_SNDBUF, nettyServerConfig.getServerSocketSndBufSize())
     *                 .childOption(ChannelOption.SO_RCVBUF, nettyServerConfig.getServerSocketRcvBufSize())
     *                     //监听端口
     *                 .localAddress(new InetSocketAddress(this.nettyServerConfig.getListenPort()))
     *                 .childHandler(new ChannelInitializer<SocketChannel>() {
     *                     @Override
     *                     public void initChannel(SocketChannel ch) throws Exception {
     *                         ch.pipeline()
     *                             .addLast(defaultEventExecutorGroup, HANDSHAKE_HANDLER_NAME, handshakeHandler)
     *                             .addLast(defaultEventExecutorGroup,
     *                                 encoder,
     *                                 new NettyDecoder(),
     *                                 new IdleStateHandler(0, 0, nettyServerConfig.getServerChannelMaxIdleTimeSeconds()),
     *                                 connectionManageHandler,
     *                                 //netty用来处理连接事件与读写事件的线程
     *                                     serverHandler
     *                             );
     *                     }
     *                 });
     *
     *
     * @param controller
     * @return
     * @throws Exception
     */
    public static NamesrvController start(final NamesrvController controller) throws Exception {

        if (null == controller) {
            throw new IllegalArgumentException("NamesrvController is null");
        }
        //2.1 初始化 NamesrvController 包括： 处理netty相关远程配置 创建远程服务与工作线程  开启定时任务：移除不活跃的broker 以及打印kV
        boolean initResult = controller.initialize();
        if (!initResult) {
            controller.shutdown();
            System.exit(-3);
        }
        //2.2 钩子函数 用于释放资源 在jvm退出之前应该是
        Runtime.getRuntime().addShutdownHook(new ShutdownHookThread(log, new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                controller.shutdown();
                return null;
            }
        }));
        // 2.3.启动nameServerController 底层其实是启动netty服务
        controller.start();

        return controller;
    }

    public static void shutdown(final NamesrvController controller) {
        controller.shutdown();
    }

    public static Options buildCommandlineOptions(final Options options) {
        Option opt = new Option("c", "configFile", true, "Name server config properties file");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("p", "printConfigItem", false, "Print all config item");
        opt.setRequired(false);
        options.addOption(opt);

        return options;
    }

    public static Properties getProperties() {
        return properties;
    }
}
