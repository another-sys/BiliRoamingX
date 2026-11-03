package okhttp3;

/** Compile-only signatures from the host's public OkHttp API. */
public class Headers {
    public int size() { return 0; }
    public String name(int index) { return null; }
    public String value(int index) { return null; }
    public Builder newBuilder() { return null; }
    public static class Builder {
        public Builder set(String name, String value) { return this; }
        public Headers build() { return null; }
    }
}
