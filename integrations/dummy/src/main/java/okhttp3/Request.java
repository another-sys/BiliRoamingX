package okhttp3;

/** Compile-only signatures from the host's public OkHttp API. */
public class Request {
    public HttpUrl url() { return null; }
    public Headers headers() { return null; }
    public Builder newBuilder() { return null; }
    public static class Builder {
        public Builder headers(Headers headers) { return this; }
        public Builder url(String url) { return this; }
        public Request build() { return null; }
    }
}
