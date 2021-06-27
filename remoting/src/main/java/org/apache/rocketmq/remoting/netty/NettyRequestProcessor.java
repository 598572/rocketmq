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
package org.apache.rocketmq.remoting.netty;

import io.netty.channel.ChannelHandlerContext;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;

/**
 * Common remoting command processor
 *
 * 这个处理器接口很重要 very 重要  broker nameServer 的各种处理器都是实现了该接口
 *
 */
public interface NettyRequestProcessor {

    /**
     * 处理请求具体执行方法
     * 实现很多 目前只看 DefaultRequestProcessor 的实现
     * 20210617 看到broker的消息处理时候 发现broker的处理器是有很多种的（nameServer就是一种默认请求处理器） 所以需要看各个处理器实现方式
     *
     *
     * @param ctx
     * @param request
     * @return
     * @throws Exception
     */
    RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request)
        throws Exception;

    boolean rejectRequest();

}
