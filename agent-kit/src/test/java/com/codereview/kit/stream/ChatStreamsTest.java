package com.codereview.kit.stream;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatStreamsTest {

    @Test
    void 分块流式拼接完整文本() {
        AtomicReference<String> full = new AtomicReference<>("");
        Flow.Publisher<String> pub = ChatStreams.of(List.of("hello", " world", "!"));
        pub.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(String item) { full.set(full.get() + item); }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
            @Override public void onComplete() { }
        });
        assertEquals("hello world!", full.get());
        assertEquals("hello world!", ChatStreams.join(List.of("hello", " world", "!")));
    }
}
