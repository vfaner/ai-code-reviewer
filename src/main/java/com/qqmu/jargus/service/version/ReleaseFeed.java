package com.qqmu.jargus.service.version;

import java.time.Instant;

/** 发布源抽象（单测可换内存实现，不碰网络）。 */
public interface ReleaseFeed {

    /** 最新发布；远端无发布或不可达时抛异常。 */
    Release latest() throws Exception;

    /** 按 tag 取发布（用于回查当前版本的发布说明正文）。 */
    Release byTag(String tag) throws Exception;

    record Release(String tag, String htmlUrl, Instant publishedAt, String body) {
    }
}
