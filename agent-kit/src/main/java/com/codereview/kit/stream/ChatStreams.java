package com.codereview.kit.stream;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流式工具类：把分块列表 / 逐块回调包装为 {@link Flow.Publisher}，零依赖。
 */
public final class ChatStreams {

    private ChatStreams() {
    }

    /** 把给定块列表一次性发布。 */
    public static Flow.Publisher<String> of(List<String> chunks) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicLong requested = new AtomicLong();
                private int index;
                private boolean cancelled;

                @Override public void request(long n) {
                    if (cancelled) {
                        return;
                    }
                    if (n <= 0) {
                        subscriber.onError(new IllegalArgumentException("negative request"));
                        return;
                    }
                    synchronized (this) {
                        long newReq = requested.addAndGet(n);
                        while (index < chunks.size() && newReq > 0) {
                            subscriber.onNext(chunks.get(index++));
                            newReq--;
                        }
                        if (index >= chunks.size()) {
                            subscriber.onComplete();
                        }
                    }
                }

                @Override public void cancel() {
                    cancelled = true;
                }
            });
        };
    }

    /** 把块列表拼接成完整文本。 */
    public static String join(List<String> chunks) {
        return String.join("", chunks);
    }
}
