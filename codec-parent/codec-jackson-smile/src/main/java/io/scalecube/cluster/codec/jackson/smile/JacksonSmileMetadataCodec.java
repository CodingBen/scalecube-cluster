package io.scalecube.cluster.codec.jackson.smile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scalecube.cluster.metadata.MetadataCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import reactor.core.Exceptions;

public class JacksonSmileMetadataCodec implements MetadataCodec {

  private final ObjectMapper delegate;

  public JacksonSmileMetadataCodec() {
    this(DefaultObjectMapper.OBJECT_MAPPER);
  }

  public JacksonSmileMetadataCodec(ObjectMapper delegate) {
    this.delegate = delegate;
  }

  @Override
  public Object deserialize(ByteBuffer buffer) {
    if (buffer.remaining() == 0) {
      return null;
    }
    try {
      final MetadataWrapper metadataWrapper =
          this.delegate.readValue(buffer.array(), MetadataWrapper.class);
      return metadataWrapper.getMetadata();
    } catch (IOException e) {
      throw Exceptions.propagate(e);
    }
  }

  @Override
  public ByteBuffer serialize(Object metadata) {
    if (metadata == null) {
      return null;
    }
    try {
      final MetadataWrapper metadataWrapper = new MetadataWrapper(metadata);
      return ByteBuffer.wrap(this.delegate.writeValueAsBytes(metadataWrapper));
    } catch (IOException e) {
      throw Exceptions.propagate(e);
    }
  }

  public static class MetadataWrapper {

    private Object metadata;

    public MetadataWrapper() {}

    public MetadataWrapper(Object metadata) {
      this.metadata = metadata;
    }

    public Object getMetadata() {
      return metadata;
    }

    public void setMetadata(Object metadata) {
      this.metadata = metadata;
    }
  }
}
