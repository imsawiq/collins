package org.sawiq.collins.paper.net;

public final class CollinsProtocol {
    private CollinsProtocol() {}

    public static final String NAMESPACE = "collins";
    public static final String PATH_MAIN = "main";

    public static final int PROTOCOL_VERSION = 4;

    // S2C (server -> client)
    public static final byte MSG_SYNC = 1;

    // C2S (client -> server)
    public static final byte MSG_VIDEO_ENDED = 2;
    public static final byte MSG_HELLO = 4;
}
