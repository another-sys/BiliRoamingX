package okhttp3;

/** Compile-only signature from the host. */
public interface Interceptor { interface Chain { Response proceed(Request request) throws java.io.IOException; } }
