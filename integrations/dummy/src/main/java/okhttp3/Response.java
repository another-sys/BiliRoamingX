package okhttp3;

/** Compile-only signature from the host. */
public final class Response {
    public static class Builder {
        public Builder request(Request r) { return this; }
        public Builder protocol(Protocol p) { return this; }
        public Builder code(int c) { return this; }
        public Builder message(String s) { return this; }
        public Builder header(String key, String value) { return this; }
        public Builder body(ResponseBody b) { return this; }
        public Response build() { return null; }
    }
}
