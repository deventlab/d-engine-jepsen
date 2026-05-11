package d_engine.client;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.63.0)",
    comments = "Source: proto/client/client_api.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RaftClientServiceGrpc {

  private RaftClientServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "d_engine.client.RaftClientService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<d_engine.client.ClientApi.ClientWriteRequest,
      d_engine.client.ClientApi.ClientResponse> getHandleClientWriteMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "HandleClientWrite",
      requestType = d_engine.client.ClientApi.ClientWriteRequest.class,
      responseType = d_engine.client.ClientApi.ClientResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<d_engine.client.ClientApi.ClientWriteRequest,
      d_engine.client.ClientApi.ClientResponse> getHandleClientWriteMethod() {
    io.grpc.MethodDescriptor<d_engine.client.ClientApi.ClientWriteRequest, d_engine.client.ClientApi.ClientResponse> getHandleClientWriteMethod;
    if ((getHandleClientWriteMethod = RaftClientServiceGrpc.getHandleClientWriteMethod) == null) {
      synchronized (RaftClientServiceGrpc.class) {
        if ((getHandleClientWriteMethod = RaftClientServiceGrpc.getHandleClientWriteMethod) == null) {
          RaftClientServiceGrpc.getHandleClientWriteMethod = getHandleClientWriteMethod =
              io.grpc.MethodDescriptor.<d_engine.client.ClientApi.ClientWriteRequest, d_engine.client.ClientApi.ClientResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "HandleClientWrite"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  d_engine.client.ClientApi.ClientWriteRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  d_engine.client.ClientApi.ClientResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RaftClientServiceMethodDescriptorSupplier("HandleClientWrite"))
              .build();
        }
      }
    }
    return getHandleClientWriteMethod;
  }

  private static volatile io.grpc.MethodDescriptor<d_engine.client.ClientApi.ClientReadRequest,
      d_engine.client.ClientApi.ClientResponse> getHandleClientReadMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "HandleClientRead",
      requestType = d_engine.client.ClientApi.ClientReadRequest.class,
      responseType = d_engine.client.ClientApi.ClientResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<d_engine.client.ClientApi.ClientReadRequest,
      d_engine.client.ClientApi.ClientResponse> getHandleClientReadMethod() {
    io.grpc.MethodDescriptor<d_engine.client.ClientApi.ClientReadRequest, d_engine.client.ClientApi.ClientResponse> getHandleClientReadMethod;
    if ((getHandleClientReadMethod = RaftClientServiceGrpc.getHandleClientReadMethod) == null) {
      synchronized (RaftClientServiceGrpc.class) {
        if ((getHandleClientReadMethod = RaftClientServiceGrpc.getHandleClientReadMethod) == null) {
          RaftClientServiceGrpc.getHandleClientReadMethod = getHandleClientReadMethod =
              io.grpc.MethodDescriptor.<d_engine.client.ClientApi.ClientReadRequest, d_engine.client.ClientApi.ClientResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "HandleClientRead"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  d_engine.client.ClientApi.ClientReadRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  d_engine.client.ClientApi.ClientResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RaftClientServiceMethodDescriptorSupplier("HandleClientRead"))
              .build();
        }
      }
    }
    return getHandleClientReadMethod;
  }

  private static volatile io.grpc.MethodDescriptor<d_engine.client.ClientApi.WatchRequest,
      d_engine.client.ClientApi.WatchResponse> getWatchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Watch",
      requestType = d_engine.client.ClientApi.WatchRequest.class,
      responseType = d_engine.client.ClientApi.WatchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<d_engine.client.ClientApi.WatchRequest,
      d_engine.client.ClientApi.WatchResponse> getWatchMethod() {
    io.grpc.MethodDescriptor<d_engine.client.ClientApi.WatchRequest, d_engine.client.ClientApi.WatchResponse> getWatchMethod;
    if ((getWatchMethod = RaftClientServiceGrpc.getWatchMethod) == null) {
      synchronized (RaftClientServiceGrpc.class) {
        if ((getWatchMethod = RaftClientServiceGrpc.getWatchMethod) == null) {
          RaftClientServiceGrpc.getWatchMethod = getWatchMethod =
              io.grpc.MethodDescriptor.<d_engine.client.ClientApi.WatchRequest, d_engine.client.ClientApi.WatchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Watch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  d_engine.client.ClientApi.WatchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  d_engine.client.ClientApi.WatchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RaftClientServiceMethodDescriptorSupplier("Watch"))
              .build();
        }
      }
    }
    return getWatchMethod;
  }

  private static volatile io.grpc.MethodDescriptor<d_engine.client.ClientApi.WatchMembershipRequest,
      d_engine.client.ClientApi.MembershipSnapshot> getWatchMembershipMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "WatchMembership",
      requestType = d_engine.client.ClientApi.WatchMembershipRequest.class,
      responseType = d_engine.client.ClientApi.MembershipSnapshot.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<d_engine.client.ClientApi.WatchMembershipRequest,
      d_engine.client.ClientApi.MembershipSnapshot> getWatchMembershipMethod() {
    io.grpc.MethodDescriptor<d_engine.client.ClientApi.WatchMembershipRequest, d_engine.client.ClientApi.MembershipSnapshot> getWatchMembershipMethod;
    if ((getWatchMembershipMethod = RaftClientServiceGrpc.getWatchMembershipMethod) == null) {
      synchronized (RaftClientServiceGrpc.class) {
        if ((getWatchMembershipMethod = RaftClientServiceGrpc.getWatchMembershipMethod) == null) {
          RaftClientServiceGrpc.getWatchMembershipMethod = getWatchMembershipMethod =
              io.grpc.MethodDescriptor.<d_engine.client.ClientApi.WatchMembershipRequest, d_engine.client.ClientApi.MembershipSnapshot>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "WatchMembership"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  d_engine.client.ClientApi.WatchMembershipRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  d_engine.client.ClientApi.MembershipSnapshot.getDefaultInstance()))
              .setSchemaDescriptor(new RaftClientServiceMethodDescriptorSupplier("WatchMembership"))
              .build();
        }
      }
    }
    return getWatchMembershipMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RaftClientServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RaftClientServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RaftClientServiceStub>() {
        @java.lang.Override
        public RaftClientServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RaftClientServiceStub(channel, callOptions);
        }
      };
    return RaftClientServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RaftClientServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RaftClientServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RaftClientServiceBlockingStub>() {
        @java.lang.Override
        public RaftClientServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RaftClientServiceBlockingStub(channel, callOptions);
        }
      };
    return RaftClientServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RaftClientServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RaftClientServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RaftClientServiceFutureStub>() {
        @java.lang.Override
        public RaftClientServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RaftClientServiceFutureStub(channel, callOptions);
        }
      };
    return RaftClientServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void handleClientWrite(d_engine.client.ClientApi.ClientWriteRequest request,
        io.grpc.stub.StreamObserver<d_engine.client.ClientApi.ClientResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHandleClientWriteMethod(), responseObserver);
    }

    /**
     */
    default void handleClientRead(d_engine.client.ClientApi.ClientReadRequest request,
        io.grpc.stub.StreamObserver<d_engine.client.ClientApi.ClientResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHandleClientReadMethod(), responseObserver);
    }

    /**
     * <pre>
     * Watch for changes to a specific key.
     * Returns a stream of WatchResponse events whenever the watched key changes.
     * The stream remains open until the client cancels or disconnects.
     * Performance characteristics:
     * - Event notification latency: typically &lt; 100μs
     * - Minimal overhead on write path (&lt; 0.01% with 100+ watchers)
     * Error handling:
     * - If the internal event buffer is full, events may be dropped
     * - Clients should use Read API to re-sync if they detect gaps
     * </pre>
     */
    default void watch(d_engine.client.ClientApi.WatchRequest request,
        io.grpc.stub.StreamObserver<d_engine.client.ClientApi.WatchResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getWatchMethod(), responseObserver);
    }

    /**
     * <pre>
     * Watch for committed cluster membership changes.
     * Immediately pushes the current MembershipSnapshot on connect, then pushes
     * a new snapshot on every committed ConfChange (AddNode, BatchPromote, BatchRemove).
     * All nodes (leader, follower, learner) emit the event after the entry commits.
     * Stream lifecycle:
     * - Stays open until the client cancels or the server shuts down.
     * - On server shutdown the stream closes with Status::UNAVAILABLE.
     *   Clients should reconnect and re-subscribe.
     * Idempotency:
     * - Use `committed_index` as an idempotency key in the receiver.
     * </pre>
     */
    default void watchMembership(d_engine.client.ClientApi.WatchMembershipRequest request,
        io.grpc.stub.StreamObserver<d_engine.client.ClientApi.MembershipSnapshot> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getWatchMembershipMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RaftClientService.
   */
  public static abstract class RaftClientServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RaftClientServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RaftClientService.
   */
  public static final class RaftClientServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RaftClientServiceStub> {
    private RaftClientServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RaftClientServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RaftClientServiceStub(channel, callOptions);
    }

    /**
     */
    public void handleClientWrite(d_engine.client.ClientApi.ClientWriteRequest request,
        io.grpc.stub.StreamObserver<d_engine.client.ClientApi.ClientResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHandleClientWriteMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void handleClientRead(d_engine.client.ClientApi.ClientReadRequest request,
        io.grpc.stub.StreamObserver<d_engine.client.ClientApi.ClientResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHandleClientReadMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Watch for changes to a specific key.
     * Returns a stream of WatchResponse events whenever the watched key changes.
     * The stream remains open until the client cancels or disconnects.
     * Performance characteristics:
     * - Event notification latency: typically &lt; 100μs
     * - Minimal overhead on write path (&lt; 0.01% with 100+ watchers)
     * Error handling:
     * - If the internal event buffer is full, events may be dropped
     * - Clients should use Read API to re-sync if they detect gaps
     * </pre>
     */
    public void watch(d_engine.client.ClientApi.WatchRequest request,
        io.grpc.stub.StreamObserver<d_engine.client.ClientApi.WatchResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getWatchMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Watch for committed cluster membership changes.
     * Immediately pushes the current MembershipSnapshot on connect, then pushes
     * a new snapshot on every committed ConfChange (AddNode, BatchPromote, BatchRemove).
     * All nodes (leader, follower, learner) emit the event after the entry commits.
     * Stream lifecycle:
     * - Stays open until the client cancels or the server shuts down.
     * - On server shutdown the stream closes with Status::UNAVAILABLE.
     *   Clients should reconnect and re-subscribe.
     * Idempotency:
     * - Use `committed_index` as an idempotency key in the receiver.
     * </pre>
     */
    public void watchMembership(d_engine.client.ClientApi.WatchMembershipRequest request,
        io.grpc.stub.StreamObserver<d_engine.client.ClientApi.MembershipSnapshot> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getWatchMembershipMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RaftClientService.
   */
  public static final class RaftClientServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RaftClientServiceBlockingStub> {
    private RaftClientServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RaftClientServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RaftClientServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public d_engine.client.ClientApi.ClientResponse handleClientWrite(d_engine.client.ClientApi.ClientWriteRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHandleClientWriteMethod(), getCallOptions(), request);
    }

    /**
     */
    public d_engine.client.ClientApi.ClientResponse handleClientRead(d_engine.client.ClientApi.ClientReadRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHandleClientReadMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Watch for changes to a specific key.
     * Returns a stream of WatchResponse events whenever the watched key changes.
     * The stream remains open until the client cancels or disconnects.
     * Performance characteristics:
     * - Event notification latency: typically &lt; 100μs
     * - Minimal overhead on write path (&lt; 0.01% with 100+ watchers)
     * Error handling:
     * - If the internal event buffer is full, events may be dropped
     * - Clients should use Read API to re-sync if they detect gaps
     * </pre>
     */
    public java.util.Iterator<d_engine.client.ClientApi.WatchResponse> watch(
        d_engine.client.ClientApi.WatchRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getWatchMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Watch for committed cluster membership changes.
     * Immediately pushes the current MembershipSnapshot on connect, then pushes
     * a new snapshot on every committed ConfChange (AddNode, BatchPromote, BatchRemove).
     * All nodes (leader, follower, learner) emit the event after the entry commits.
     * Stream lifecycle:
     * - Stays open until the client cancels or the server shuts down.
     * - On server shutdown the stream closes with Status::UNAVAILABLE.
     *   Clients should reconnect and re-subscribe.
     * Idempotency:
     * - Use `committed_index` as an idempotency key in the receiver.
     * </pre>
     */
    public java.util.Iterator<d_engine.client.ClientApi.MembershipSnapshot> watchMembership(
        d_engine.client.ClientApi.WatchMembershipRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getWatchMembershipMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RaftClientService.
   */
  public static final class RaftClientServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RaftClientServiceFutureStub> {
    private RaftClientServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RaftClientServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RaftClientServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<d_engine.client.ClientApi.ClientResponse> handleClientWrite(
        d_engine.client.ClientApi.ClientWriteRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHandleClientWriteMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<d_engine.client.ClientApi.ClientResponse> handleClientRead(
        d_engine.client.ClientApi.ClientReadRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHandleClientReadMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_HANDLE_CLIENT_WRITE = 0;
  private static final int METHODID_HANDLE_CLIENT_READ = 1;
  private static final int METHODID_WATCH = 2;
  private static final int METHODID_WATCH_MEMBERSHIP = 3;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_HANDLE_CLIENT_WRITE:
          serviceImpl.handleClientWrite((d_engine.client.ClientApi.ClientWriteRequest) request,
              (io.grpc.stub.StreamObserver<d_engine.client.ClientApi.ClientResponse>) responseObserver);
          break;
        case METHODID_HANDLE_CLIENT_READ:
          serviceImpl.handleClientRead((d_engine.client.ClientApi.ClientReadRequest) request,
              (io.grpc.stub.StreamObserver<d_engine.client.ClientApi.ClientResponse>) responseObserver);
          break;
        case METHODID_WATCH:
          serviceImpl.watch((d_engine.client.ClientApi.WatchRequest) request,
              (io.grpc.stub.StreamObserver<d_engine.client.ClientApi.WatchResponse>) responseObserver);
          break;
        case METHODID_WATCH_MEMBERSHIP:
          serviceImpl.watchMembership((d_engine.client.ClientApi.WatchMembershipRequest) request,
              (io.grpc.stub.StreamObserver<d_engine.client.ClientApi.MembershipSnapshot>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getHandleClientWriteMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              d_engine.client.ClientApi.ClientWriteRequest,
              d_engine.client.ClientApi.ClientResponse>(
                service, METHODID_HANDLE_CLIENT_WRITE)))
        .addMethod(
          getHandleClientReadMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              d_engine.client.ClientApi.ClientReadRequest,
              d_engine.client.ClientApi.ClientResponse>(
                service, METHODID_HANDLE_CLIENT_READ)))
        .addMethod(
          getWatchMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              d_engine.client.ClientApi.WatchRequest,
              d_engine.client.ClientApi.WatchResponse>(
                service, METHODID_WATCH)))
        .addMethod(
          getWatchMembershipMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              d_engine.client.ClientApi.WatchMembershipRequest,
              d_engine.client.ClientApi.MembershipSnapshot>(
                service, METHODID_WATCH_MEMBERSHIP)))
        .build();
  }

  private static abstract class RaftClientServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RaftClientServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return d_engine.client.ClientApi.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RaftClientService");
    }
  }

  private static final class RaftClientServiceFileDescriptorSupplier
      extends RaftClientServiceBaseDescriptorSupplier {
    RaftClientServiceFileDescriptorSupplier() {}
  }

  private static final class RaftClientServiceMethodDescriptorSupplier
      extends RaftClientServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RaftClientServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (RaftClientServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RaftClientServiceFileDescriptorSupplier())
              .addMethod(getHandleClientWriteMethod())
              .addMethod(getHandleClientReadMethod())
              .addMethod(getWatchMethod())
              .addMethod(getWatchMembershipMethod())
              .build();
        }
      }
    }
    return result;
  }
}
