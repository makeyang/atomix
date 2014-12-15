/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.internal.cluster.coordinator;

import net.kuujo.copycat.Task;
import net.kuujo.copycat.cluster.MessageHandler;
import net.kuujo.copycat.cluster.coordinator.LocalMemberCoordinator;
import net.kuujo.copycat.protocol.ProtocolException;
import net.kuujo.copycat.protocol.ProtocolServer;
import net.kuujo.copycat.spi.ExecutionContext;
import net.kuujo.copycat.spi.Protocol;
import net.kuujo.copycat.util.serializer.Serializer;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Default local member implementation.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class DefaultLocalMemberCoordinator extends AbstractMemberCoordinator implements LocalMemberCoordinator {
  private static final int USER_ADDRESS = -1;
  private static final int SYSTEM_ADDRESS = 0;
  private final ProtocolServer server;
  private final ExecutionContext context;
  private final Map<Integer, ExecutionContext> contexts = new HashMap<>();
  @SuppressWarnings("rawtypes")
  private final Map<String, Map<Integer, MessageHandler>> handlers = new HashMap<>();
  private final Serializer serializer = Serializer.serializer();

  public DefaultLocalMemberCoordinator(String uri, Protocol protocol, ExecutionContext context) {
    super(uri);
    try {
      URI realUri = new URI(uri);
      if (!protocol.isValidUri(realUri)) {
        throw new ProtocolException(String.format("Invalid protocol URI %s", uri));
      }
      this.server = protocol.createServer(realUri);
    } catch (URISyntaxException e) {
      throw new ProtocolException(e);
    }
    this.context = context;
  }

  /**
   * Returns the execution context for a given address.
   */
  private ExecutionContext getContext(int address) {
    ExecutionContext context = contexts.get(address);
    return context != null ? context : this.context;
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public <T, U> CompletableFuture<U> send(String topic, int address, T message) {
    CompletableFuture<U> future = new CompletableFuture<>();
    Map<Integer, MessageHandler> handlers = this.handlers.get(topic);
    if (handlers != null) {
      MessageHandler handler = handlers.get(address);
      if (handler != null) {
        getContext(address).execute(() -> {
          ((CompletionStage<U>) handler.handle(message)).whenComplete((result, error) -> {
            if (error == null) {
              future.complete(result);
            } else {
              future.completeExceptionally(error);
            }
          });
        });
      } else {
        getContext(address).execute(() -> future.completeExceptionally(new IllegalStateException("No handlers")));
      }
    } else {
      getContext(address).execute(() -> future.completeExceptionally(new IllegalStateException("No handlers")));
    }
    return future;
  }

  @Override
  @SuppressWarnings("rawtypes")
  public <T, U> LocalMemberCoordinator register(String topic, int address, MessageHandler<T, U> handler) {
    Map<Integer, MessageHandler> handlers = this.handlers.get(topic);
    if (handlers == null) {
      handlers = new HashMap<>();
      this.handlers.put(topic, handlers);
    }
    handlers.put(address, handler);
    return this;
  }

  @Override
  @SuppressWarnings("rawtypes")
  public LocalMemberCoordinator unregister(String topic, int address) {
    Map<Integer, MessageHandler> handlers = this.handlers.get(topic);
    if (handlers != null) {
      handlers.remove(address);
      if (handlers.isEmpty()) {
        this.handlers.remove(topic);
      }
    }
    return this;
  }

  /**
   * Handles a request.
   *
   * @param request The request to handle.
   * @return A completable future to be completed once the response is ready.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private CompletableFuture<ByteBuffer> handle(ByteBuffer request) {
    CompletableFuture<ByteBuffer> future = new CompletableFuture<>();
    int type = request.getInt();
    switch (type) {
      case 0:
        int contextAddress = request.getInt();
        Task task = serializer.readObject(request.slice());
        getContext(contextAddress).execute(() -> {
          try {
            Object result = task.execute();
            future.complete(serializer.writeObject(result));
          } catch (Exception e) {
            future.completeExceptionally(e);
          }
        });
        break;
      case 1:
        int topicLength = request.getInt();
        byte[] topicBytes = new byte[topicLength];
        request.get(topicBytes);
        String topic = new String(topicBytes);
        Map<Integer, MessageHandler> handlers = this.handlers.get(topic);
        if (handlers != null) {
          int address = request.getInt();
          MessageHandler handler = handlers.get(address);
          if (handler != null) {
            Object message = serializer.readObject(request);
            getContext(address).execute(() -> {
              ((CompletionStage<?>) handler.handle(message)).whenComplete((result, error) -> {
                if (error == null) {
                  future.complete(serializer.writeObject(message));
                } else {
                  future.completeExceptionally(error);
                }
              });
            });
          } else {
            future.completeExceptionally(new IllegalStateException("No handlers"));
          }
        } else {
          future.completeExceptionally(new IllegalStateException("No handlers"));
        }
        break;
    }
    return future;
  }

  @Override
  public CompletableFuture<Void> execute(int address, Task<Void> task) {
    return getContext(address).submit(task::execute);
  }

  @Override
  public <T> CompletableFuture<T> submit(int address, Task<T> task) {
    return getContext(address).submit(task::execute);
  }

  @Override
  public LocalMemberCoordinator executor(int address, ExecutionContext context) {
    if (context != null) {
      contexts.put(address, context);
    } else {
      contexts.remove(address);
    }
    return this;
  }

  @Override
  @SuppressWarnings("rawtypes")
  public CompletableFuture<Void> open() {
    CompletableFuture<Void> future = new CompletableFuture<>();
    server.listen().whenComplete((result, error) -> {
      server.handler(this::handle);
      context.execute(() -> {
        if (error == null) {
          future.complete(null);
        } else {
          future.completeExceptionally(error);
        }
      });
    });
    return future;
  }

  @Override
  public CompletableFuture<Void> close() {
    CompletableFuture<Void> future = new CompletableFuture<>();
    server.close().whenComplete((result, error) -> {
      context.execute(() -> {
        if (error == null) {
          future.complete(null);
        } else {
          future.completeExceptionally(error);
        }
      });
    });
    return future;
  }

}