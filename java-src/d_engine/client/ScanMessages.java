package d_engine.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal hand-written protobuf encoding/decoding for:
 *   KvEntry    { bytes key = 1; bytes value = 2; }
 *   ScanRequest  { uint32 client_id = 1; bytes prefix = 2; }
 *   ScanResponse { repeated KvEntry entries = 1; uint64 revision = 2; }
 *
 * Wire-format compatible with the generated Rust/Go code from client_api.proto.
 * Used in Jepsen tests because protoc 33.0 (available on dev machine) generates
 * code incompatible with protobuf-java 3.25.3 used by the Jepsen project.
 */
public final class ScanMessages {

    private ScanMessages() {}

    // ── KvEntry ──────────────────────────────────────────────────────────────

    public static final class KvEntry {
        public final ByteString key;
        public final ByteString value;

        public KvEntry(ByteString key, ByteString value) {
            this.key   = key   != null ? key   : ByteString.EMPTY;
            this.value = value != null ? value : ByteString.EMPTY;
        }

        /** Encode to protobuf wire bytes. */
        public byte[] toByteArray() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CodedOutputStream cos = CodedOutputStream.newInstance(baos);
            if (!key.isEmpty()) {
                cos.writeBytes(1, key);
            }
            if (!value.isEmpty()) {
                cos.writeBytes(2, value);
            }
            cos.flush();
            return baos.toByteArray();
        }

        /** Decode from protobuf wire bytes. */
        public static KvEntry parseFrom(byte[] data) throws InvalidProtocolBufferException {
            try {
                CodedInputStream cis = CodedInputStream.newInstance(data);
                ByteString key   = ByteString.EMPTY;
                ByteString value = ByteString.EMPTY;
                int tag;
                while ((tag = cis.readTag()) != 0) {
                    int field = tag >>> 3;
                    switch (field) {
                        case 1: key   = cis.readBytes(); break;
                        case 2: value = cis.readBytes(); break;
                        default: cis.skipField(tag);
                    }
                }
                return new KvEntry(key, value);
            } catch (IOException e) {
                throw new InvalidProtocolBufferException(e.getMessage());
            }
        }

        public ByteString getKey()   { return key;   }
        public ByteString getValue() { return value; }
    }

    // ── ScanRequest ──────────────────────────────────────────────────────────

    public static final class ScanRequest {
        private final int clientId;
        private final ByteString prefix;

        private ScanRequest(int clientId, ByteString prefix) {
            this.clientId = clientId;
            this.prefix   = prefix != null ? prefix : ByteString.EMPTY;
        }

        public static Builder newBuilder() { return new Builder(); }

        public static final class Builder {
            private int clientId;
            private ByteString prefix = ByteString.EMPTY;

            public Builder setClientId(int id)          { this.clientId = id; return this; }
            public Builder setPrefix(ByteString prefix) { this.prefix = prefix; return this; }
            public ScanRequest build()                  { return new ScanRequest(clientId, prefix); }
        }

        /** Encode to protobuf wire bytes. */
        public byte[] toByteArray() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CodedOutputStream cos = CodedOutputStream.newInstance(baos);
            if (clientId != 0) {
                cos.writeUInt32(1, clientId);
            }
            if (!prefix.isEmpty()) {
                cos.writeBytes(2, prefix);
            }
            cos.flush();
            return baos.toByteArray();
        }
    }

    // ── ScanResponse ─────────────────────────────────────────────────────────

    public static final class ScanResponse {
        private final List<KvEntry> entries;
        private final long revision;

        private ScanResponse(List<KvEntry> entries, long revision) {
            this.entries  = Collections.unmodifiableList(entries);
            this.revision = revision;
        }

        public List<KvEntry>  getEntriesList() { return entries;  }
        public long           getRevision()    { return revision; }

        /** Decode from protobuf wire bytes. */
        public static ScanResponse parseFrom(byte[] data) throws InvalidProtocolBufferException {
            try {
                CodedInputStream cis = CodedInputStream.newInstance(data);
                List<KvEntry> entries = new ArrayList<>();
                long revision = 0L;
                int tag;
                while ((tag = cis.readTag()) != 0) {
                    int field = tag >>> 3;
                    switch (field) {
                        case 1:
                            byte[] entryBytes = cis.readByteArray();
                            entries.add(KvEntry.parseFrom(entryBytes));
                            break;
                        case 2:
                            revision = cis.readUInt64();
                            break;
                        default:
                            cis.skipField(tag);
                    }
                }
                return new ScanResponse(entries, revision);
            } catch (IOException e) {
                throw new InvalidProtocolBufferException(e.getMessage());
            }
        }
    }
}
