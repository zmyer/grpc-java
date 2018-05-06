// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: grpc/lb/v1/load_balancer.proto

package io.grpc.grpclb;

/**
 * <pre>
 * Contains the number of calls finished for a particular load balance token.
 * </pre>
 *
 * Protobuf type {@code grpc.lb.v1.ClientStatsPerToken}
 */
public  final class ClientStatsPerToken extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:grpc.lb.v1.ClientStatsPerToken)
    ClientStatsPerTokenOrBuilder {
private static final long serialVersionUID = 0L;
  // Use ClientStatsPerToken.newBuilder() to construct.
  private ClientStatsPerToken(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private ClientStatsPerToken() {
    loadBalanceToken_ = "";
    numCalls_ = 0L;
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private ClientStatsPerToken(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    int mutable_bitField0_ = 0;
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          default: {
            if (!parseUnknownFieldProto3(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
          case 10: {
            java.lang.String s = input.readStringRequireUtf8();

            loadBalanceToken_ = s;
            break;
          }
          case 16: {

            numCalls_ = input.readInt64();
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.grpc.grpclb.LoadBalancerProto.internal_static_grpc_lb_v1_ClientStatsPerToken_descriptor;
  }

  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.grpc.grpclb.LoadBalancerProto.internal_static_grpc_lb_v1_ClientStatsPerToken_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.grpc.grpclb.ClientStatsPerToken.class, io.grpc.grpclb.ClientStatsPerToken.Builder.class);
  }

  public static final int LOAD_BALANCE_TOKEN_FIELD_NUMBER = 1;
  private volatile java.lang.Object loadBalanceToken_;
  /**
   * <pre>
   * See Server.load_balance_token.
   * </pre>
   *
   * <code>string load_balance_token = 1;</code>
   */
  public java.lang.String getLoadBalanceToken() {
    java.lang.Object ref = loadBalanceToken_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      loadBalanceToken_ = s;
      return s;
    }
  }
  /**
   * <pre>
   * See Server.load_balance_token.
   * </pre>
   *
   * <code>string load_balance_token = 1;</code>
   */
  public com.google.protobuf.ByteString
      getLoadBalanceTokenBytes() {
    java.lang.Object ref = loadBalanceToken_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      loadBalanceToken_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int NUM_CALLS_FIELD_NUMBER = 2;
  private long numCalls_;
  /**
   * <pre>
   * The total number of RPCs that finished associated with the token.
   * </pre>
   *
   * <code>int64 num_calls = 2;</code>
   */
  public long getNumCalls() {
    return numCalls_;
  }

  private byte memoizedIsInitialized = -1;
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (!getLoadBalanceTokenBytes().isEmpty()) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, loadBalanceToken_);
    }
    if (numCalls_ != 0L) {
      output.writeInt64(2, numCalls_);
    }
    unknownFields.writeTo(output);
  }

  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!getLoadBalanceTokenBytes().isEmpty()) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(1, loadBalanceToken_);
    }
    if (numCalls_ != 0L) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt64Size(2, numCalls_);
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.grpc.grpclb.ClientStatsPerToken)) {
      return super.equals(obj);
    }
    io.grpc.grpclb.ClientStatsPerToken other = (io.grpc.grpclb.ClientStatsPerToken) obj;

    boolean result = true;
    result = result && getLoadBalanceToken()
        .equals(other.getLoadBalanceToken());
    result = result && (getNumCalls()
        == other.getNumCalls());
    result = result && unknownFields.equals(other.unknownFields);
    return result;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    hash = (37 * hash) + LOAD_BALANCE_TOKEN_FIELD_NUMBER;
    hash = (53 * hash) + getLoadBalanceToken().hashCode();
    hash = (37 * hash) + NUM_CALLS_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashLong(
        getNumCalls());
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.grpc.grpclb.ClientStatsPerToken parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.grpc.grpclb.ClientStatsPerToken parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.grpc.grpclb.ClientStatsPerToken prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * Contains the number of calls finished for a particular load balance token.
   * </pre>
   *
   * Protobuf type {@code grpc.lb.v1.ClientStatsPerToken}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:grpc.lb.v1.ClientStatsPerToken)
      io.grpc.grpclb.ClientStatsPerTokenOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.grpc.grpclb.LoadBalancerProto.internal_static_grpc_lb_v1_ClientStatsPerToken_descriptor;
    }

    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.grpc.grpclb.LoadBalancerProto.internal_static_grpc_lb_v1_ClientStatsPerToken_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.grpc.grpclb.ClientStatsPerToken.class, io.grpc.grpclb.ClientStatsPerToken.Builder.class);
    }

    // Construct using io.grpc.grpclb.ClientStatsPerToken.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    public Builder clear() {
      super.clear();
      loadBalanceToken_ = "";

      numCalls_ = 0L;

      return this;
    }

    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.grpc.grpclb.LoadBalancerProto.internal_static_grpc_lb_v1_ClientStatsPerToken_descriptor;
    }

    public io.grpc.grpclb.ClientStatsPerToken getDefaultInstanceForType() {
      return io.grpc.grpclb.ClientStatsPerToken.getDefaultInstance();
    }

    public io.grpc.grpclb.ClientStatsPerToken build() {
      io.grpc.grpclb.ClientStatsPerToken result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    public io.grpc.grpclb.ClientStatsPerToken buildPartial() {
      io.grpc.grpclb.ClientStatsPerToken result = new io.grpc.grpclb.ClientStatsPerToken(this);
      result.loadBalanceToken_ = loadBalanceToken_;
      result.numCalls_ = numCalls_;
      onBuilt();
      return result;
    }

    public Builder clone() {
      return (Builder) super.clone();
    }
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return (Builder) super.setField(field, value);
    }
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return (Builder) super.clearField(field);
    }
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return (Builder) super.clearOneof(oneof);
    }
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return (Builder) super.setRepeatedField(field, index, value);
    }
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return (Builder) super.addRepeatedField(field, value);
    }
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.grpc.grpclb.ClientStatsPerToken) {
        return mergeFrom((io.grpc.grpclb.ClientStatsPerToken)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.grpc.grpclb.ClientStatsPerToken other) {
      if (other == io.grpc.grpclb.ClientStatsPerToken.getDefaultInstance()) return this;
      if (!other.getLoadBalanceToken().isEmpty()) {
        loadBalanceToken_ = other.loadBalanceToken_;
        onChanged();
      }
      if (other.getNumCalls() != 0L) {
        setNumCalls(other.getNumCalls());
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    public final boolean isInitialized() {
      return true;
    }

    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.grpc.grpclb.ClientStatsPerToken parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.grpc.grpclb.ClientStatsPerToken) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private java.lang.Object loadBalanceToken_ = "";
    /**
     * <pre>
     * See Server.load_balance_token.
     * </pre>
     *
     * <code>string load_balance_token = 1;</code>
     */
    public java.lang.String getLoadBalanceToken() {
      java.lang.Object ref = loadBalanceToken_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        loadBalanceToken_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <pre>
     * See Server.load_balance_token.
     * </pre>
     *
     * <code>string load_balance_token = 1;</code>
     */
    public com.google.protobuf.ByteString
        getLoadBalanceTokenBytes() {
      java.lang.Object ref = loadBalanceToken_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        loadBalanceToken_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <pre>
     * See Server.load_balance_token.
     * </pre>
     *
     * <code>string load_balance_token = 1;</code>
     */
    public Builder setLoadBalanceToken(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      loadBalanceToken_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * See Server.load_balance_token.
     * </pre>
     *
     * <code>string load_balance_token = 1;</code>
     */
    public Builder clearLoadBalanceToken() {
      
      loadBalanceToken_ = getDefaultInstance().getLoadBalanceToken();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * See Server.load_balance_token.
     * </pre>
     *
     * <code>string load_balance_token = 1;</code>
     */
    public Builder setLoadBalanceTokenBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      loadBalanceToken_ = value;
      onChanged();
      return this;
    }

    private long numCalls_ ;
    /**
     * <pre>
     * The total number of RPCs that finished associated with the token.
     * </pre>
     *
     * <code>int64 num_calls = 2;</code>
     */
    public long getNumCalls() {
      return numCalls_;
    }
    /**
     * <pre>
     * The total number of RPCs that finished associated with the token.
     * </pre>
     *
     * <code>int64 num_calls = 2;</code>
     */
    public Builder setNumCalls(long value) {
      
      numCalls_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The total number of RPCs that finished associated with the token.
     * </pre>
     *
     * <code>int64 num_calls = 2;</code>
     */
    public Builder clearNumCalls() {
      
      numCalls_ = 0L;
      onChanged();
      return this;
    }
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFieldsProto3(unknownFields);
    }

    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:grpc.lb.v1.ClientStatsPerToken)
  }

  // @@protoc_insertion_point(class_scope:grpc.lb.v1.ClientStatsPerToken)
  private static final io.grpc.grpclb.ClientStatsPerToken DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.grpc.grpclb.ClientStatsPerToken();
  }

  public static io.grpc.grpclb.ClientStatsPerToken getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<ClientStatsPerToken>
      PARSER = new com.google.protobuf.AbstractParser<ClientStatsPerToken>() {
    public ClientStatsPerToken parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new ClientStatsPerToken(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<ClientStatsPerToken> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<ClientStatsPerToken> getParserForType() {
    return PARSER;
  }

  public io.grpc.grpclb.ClientStatsPerToken getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

