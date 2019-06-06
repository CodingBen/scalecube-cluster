package io.scalecube.cluster.metadata;

import io.scalecube.utils.ServiceLoaderUtil;
import java.nio.ByteBuffer;

@FunctionalInterface
public interface MetadataDecoder {

  MetadataDecoder INSTANCE = ServiceLoaderUtil.findFirst(MetadataDecoder.class).orElse(null);

  <T> T decode(ByteBuffer buffer);
}
