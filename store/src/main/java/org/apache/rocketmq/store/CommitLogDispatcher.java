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

package org.apache.rocketmq.store;

/**
 * Dispatcher of commit log.
 *
 * 提交日志的调度程序。
 */
public interface CommitLogDispatcher {

    /**
     *
     * 重要的方法
     *
     * 将 commitLog 文件的部分信息 (消息在commitLog文件的位置、tags等信息) 分发或者说存储到  consumerQueue和 index 的功能
     *
     * 将消息 存储到 consumerQueue :::  写数据到 Bytebuffer 然后写到 FileChannel（写到filechannel后 可以理解为写到 pagecache） 通过OS(操作系统)的 pdflush()方法来保证 刷盘
     * 将消息 存储到 index文件  :::  但是 index不一样 index是创建个异步线程 调用force方法刷盘的
     *
     *
     * @param request
     */
    void dispatch(final DispatchRequest request);
}
